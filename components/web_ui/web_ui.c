#include "web_ui.h"

#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include "firmware_update.h"

#include "bsp/bsp_display.h"
#include "esp_event.h"
#include "esp_check.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"

#define WIFI_SSID "C3FN4-EPAPER"
#define WIFI_PASSWORD "12345678"
#define RX_CHUNK_BYTES 2048

static const char *TAG = "web_ui";
static atomic_bool s_display_busy;

extern const uint8_t index_html_start[] asm("_binary_index_html_start");
extern const uint8_t index_html_end[] asm("_binary_index_html_end");
extern const uint8_t epdoptimize_mjs_start[] asm("_binary_epdoptimize_mjs_start");
extern const uint8_t epdoptimize_mjs_end[] asm("_binary_epdoptimize_mjs_end");

static esp_err_t send_embedded(httpd_req_t *req, const char *type,
                               const uint8_t *start, const uint8_t *end)
{
    httpd_resp_set_type(req, type);
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    return httpd_resp_send(req, (const char *)start, end - start);
}

static esp_err_t index_handler(httpd_req_t *req)
{
    return send_embedded(req, "text/html; charset=utf-8", index_html_start, index_html_end);
}

static esp_err_t module_handler(httpd_req_t *req)
{
    return send_embedded(req, "text/javascript; charset=utf-8",
                         epdoptimize_mjs_start, epdoptimize_mjs_end);
}

static esp_err_t status_handler(httpd_req_t *req)
{
    httpd_resp_set_type(req, "application/json");
    return httpd_resp_sendstr(req, atomic_load(&s_display_busy)
        ? "{\"busy\":true}" : "{\"busy\":false}");
}

static void display_worker(void *arg)
{
    (void)arg;
    esp_err_t err = bsp_display_refresh();
    if (err != ESP_OK) ESP_LOGE(TAG, "display refresh failed: %s", esp_err_to_name(err));
    atomic_store(&s_display_busy, false);
    vTaskDelete(NULL);
}

static esp_err_t frame_handler(httpd_req_t *req)
{
    const size_t expected = bsp_display_frame_size();
    if ((size_t)req->content_len != expected) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "invalid framebuffer size");
        return ESP_FAIL;
    }
    if (atomic_exchange(&s_display_busy, true)) {
        httpd_resp_set_status(req, "409 Conflict");
        httpd_resp_sendstr(req, "display is busy");
        return ESP_FAIL;
    }

    uint8_t chunk[RX_CHUNK_BYTES];
    size_t offset = 0;
    while (offset < expected) {
        const size_t wanted = expected - offset < sizeof(chunk)
                            ? expected - offset : sizeof(chunk);
        int received = httpd_req_recv(req, (char *)chunk, wanted);
        if (received == HTTPD_SOCK_ERR_TIMEOUT) continue;
        if (received <= 0 || bsp_display_frame_write(offset, chunk, received) != ESP_OK) {
            atomic_store(&s_display_busy, false);
            httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "upload failed");
            return ESP_FAIL;
        }
        offset += received;
    }

    if (xTaskCreate(display_worker, "epaper_refresh", 4096, NULL, 4, NULL) != pdPASS) {
        atomic_store(&s_display_busy, false);
        httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "worker start failed");
        return ESP_FAIL;
    }
    httpd_resp_set_status(req, "202 Accepted");
    return httpd_resp_sendstr(req, "refresh started");
}

static esp_err_t firmware_handler(httpd_req_t *req)
{
    char crc_text[9];
    if (httpd_req_get_hdr_value_len(req, "X-Firmware-CRC32") != 8 ||
        httpd_req_get_hdr_value_str(req, "X-Firmware-CRC32", crc_text, sizeof(crc_text)) != ESP_OK ||
        strspn(crc_text, "0123456789abcdefABCDEF") != 8 || req->content_len <= 0 ||
        (uint32_t)req->content_len > firmware_update_capacity()) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Invalid firmware size or CRC32 header");
        return ESP_FAIL;
    }
    if (atomic_exchange(&s_display_busy, true)) {
        httpd_resp_set_status(req, "409 Conflict");
        return httpd_resp_sendstr(req, "Device busy; wait for display/upload to finish");
    }
    esp_err_t err = firmware_update_begin((uint32_t)req->content_len,
                                          (uint32_t)strtoul(crc_text, NULL, 16));
    uint8_t chunk[2048];
    uint32_t offset = 0;
    while (err == ESP_OK && offset < (uint32_t)req->content_len) {
        size_t count = req->content_len - offset;
        if (count > sizeof(chunk)) count = sizeof(chunk);
        int received = httpd_req_recv(req, (char *)chunk, count);
        if (received <= 0) { err = ESP_FAIL; break; }
        err = firmware_update_write(offset, chunk, received);
        offset += received;
    }
    if (err == ESP_OK) err = firmware_update_commit();
    if (err != ESP_OK) {
        firmware_update_abort(); atomic_store(&s_display_busy, false);
        ESP_LOGE(TAG, "firmware upload failed: %s", esp_err_to_name(err));
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST,
                           "Firmware rejected or upload interrupted; current firmware retained");
        return ESP_FAIL;
    }
    httpd_resp_set_type(req, "application/json");
    bool restarting = firmware_update_schedule_restart() == ESP_OK;
    return httpd_resp_sendstr(req, restarting ? "{\"ok\":true,\"reboot\":true}"
                                             : "{\"ok\":true,\"reboot\":false}");
}

static esp_err_t start_http_server(void)
{
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.stack_size = 8192;
    config.recv_wait_timeout = 15;

    httpd_handle_t server = NULL;
    ESP_RETURN_ON_ERROR(httpd_start(&server, &config), TAG, "HTTP server start failed");

    const httpd_uri_t routes[] = {
        {.uri = "/", .method = HTTP_GET, .handler = index_handler},
        {.uri = "/epdoptimize.mjs", .method = HTTP_GET, .handler = module_handler},
        {.uri = "/api/status", .method = HTTP_GET, .handler = status_handler},
        {.uri = "/api/frame", .method = HTTP_POST, .handler = frame_handler},
        {.uri = "/api/firmware", .method = HTTP_POST, .handler = firmware_handler},
    };
    for (size_t i = 0; i < sizeof(routes) / sizeof(routes[0]); ++i) {
        ESP_RETURN_ON_ERROR(httpd_register_uri_handler(server, &routes[i]), TAG,
                            "route registration failed");
    }
    return ESP_OK;
}

esp_err_t web_ui_start(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_RETURN_ON_ERROR(nvs_flash_erase(), TAG, "NVS erase failed");
        err = nvs_flash_init();
    }
    ESP_RETURN_ON_ERROR(err, TAG, "NVS init failed");
    ESP_RETURN_ON_ERROR(esp_netif_init(), TAG, "netif init failed");
    ESP_RETURN_ON_ERROR(esp_event_loop_create_default(), TAG, "event loop failed");
    esp_netif_create_default_wifi_ap();

    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_RETURN_ON_ERROR(esp_wifi_init(&init), TAG, "Wi-Fi init failed");
    wifi_config_t ap = {
        .ap = {
            .ssid = WIFI_SSID,
            .password = WIFI_PASSWORD,
            .ssid_len = sizeof(WIFI_SSID) - 1,
            .channel = 1,
            .authmode = WIFI_AUTH_WPA2_PSK,
            .max_connection = 4,
            .pmf_cfg = {.required = false},
        },
    };
    ESP_RETURN_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_AP), TAG, "AP mode failed");
    ESP_RETURN_ON_ERROR(esp_wifi_set_config(WIFI_IF_AP, &ap), TAG, "AP config failed");
    ESP_RETURN_ON_ERROR(esp_wifi_start(), TAG, "AP start failed");
    ESP_LOGI(TAG, "Wi-Fi AP started: %s / %s", WIFI_SSID, WIFI_PASSWORD);
    ESP_RETURN_ON_ERROR(start_http_server(), TAG, "Web UI start failed");

    return ESP_OK;
}
