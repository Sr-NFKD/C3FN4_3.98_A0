/* Run the real app entry and startup-clear worker code with a failed panel. */
#include <assert.h>
#include <stdbool.h>
#include <stdio.h>
typedef int esp_err_t;
#define ESP_OK 0
#define portMAX_DELAY 0
#define ESP_LOGI(tag, ...) ((void)(tag))
#define ESP_LOGE(tag, ...) ((void)(tag))
#define ESP_ERROR_CHECK(result) assert((result) == ESP_OK)
static const char *TAG = "startup-test";
static int s_photo_mutex, s_display_mutex, s_config_mutex;
static unsigned s_startup_clear_count, clear_calls;
static int s_display_state, s_display_error, locks;
static bool ble_started, fail_panel;
static int xSemaphoreTake(int mutex, int ticks) { (void)mutex; (void)ticks; ++locks; return 1; }
static void xSemaphoreGive(int mutex) { (void)mutex; --locks; }
static esp_err_t bsp_display_init(void) { return ESP_OK; }
static void firmware_update_confirm_boot(void) { assert(ble_started); }
static esp_err_t ble_epaper_start(unsigned count) {
    assert(clear_calls == 0);
    s_startup_clear_count = count;
    ble_started = true;
    return ESP_OK;
}
static esp_err_t bsp_display_clear_white(void) {
    assert(ble_started && s_display_state == 2);
    ++clear_calls;
    return fail_panel ? -1 : ESP_OK;
}
#include "app_entry.inc"
#include "startup_clear.inc"
int main(void) {
    app_main();
    assert(ble_started && clear_calls == 0);
    for (unsigned count = 0; count <= 3; ++count) {
        for (int failed = 0; failed <= 1; ++failed) {
            s_startup_clear_count = count; clear_calls = 0;
            s_display_state = s_display_error = locks = 0; fail_panel = failed;
            refresh_task(NULL);
            assert(ble_started && locks == 0);
            assert(clear_calls == (failed && count ? 1 : count));
            assert(s_display_state == (!count ? 0 : failed ? 4 : 3));
            assert(s_display_error == (failed && count ? 5 : 0));
        }
    }
    puts("Startup: BLE starts before panel I/O; failed clear preserves BLE and releases locks");
    return 0;
}
