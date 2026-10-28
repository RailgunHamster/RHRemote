# Windows 配对保存与真实连接复验（本地 preview.55）

本轮从 UI 的“配对成功 → 连接”流程发现并修复了原生进程退出时的保存竞争。没有修改 Sunshine 的认证规则、编码器或传输协议。Windows UI 继续沿用 preview.54；Android 保留 preview.54，未操作 OPPO。

## 失败与直接原因

原版 preview.54 在实体副屏通过真实配对按钮完成配对，主机登记了唯一测试客户端，界面返回“配对成功”。随后同一客户端第一次连接却在约 829 ms 显示“计算机 winserver 尚未配对。”

失败证据在 `D:\RHRemoteTools\ui-session54-review-20261028`：

- `pair-approved01/pair-result.json`、`connect-phases.json`：配对返回成功，但随后的新串流进程失败；没有重试掩盖失败。
- `pair-storage-before.json`：正式 `RHRemote.ini` 中对应主机的 `srvcert` 仍为空的 `@ByteArray()`，临时文件 `RHRemote.ini.OicKqw` 中却有完整主机证书，并留下已退出进程的锁文件。客户端自身身份没有重新生成；问题是主机证书尚未提交到正式文件。
- `host-cleanup-before-fix.json`：失败轮的唯一临时授权已移除，主机恢复原 8 项及原始文件哈希。

源码中 `PendingPairingTask` 在成功后调用 `saveHost()`，而 `ComputerManager` 把保存交给独立的 `DelayedFlushThread`。原无界面 pair/list 入口用 `new ComputerManager(...)` 后没有持有或销毁它。配对成功立即写 `paired` 并退出进程，跳过了析构函数中等待写盘线程完成的逻辑；主进程的 `QThreadPool::waitForDone()` 也不等待这个独立 QThread。因此协议成功不等于下一进程已能读取配对证书。

## 修改

修改保留在 `tools/prepare-windows.ps1` 和 `client/windows/overlay/app/gui/rhpairingpersistence.h`，仍以固定 Moonlight 上游版本为基底生成，便于后续重新应用和合并官方更新。

1. 无界面 pair/list 的 ComputerManager 使用明确的进程内所有权。应用退出时，先等相关 worker 结束，再在 Qt 应用仍存活时销毁 manager，等待其延迟写盘线程。
2. 配对协议成功后先写内部 `pair_saving`；销毁 manager 后同步设置，核对保存的 UUID 和精确主机证书，全部通过才写最终 `paired`。文件提交失败、证书缺失或不匹配均返回具体错误及非零退出码，UI 不会把它显示为配对成功。
3. 既有 30 秒全局任务等待若超时，不销毁仍被任务引用的 manager，也不报告配对成功。此错误分支保留原进程结束边界；本轮没有模拟该任务池超时。
4. `tools/test-windows-pairing-persistence.ps1` 加入 Windows 原生构建检查。测试使用两份只含公钥证书的固定夹具，没有保存测试私钥。

## 验证

正向证据目录：`D:\RHRemoteTools\ui-session55-review-20261028`。只使用 DISPLAY2／C3273IUY，主界面位置为 `(-3776,64)`；启动及工具栏检查均 `windowActive=false`。业务操作来自应用共享动作和实际 Slint 控件，未注入系统输入，没有 computer use。

| 路径 | 结果与证据 |
| --- | --- |
| 本地存储边界 | `ui-session54-review-20261028/pin-persistence-tests01.log`：Qt 真实 INI 提交、独立进程回读、错误 UUID／证书、目标文件禁止原子替换、不可写目录、解除阻止后重试及其他设置保留均通过 |
| 原生构建 | `ui-session54-review-20261028/native55-build.log`：构建通过；现有实际 RTP/FEC 队列与帧时钟检查通过。Rust UI 没有产品代码改动，本轮 release 构建通过；既有 preview.54 的 123 core／152 UI 回归不是本轮重复执行的测试 |
| 真实配对后首次连接 | `pair-approved01`、`connect-result.json`、`connect-phases.json`：配对、连接均由实际按钮发出一次动作，重复连接被拒绝；2866 ms 轮询观察到首帧，随后取得 1920×1080 的实际解码图 |
| 结果落盘 | `pair-storage-after.json`：正式 INI 中已有与失败轮临时文件相同的主机证书，没有遗留临时文件或锁文件 |
| 首帧前取消 | `before-first-frame-summary.json`、`before-first-frame-phases.json`：原生进程已存在，实际“取消连接”按钮结束启动并回到可重试状态；完整原生记录显示 `owner_launch_aborted`，没有组帧、解码输出或渲染帧，末尾 lost_events=0。记录中的约 2.3 秒含测试接口操作和清理，不是纯清理耗时 |
| 取消后重试 | `retry-after-cancel/phases.json`、`retry-after-cancel/result-verified.json`：同一实际连接按钮重试，3119 ms 观察到后继首帧，原生组帧／解码／渲染记录对应同一次启动；无未配对、旧进程占用或 409 错误 |
| 重启后保留 | `restart-persistence.json`、`cold-reopen/result.json`：关闭并重新启动独立客户端，自动刷新仍确认已配对，设备列表仍为原 3 台；没有再次配对。实际按钮连接后 2389 ms 观察到首帧，并取得使用新文件名的实际解码图 |
| 当前会话 UI | `toolbar-connected.png`、`toolbar-expanded.png`、`expand-controls.json`、`disconnect-button.json`：实际展开控制和断开按钮各分发一次共享动作；工具栏在副屏且未激活，主动断开完成后回到原设备 |
| 清理与配置 | `host-cleanup.json`、`final-client-state.json`：两轮预览均退出，未残留串流；唯一临时授权移除，主机回到原 8 项及 SHA-256 `177acc4890b4b3b5449745a36288d26f2f0613bf114c5e2edf3562f51163134a`；日常与隔离偏好均保持 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092` |

重试驱动最初要求重新导出同名解码图而失败，原始失败日志保留。现有原生诊断功能在第 90 帧导图，并明确拒绝覆盖已有文件；该失败不等于串流失败。重试结果按当次 API 状态及完整原生解码／渲染记录复核，重启轮改用新的图像路径。文档和清单不把第 90 帧诊断图称为首帧图。

三轮成功连接的观察值为 2866／3119／2389 ms，包含应用测试接口与轮询开销；它们不是物理显示延迟，不证明启动性能提升或 1080p30 持续动态画面验收。原生 `render_return` 也只证明渲染调用返回。保持原 1080p30、H.264、25 Mbps 配置，声音始终关闭。

## 本地包及剩余工作

本地 Windows 包为 `dist/velopack-releases-0.2.11-preview.55`。便携 ZIP SHA-256 为 `8c337a25c48420029b6dc576f8f5dc871ab3329d5432c9db85930f7ca9cd2cc7`；原生组件为 `6711cac6f5e5d4dc711b2655b72a227ef612f676c249c5e24b27cedb65f9fa01`。未发布到 GitHub／Gitee，未修改主机引擎、服务或日常参数。

preview.51 的初始空阶段修正已通过本轮新包真实连接复验。首帧前取消本次发生在显示准备／正式发起会话之前，不能替代视频等待卡片在 RTSP 建立后的取消验证。本机、claw、Android 实机、跨网络、其余连接失败／恢复、实际快捷键／声音及全局键盘路径仍按 TODO 验收，完整 UI 目标尚未完成。

完整统计已记录并分析。`native-trace-summary.json` 保留各原始文件哈希、总条数、阶段计数及首个事件时间；成功轮仅保留与首帧相关的开头 8 秒、完整 UI 事件和无丢失的结束记录，已使用的后续原生帧流水按用户要求清理。失败与取消轮原始证据保留。包、清理和缩减记录见 `verification55.json`。
