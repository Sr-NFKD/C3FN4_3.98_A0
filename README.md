# SE0398NZ07-FNG-A0 white-screen test

ESP32-C3 drives one 768 x 552 four-colour JD79665 e-paper panel. On boot the
firmware initializes the panel, writes a full white frame, refreshes once, and
powers off the display controller.

On boot the board creates Wi-Fi network `C3FN4-EPAPER` with password
`12345678`. Connect to it and open `http://192.168.4.1` to upload an image,
generate a four-colour preview, choose landscape/portrait or colour inversion,
and send the processed frame to the display. Images are scaled with `contain`,
so the complete image is always visible and is never cropped.

## Project layout

```text
components/bsp/
├── include/bsp/bsp_board.h    Board pin assignments
├── include/bsp/bsp_display.h  Public display API
├── bsp_display.c              JD79665 display implementation
└── CMakeLists.txt
components/web_ui/
├── index.html                 Offline upload and preview page
├── epdoptimize.mjs            Vendored epdoptimize 1.3.0
├── web_ui.c                   SoftAP and HTTP endpoints
├── include/web_ui.h           Public Web UI API
└── third_party/epdoptimize/   Upstream attribution and license
main/
├── app_main.c                 Application startup
└── CMakeLists.txt
```

| E-paper | ESP32-C3 |
| --- | ---: |
| SCK | GPIO4 |
| MOSI | GPIO3 |
| CS | GPIO5 |
| BUSY | GPIO10 |
| D/C | GPIO6 |
| RST | GPIO7 |

```text
idf.py set-target esp32c3
idf.py build
idf.py -p PORT flash monitor
```

## Wireless firmware update (OTA)

Firmware 2.2.0 includes a firmware upload section in the device web page. A one-time USB migration to the 4MB dual-OTA partition table is required; back up settings/images before migration. See [OTA_UPDATE.md](OTA_UPDATE.md) for setup, application bin selection, and validation limits.

## 2.3 双向互刷

蓝牙 App 与 Wi-Fi 网页均可上传另一版应用固件。两版共用分区和字库；旧 Wi-Fi 布局需先 USB 完整更新一次。操作与迁移条件见 [OTA_UPDATE.md](OTA_UPDATE.md)。
