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
#include "app/refresh_policy.h"
#define ESP_LOGW(tag, ...) ((void)(tag))
#define NVS_STORE_MODE_PHOTO 2
static struct { struct { int home_mode; } config; } s_app_state;
static int queue_placeholder, queued_calls;
static void *s_refresh_queue = &queue_placeholder;
static unsigned s_refresh_requested;
typedef struct { uint32_t reason_mask; bool force; uint64_t requested_ms; } refresh_queue_item_t;
static uint64_t esp_timer_get_time(void) { return 0; }
static bool refresh_queue_submit(void *queue, const refresh_queue_item_t *item) {
    (void)queue; (void)item; ++queued_calls; return true;
}
#include "submit_refresh.inc"
#include "app_entry.inc"
#include "startup_clear.inc"
int main(void) {
    app_main();
    assert(ble_started && clear_calls == 0);
    assert(s_startup_clear_count == 0);
    s_app_state.config.home_mode = NVS_STORE_MODE_PHOTO;
    assert(submit_refresh(REFRESH_REASON_BOOT, true));
    assert(submit_refresh(REFRESH_REASON_NTP_SYNC, true));
    assert(submit_refresh(REFRESH_REASON_WEATHER, false));
    assert(submit_refresh(REFRESH_REASON_AUTO_TIMER, false));
    assert(queued_calls == 0 && s_refresh_requested == 0 && locks == 0);
    assert(submit_refresh(REFRESH_REASON_PHOTO_UPLOAD, true));
    assert(submit_refresh(REFRESH_REASON_USER, true));
    assert(submit_refresh(REFRESH_REASON_MODE_CHANGE, true));
    assert(queued_calls == 3);
    s_app_state.config.home_mode = 0;
    assert(submit_refresh(REFRESH_REASON_NTP_SYNC, true));
    assert(queued_calls == 4 && locks == 0);
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
