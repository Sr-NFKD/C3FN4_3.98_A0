/* SE0398NZ07-FNG-A0 768 x 552 four-colour e-paper BSP driver. */
#include "bsp/bsp_display.h"

#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#include "bsp/bsp_board.h"
#include "esp_attr.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define EPD_WIDTH 768
#define EPD_HEIGHT 552
#define EPD_FRAME_BYTES (EPD_WIDTH * EPD_HEIGHT / 4)
#define EPD_SPI_CLOCK_HZ (500 * 1000)
#define EPD_TX_BYTES 4092
#define EPD_READY_TIMEOUT_US (5 * 1000 * 1000LL)
#define EPD_REFRESH_TIMEOUT_US (180 * 1000 * 1000LL)

static const char *TAG = "bsp_display";
static spi_device_handle_t s_spi;
/* Legacy web upload buffer is allocated only if that API is used. */
static uint8_t *s_frame;
static uint8_t s_strip[16 * EPD_WIDTH / 4];
static int s_strip_y;
DMA_ATTR static uint8_t s_tx_buffer[EPD_TX_BYTES];

static void epd_write(bool data_mode, const uint8_t *bytes, size_t size)
{
    spi_transaction_t transaction = {.length = size * 8, .tx_buffer = bytes};
    gpio_set_level(BSP_EPD_PIN_DC, data_mode);
    gpio_set_level(BSP_EPD_PIN_CS, 0);
    ESP_ERROR_CHECK(spi_device_transmit(s_spi, &transaction));
    gpio_set_level(BSP_EPD_PIN_CS, 1);
}

static void epd_command(uint8_t command) { epd_write(false, &command, 1); }
static void epd_data(const uint8_t *data, size_t size) { epd_write(true, data, size); }

static void epd_command_data(uint8_t command, const uint8_t *data, size_t size)
{
    epd_command(command);
    epd_data(data, size);
}

/* JD79665 BUSY_N is low while the controller is busy. */
static esp_err_t epd_wait_idle(int64_t timeout_us)
{
    const int64_t deadline = esp_timer_get_time() + timeout_us;
    while (gpio_get_level(BSP_EPD_PIN_BUSY) == 0) {
        if (esp_timer_get_time() >= deadline) {
            ESP_LOGE(TAG, "BUSY timeout");
            return ESP_ERR_TIMEOUT;
        }
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    return ESP_OK;
}

static void epd_reset(void)
{
    gpio_set_level(BSP_EPD_PIN_RST, 1); vTaskDelay(pdMS_TO_TICKS(20));
    gpio_set_level(BSP_EPD_PIN_RST, 0); vTaskDelay(pdMS_TO_TICKS(40));
    gpio_set_level(BSP_EPD_PIN_RST, 1); vTaskDelay(pdMS_TO_TICKS(50));
}

static void epd_set_window(uint16_t y0, uint16_t y1)
{
    const uint16_t x1 = EPD_WIDTH - 1;
    const uint8_t window[] = {0, 0, x1 >> 8, x1 & 0xff,
        y0 >> 8, y0 & 0xff, y1 >> 8, y1 & 0xff, 1};
    epd_command_data(0x83, window, sizeof(window));
}

static esp_err_t epd_init_controller(void)
{
    static const uint8_t resolution[] = {EPD_WIDTH >> 8, EPD_WIDTH & 0xff,
                                         EPD_HEIGHT >> 8, EPD_HEIGHT & 0xff};
    const uint8_t panel_setting = 0x0b;
    epd_reset();
    ESP_LOGI(TAG, "BUSY=%d after reset", gpio_get_level(BSP_EPD_PIN_BUSY));
    if (epd_wait_idle(EPD_READY_TIMEOUT_US) != ESP_OK) return ESP_ERR_TIMEOUT;
    vTaskDelay(pdMS_TO_TICKS(30));
    epd_command_data(0x00, &panel_setting, 1);
    epd_command_data(0x61, resolution, sizeof(resolution));
    epd_command(0x04);
    vTaskDelay(1);
    return epd_wait_idle(EPD_READY_TIMEOUT_US);
}

static esp_err_t epd_send_frame(const uint8_t *frame, bsp_display_render_fn render, void *ctx)
{
    const size_t row_bytes = EPD_WIDTH / 4;
    epd_command(0x04);
    vTaskDelay(1);
    if (epd_wait_idle(EPD_READY_TIMEOUT_US) != ESP_OK) return ESP_ERR_TIMEOUT;

    for (uint16_t source_y = 0; source_y < EPD_HEIGHT; ++source_y) {
        if (render && source_y % 16 == 0) {
            s_strip_y = source_y;
            bsp_display_strip_fill(1);
            esp_err_t err = render(ctx, source_y);
            if (err != ESP_OK) return err;
            vTaskDelay(1);
        }
        const uint16_t scan_y = EPD_HEIGHT - 1 - source_y;
        const uint16_t panel_y = scan_y < EPD_HEIGHT / 2
                               ? scan_y * 2 : 2 * (EPD_HEIGHT - scan_y) - 1;
        epd_set_window(panel_y, panel_y);
        epd_command(0x10);
        const uint8_t *row = render ? s_strip + (source_y % 16) * row_bytes
                                    : frame + source_y * row_bytes;
        for (size_t i = 0; i < row_bytes; ++i) {
            const uint8_t src = row[row_bytes - 1 - i];
            s_tx_buffer[i] = ((src & 0x03) << 6) | ((src & 0x0c) << 2) |
                             ((src & 0x30) >> 2) | ((src & 0xc0) >> 6);
        }
        epd_data(s_tx_buffer, row_bytes);
        taskYIELD();
    }
    epd_set_window(0, EPD_HEIGHT - 1);
    return ESP_OK;
}

esp_err_t bsp_display_init(void)
{
    if (s_spi != NULL) return ESP_OK;

    const gpio_config_t outputs = {
        .pin_bit_mask = (1ULL << BSP_EPD_PIN_RST) | (1ULL << BSP_EPD_PIN_DC) |
                        (1ULL << BSP_EPD_PIN_CS),
        .mode = GPIO_MODE_OUTPUT, .intr_type = GPIO_INTR_DISABLE};
    const gpio_config_t input = {
        .pin_bit_mask = 1ULL << BSP_EPD_PIN_BUSY, .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE, .intr_type = GPIO_INTR_DISABLE};
    const spi_bus_config_t bus = {
        .mosi_io_num = BSP_EPD_PIN_MOSI, .miso_io_num = GPIO_NUM_NC,
        .sclk_io_num = BSP_EPD_PIN_SCK, .quadwp_io_num = GPIO_NUM_NC,
        .quadhd_io_num = GPIO_NUM_NC, .max_transfer_sz = EPD_TX_BYTES};
    const spi_device_interface_config_t device = {
        .clock_speed_hz = EPD_SPI_CLOCK_HZ, .mode = 0,
        .spics_io_num = GPIO_NUM_NC, .queue_size = 1};

    ESP_RETURN_ON_ERROR(gpio_config(&outputs), TAG, "output GPIO init failed");
    ESP_RETURN_ON_ERROR(gpio_config(&input), TAG, "BUSY GPIO init failed");
    gpio_set_level(BSP_EPD_PIN_CS, 1);
    gpio_set_level(BSP_EPD_PIN_DC, 0);
    gpio_set_level(BSP_EPD_PIN_RST, 1);
    ESP_RETURN_ON_ERROR(spi_bus_initialize(BSP_EPD_SPI_HOST, &bus, SPI_DMA_CH_AUTO),
                        TAG, "SPI bus init failed");
    return spi_bus_add_device(BSP_EPD_SPI_HOST, &device, &s_spi);
}

esp_err_t bsp_display_clear_white(void)
{
    if (s_spi == NULL) return ESP_ERR_INVALID_STATE;

    /* Colour code 1 is white: four packed white pixels form 0x55. */
    if (s_frame) memset(s_frame, 0x55, EPD_FRAME_BYTES);
    return bsp_display_render(NULL, NULL);
}

size_t bsp_display_frame_size(void)
{
    return EPD_FRAME_BYTES;
}

esp_err_t bsp_display_frame_write(size_t offset, const void *data, size_t size)
{
    if (data == NULL || offset > EPD_FRAME_BYTES || size > EPD_FRAME_BYTES - offset) {
        return ESP_ERR_INVALID_ARG;
    }
    if (!s_frame) {
        s_frame = malloc(EPD_FRAME_BYTES);
        if (!s_frame) return ESP_ERR_NO_MEM;
        memset(s_frame, 0x55, EPD_FRAME_BYTES);
    }
    memcpy(s_frame + offset, data, size);
    return ESP_OK;
}

static esp_err_t display_refresh(const uint8_t *frame, bsp_display_render_fn render, void *ctx)
{
    if (s_spi == NULL) return ESP_ERR_INVALID_STATE;

    if (epd_init_controller() != ESP_OK) return ESP_FAIL;
    if (epd_send_frame(frame, render, ctx) != ESP_OK) return ESP_FAIL;

    epd_command(0x12);
    const uint8_t zero = 0;
    epd_data(&zero, 1);
    vTaskDelay(1);
    if (epd_wait_idle(EPD_REFRESH_TIMEOUT_US) != ESP_OK) return ESP_ERR_TIMEOUT;

    vTaskDelay(pdMS_TO_TICKS(500));
    epd_command(0x02);
    epd_data(&zero, 1);
    vTaskDelay(1);
    return epd_wait_idle(EPD_READY_TIMEOUT_US);
}

esp_err_t bsp_display_refresh(void)
{
    if (!s_frame) return ESP_ERR_INVALID_STATE;
    return display_refresh(s_frame, NULL, NULL);
}

void bsp_display_strip_fill(uint8_t color)
{
    memset(s_strip, (color & 3u) * 0x55u, sizeof(s_strip));
}

void bsp_display_strip_pixel(int x, int y, uint8_t color)
{
    if (x < 0 || x >= EPD_WIDTH || y < s_strip_y || y >= s_strip_y + 16 || y >= EPD_HEIGHT) return;
    const size_t index = (y - s_strip_y) * (EPD_WIDTH / 4) + x / 4;
    const unsigned shift = 6 - 2 * (x & 3);
    s_strip[index] = (s_strip[index] & ~(3u << shift)) | ((color & 3u) << shift);
}

static esp_err_t white_strip(void *ctx, int y0)
{
    (void)ctx; (void)y0;
    return ESP_OK;
}

esp_err_t bsp_display_render(bsp_display_render_fn render, void *ctx)
{
    return display_refresh(NULL, render ? render : white_strip, ctx);
}
