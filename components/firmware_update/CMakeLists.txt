idf_component_register(SRCS "firmware_update.c" INCLUDE_DIRS "include"
                       REQUIRES app_update esp_timer esp_system spi_flash)
