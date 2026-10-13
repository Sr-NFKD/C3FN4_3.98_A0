#include "ble_epaper.h"
#include "bsp/bsp_display.h"
#include <string.h>
#include "esp_log.h"
#include "esp_rom_crc.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

/* UUIDs: 7e400001/2/3/4-b5a3-f393-e0a9-e50e24dcca9e. */
#define EPD_UUID(n) BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0, \
                                    0x93,0xf3,0xa3,0xb5,n,0x00,0x40,0x7e)
static const ble_uuid128_t service_uuid = EPD_UUID(1);
static const ble_uuid128_t control_uuid = EPD_UUID(2);
static const ble_uuid128_t data_uuid = EPD_UUID(3);
static const ble_uuid128_t status_uuid = EPD_UUID(4);
enum { IDLE, RECEIVING, REFRESHING, DONE, FAILED };
enum { NO_ERROR, BAD_COMMAND, BAD_SIZE, BAD_OFFSET, BAD_CRC, DISPLAY_ERROR, UPLOAD_TIMEOUT };
static const char *TAG = "ble_epaper";
static SemaphoreHandle_t lock;
static QueueHandle_t work;
static uint8_t state, error_code, own_addr_type;
static uint32_t received, expected_crc, running_crc;
static int64_t last_packet;

static uint32_t read32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void write32(uint8_t *p, uint32_t v)
{
    for (int i = 0; i < 4; ++i) p[i] = v >> (8 * i);
}
static int reject(uint8_t code)
{
    error_code = code;
    return BLE_ATT_ERR_UNLIKELY;
}
static void expire_upload(void)
{
    if (state == RECEIVING && esp_timer_get_time() - last_packet > 30000000LL) {
        state = FAILED;
        error_code = UPLOAD_TIMEOUT;
    }
}

static int access_gatt(uint16_t conn, uint16_t attr,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn; (void)attr;
    const int kind = (int)(intptr_t)arg;
    uint8_t packet[512];
    int rc = 0;
    xSemaphoreTake(lock, portMAX_DELAY);
    expire_upload();
    if (kind == 4 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t status[20] = {1, state, error_code, 0};
        write32(status + 4, received);
        write32(status + 8, bsp_display_frame_size());
        status[12] = 768 & 255; status[13] = 768 >> 8;
        status[14] = 552 & 255; status[15] = 552 >> 8;
        write32(status + 16, running_crc);
        rc = os_mbuf_append(ctxt->om, status, sizeof(status)) == 0
             ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        goto out;
    }
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        rc = BLE_ATT_ERR_WRITE_NOT_PERMITTED;
        goto out;
    }
    const uint16_t size = OS_MBUF_PKTLEN(ctxt->om);
    if (!size || size > sizeof(packet) ||
        os_mbuf_copydata(ctxt->om, 0, size, packet) != 0) {
        rc = reject(BAD_SIZE); goto out;
    }
    if (state == REFRESHING) {
        rc = BLE_ATT_ERR_INSUFFICIENT_RES; goto out;
    }
    if (kind == 3) {
        if (state != RECEIVING || size <= 4 || read32(packet) != received ||
            size - 4 > bsp_display_frame_size() - received) {
            rc = reject(BAD_OFFSET); goto out;
        }
        if (bsp_display_frame_write(received, packet + 4, size - 4) != ESP_OK) {
            rc = reject(BAD_SIZE); goto out;
        }
        running_crc = esp_rom_crc32_le(running_crc, packet + 4, size - 4);
        received += size - 4;
        last_packet = esp_timer_get_time();
        error_code = NO_ERROR;
        goto out;
    }
    if (kind != 2) { rc = reject(BAD_COMMAND); goto out; }
    switch (packet[0]) {
    case 1: /* BEGIN: length + CRC32, little endian */
        if (size != 9 || read32(packet + 1) != bsp_display_frame_size()) {
            rc = reject(BAD_SIZE); break;
        }
        received = running_crc = 0;
        expected_crc = read32(packet + 5);
        error_code = NO_ERROR;
        state = RECEIVING;
        last_packet = esp_timer_get_time();
        break;
    case 2: /* COMMIT */
        if (size != 1 || state != RECEIVING || received != bsp_display_frame_size()) {
            rc = reject(BAD_SIZE); break;
        }
        if (running_crc != expected_crc) {
            state = FAILED; rc = reject(BAD_CRC); break;
        }
        /* Fall through to enqueue a refresh, without running SPI in the BLE host. */
        __attribute__((fallthrough));
    case 3: { /* CLEAR */
        if (size != 1) { rc = reject(BAD_SIZE); break; }
        const bool clear = packet[0] == 3;
        if (xQueueSend(work, &clear, 0) != pdTRUE) {
            rc = BLE_ATT_ERR_INSUFFICIENT_RES; break;
        }
        state = REFRESHING;
        error_code = NO_ERROR;
        if (clear) received = running_crc = 0;
        break;
    }
    case 4: /* ABORT (does not interrupt physical refresh) */
        if (size != 1) { rc = reject(BAD_SIZE); break; }
        state = IDLE; received = running_crc = 0; error_code = NO_ERROR;
        break;
    default: rc = reject(BAD_COMMAND);
    }
out:
    xSemaphoreGive(lock);
    return rc;
}

static const struct ble_gatt_svc_def services[] = {
    {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &service_uuid.u,
     .characteristics = (struct ble_gatt_chr_def[]) {
         {.uuid = &control_uuid.u, .access_cb = access_gatt,
          .arg = (void *)2, .flags = BLE_GATT_CHR_F_WRITE},
         {.uuid = &data_uuid.u, .access_cb = access_gatt,
          .arg = (void *)3, .flags = BLE_GATT_CHR_F_WRITE},
         {.uuid = &status_uuid.u, .access_cb = access_gatt,
          .arg = (void *)4, .flags = BLE_GATT_CHR_F_READ},
         {0}}},
    {0}
};

static void display_worker(void *arg)
{
    bool clear;
    for (;;) {
        xQueueReceive(work, &clear, portMAX_DELAY);
        const esp_err_t err = clear ? bsp_display_clear_white() : bsp_display_refresh();
        xSemaphoreTake(lock, portMAX_DELAY);
        state = err == ESP_OK ? DONE : FAILED;
        error_code = err == ESP_OK ? NO_ERROR : DISPLAY_ERROR;
        xSemaphoreGive(lock);
        ESP_LOGI(TAG, "refresh finished: %s", esp_err_to_name(err));
    }
}

static int gap_event(struct ble_gap_event *event, void *arg);
static void advertise(void)
{
    struct ble_hs_adv_fields fields = {
        .flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP,
        .uuids128 = (ble_uuid128_t *)&service_uuid,
        .num_uuids128 = 1, .uuids128_is_complete = 1};
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc) { ESP_LOGE(TAG, "advertising fields: %d", rc); return; }
    const char *name = ble_svc_gap_device_name();
    struct ble_hs_adv_fields response = {
        .name = (uint8_t *)name, .name_len = strlen(name), .name_is_complete = 1};
    rc = ble_gap_adv_rsp_set_fields(&response);
    if (rc) { ESP_LOGE(TAG, "scan response: %d", rc); return; }
    struct ble_gap_adv_params params = {
        .conn_mode = BLE_GAP_CONN_MODE_UND, .disc_mode = BLE_GAP_DISC_MODE_GEN};
    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
    if (rc) ESP_LOGE(TAG, "advertising start: %d", rc);
}
static int gap_event(struct ble_gap_event *event, void *arg)
{
    if (event->type == BLE_GAP_EVENT_CONNECT) {
        if (event->connect.status) advertise();
        else {
            const struct ble_gap_upd_params params = {
                .itvl_min = 12, .itvl_max = 24, .latency = 0, .supervision_timeout = 400};
            ble_gap_update_params(event->connect.conn_handle, &params);
            ESP_LOGI(TAG, "phone connected");
        }
    } else if (event->type == BLE_GAP_EVENT_DISCONNECT) {
        xSemaphoreTake(lock, portMAX_DELAY);
        if (state == RECEIVING) {
            state = IDLE; received = running_crc = 0; error_code = NO_ERROR;
        }
        xSemaphoreGive(lock);
        advertise();
    } else if (event->type == BLE_GAP_EVENT_ADV_COMPLETE) advertise();
    return 0;
}
static void on_sync(void)
{
    if (ble_hs_util_ensure_addr(0) == 0 &&
        ble_hs_id_infer_auto(0, &own_addr_type) == 0) advertise();
    else ESP_LOGE(TAG, "BLE address initialization failed");
}
static void host_task(void *arg)
{
    nimble_port_run();
    nimble_port_freertos_deinit();
}
esp_err_t ble_epaper_start(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    if (err != ESP_OK) return err;
    lock = xSemaphoreCreateMutex();
    work = xQueueCreate(1, sizeof(bool));
    if (!lock || !work) return ESP_ERR_NO_MEM;
    err = nimble_port_init();
    if (err != ESP_OK) return err;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    if (ble_gatts_count_cfg(services) || ble_gatts_add_svcs(services) ||
        ble_svc_gap_device_name_set("C3FN4-EPAPER-BLE")) return ESP_FAIL;
    ble_hs_cfg.sync_cb = on_sync;
    if (xTaskCreate(display_worker, "epaper_refresh", 4096, NULL, 4, NULL) != pdPASS)
        return ESP_ERR_NO_MEM;
    nimble_port_freertos_init(host_task);
    return ESP_OK;
}
