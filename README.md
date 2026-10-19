# ESP32-C3 四色墨水屏固件（EPD-Calendar 协议）

本工程兼容 [panyihang/3.98Inch-ESP32C3-EPD](https://github.com/panyihang/3.98Inch-ESP32C3-EPD) 提交 `779eab2` 的公开 BLE v1 协议。设备名为 **EPD-Calendar**，使用相同服务 UUID、控制/图片/通知特征、EP 帧封装和全部 14 条命令。我们的安卓 App 同步采用此协议。尚未使用对方小程序进行实机联调，不能把协议检查通过等同于小程序验收完成。

## 功能

- 手机处理 768×552 四色 2bpp 图片，BLE 分包传输、命令 CRC16、整图 CRC32。
- 四个照片槽，每槽双 bank；Flash 回读校验成功后才提交，失败保留旧照片。
- BLE 断线后同一次开机期间可以续传；设备重启后保留已提交照片。
- 日历、相框、日历+相框、状态页；旋转、时区、位置、今日计划持久化。
- BLE 配网，设备 Wi-Fi 联网校时、获取天气。无需手机连接设备热点。
- 保留本板已验证的 BSP 屏幕指令与物理行映射。条带绘制避免常驻 106KB 帧缓冲。
- 可选的只读状态特征供我们的 App 确认实际刷新完成，不改变小程序依赖的特征。

蓝牙启动后，由独立屏幕任务按 `APP_POWER_ON_CLEAR_WHITE_COUNT` 执行开机清白（当前为 1 次，设为 0 可保留原画面）；清白超时或失败不会阻塞蓝牙广播。相框模式有已保存照片时随后重新显示。相框显示原图，不叠加槽位标签或边框。默认模式仍为上游日历模式，小程序可设置为相框，我们的 App 发图时会选择相框模式。

## 编译与烧录

使用 ESP-IDF **5.5.4**，无需升级到 IDF 6 或安装 esp-nimble-cpp。NimBLE 服务使用 IDF 原生 C 接口。

```powershell
idf.py -B build_ble build
idf.py -B build_ble -p COM18 flash monitor
```

COM18 换为实际端口。本版使用 **4MB Flash** 和 `partitions.csv`，首次迁移必须完整烧录 bootloader、分区表、应用及字库；不要仅烧录应用 bin。正常 `idf.py flash` 已包含字库 `assets/fonts.bin`，地址 0x3a0000。原 BLE 版本没有保存照片；迁移后新增照片分区，后续不要再用旧分区表烧录。

无线固件更新已加入：蓝牙版使用 App 2.2 的“选择固件并更新设备”，Wi-Fi 版使用网页的“固件更新”。首次必须迁移为双 OTA 分区，具体步骤及数据迁移说明见 [OTA_UPDATE.md](OTA_UPDATE.md)。

输出固件：`build_ble/C3FN4_3.98_A0_BLE.bin`，应用地址 0x10000。完整烧录参数见 `build_ble/flash_args`。本次 OTA 版本尚未自动烧录设备。

## 安卓 App

源码：`android_app/`；APK：`android_app/app/build/outputs/apk/debug/app-debug.apk`。

Android 13 及以上，允许“附近的设备”权限后扫描 **EPD-Calendar**。发图和清白都使用上游照片上传命令。断线后重新连接并发送同一张图片，可在设备未重启、App 仍保留该上传会话时恢复进度。App 退出后重新启动会创建新会话。

## 接线

| 屏幕 | ESP32-C3 |
|---|---:|
| SCK | GPIO4 |
| MOSI | GPIO3 |
| CS | GPIO5 |
| BUSY | GPIO10 |
| D/C | GPIO6 |
| RST | GPIO7 |

## 验证

- ESP-IDF 构建通过，4MB 分区范围及应用大小检查通过。
- APK 构建与协议/图像单元测试通过，Android lint 无错误（有现有样式、文本等警告）。
- `tests/run_checks.py` 编译并运行固件 C 源码，检查所有命令、CRC、拆帧、重试去重、参数校验、续传。
- `tests/test_flash.c` 在模拟 NOR Flash 上运行真实 ESP 存储路径，检查乱序/重复上传、CRC、取消、提交失败、重启选择有效 bank。

```powershell
python tests/run_checks.py --compiler <gcc、clang 或 zig.exe 的路径>
```

还需上板验证：小程序扫描与订阅、四色传图、断线续传、重启显示、配网与天气、刷新期间控制命令和长时间运行。详细协议见 `BLE_PROTOCOL.md`。

## 来源

`components/ble_epaper/` 下协议、命令、存储、UI、网络和刷新模块改编自上述上游提交；本工程提供 IDF 5.5 原生 GATT 接入、BSP 适配、刷新状态扩展及安卓客户端。字库 `assets/fonts.bin` 同样来自该提交。

## 2.3 双向互刷

蓝牙 App 与 Wi-Fi 网页均可上传另一版应用固件。两版共用分区和字库；旧 Wi-Fi 布局需先 USB 完整更新一次。操作与迁移条件见 [OTA_UPDATE.md](OTA_UPDATE.md)。
