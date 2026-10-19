#include "firmware_update.h"
#include <string.h>
#include <stdatomic.h>
#include "esp_app_desc.h"
#include "esp_app_format.h"
#include "esp_ota_ops.h"
#include "esp_rom_crc.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdkconfig.h"

#define HEADER_SIZE (sizeof(esp_image_header_t) + sizeof(esp_image_segment_header_t) + sizeof(esp_app_desc_t))
static esp_ota_handle_t handle;
static const esp_partition_t *target;
static uint8_t header[HEADER_SIZE];
static uint32_t total, received, expected_crc, running_crc;
static int64_t last_packet;
static atomic_bool active;
static bool header_written, committed, restart_scheduled;

void firmware_update_abort(void)
{
    if (active) (void)esp_ota_abort(handle);
    active = false;
    header_written = false;
    received = 0;
}
bool firmware_update_active(void)
{
    if (active && esp_timer_get_time() - last_packet > 60000000LL) firmware_update_abort();
    return active;
}
uint32_t firmware_update_received(void) { return received; }
uint32_t firmware_update_capacity(void)
{
    const esp_partition_t *part = esp_ota_get_next_update_partition(NULL);
    return part ? part->size : 0;
}
esp_err_t firmware_update_begin(uint32_t size, uint32_t crc32)
{
    if (committed) return ESP_ERR_INVALID_STATE;
    const esp_partition_t *part = esp_ota_get_next_update_partition(NULL);
    if (!part || size < HEADER_SIZE || size > part->size) return ESP_ERR_INVALID_SIZE;
    /* A fresh upload explicitly replaces an interrupted session. */
    firmware_update_abort();
    esp_err_t err = esp_ota_begin(part, size, &handle);
    if (err != ESP_OK) return err;
    target = part; total = size; expected_crc = crc32; running_crc = 0;
    received = 0; active = true; header_written = false;
    last_packet = esp_timer_get_time();
    return ESP_OK;
}
static esp_err_t validate_header(void)
{
    esp_image_header_t image;
    esp_app_desc_t description;
    memcpy(&image, header, sizeof(image));
    memcpy(&description, header + sizeof(image) + sizeof(esp_image_segment_header_t), sizeof(description));
    const esp_app_desc_t *running = esp_app_get_description();
    if (image.magic != ESP_IMAGE_HEADER_MAGIC || image.chip_id != CONFIG_IDF_FIRMWARE_CHIP_ID ||
        description.magic_word != ESP_APP_DESC_MAGIC_WORD ||
        memchr(description.project_name, 0, sizeof(description.project_name)) == NULL ||
        (strcmp(description.project_name, "C3FN4_3.98_A0_BLE") != 0 &&
         strcmp(description.project_name, "C3FN4_3.98_A0") != 0)) return ESP_ERR_INVALID_ARG;
    /* Application OTA cannot install a partition table or the BLE font asset. */
    if (strcmp(description.project_name, running->project_name) != 0) {
        const esp_partition_t *photos = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, 0x40, "photos");
        const esp_partition_t *fonts = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, 0x41, "fonts");
        if (!photos || photos->address != 0x2d0000 || photos->size != 0xd0000 ||
            !fonts || fonts->address != 0x3a0000 || fonts->size != 0x60000)
            return ESP_ERR_INVALID_STATE;
        if (strcmp(description.project_name, "C3FN4_3.98_A0_BLE") == 0) {
            char magic[4];
            if (esp_partition_read(fonts, 0, magic, sizeof(magic)) != ESP_OK || memcmp(magic, "EPF1", 4))
                return ESP_ERR_INVALID_STATE;
        }
    }
    return ESP_OK;
}
esp_err_t firmware_update_write(uint32_t offset, const void *data, size_t size)
{
    if (!active) return ESP_ERR_INVALID_STATE;
    if (esp_timer_get_time() - last_packet > 60000000LL) {
        firmware_update_abort(); return ESP_ERR_TIMEOUT;
    }
    if (!data || !size || offset > total || size > total - offset) return ESP_ERR_INVALID_SIZE;
    if (offset < received) {
        /* Same-offset retry is accepted only when every byte matches. */
        if (size > received - offset) return ESP_ERR_INVALID_ARG;
        const uint8_t *bytes = data;
        uint8_t check[256];
        for (size_t done = 0; done < size;) {
            size_t count = size - done < sizeof(check) ? size - done : sizeof(check);
            if (!header_written) {
                if (offset + done + count > HEADER_SIZE) return ESP_ERR_INVALID_STATE;
                memcpy(check, header + offset + done, count);
            } else {
                esp_err_t err = esp_partition_read(target, offset + done, check, count);
                if (err != ESP_OK) return err;
            }
            if (memcmp(check, bytes + done, count)) return ESP_ERR_INVALID_ARG;
            done += count;
        }
        last_packet = esp_timer_get_time();
        return ESP_OK;
    }
    if (offset != received) return ESP_ERR_INVALID_ARG;
    const uint8_t *bytes = data;
    size_t prefix = 0;
    if (!header_written) {
        prefix = HEADER_SIZE - received;
        if (prefix > size) prefix = size;
        memcpy(header + received, bytes, prefix);
        if (received + prefix == HEADER_SIZE) {
            esp_err_t err = validate_header();
            if (err == ESP_OK) err = esp_ota_write(handle, header, HEADER_SIZE);
            if (err != ESP_OK) { firmware_update_abort(); return err; }
            header_written = true;
        }
    }
    if (size > prefix) {
        esp_err_t err = esp_ota_write(handle, bytes + prefix, size - prefix);
        if (err != ESP_OK) { firmware_update_abort(); return err; }
    }
    running_crc = esp_rom_crc32_le(running_crc, data, size);
    received += size; last_packet = esp_timer_get_time();
    return ESP_OK;
}
esp_err_t firmware_update_commit(void)
{
    if (!active || !header_written || received != total) return ESP_ERR_INVALID_STATE;
    if (running_crc != expected_crc) { firmware_update_abort(); return ESP_ERR_INVALID_CRC; }
    esp_err_t err = esp_ota_end(handle); /* ESP image checksum/hash/chip validation. */
    active = false;
    if (err != ESP_OK) return err;
    err = esp_ota_set_boot_partition(target);
    if (err == ESP_OK) committed = true;
    return err;
}
void firmware_update_confirm_boot(void)
{
    esp_ota_img_states_t state;
    const esp_partition_t *running = esp_ota_get_running_partition();
    if (esp_ota_get_state_partition(running, &state) == ESP_OK && state == ESP_OTA_IMG_PENDING_VERIFY)
        ESP_ERROR_CHECK(esp_ota_mark_app_valid_cancel_rollback());
}
static void restart_task(void *arg)
{
    (void)arg;
    vTaskDelay(pdMS_TO_TICKS(2000));
    esp_restart();
    vTaskDelete(NULL);
}
esp_err_t firmware_update_schedule_restart(void)
{
    if (!committed) return ESP_ERR_INVALID_STATE;
    if (restart_scheduled) return ESP_OK;
    if (xTaskCreate(restart_task, "ota_restart", 2048, NULL, 3, NULL) != pdPASS)
        return ESP_ERR_NO_MEM;
    restart_scheduled = true;
    return ESP_OK;
}
