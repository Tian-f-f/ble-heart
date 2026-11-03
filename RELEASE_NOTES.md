# v0.2.0 (诊断版)

- 新增独立 GATT 只读诊断页：服务、特征、描述符 UUID、属性。
- 15 秒连接/发现超时与即时释放 BLE 连接。
- 脱敏文本复制，便于交回诊断结果；无私有包发送。
- 保留 v0.1.1 已实机验证的心率广播及标准 BLE 输入。
- 注意：尚不能读取 Xiaomi Band 8 Pro 私有实时心率。

# v0.1.1 · 2026-11-03

- 提升 Gradle 8.9 / AGP 8.7.3 / Kotlin Gradle 插件版本兼容性。
- 输入端缺少标准 0x180D 服务时主动断开，减少与小米运动健康的连接冲突。
- 本地通过独立 Kotlin JVM 心率协议冒烟测试，但缺少 Android SDK，未生成 APK。

# 0.1.0 prototype status

- Standard BLE Heart Rate Service `0x180D` / Measurement `0x2A37`, CCCD read/write and Notify.
- Android foreground service, demo and manual input, advertised service UUID and local name in scan response.
- Generic **standard** BLE HR client and discovery UI to probe devices without changing pairing state.
- Timeout guard: stop relaying external data when samples are older than 5 seconds.
- No Xiaomi proprietary real-time HR command or Xiaomi account authentication.
- No APK included; Android SDK and Gradle cannot be fetched in the current build environment, so this release is source-only and not compiled or device-tested.