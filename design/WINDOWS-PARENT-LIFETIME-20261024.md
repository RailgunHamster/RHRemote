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

## 轻量归属进程已复现服务端残留

上述边界现已使用独立、无图形资源的归属进程复现。原生客户端使用正常启动参数和
现有配对状态的隔离副本，继续走已配对 TLS；读取该准确 PID 的 HTTPS 流来选择
中断时刻，事后再以原生事件确认 ownerDuringLaunch=true、connection_begin 不存在。
这不是靠进程消失猜测中断发生在什么阶段。

最终测试 `light-owner-reconnect-state-api-20261024.json` 中，归属进程约 9.563 ms
发出退出信号，原生约 162.800 ms 正常结束，具有正常清理标记和唯一尾部。
第一次立即重试在归属进程退出后约 1.757 秒被主机 HTTP 409 拒绝，错误为另一个
launch/stream 正在占用主机；该次没有进入串流协议，也没有渲染。
等到退出后 11 秒再次发起，约 1.628 秒收到画面，状态接口和两条实际 render_return
同时确认。此实验给出了拒绝和恢复的观测边界，没有精确测量槽位的过期瞬间。

首次轻量测试的最终断言失败来自测试脚本：状态文件只在阶段变化时持久化，connected
写入时 firstFrame 仍为 false，后续实际已经有 539 条 render_return。原始失败结果
保留，没有改成成功；脚本改读实际 /v1/state，并在结束后核对原始渲染事件，再得到
上述独立成功复现。不能将那次脚本等待 25 秒当作产品迟迟没出画面的证据。

测试前后原生原始配置哈希不变，winserver 配置和 6 个配对保留，服务 PID 14172、
引擎 PID 30888 保持，两个显示路径的尺寸、位置、刷新率和旋转均不变。
仅停止测试自身的准确进程；普通客户端保持空闲，OPPO 未操作。

已经确认客户端本地结束并不能清掉已接受的服务端待 RTSP 请求，需要按该请求归属
取消。完整实现与并发要求见 [PENDING-LAUNCH-CANCELLATION.md](PENDING-LAUNCH-CANCELLATION.md)。
该故障路径与正常初次连接、实际串流结束超时或解锁恢复不是同一项验收。

## 本地证据

- D:\RHRemoteTools\test-windows-parent-lifetime-20261024.log
- D:\RHRemoteTools\rust-ui-parent-lifetime-tests-20261024.log
- D:\RHRemoteTools\build-rust-ui-parent-lifetime-20261024.log
- D:\RHRemoteTools\build-native-parent-lifetime-cleanup-20261024.log
- D:\RHRemoteTools\owner-exit-established-fixed-20261024.json
- D:\RHRemoteTools\owner-exit-initializing-fixed-20261024.json
- D:\RHRemoteTools\winserver-idle-after-owner-tests-20261024.json
- D:\RHRemoteTools\winserver-provenance-after-owner-tests-20261024.json
- D:\RHRemoteTools\test-light-owner-reconnect-20261024.ps1
- D:\RHRemoteTools\light-owner-reconnect-state-api-20261024.json
- D:\RHRemoteTools\winserver-provenance-after-lightowner-20261024.json
- D:\RHRemoteTools\display-after-lightowner-20261024.json
