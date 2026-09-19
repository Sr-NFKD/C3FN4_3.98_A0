#pragma once

#include <stddef.h>
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

#ifdef __cplusplus
}
#endif
