# 墨映 · ESP32-C3 四色墨水屏（蓝牙版）

本分支集中保存 **ESP32-C3 蓝牙固件、Android APP 2.5 和微信小程序**，适配 SE0398NZ07-FNG-A0（768×552，黑/白/黄/红）。设备广播名为 `EPD-Calendar`，采用 EP BLE v1 协议。

- 当前蓝牙源码：[BLE 分支](https://github.com/Sr-NFKD/C3FN4_3.98_A0/tree/BLE)。
- Wi-Fi 固件：[wifi 分支](https://github.com/Sr-NFKD/C3FN4_3.98_A0/tree/wifi)。仓库默认页面目前显示 Wi-Fi 分支，查看本版请切到 BLE。
- **源码、算法、第三方许可和改动说明：[SOURCES.md](SOURCES.md)**。APP 内的 `ReinkRaster` 改编自声明非商业许可的上游；本仓库不作统一 MIT/Apache 授权。

## 目录

| 路径 | 内容 |
|---|---|
| `main/`、`components/ble_epaper/` | 固件入口、BLE 服务、照片存储、日历、网络与刷新管理 |
| `components/bsp/` | ESP32-C3 板级驱动、屏幕指令和物理行映射 |
| `components/firmware_update/` | ESP-IDF 原生双分区 OTA |
| `assets/fonts.bin` | 上游字库，完整烧录时写入 fonts 分区 |
| [`android_app/`](android_app/README.md) | 墨映 Android 2.5 完整 Gradle 工程、四种图像风格及测试 |
| [`miniprogram/`](miniprogram/README.md) | 微信小程序完整工程、图片编辑、BLE 传图及测试 |
| `tests/` | 固件协议、Flash、OTA 和启动行为的主机测试 |
| [`BLE_PROTOCOL.md`](BLE_PROTOCOL.md) | UUID、EP 帧、图片编码和命令定义 |
| [`OTA_UPDATE.md`](OTA_UPDATE.md) | 首次分区迁移和蓝牙/Wi-Fi 双向互刷 |
| `components/web_ui/` | 从 Wi-Fi 工程保留的网页组件；当前 BLE 入口不构建该组件 |

## 功能与使用

手机本地处理图片，生成 105984 字节的四色 2bpp 帧，通过 BLE 分包传输。固件提供四个照片槽、双 bank 保存、回读校验、同次开机期间断线续传，以及日历、相框、日历+相框、状态页。支持 BLE 配网，联网校时与天气。APP 提供固件 OTA，小程序当前不提供 OTA。

上电默认保持墨水屏原有画面，不清白、不自动重刷照片。相框模式下网络校时、天气和定时任务也不触发照片重刷；主动传图、切换模式、刷新仍正常工作。相框照片不叠加边框或槽位标签。

1. 首次通过 USB 完整烧录固件和字库。
2. 安装自行构建的 APP，或用微信开发者工具打开 `miniprogram/` 并配置自己的 AppID。
3. 手机开启蓝牙，允许“附近的设备”或微信蓝牙权限，扫描并连接 `EPD-Calendar`。设备仅允许一个 BLE 连接，切换客户端前先断开。
4. 选图、调整、查看四色预览，点击发送并等待“刷新完成”。100% 传输进度不等于屏幕已刷新。
5. 清屏通过全白照片上传实现，会覆盖当前照片槽。续传需要设备未重启、客户端保留当前会话且再次发送同一帧。

APP 和小程序使用不同图像算法，输出的点阵可能不同；各自预览均使用实际发送帧。照片在手机本地处理，通过 BLE 发给设备。

## 固件编译与烧录

使用 **ESP-IDF 5.5.4**，目标 **ESP32-C3 / 4MB Flash**。在已配置好的 ESP-IDF 终端，从仓库根目录执行：

```powershell
idf.py -B build_ble set-target esp32c3
idf.py -B build_ble build
idf.py -B build_ble -p COM18 flash monitor
```

将 COM18 换成实际端口。输出应用为 `build_ble/C3FN4_3.98_A0_BLE.bin`。工程使用 IDF 原生 NimBLE C 接口，无需安装 esp-nimble-cpp。

**首次迁移必须写入 bootloader、分区表、OTA 初始数据、应用和字库，不能只烧应用 bin。** `idf.py flash` 已包含 `assets/fonts.bin`，字库地址为 `0x3a0000`，应用地址为 `0x10000`。旧版分区迁移可能清除设置和照片，操作前阅读 [OTA_UPDATE.md](OTA_UPDATE.md)。

本机路径和串口可在自己的开发环境配置；以以上命令为准。源码不包含构建缓存、APK、打包固件或本机 `sdkconfig`，配置从 `sdkconfig.defaults` 生成。

## APP 与小程序构建

Android：用 Android Studio 打开 `android_app/`，安装项目要求的 Android SDK 37，配置本机 SDK 与 JDK；Gradle wrapper 为 9.6.0、AGP 为 9.4.1，项目 daemon 配置使用 JDK 25。最低 Android 13（API 33）。在该目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 输出到 `android_app/app/build/outputs/apk/debug/app-debug.apk`，采用测试签名。更多操作见 [APP 说明](android_app/README.md)。

微信：在开发者工具打开 `miniprogram/`，将占位 `touristappid` 换为自己的 AppID，编译、预览后在手机测试 BLE。无需 npm 或后端服务。公开源码不含 `project.private.config.json`。详见 [小程序说明](miniprogram/README.md)。

## 接线

| 屏幕 | ESP32-C3 |
|---|---:|
| SCK | GPIO4 |
| MOSI | GPIO3 |
| CS | GPIO5 |
| BUSY | GPIO10 |
| D/C | GPIO6 |
| RST | GPIO7 |

## 检查与实机范围

```powershell
# 在仓库根目录，编译器可使用 GCC、Clang 或 Zig
python tests/run_checks.py --compiler <编译器完整路径>
node --test miniprogram/tests/checks.test.js
# Android 检查见上面的 Gradle 命令
```

主机测试覆盖命令、CRC、通知拆帧、重试去重、参数、续传、模拟 NOR Flash 提交、OTA 和启动不重刷。Android 另有图像风格固定样本、预览打包与缩放测试，小程序有模拟 BLE 会话测试。自动测试和编译不能替代手机/实体屏幕验收；仍需验证颜色方向、手机微信 BLE、断线续传、重启保图、配网天气及实际 OTA。

### 本次整理验证（2026-11-06）

- ESP-IDF 5.5.4：现有 `build_ble` 增量构建和分区大小检查通过，应用为 `0x1320c0` 字节，OTA 应用分区剩余 13%。
- 固件主机检查：Startup、OTA、Protocol、Flash 四组通过（Zig C 编译器）。
- Android：`assembleDebug`、`testDebugUnitTest`、`lintDebug` 全部通过；16 项单元测试，lint 0 错误、46 条警告。
- 小程序：Node 原生测试 9 项通过。
- 发布内容：文档本地链接检查通过，小程序 AppID 使用占位值，不包含私有配置与构建输出。本次没有烧录设备或执行手机/屏幕实机验收。
