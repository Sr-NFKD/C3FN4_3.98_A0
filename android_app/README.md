# 墨水屏 BLE 安卓应用 2.2

适用于 Android 13 及以上，连接 **EPD-Calendar**。与设备端共同使用 panyihang/3.98Inch-ESP32C3-EPD 的公开 BLE v1 协议。

1. 烧录本仓库完整固件（包含新分区表与字库）。
2. 安装 `app/build/outputs/apk/debug/app-debug.apk`。
3. 开启蓝牙，允许“附近的设备”，扫描并连接设备。
4. 选图 → 四色预览 → 发送 → 等待刷新完成。
5. 清白使用全白照片上传，会替换当前照片槽。

App 使用 Android 原生 GATT API，有响应写入，先订阅 Notify，再发送 EP 协议帧。通知支持拆包/粘包，命令响应按序号匹配。正常超时以同序号重试，设备忙则以新序号重试。图片采用设备返回的接收偏移，不把 Android 写入成功当作照片保存成功。

断线后重新连接并发送同一张图，可续传（设备未重启、App 仍保留该会话）。退出 App 或更换图片后会建立新会话，必要时取消旧上传。上传完成后，我们的固件通过可选状态特征确认物理刷新；连接上游固件时没有该特征，仅报告图片已保存、设备已安排刷新。

协议 UUID 已改变，旧版 BLE 固件不能与 App 2.2 通信，需要两端一起更新。

## 构建与检查

```powershell
$env:JAVA_HOME = 'A:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\Sr_NFKD\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

10 个测试覆盖四色像素、图像打包、不同 MTU 分包、上游协议固定字节样本、CRC16/CRC32、通知流重组、设备信息与状态解析。已通过构建、单元测试、lint 错误检查；尚未安装或用本版进行实机验收。

验证时检查：通知订阅、发图、清白、上传中断线再发同一张图、刷新期间断线重连、手机蓝牙关闭后恢复。完整协议见仓库根目录 `BLE_PROTOCOL.md`。


## 固件更新（2.2）

连接设备并等待当前操作结束后，点击“选择固件并更新设备”，选择蓝牙版应用 bin。App 检查芯片/项目名和大小，确认后分包上传；校验成功后设备重启，需重新扫描连接。中断后固件必须从头上传，旧固件不会被未完成上传替换。首次需通过 USB 烧录支持双 OTA 分区的固件，步骤见仓库根目录 OTA_UPDATE.md。

扫描恢复为服务 UUID 过滤，持续 15 秒。

## App 2.3

固件选择支持蓝牙版与 Wi-Fi 版应用 bin，更新前后提示目标版本的连接方式。服务 UUID 扫描过滤保持启用。支持互刷需设备先运行 2.3 固件并使用共同分区/字库。
