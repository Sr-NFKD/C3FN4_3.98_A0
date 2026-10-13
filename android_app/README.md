# 墨水屏 BLE 安卓应用

适用于 Android 13 及以上，配套 ESP32-C3 固件位于此仓库根目录（上一级目录）。
屏幕为 SE0398NZ07-FNG-A0，768×552 黑白黄红四色。

## 安装和使用

1. 先按固件工程 README 烧录 BLE 固件，给墨水屏设备上电。
2. 安装 `app/build/outputs/apk/debug/app-debug.apk`（测试签名）。
3. 开启手机蓝牙，打开“墨水屏 BLE”，允许“附近的设备”权限。
4. 点击“扫描墨水屏”，选择设备，点击“连接”。设备广播名为 `C3FN4-EPAPER-BLE`。
5. 点击“选择图片”，检查四色预览。支持旋转 90°、照片抖动和颜色反转。
6. 点击“发送到墨水屏”，等到“刷新完成”。100% 表示传输结束，实际刷新仍需等待。
7. “清屏为白色”执行整屏清白。传输中“断开”会丢弃未完成上传；已开始的物理刷新仍继续。

图片按比例完整放入画布，多余区域填白；照片通过系统文件选择器读取，无需相册或存储权限。
传输期间请保持应用在前台并靠近设备。旋转手机不会断开连接；退出应用后可重新连接。

## 构建与检查

使用现有 Android Studio / SDK 和 Gradle 配置，没有新增第三方依赖。
在 Android Studio 打开本目录，选择本机 Android SDK；`local.properties` 不提交到仓库，
由 Android Studio 生成。命令行构建也可以通过 `ANDROID_HOME` 指定 SDK。
Java 使用 Android Studio 自带的 JBR 25，下面的 JAVA_HOME 路径是本机示例。

```powershell
$env:JAVA_HOME = 'A:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`EpaperTransferTest` 检查四色编码、预览与打包一致性、最小/大 MTU 的完整分包还原、
CRC32 和设备状态格式。BLE 使用 Android 原生 GATT API，有响应写入，一次只执行一个操作。
状态每秒读取一次，确认刷新完成；普通 BLE 操作 15 秒超时，屏幕刷新 240 秒超时。

通信协议见仓库根目录 `BLE_PROTOCOL.md`。

## 实机验证

- 连上设备后清白，确认手机显示“刷新完成”且屏幕为白。
- 发送黑白黄红测试图片，确认顺序、方向与手机预览相符。
- 上传途中断开，确认屏幕不刷新，再连接后可完整重传。
- 刷新时断开再连接，应用应显示刷新中并等待完成。
- 关闭手机蓝牙后应提示断开，重新开启后可再次扫描。

已通过固件和 APK 构建及协议单元测试，APK 已安装到手机，用户已反馈可以使用。
上述步骤可用于后续回归检查。
