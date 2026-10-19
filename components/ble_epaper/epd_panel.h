#pragma once
#include "bsp/bsp_display.h"
/* Adapt the upstream canvas to our board's SPI driver and physical row mapping. */
#define epd_fb_fill bsp_display_strip_fill
#define epd_fb_set_pixel bsp_display_strip_pixel
#define epd_display bsp_display_render
