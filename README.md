# Mi HR Bridge v0.2.0 — 手环 GATT 只读诊断

本版在 v0.1.1 实机验证成功的 BLE 0x180D 心率输出基础上，增加 **手环 GATT 只读诊断**。

- 扫描设备或使用系统已绑定设备列表、手动输入手环地址；连接并枚举 UUID、特征属性和描述符。
- 不发配对、解绑、私有命令，不读取特征值，不主动订阅私有通知。
- 完成服务发现或 15 秒超时后自动断开。
- 可复制脱敏报告（MAC 后三组隐藏），用于下一阶段协议研究。
- 小米手环 8 Pro 的私有认证与实时心率读取仍未实现。

> 特别注意：小米运动健康若已连接手环，此诊断可能失败。不要为运行诊断而重置配对。

## Android 构建

GitHub Actions 推送后自动执行 `:app:testDebugUnitTest :app:assembleDebug` 并上传 APK。

# Mi HR Bridge (Android) / 安卓 BLE 心率转发器

**版本：0.2.0（GATT 诊断原型；v0.1.1 的 BLE 心率广播已在小米 15S Pro 实测）**

目标设备：小米 15S Pro；输出协议：Bluetooth SIG Heart Rate Service `0x180D`，Heart Rate Measurement `0x2A37`（Notify）；测试工具：Windows Bluetooth LE Explorer。此项目不需要 Python。

## 功能实现程度

| 项目 | 当前状态 |
|---|---|
| Android BLE 可连接广播，0x180D GATT 服务 | 已编写源代码；等待实机验证 |
| 0x2A37 每秒模拟心率通知 | 已编写源代码；等待实机验证 |
| 手动设置 30–220 BPM 并通知 | 已编写源代码；等待实机验证 |
| 处理 CCCD 订阅/取消、多个 GATT 客户端 | 已编写源代码；等待实机验证 |
| 前台服务（锁屏可继续运行的基础条件） | 已编写源代码；省电策略需实测 |
| 扫描附近设备并订阅通用 BLE 0x180D 心率输入 | 已编写源代码；等待实机验证 |
| 真实 BLE 心率超 5 s 未更新则停止转发旧包 | 已编写源代码；等待实机验证 |
| 小米手环 8 Pro v1.0.18 私有认证/实时心率协议 | **未实现，不能声称可实时读取手环** |
| 迈金 C506SE 兼容性 | 未实测；用户目前没有该码表 |

小米手环 8 Pro 原神定制版可能不暴露标准 0x180D。Gadgetbridge 官方资料将该类小米 protobuf 设备的第三方实时心率访问列为不支持。因此，“扫描/连接该手环”不等于“读取真实心率”。实验仅测试标准服务是否存在，不会尝试反复解绑、刷写固件或绕过认证。

## 直接用 Android Studio 构建并安装（Windows）

1. 安装 Android Studio（包括 Android SDK Platform 35 / Build Tools 35）和 JDK 17。新版本 Android Studio 内置 JBR 通常可使用。
2. 解压本工程，Android Studio 选择 **Open**，选中 `MiHRBridge` 文件夹。
3. 等待 Gradle 同步完成，安装 Android SDK Platform 35（如提示）。
4. 用数据线连接小米 15S Pro，在开发者选项打开 USB 调试，允许此电脑调试。
5. 在 Android Studio 点 **Run 'app'**，或执行 `gradlew.bat :app:assembleDebug` 构建调试 APK。
6. 调试 APK 路径：`app/build/outputs/apk/debug/app-debug.apk`。该文件只有本地或云端实际成功构建后才会出现。

### 不安装 Android Studio 的可选构建方式

仓库包含 `.github/workflows/build-apk.yml`。将整个项目上传到你的 GitHub 仓库，在 Actions 中启动 **Build Android debug APK**，成功后从 Artifacts 下载 `MiHRBridge-debug-APK`。此方式在云端构建，但需要联网和一个 GitHub 仓库；未替用户执行。

### 关于 Gradle 启动脚本

为便于在 Windows 上首次构建，项目附带一个**可审计的自制 Gradle 启动器（不是官方 Gradle Wrapper JAR）**。Java 源码在 `build-tools/org/gradle/wrapper/GradleWrapperMain.java`，编译后的文件在 `gradle/wrapper/gradle-wrapper.jar`。第一次调用 `gradlew.bat` 时，它会从 `https://services.gradle.org/distributions/gradle-8.9-bin.zip` 下载 Gradle，严格校验官方 SHA-256 `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab` 再解压。你也可以不用这个启动器，改用自行安装的 Gradle 8.9 运行 `gradle :app:assembleDebug`。

Gradle 同步还会通过 Google Maven / Maven Central 下载 Android Gradle Plugin、Kotlin Gradle 插件等官方依赖。

## 手机操作

1. 先关闭 nRF Connect 中运行的 `MI HR TEST` 广播，避免两个应用同时作为心率传感器影响识别。
2. 打开 Mi HR Bridge，授权 **附近设备 / 蓝牙**；若系统要求，授予通知权限。
3. 选择“模拟心率”或“手动输入心率”，点击“启动 BLE 心率广播”。
4. Windows Bluetooth LE Explorer 扫描包含 `0x180D` 的设备；**显示名称通常是手机系统蓝牙名称，不一定是 Mi HR Bridge**。连接并打开 `HeartRateMeasurement (0x2A37)` 的 Notify。
5. `00-5E` 代表 94 BPM（测试值）。电脑应看到数据变化。
6. 若选择“标准 BLE 心率设备”作为输入，先启动广播再扫描附近 BLE 设备，选中真正提供 `0x180D/0x2A37` 的心率传感器。成功订阅之后会转发实测心率。小米手环 8 Pro 的私有协议尚未实现；如对端无标准心率服务，会主动断开以降低占用手环连接的风险。

## 权限与后台

- `BLUETOOTH_CONNECT`：GATT 连接、服务端和蓝牙名称。
- `BLUETOOTH_ADVERTISE`：BLE 外设广播。
- `BLUETOOTH_SCAN`：仅在你点击“扫描 BLE 设备”时使用。
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`：运行 BLE 传输的前台服务。
- `POST_NOTIFICATIONS`：显示运行状态（Android 13 以上请求，可拒绝，但建议允许）。
- **不请求互联网或存储权限**，不上传心率数据，不需要用户账号。

系统可能通过省电、后台运行管制中止服务；长时间骑行需关闭该应用的后台限制并实测。应用不承诺医疗用途或绝对连续监测。

## 工程结构

- `HeartRatePeripheral.kt`：GATT Server、CCCD 订阅、广播、发送通知。
- `HrBridgeService.kt`：前台服务、数据源、1 Hz 转发与 5 s 超时策略。
- `StandardHrClient.kt`：通用 BLE 心率设备 GATT 客户端（仅标准 0x180D）。
- `BleScanActivity.kt`：设备发现，不修改配对状态。
- `MainActivity.kt`：操作界面与权限申请。
- `HrProtocol.kt`：标准心率报文编码与解析。

## 状态与限制

本工程在生成环境缺少 Android SDK、Gradle 下载网络被限制，**未能真正运行 Gradle 编译或在小米 15S Pro 安装验证**。已进行 XML 和启动器 Java 的静态检查；如果 Android Studio 首次构建报错，请提供 Build Output 的第一段错误以便修正。

### 2026-11-03 兼容性调整（v0.1.1）

- Kotlin Gradle Plugin 从 `2.0.21` 调整为 `2.2.20`，以匹配 Gradle `8.9` 和 Android Gradle Plugin `8.7.3` 的官方兼容范围。
- 若输入设备未提供 `0x180D/0x2A37`，或其 GATT 服务/CCCD 不符合标准，客户端将主动释放连接，避免持续占用小米手环。
- 本地已编译并运行 `HrProtocol.kt` 的 JVM 冒烟测试：191 个心率值往返编码/解码与 8/16 位数据包检查均通过。**这些测试不等价于 Android APK 构建或实机测试。**
- 当前执行环境没有 Android SDK，也无法解析外部 Gradle 下载域名，因此尚不能提供已编译 APK。