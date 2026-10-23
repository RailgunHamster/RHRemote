# Windows UI 与原生进程的生命周期

旧版 UI 发生呈现崩溃时，原生串流进程会继续运行，失去界面中的断开入口。
该现象已在 PID 108016 的 UI 崩溃中出现。当前修复管理原生进程的归属；UI 的 GPU 崩溃原因仍在独立待办中。

## 实现

Rust 启动原生程序时传入自身 PID。原生端打开并持有该进程的同步／有限查询句柄，
验证其创建时间不晚于原生进程，拒绝无效 PID 和自身 PID。监听使用保留的句柄，后续 PID 被复用不会换成新的进程。
没有该环境参数的独立原生启动保持原行为。

独立线程等待原 UI 退出或本组件停止，无轮询和网络心跳。退出时禁止恢复／回退，
中断正在初始化的协议并发送 SDL 退出事件。持续串流循环还检查退出标记，避免事件在启动过程中被清掉。
连接前及协议阶段回调检查退出，覆盖 LiStartConnection 重置其内部中断标记的窗口。

已有 Session 时等待原清理任务及 readyForDeletion 后结束 Qt，不能因尚未收到首帧就提前结束事件循环。
显示准备返回后、发送 launch/resume 之前再检查一次归属。控制状态增加 ownerMonitorEnabled / ownerExited，
测试统计增加 owner_process_exited / owner_launch_aborted，按钮与正常串流控制接口不变。

## 验证

MSVC 独立进程测试覆盖存活父进程、不误报、停止监听、无效／自身／晚创建进程拒绝，
以及实际父进程被结束后对子进程的通知。通知样本约 17 ms。
Rust UI 库完整 42 项回归通过，Rust 发布构建及 Qt 原生完整构建通过。

在本机主屏、winserver、H.264 / 1080p30 / 5 Mbps / LAN、关闭声音下进行可记录的合成故障测试：
只终止本轮启动的准确 UI PID，确认原生进程是它的直接子进程，保留子进程查询句柄和完整统计。

| 场景 | 原生进程退出时间 | 退出码 | 正常清理及原生统计尾部 |
| --- | ---: | ---: | --- |
| 已收到画面后，最终构建 | 407 ms | 0 | 均确认 |
| 首帧前，最终构建 | 199 ms | 0 | 均确认 |

早期版本持续串流样本为 330 ms。第一次首帧前样本虽在 579 ms 退出且原生尾部存在，
但正常清理标记缺失，因此判失败；据此修正 Qt 退出时机后再测，没有把进程消失等同于清理已完成。
故障测试故意终止 UI，因此 UI 统计没有正常尾部；分析时必须保留这一不完整性，不能借原生尾部补齐。

每轮恢复设备参数、选中设备、统计开关和 WebDAV 自动同步。winserver 的服务 PID 36212、
引擎 PID 43516 均保持，6 个配对保留；检查命名媒体线程为空。OPPO 未操作，未发布新的 Windows 正式版。

## 仍待完成

当归属变化落在已经发出的 HTTP launch/resume 内，原生端退出不等于取消了服务端待 RTSP 的槽。
实际首帧前最终样本中，owner_process_exited 在 http_resume 开始之后、完成之前；随后在 before_protocol_start 中止。
Sunshine 的该槽按 ping_timeout（默认 10 秒）过期。还需实测立即重连，并实现按请求归属的取消。
全局 /cancel 会终止其他会话、应用并恢复显示配置，不能用来替代这一功能。

随后补做立即重连诊断尚未命中预期边界，不能据此报告实际重连等待 10 秒。
逐帧记录器按 250 ms 批次落盘，观察到 HTTP queued 时可能已经进入协议阶段；
改看该原生 PID 的实际 HTTPS 流后，一轮虽在进程创建后 1453 ms 观察到连接，
但终止带图形资源的 Rust UI 到其进程句柄发出退出信号仍有延迟，owner 通知发生于
50.158 ms 的 resume scope 结束之后，connection_begin 已存在。
该轮原生正常退出约 257 ms，配置与同步恢复，但 ownerDuringLaunch=false，
因此只证明该合成故障清理成功，不是待 RTSP 取消或立即重连验收。

下一步应使用没有图形资源的独立、较早创建的测试归属进程，按现有参数启动原生客户端，
再在实际 HTTP 流内结束该归属进程并事后核对 scope。禁止通过不断重跑有竞争的旧脚本
把偶然进程消失当作正确取消。当前没有增加全局取消调用或修改其他客户端会话。

## 本地证据

- D:\RHRemoteTools\test-windows-parent-lifetime-20261024.log
- D:\RHRemoteTools\rust-ui-parent-lifetime-tests-20261024.log
- D:\RHRemoteTools\build-rust-ui-parent-lifetime-20261024.log
- D:\RHRemoteTools\build-native-parent-lifetime-cleanup-20261024.log
- D:\RHRemoteTools\owner-exit-established-fixed-20261024.json
- D:\RHRemoteTools\owner-exit-initializing-fixed-20261024.json
- D:\RHRemoteTools\winserver-idle-after-owner-tests-20261024.json
- D:\RHRemoteTools\winserver-provenance-after-owner-tests-20261024.json
