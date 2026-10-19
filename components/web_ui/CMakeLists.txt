idf_component_register(SRCS "web_ui.c"
                       INCLUDE_DIRS "include"
                       REQUIRES bsp esp_http_server esp_wifi nvs_flash firmware_update
                       EMBED_FILES "index.html" "epdoptimize.mjs")

