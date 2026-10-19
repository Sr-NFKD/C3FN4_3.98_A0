#pragma once

#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Initialize the board e-paper GPIO and SPI bus. */
esp_err_t bsp_display_init(void);

/** Refresh the entire e-paper panel to white, then power off its controller. */
esp_err_t bsp_display_clear_white(void);

/** Size of the packed four-colour framebuffer in bytes. */
size_t bsp_display_frame_size(void);

/** Write one chunk into the BSP-owned framebuffer. */
esp_err_t bsp_display_frame_write(size_t offset, const void *data, size_t size);

/** Refresh the panel from the BSP-owned framebuffer. */
esp_err_t bsp_display_refresh(void);

/** Render into a 16-row strip; retain the board's proven physical row mapping. */
typedef esp_err_t (*bsp_display_render_fn)(void *ctx, int y0);
esp_err_t bsp_display_render(bsp_display_render_fn render, void *ctx);
void bsp_display_strip_fill(uint8_t color);
void bsp_display_strip_pixel(int x, int y, uint8_t color);

#ifdef __cplusplus
}
#endif
