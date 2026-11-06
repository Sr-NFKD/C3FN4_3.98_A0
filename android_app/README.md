# 墨映 · Android APP 2.5

Android 13（API 33）及以上，连接本仓库蓝牙固件 `EPD-Calendar`。本目录同步自独立的 Android 工程，包含源码、资源、Gradle wrapper 与单元测试。

## 功能

- 未连接时也能选图、编辑；仅发送、清屏和 OTA 需要连接。
- 552×768 竖向预览，转换为屏幕原生 768×552 帧；25%～300% 缩放、适应屏幕、90°旋转。
- 鎏金、质感、色阶、水墨四种风格；反色以及红、黑、黄独立深浅调节（0%～200%）。
- 纸白、松绿、海蓝、胭红主题和四色图标。
- BLE 顺序传输、应答序号匹配、忙状态重试、通知拆帧、断线续传、刷新状态确认。
- 选择应用 bin 进行蓝牙 OTA，支持配套蓝牙/Wi-Fi 固件互刷。

## 使用

1. 按根目录 README 完整烧录设备固件与字库。
2. 安装构建后的 `app/build/outputs/apk/debug/app-debug.apk`。
3. 开启蓝牙，允许“附近的设备”，扫描并连接 `EPD-Calendar`。
4. 选图、调整风格与颜色、检查预览，发送并等待实际刷新完成。
5. 清白会以全白图片替换当前照片槽；固件更新前等待当前操作完成。

手机处理图片时采用白底合成，预览颜色与发送帧编码一致。保持应用在前台并靠近设备。设备未重启、APP 保留上传会话、再次发送相同帧时可以续传；退出 APP 或更换图片会建立新会话。

客户端先订阅通知，再发送 EP v1 帧。可选 Status 特征用于确认物理刷新；连接不提供此特征的上游固件时，只能确认图片保存并已安排刷新。不能与最早 `7e400001...` UUID 的旧固件混用。

## 构建和检查

用 Android Studio 打开本目录，安装 SDK 37，配置本机 SDK 路径与 JDK。项目使用 Gradle 9.6.0、AGP 9.4.1，daemon JVM 配置使用 JDK 25。`local.properties` 属于本机配置，不上传。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

测试包含协议固定样本、CRC16/CRC32、最小/大 MTU 分包、通知流、设备状态、预览与打包、缩放、透明像素，以及四种风格的上游固定样本摘要。调试 APK 使用测试签名；源码不含发布签名。

## 源码与算法来源

`ReinkRaster.java` 改编自 [yhl88890/qpoll-lite](https://github.com/yhl88890/qpoll-lite)，上游声明 **PolyForm Noncommercial 1.0.0**，并将算法归于墨小佳（Reink Case）。本工程保留原注释、声明和许可文本，新增 Java 包名适配、BLE 打包、预览、深浅调节及 UI 接入。未移植上游 NFC 或设备供电代码。

- [详细来源与改动](../SOURCES.md)
- [随 APK 打包的声明](app/src/main/assets/qpoll-lite-NOTICE.txt)
- [PolyForm 非商业许可全文](app/src/main/assets/licenses/PolyForm-Noncommercial-1.0.0.txt)
- [设备协议](../BLE_PROTOCOL.md)
- [OTA 与分区迁移](../OTA_UPDATE.md)

本 APP 的四种风格与小程序的 Floyd–Steinberg 量化不同；不保证跨客户端点阵一致，也不代表获得 Reink Case 商业授权。实机联调需另外验证扫描、四颜色、断线、刷新状态与 OTA。
