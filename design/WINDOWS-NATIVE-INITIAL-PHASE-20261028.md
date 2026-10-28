# Windows 原生组件初始空阶段误判

本地 preview.50 的真实配对／连接检查发现：客户端已承认一次连接，原生进程仍存活且已启动 HTTP 状态接口，但 `phase` 尚为空字符串。UI 控制器将这一状态当作连接提前结束。随后原生组件收到首帧并进入 connected，UI 已停在失败页面；再点连接则遇到 native session already running。

证据根目录：`D:\RHRemoteTools\ui-session50-review-20261028`。`connect-phases.json` 记录约 420 ms 时 UI 错误失败；`premature-failure-native-connected.json` 对照 UI failed 与原生 connected／firstFrame=true；`decoded-first-session01.png` 是原生组件实际解码出的首帧。这个问题与“先配对成功后是否需要新增连接按钮”无关，实际配对成功后已有正常设备页连接入口。

修正在 Windows NativePlatform 状态适配器中：仅当原生进程属于当前启动、仍存活、有本次 Recovery、尚无首帧、阶段为空且无错误／routeFatal 时，将状态视为 connecting 或 reconnecting。既有启动／首帧超时预算继续运行，不延长无限等待，也不忽略显式错误、身份失败或进程归属不一致。

新增两条回归使用实际受控子进程、HTTP 状态接口及正式 Controller：空阶段 → starting → connected 保持同一次操作，期间重复连接不启动第二次；空阶段超过初始化预算仍发出 `/v1/disconnect`。旧实现由 `empty-phase-before02.log` 复现，修正后 `empty-phase-after-recovery.log` 的 16 条恢复传输测试通过；随后完整 UI 148 项回归通过。

本地 preview.51 包含修正，但本次用户要求先重做设备设置导航，尚未以新包完成真实连接复验，不能把回归结果写成真实连接通过。测试期间创建的唯一客户端已移除，`host-cleanup.json` 记录授权恢复为原 8 项、原始哈希不变；日常配置及预览偏好哈希相同，没有重启 Sunshine 或修改显示拓扑。
