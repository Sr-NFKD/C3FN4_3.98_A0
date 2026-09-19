#include "bsp/bsp_display.h"
#include "esp_log.h"
#include "web_ui.h"

static const char *TAG = "app";

void app_main(void)
{
    ESP_LOGI(TAG, "initializing display");
    ESP_ERROR_CHECK(bsp_display_init());
    ESP_ERROR_CHECK(web_ui_start());
    ESP_LOGI(TAG, "ready: connect to C3FN4-EPAPER and open http://192.168.4.1");
}
