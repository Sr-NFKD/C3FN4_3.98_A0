#pragma once
#include "esp_err.h"
#include <stdint.h>
/* Start BLE before scheduling optional power-on clears on the display worker. */
esp_err_t ble_epaper_start(unsigned startup_clear_count);
/* Optional Android extension; upstream mini-program does not need this characteristic. */
void ble_epaper_read_status(uint8_t out[20]);
/* Host callback only signals; OTA cleanup runs in the serialized command task. */
void ble_epaper_disconnected(void);
