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
