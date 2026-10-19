#include "bsp/bsp_display.h"
#include "esp_log.h"
#include "ble_epaper.h"
#include "firmware_update.h"

static const char *TAG = "app";

void app_main(void)
{
    #define APP_POWER_ON_CLEAR_WHITE_COUNT 0 /* 0: disable; 1: once; 2: twice; etc. */

    ESP_LOGI(TAG, "initializing display");
    ESP_ERROR_CHECK(bsp_display_init());
    ESP_ERROR_CHECK(ble_epaper_start(APP_POWER_ON_CLEAR_WHITE_COUNT));
    firmware_update_confirm_boot();
    ESP_LOGI(TAG, "ready: EPD-Calendar (upstream mini-program protocol)");
}
