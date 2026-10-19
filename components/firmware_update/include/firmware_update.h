#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

/* Serialized by each transport's existing worker / HTTP request lock. */
esp_err_t firmware_update_begin(uint32_t size, uint32_t crc32);
esp_err_t firmware_update_write(uint32_t offset, const void *data, size_t size);
esp_err_t firmware_update_commit(void);
void firmware_update_abort(void);
bool firmware_update_active(void);
uint32_t firmware_update_received(void);
uint32_t firmware_update_capacity(void);
void firmware_update_confirm_boot(void);
esp_err_t firmware_update_schedule_restart(void);
