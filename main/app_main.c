#include "bsp/bsp_display.h"
#include "esp_log.h"
#include "web_ui.h"
#include "firmware_update.h"

static const char *TAG = "app";

void app_main(void)
{
    #define APP_POWER_ON_CLEAR_WHITE_COUNT 0 /* 0: disable; 1: once; 2: twice; etc. */

    ESP_LOGI(TAG, "initializing display");
    ESP_ERROR_CHECK(bsp_display_init());
    for (int i = 0; i < APP_POWER_ON_CLEAR_WHITE_COUNT; ++i) {
        ESP_LOGI(TAG, "clearing display to white on startup (%d/%d)",
                 i + 1, APP_POWER_ON_CLEAR_WHITE_COUNT);
        esp_err_t clear_err = bsp_display_clear_white();
        if (clear_err != ESP_OK) {
            ESP_LOGE(TAG, "startup clear failed: %s", esp_err_to_name(clear_err));
            break;
        }
    }
    ESP_ERROR_CHECK(web_ui_start());
    firmware_update_confirm_boot();
    ESP_LOGI(TAG, "ready: connect to C3FN4-EPAPER and open http://192.168.4.1");
}
