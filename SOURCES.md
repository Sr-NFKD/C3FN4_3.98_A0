# 源码、算法来源与第三方声明

整理日期：2026-11-06。本文区分直接改编代码、通用算法和仅保留的组件。来源说明不能替代授权；未明确授权的部分不自动视为 MIT 或 Apache。本仓库没有给所有文件统一添加许可证。

## 1. 蓝牙固件、协议和字库

- 上游：[panyihang/3.98Inch-ESP32C3-EPD](https://github.com/panyihang/3.98Inch-ESP32C3-EPD)。
- 参考版本：[779eab27ba8b914e097fec530d1e1d601265fdcc](https://github.com/panyihang/3.98Inch-ESP32C3-EPD/tree/779eab27ba8b914e097fec530d1e1d601265fdcc)。
- 对应文件：`components/ble_epaper/` 下协议、命令、照片存储、UI、网络、刷新等模块，以及 `assets/fonts.bin`。这些模块基于上游改编，不应标注为全部原创；字库字体的更早授权链未在本次整理中独立确认。
- 协议兼容：EPD-Calendar 名称、服务 UUID、Control/Notify/Image 特征、EP 帧与原有 14 条命令；详见 [BLE_PROTOCOL.md](BLE_PROTOCOL.md)。
- 本工程适配：ESP-IDF 5.5 原生 NimBLE C GATT、现有 ESP32-C3 BSP/屏幕物理行映射、可选刷新状态特征、启动保图及相框刷新策略、共用分区 OTA 和手机端对接。
- 许可核对：上述提交没有仓库级 LICENSE 文件，README 未见统一许可声明。上游 `managed_components/h2zero__esp-nimble-cpp` 的许可只适用于该依赖，不能代表整个固件或字库；本工程使用 IDF 原生 NimBLE，不依赖该 C++ 库。需要再分发或商业使用时，应核对原作者授权和字库来源。

板级驱动 `components/bsp/` 延续本仓库原有 Wi-Fi 工程；板级历史来源未在现存文件中形成完整第三方许可声明，不将其重新归为本次上传原创。

## 2. Android 四种出图风格：ReinkRaster

直接代码来源是 [yhl88890/qpoll-lite](https://github.com/yhl88890/qpoll-lite)，而不是本项目从零编写。

- 核对上游提交：[8c6bae56ad7c8e5229d66ad176cabaf2822cadc9](https://github.com/yhl88890/qpoll-lite/tree/8c6bae56ad7c8e5229d66ad176cabaf2822cadc9)。
- 原文件：[src/com/qpoll/lite/ReinkRaster.java](https://github.com/yhl88890/qpoll-lite/blob/8c6bae56ad7c8e5229d66ad176cabaf2822cadc9/src/com/qpoll/lite/ReinkRaster.java)。Git blob：`0566be301c9b14121c192c59627379c556c0f737`。
- 本地文件：[ReinkRaster.java](android_app/app/src/main/java/com/sr/nfkd/ReinkRaster.java)。
- 上游 README 声明 **PolyForm Noncommercial 1.0.0**，并说明四档图像算法取自墨小佳（Reink Case）App 的 Flutter debug 构建。该更早来源是上游作者的陈述，本项目没有独立核验原 App 或获得其商业许可；不能仅凭上游仓库声明推断更早权利方已授权。
- 保留原算法注释，Java 包名调整为 `com.sr.nfkd`；通过 `FrameCodec` 接入本工程的 2bpp 打包、预览、反色、红黑黄深浅调节与 Android 风格选择。没有引入该项目的 NFC 或设备供电实现。
- [随 APK 打包的来源声明](android_app/app/src/main/assets/qpoll-lite-NOTICE.txt)；[许可文本](android_app/app/src/main/assets/licenses/PolyForm-Noncommercial-1.0.0.txt)。

| APP 风格 | 当前实现 |
|---|---|
| 鎏金 | 原图 + Atkinson 误差扩散 |
| 质感 | 上游 LOMO 4×5 色彩矩阵 + Atkinson |
| 色阶 | 最近色量化，不扩散误差 |
| 水墨 | 灰度化 + Atkinson |

Atkinson 核向右一、右二、左下、下、右下、下二六个邻居各分配误差的 1/8，只传播 6/8。其名称来自 Bill Atkinson；本项目的具体 Java 实现来源为上面固定的 `qpoll-lite` 文件。最近色匹配采用上游 redmean（红均值加权）距离。匹配调色板为黑、白、`#f5cd00`、`#c80000`，传输/预览仍采用四种协议色，不能把屏幕匹配调色板和传输颜色混为一谈。

Android 的界面、蓝牙客户端、协议封装、`FrameCodec` 集成和 `ImagePlacement` 等为本工程的配套实现，不因此改变所引用算法文件的上游许可。

## 3. 微信小程序图像算法

文件：[utils/image.js](miniprogram/utils/image.js)。本次从配套小程序工程整理入库，没有复制 Android `ReinkRaster`。

- 四色最近色选择：对 RGB 差平方按 `0.30 / 0.59 / 0.11` 加权，在黑、白、黄、红中选择距离最小颜色。这是当前实现的距离权重，不等于完整色彩管理模型。
- 抖动：蛇形 Floyd–Steinberg 误差扩散；逐行反转扫描方向，并镜像扩散核，向前方和下一行传播 `7/16、3/16、5/16、1/16` 误差。
- 算法文献来源：Robert W. Floyd、Louis Steinberg，*An Adaptive Algorithm for Spatial Greyscale*，Proceedings of the Society for Information Display，17(2)，75–77，1976。这里说明算法名称和文献归属，不声称本文件直接复制该论文源码。
- 本项目实现：白底透明像素合成、红/黑/黄 0%～200% 深浅预处理、反色、取消处理、四色 Canvas 预览和高位像素优先的 2bpp 打包。
- 缩放由 Canvas 完成，按屏幕与原图比例适应，允许 25%～300% 调整与 90°旋转。小程序不含 APP 的 LOMO/Atkinson 四档风格，两端结果不要求逐像素相同。

小程序的 BLE 管理使用微信原生 API；协议实现参考同一上游 EP v1 定义，并增加本项目 Status 扩展适配。

## 4. 数据校验与存储策略

这些是通用算法或工程策略，不能全部宣称为本项目首创。

| 项目 | 采用参数/策略 | 实现位置 |
|---|---|---|
| 命令 CRC16 | CCITT-FALSE 参数：poly `0x1021`、init `0xffff`，无反射、无最终异或 | 固件 `ble/ble_protocol.c`、Android `EpaperProtocol.java`、小程序 `utils/protocol.js` |
| 图片 CRC32 | 反射多项式 `0xedb88320`，初值/最终异或 `0xffffffff`，对应常用 CRC-32/ISO-HDLC | 同上，Android 也使用 `java.util.zip.CRC32` |
| 四色 2bpp | 0黑/1白/2黄/3红；一字节四像素，首像素在高两位；768×552 共 105984 字节 | 两客户端图像模块及固件显示/存储 |
| 断线续传 | 设备报告接收偏移；需同次开机和客户端仍保留会话 | `ble_upload.c`、两客户端 BLE 模块 |
| 照片持久化 | 四槽双 bank、回读校验后提交，失败保留旧照片 | `storage/photo_store.c` |
| OTA | ESP-IDF 官方 OTA API、双应用分区、校验与回滚 | `components/firmware_update/` |

## 5. 保留的网页图像库与平台依赖

`components/web_ui/epdoptimize.mjs` 来自 **paperlesspaper/epdoptimize 1.3.0**，原作者 Robert Gühne，Apache-2.0。见 [上游](https://github.com/paperlesspaper/epdoptimize)、[本地来源说明](components/web_ui/third_party/epdoptimize/README.md) 和 [许可全文](components/web_ui/third_party/epdoptimize/LICENSE)。该网页组件是 Wi-Fi 工程遗留，当前 BLE `main/CMakeLists.txt` 只依赖 BSP、BLE 与固件更新组件；它不是 APP 或小程序正在调用的图像算法。

固件依赖 ESP-IDF（含 NimBLE 等平台组件）；Android 依赖 Gradle 配置中列出的 AndroidX、Material、JUnit 等；小程序运行于微信 API，不需要 npm。平台和依赖各按其自身许可/使用条件使用，具体版本分别见构建配置，不能用单个第三方库许可证覆盖整仓库。

## 6. 本次发布内容和验证边界

本次将最新配套 Android 2.5 与小程序完整源码汇入 BLE 分支，保留固件已有功能与测试，排除构建目录、APK、固件输出、私有配置和发布签名。小程序 AppID 替换为占位值，使用者需配置自己的 AppID。

编译和自动测试只能证明对应构建或模拟路径通过；手机蓝牙、屏幕物理表现、微信平台审核与实体 OTA 需要单独实测。使用和复现步骤见根目录及两个客户端 README。
