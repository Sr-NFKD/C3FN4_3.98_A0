idf_component_register(
    SRCS "ble_epaper.c" "ble/ble_service.c" "ble/ble_gatt.c"
         "ble/ble_protocol.c" "ble/ble_commands.c" "ble/ble_upload.c"
         "storage/nvs_store.c" "storage/photo_store.c" "storage/font_store.c"
         "app/app_state.c" "app/refresh_policy.c" "app/refresh_queue.c"
         "net/wifi_manager.c" "net/ntp_manager.c" "net/weather_client.c"
         "display/rotation.c" "display/manual_canvas.c" "display/font_bitmap.c"
         "ui/ui_app.c"
    INCLUDE_DIRS "include" "." "ble" "storage" "app" "net" "display" "ui"
    REQUIRES bsp bt nvs_flash esp_timer esp_wifi esp_event esp_netif esp_http_client esp-tls spi_flash firmware_update)
