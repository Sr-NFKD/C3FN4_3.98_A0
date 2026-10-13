# ESP32-C3 四色墨水屏 BLE 固件

此目录已改为 BLE 手机通信。屏幕是 SE0398NZ07-FNG-A0 / JD79665，768×552 黑白黄红四色。
启动后保留屏幕原画面并广播 `C3FN4-EPAPER-BLE`，使用配套安卓应用发送图片或清白。
此版本启动流程只开启 BLE，不启动 Wi-Fi 网页。

## 手机应用

安卓完整工程位于仓库的 `android_app/` 目录，可在 Android Studio 中直接打开。
构建 APK 后输出到 `android_app/app/build/outputs/apk/debug/app-debug.apk`。
支持 Android 13 及以上，安装后允许“附近的设备”权限，扫描并连接墨水屏。
选图 → 四色预览 → 发送 → 等待“刷新完成”。手机可旋转图片、开启抖动、反转颜色。

## 编译和烧录

使用 ESP-IDF 5.5.4 环境。此目录从旧工程复制过来，原有 build 文件夹仍指向旧工程，
所以本版本固定使用独立的 `build_ble` 目录，VS Code 配置也已切换。

```powershell
idf.py -B build_ble build
idf.py -B build_ble -p COM27 flash monitor
```

将 COM27 换成实际设备端口。已编译文件位于 `build_ble`：

- `C3FN4_3.98_A0_BLE.bin`：应用固件，烧录地址 0x10000，不能把它单独烧到 0。
- `bootloader/bootloader.bin`：引导程序，地址 0。
- `partition_table/partition-table.bin`：分区表，地址 0x8000。
- `flash_args`：完整烧录参数；优先使用上面的 idf.py 命令。

固件位于仓库根目录，安卓源码位于 `android_app/`。此版本提交到 `BLE` 分支。
本机原安卓工作目录 `A:\Android_project` 仍保留，原 Wi-Fi 工程无需修改。
若从版本库全新检出且尚未设置目标，先运行 `idf.py -B build_ble set-target esp32c3`。

## 接线

| 屏幕 | ESP32-C3 |
|---|---:|
| SCK | GPIO4 |
| MOSI | GPIO3 |
| CS | GPIO5 |
| BUSY | GPIO10 |
| D/C | GPIO6 |
| RST | GPIO7 |

## 实现和验证

`components/ble_epaper` 实现 NimBLE GATT 服务，复用原 BSP 的单帧缓冲区和刷新驱动。
固定帧大小 105984 字节，数据带偏移量，整帧 CRC32 校验成功后由独立任务刷新。
刷新期间拒绝改写缓冲区。上传断开不刷新半张图片，重新连接后可从头重传。
协议详见 `BLE_PROTOCOL.md`。
原 `components/web_ui` 作为参考保留，但没有接入本版本启动流程和应用依赖。

已通过 ESP-IDF 构建、APK 构建和安卓协议/图像单元测试，APK 已安装到手机，
用户已反馈可以使用。进一步的实机检查步骤见 `android_app/README.md`。
