#pragma once
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
typedef int esp_err_t;
#define ESP_OK 0
#define ESP_FAIL -1
#define ESP_ERR_INVALID_ARG 1
#define ESP_ERR_INVALID_SIZE 2
#define ESP_ERR_INVALID_STATE 3
#define ESP_ERR_INVALID_CRC 4
#define ESP_ERR_TIMEOUT 5
#define ESP_ERR_NO_MEM 6
#define ESP_ERROR_CHECK(err) assert((err)==ESP_OK)
#define ESP_IMAGE_HEADER_MAGIC 0xe9
#define ESP_APP_DESC_MAGIC_WORD 0xabcd5432u
#define CONFIG_IDF_FIRMWARE_CHIP_ID 5
#define pdPASS 1
#define pdMS_TO_TICKS(value) (value)
typedef unsigned esp_ota_handle_t;
#define ESP_PARTITION_TYPE_DATA 1
typedef struct { size_t size; uint32_t address; } esp_partition_t;
const esp_partition_t *esp_partition_find_first(int type,int subtype,const char *label);
typedef struct __attribute__((packed)) { uint8_t magic,pad[11];uint16_t chip_id;uint8_t tail[10]; } esp_image_header_t;
typedef struct { uint32_t addr,size; } esp_image_segment_header_t;
typedef struct { uint32_t magic_word;uint8_t pad[44];char project_name[32];uint8_t tail[176]; } esp_app_desc_t;
_Static_assert(sizeof(esp_image_header_t)==24,"ESP header size");
_Static_assert(sizeof(esp_app_desc_t)==256,"ESP app description size");
typedef enum { ESP_OTA_IMG_PENDING_VERIFY,ESP_OTA_IMG_VALID } esp_ota_img_states_t;
const esp_app_desc_t *esp_app_get_description(void);
const esp_partition_t *esp_ota_get_next_update_partition(const void *unused);
const esp_partition_t *esp_ota_get_running_partition(void);
esp_err_t esp_ota_get_state_partition(const esp_partition_t *p,esp_ota_img_states_t *state);
esp_err_t esp_ota_mark_app_valid_cancel_rollback(void);
esp_err_t esp_ota_begin(const esp_partition_t *p,size_t size,esp_ota_handle_t *handle);
esp_err_t esp_ota_abort(esp_ota_handle_t handle);
esp_err_t esp_ota_write(esp_ota_handle_t handle,const void *data,size_t size);
esp_err_t esp_partition_read(const esp_partition_t *p,size_t offset,void *data,size_t size);
esp_err_t esp_ota_end(esp_ota_handle_t handle);
esp_err_t esp_ota_set_boot_partition(const esp_partition_t *p);
int64_t esp_timer_get_time(void);
uint32_t esp_rom_crc32_le(uint32_t crc,const uint8_t *data,size_t size);
int xTaskCreate(void (*task)(void *),const char *name,unsigned stack,void *arg,unsigned priority,void *result);
void vTaskDelay(unsigned ticks);
void vTaskDelete(void *task);
void esp_restart(void);
