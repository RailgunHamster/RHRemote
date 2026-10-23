# 启动前选择捕获屏

2026-10-23，Windows 与服务端已部署验证；Android 已构建并通过 JVM 测试，OPPO 实机继续暂停。

旧流程在 `/v1/display/prepare` 得到目标屏后，仍按 Sunshine 的全局默认屏建立捕获。Windows / Android 到串流启动之后再发 Ctrl+Alt+Shift+Fn 切屏。winserver 的日志在同一次连接中出现 1920×1080、2520×1680 两次捕获初始化，两次编码器创建；旧统计的首个 render_return 可能属于默认屏。

新版沿用已经配对的 HTTPS `/launch`、`/resume`，增加 `rhrdisplay` 参数，内容为刚准备完成的 Windows GDI 源名。服务端验证格式，存入该 launch session，经 RTSP 配置传到捕获线程；第一次使用全新的后端可捕获列表精确匹配，然后才创建捕获设备。目标不存在或出现歧义时失败，不以主屏或旧列表代替。全局 output_name 不修改；串流中的后续切屏热键仍保留原行为。

服务端返回 `RHInitialDisplay`，表示接受了启动前的源选择约定，尚不表示捕获成功或显示器已扫描输出。客户端只在返回值与请求一致时取消启动后的切屏热键；未返回该字段的旧服务端继续走兼容路径，返回不同源则失败。新参数只在既有配对鉴权通过的启动接口使用，不增加代理权限或放宽证书校验。

捕获是 Sunshine 共享的资源。显式选屏不能在其他串流进行中悄悄加入已有捕获；这种请求返回 409。HTTP 启动、恢复与取消由同一互斥锁串行化，未完成的 RTSP 启动槽也阻止第二次启动。先读取 pending，再读取 active，避免 ANNOUNCE 插入会话、PLAY 清除 pending 期间误判空闲。没有新参数的旧客户端仍可按原方式加入已有串流。

Windows 自动重连保留上次选择的稳定屏幕 ID，重新调用主机准备接口，不再复用旧 captureIndex 或 GDI 源名。显式目标的准备失败终止本次连接；自动模式首次连接仍允许没有主机辅助接口的旧 Sunshine。

忙碌或非法目标的请求在写入共享 host_audio 之前拒绝，避免被拒绝的第二次启动改变已有请求的声音配置。

统计新增 initial_display_launch，区分主机是否接受启动前选屏。分析器记录 initial_display_before_capture_accepted：缺少事件为未知，旧协议为 false，新协议匹配为 true；后续重试的 ACK 不得改变更早首帧的归属。所有首帧终点仍是 Windows CPU 渲染提交返回或 Android 回调到达，不能解释为物理屏幕扫描完成。

验证范围：独立 C++ 模型以 UCRT64 GCC 及 -Wall -Wextra -Werror 通过，覆盖 GDI 边界、重复/丢失/重排源及忙碌准入；MSVC / Qt 测试覆盖编码、ACK 和重连身份；39 项 JVM 测试覆盖 ACK 后不发切屏键及非法源；11 项统计回归通过。Windows 原生程序和 Android APK / 测试 APK 均构建成功。还需真实新版服务端的物理/虚拟屏、分辨率变化、手动切屏与中继回归。OPPO 实机继续暂停。

新 Windows 原生程序 B7BD6A7C214E8C81F610BA91B6CD493FDCA0DAAC14F7884A5FE4DE6B1CBE34C7 已连接现有 f517c155 服务端，2520×1680 虚拟屏分别经 LAN / HTTPS 中继收到视频。两段均记录 requested=true、beforeCapture=false，证明旧服务端回退仍工作；首个通用 render_return 为 3034.638 / 3641.328 ms，不能作为新版启动前选屏的优化数据。两段完整结束，原始统计分析后删除，配置、自动同步和显示布局恢复。证据 D:\RHRemoteTools\initial-display-legacy-compat-short-20261023.json。

## 部署与目标屏验证

最终引擎和主机源码为 8f00c0b36b359b93b04b23c2463451f28927042d。CI 39956076616 成功：50 项引擎回归（含 3 项初始选屏、6 项软件能力复用、1 项设备复用及 40 项配对/授权回归）、21 项 Rust 主机回归；没有用早期原型 CI 代替最终构建。147 文件的包已在 winserver、claw 安装，分别保留 6、4 个配对；四份配置/授权状态的哈希在部署前后及串流测试后均不变，独立 HTTPS 中继恢复连接。本机升级仍等待用户醒来后的 UAC。

Windows 普通模式连接 winserver，三次均收到匹配 ACK。主机记录的第一次捕获就是 \\.\DISPLAY25，捕获刷新、实际串流编码器创建各一次；能力探测中的临时编码器单独归类，不计入该数量。

| 场景 | 目标屏首个 render_return | 显示准备 | 捕获枚举/验证 | 编码器创建 |
|---|---:|---:|---:|---:|
| 虚拟屏 2520×1680 → 1920×1080 | 3658.841 ms | 2110.692 ms | 969.611 ms | 4.313 ms |
| 虚拟屏保持 1920×1080 | 1684.280 ms | 741.928 ms | 423.180 ms | 4.068 ms |
| 虚拟屏恢复 2520×1680 | 4571.281 ms | 3071.104 ms | 929.457 ms | 4.798 ms |

恢复 2520×1680 的显示准备中，D3D 创建 1953.213 ms、释放 808.217 ms，实际输出验证仅 1.211 ms；模式应用 230.235 ms。软件能力检查为 12.181 ms。重复启动默认屏的流程已消除，剩余创建/释放与捕获枚举开销仍很明显，不能宣称启动耗时已不可优化，也不能把 1.684 秒当作稳定上限。

另一次自动选择物理屏的首帧为 2720.232 ms，首次捕获 \\.\DISPLAY1 / 1920×1080。经同一控制 API 切到虚拟屏再回物理屏，主机实际捕获尺寸依次为 1920×1080 → 2520×1680 → 1920×1080，原生进程 PID 不变，持续连接。编码输出此轮配置为 2520×1680，不将其与物理源尺寸混为一谈。

两台主机的 HTTPS 中继短测均记录 beforeCapture=true 并完成 trace_end：winserver 3221.202 ms / 340 次 render_return，claw 2558.741 ms / 123 次。它们是短窗静态桌面连接回归，不能据此宣称动态 30 FPS。所有测试静音，客户端参数、自动同步及 winserver 的物理布局/自有虚拟屏尺寸恢复；测试后两台服务与引擎进程仍存活，没有媒体线程遗留。分析后的原始统计已删除，当前对照需要的报告保留。

部署预检曾因最近一条 ENet 日志仍是 CLIENT CONNECTED 而拒绝安装。源代码中该日志只对应 ENet 连接事件，Ping Timeout 会停止会话但未必产生断开日志。只读核对所属服务的准确 Sunshine 子进程，确认 rtsp::handler 可读且没有 session::video/audio、video::、audio:: 等媒体线程后才继续；子进程 PID、快照新鲜度或连接事件变化都会中止安装，没有跳过正在运行的媒体会话。

证据：D:\RHRemoteTools\initial-display-short-20261023.json、initial-display-switch-short-20261023.json、windows-initial-display-both-tls-relay-validation-20261023.json；部署及配对状态见 winserver-initial-display-deployment-20261023.json、claw-initial-display-deployment-20261023.json 及各自 initial-display-provenance-after-tests 文件。服务端引擎 SHA256 3D5E7D3CE802115D219CF41EBDDA0A7D636C317A8A2EE54904AF680A7D20FC5F，主机 SHA256 EECB4F1EEC96027A35F4DE83EFDC2E8840CBC150BACC57FC14ACC7B13C8655EB。
