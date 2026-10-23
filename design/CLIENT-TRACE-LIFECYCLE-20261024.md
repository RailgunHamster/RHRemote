# Windows 客户端统计文件生命周期

2026-10-24，测试新主机统计请求时发现两个独立问题。

## 统计完整性

Windows 的 `ui.jsonl` 原来没有 `trace_end`；每条 UI 元数据打开文件追加，没有显式的会话结束。
分析器只检查合并后的所有事件中是否存在任意结束标记，因此原生进程的正常尾部会掩盖 UI 文件不完整。
这既不能证明 UI 正常结束，也可能让分析器删除仍需定位的原始记录。

现在 UI 记录有独立的 recording 状态，latest 仍保留供报告查找。真实启动工作、当前原生进程和退役进程均已结束，
且没有等待中的恢复时，保持连接准入锁关闭旧 UI 记录，防止检查空闲后新连接恰好创建新文件。
新一轮选路开始前关闭上一轮文件；结束操作幂等，迟到事件不再追加到已关闭的文件。
关闭统计和正常释放记录器也会完成尾部；异常退出仍保留缺少尾部的证据。原生文件维持独立生命周期。

分析器逐个检查 manifest 声明的文件，包括不存在、缺少／重复结束标记、尾部后还有事件、记录丢失、
文件封顶、损坏／截断行和非法尾部。刷新已保存报告时保留这些原始完整性结果。
只有全部文件完整且其他分析没有警告，才在报告、表格和时间线保存后自动删除原始文件。

42 项 Rust UI 库测试通过，其中新增 4 项记录器生命周期和 2 项真实启动／退役进程等待测试。
15 项客户端分析测试通过，其中新增 4 项逐文件完整性、刷新和清理回归；主机分析器 18 项测试仍通过。

实际 Windows 连接旧主机 8f00c0b3：H.264 / 1080p30 / 5 Mbps / 1024 分包 / LAN，声音关闭。
新的可选统计请求得到 unsupported，但串流正常。修复 UI 生命周期后的记录为
`1792781989836-d300b7b6402c`，192 次 render_return；两个文件各有一个正常结束标记，无丢记录或分析警告。
本次是 resume，连接动作至首次渲染提交返回为 2030.688 ms，不代表首次启动或稳定时延上限。
配置、自动同步和选择的设备已恢复，原始记录已在分析后删除。

## 一次主界面呈现崩溃（未解决）

第一次测试中，旧 UI PID 108016 运行约 6 小时后发生访问违规，故障模块 d3d11.dll。
本地符号转储显示 Slint / FemtoVG 经 glutin swap_buffers 调用 WGL，随后进入 NVIDIA OpenGL
`nvoglv64!DrvPresentBuffers` 和 d3d11，最终读取空指针。该调用栈定位到 UI 呈现路径，
还不能确定是哪个资源生命周期、驱动状态或外部条件触发。

原生进程 PID 128672 仍收到画面；已通过其认证控制接口发送正常断开，并确认进程退出。
重启 UI 后两次短连接正常，不能作为长期崩溃问题已解决的证据。
后续需要复现、核对显示变化和呈现资源，并保证 UI 异常时原生会话有清理路径。
没有据此修改显卡驱动、服务端业务、用户分辨率或发布新的 Windows 正式版。

保留用于当前定位的第一轮统计、转储和本地调用栈。OPPO 未操作。

证据：

- `D:\RHRemoteTools\rust-ui-d3d11-crash-local-20261024.log`
- `C:\Users\40608\AppData\Local\CrashDumps\RHRemote.exe.108016.dmp`
- `D:\RHRemoteTools\ui-crash-test-restoration-20261024.json`
- `D:\RHRemoteTools\rust-client-statistics-lifecycle-full-20261024.log`
- `D:\RHRemoteTools\test-client-file-completeness-20261024.log`
- `D:\RHRemoteTools\host-frame-connection-legacy-lan-ui-footer-20261024.json`
- `D:\RHRemoteTools\host-frame-legacy-ui-footer-analysis-20261024.log`
