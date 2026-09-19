#pragma once

#include "driver/gpio.h"
#include "driver/spi_master.h"

#define BSP_EPD_SPI_HOST SPI2_HOST
#define BSP_EPD_PIN_SCK  GPIO_NUM_4
#define BSP_EPD_PIN_MOSI GPIO_NUM_3
#define BSP_EPD_PIN_CS   GPIO_NUM_5
#define BSP_EPD_PIN_BUSY GPIO_NUM_10
#define BSP_EPD_PIN_DC   GPIO_NUM_6
#define BSP_EPD_PIN_RST  GPIO_NUM_7

