# Windows 原生控制接口队列与首帧状态

后续 preview.91 的分段采集及当前未解决边界见[后续记录](WINDOWS-NATIVE-STATE-20261029.md)；本文件保留 preview.90 的原始失败及复验结果。

本轮使用 Windows `0.2.11-preview.90`，只修改原生 localhost 控制服务取连接的顺序。没有更改视频编码／解码、声音、超时、身份校验或服务器服务。OPPO 未操作。

## 已确定并修复的代码缺陷

`RhControl::Server::run()` 原来每轮先调用 `waitForNewConnection(100)`，再从 Qt 的待处理队列取一条连接。Qt 6.7.2 的实现会先等待底层监听 socket 可读，并不先检查已经接收的队列；一次底层 accept 可以放入多条连接，因此剩余连接可能要靠之后的新连接唤醒。

对应官方源码：[waitForNewConnection 与 readNotification](https://github.com/qt/qtbase/blob/v6.7.2/src/network/socket/qtcpserver.cpp#L455)。修复通过 `rhcontrolaccept.h` 先检查待处理队列，有排队连接时直接取出；队列为空才等待。工作线程上限、认证和请求解析保持原有规则。

使用实际 Qt 6.7.2 的独立复现中，两条 TCP 连接先全部建立，再取第一条。旧等待返回 `false`／超时 **108 ms**，而 `hasPendingConnections()` 仍为 `true`；修复分支 **0 ms** 取出剩余连接。

仓库回归用真实 TCP 客户端分别同时建立 2 条、8 条连接，逐一核对请求与回复，并检查空队列有界等待。相同用例替换成旧分支后在第二条连接处失败；实际共享修复函数通过。该回归已接入 `build-windows.ps1`。原生构建及原有帧时钟、配对持久化、含零字节配对密钥回归也通过。

## 实际失败与证据边界

证据目录：`D:\RHRemoteTools\ui-native-control90-review-20261029`。测试客户端只放在实体 `DISPLAY2`，复制三台设备配置到独立数据目录，实际连接 winserver 的既有物理源屏幕，1080p30／25 Mbps／H.264／硬解／静音。输入仅发给本轮有独立运行编号和 PID 的测试窗。

未改原生引擎的 `after-dark01`～`after-dark03` 完整通过；`after-dark04` 完成全部 10 个悬浮键回执后，展开面板被“视频等待状态已变化”拒绝。此时直接 HTTP 三次仍是正确 PID、`firstFrame:true`、`streaming:true`，耗时 **194／262／262 ms**。非侵入、不暂停的线程栈显示控制监听线程在 `QTcpServer::waitForNewConnection()`，不能据此宣称运行时互斥锁死锁。

独立复现证明了队列缺陷，但未证明它是全部实际失败的唯一原因。旧包实际并发基线 `baseline-dark01` 的 40 次请求也全部通过，最大 13 ms，悬浮键和断开均通过；外层脚本原来预期该轮失败，因此外层断言失败，不能改写实际成功结果。记录见 `baseline-dark01-outcome.json`。

修复版 `fixed-dark03` 仍然失败：首轮 LAN 原生记录 **577 次 render_return**，UI 却没有确认首帧，**17,137 ms** 后记录 `route_first_frame_timeout`，随后换打洞路径，在原会话尚未从主机清理时得到 409。它不能归为“从未收到视频”，也不能算作队列修复后的完整通过。原生首帧标记在渲染返回后置位，而状态文件可能仍停留在置位之前；控制接口及 UI 回退读取的具体丢点还需要继续采集。

该失败 UI 在确认 `streaming:false`、`connecting:false` 和清理未挂起后，通过自身 `window.close` 关闭，独立配置恢复，后续清理单独记录在 `fixed-dark03/late-ui-cleanup.json`。没有向未知会话发送断开，也没有重启服务。

后续观察器同时核对本次子进程、监听端口、直接 HTTP、UI 状态和渲染计数；仅在持续不一致时抓取本次进程的非侵入线程栈，不读取内存变量、令牌或画面。`fixed-dark05` 的观察器修订曾误写成未执行的 PowerShell ScriptBlock，故该轮不计观察器覆盖；实际按钮、TCP 并发和远端回执结果另外保留。后续唯一轮次使用修正脚本。

## 修复包实际复验

共 9 轮中 **8 轮完整通过、1 轮失败**，没有将失败轮重跑或覆盖。每个通过轮均完成 40 次实际并发状态请求、10 个悬浮键远端回执、开关／隐藏和正常断开；合计 **320 次请求、80 个按键回执**。状态请求采用原有 500 ms 上限，断开身份确认请求仍为 250 ms，没有提高时限。

| 轮次 | 40 次请求最大耗时 | 原生断开确认耗时 | 结果 |
|---|---:|---:|---|
| fixed-light01 | 20 ms | 510 ms | 完整通过 |
| fixed-dark01 | 18 ms | 461 ms | 完整通过 |
| fixed-dark02 | 22 ms | 463 ms | 完整通过 |
| fixed-dark03 | 未进入该步骤 | 未完整确认 | 首帧状态超时及后继 409，失败 |
| fixed-dark04 | 27 ms | 546 ms | 完整通过 |
| fixed-dark05 | 13 ms | 493 ms | 完整通过，观察器无效另记 |
| fixed-dark06 | 11 ms | 424 ms | 完整通过 |
| fixed-dark07 | 13 ms | 390 ms | 完整通过 |
| fixed-dark08 | 12 ms | 373 ms | 完整通过 |

断开耗时是从请求到子进程退出的整个阶段，不是单个 HTTP 请求耗时。8 个通过轮均在一次正确进程状态核验、一次已确认的断开请求后退出，`confirmed:true`、`forced:false`。这些数值不是与旧包的可比性能长测，不据此声称平均延迟改善。后续有效观察轮没有再次捕获首帧不一致，尚不能排除偶发问题；启动前不一致抓栈分支也未获得新的失败实测。

`final-cleanup90.json` 和 `final-run-cleanup90.json` 核对本轮全部 **14** 个实际测试：测试 UI／原生进程退出、源测试窗结束、临时任务不存在、修饰键释放；正常 Windows PID 43596 和 preferences 哈希保持不变，winserver 原 8 个配对的状态文件哈希与起始一致。远端追加核对没有本轮测试窗进程，Sunshine 和 RHVideo 仍运行。产物清理按既有脚本执行，本轮兜底检查无待删除项。

## 交付与剩余范围

候选包：`dist/velopack-releases-0.2.11-preview.90`。Portable SHA-256 为 `631CA4DF84D86DF65D4836FBB02C8E5FCA735F9D526AE14989DC62CB51ED3898`，Setup 为 `A5FD755FE5690F0B84535956365F66D8FC6C8F2497C34DAE5A59019BB391E791`。新原生引擎为 `BDA423DDBB4A7F6A5E4AAF5A7A410048A76BDD39BD9CFF17C5A5A637F1544E1C`；271 个 ZIP 条目中未发现私有配置。未替换日常客户端、未发布。

首帧状态丢失、控制接口长期不可达、最小化／恢复、跨屏 DPI 和 OPPO 实机仍在 TODO；成功短测不能代替这些范围，也不能据此声称视频性能或初次连接耗时已经改善。
