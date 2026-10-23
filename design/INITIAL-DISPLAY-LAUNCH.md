# 启动前选择捕获屏

2026-10-23，候选实现；双端编译及本地逻辑测试通过，服务端完整构建、部署与实测结果另行记录。

旧流程在 `/v1/display/prepare` 得到目标屏后，仍按 Sunshine 的全局默认屏建立捕获。Windows / Android 到串流启动之后再发 Ctrl+Alt+Shift+Fn 切屏。winserver 的日志在同一次连接中出现 1920×1080、2520×1680 两次捕获初始化，两次编码器创建；旧统计的首个 render_return 可能属于默认屏。

新版沿用已经配对的 HTTPS `/launch`、`/resume`，增加 `rhrdisplay` 参数，内容为刚准备完成的 Windows GDI 源名。服务端验证格式，存入该 launch session，经 RTSP 配置传到捕获线程；第一次使用全新的后端可捕获列表精确匹配，然后才创建捕获设备。目标不存在或出现歧义时失败，不以主屏或旧列表代替。全局 output_name 不修改；串流中的后续切屏热键仍保留原行为。

服务端返回 `RHInitialDisplay`，表示接受了启动前的源选择约定，尚不表示捕获成功或显示器已扫描输出。客户端只在返回值与请求一致时取消启动后的切屏热键；未返回该字段的旧服务端继续走兼容路径，返回不同源则失败。新参数只在既有配对鉴权通过的启动接口使用，不增加代理权限或放宽证书校验。

捕获是 Sunshine 共享的资源。显式选屏不能在其他串流进行中悄悄加入已有捕获；这种请求返回 409。HTTP 启动、恢复与取消由同一互斥锁串行化，未完成的 RTSP 启动槽也阻止第二次启动。先读取 pending，再读取 active，避免 ANNOUNCE 插入会话、PLAY 清除 pending 期间误判空闲。没有新参数的旧客户端仍可按原方式加入已有串流。

Windows 自动重连保留上次选择的稳定屏幕 ID，重新调用主机准备接口，不再复用旧 captureIndex 或 GDI 源名。显式目标的准备失败终止本次连接；自动模式首次连接仍允许没有主机辅助接口的旧 Sunshine。

统计新增 initial_display_launch，区分主机是否接受启动前选屏。分析器记录 initial_display_before_capture_accepted：缺少事件为未知，旧协议为 false，新协议匹配为 true；后续重试的 ACK 不得改变更早首帧的归属。所有首帧终点仍是 Windows CPU 渲染提交返回或 Android 回调到达，不能解释为物理屏幕扫描完成。

验证范围：独立 C++ 模型以 UCRT64 GCC 及 -Wall -Wextra -Werror 通过，覆盖 GDI 边界、重复/丢失/重排源及忙碌准入；MSVC / Qt 测试覆盖编码、ACK 和重连身份；39 项 JVM 测试覆盖 ACK 后不发切屏键及非法源；11 项统计回归通过。Windows 原生程序和 Android APK / 测试 APK 均构建成功。还需真实新版服务端的物理/虚拟屏、分辨率变化、手动切屏与中继回归。OPPO 实机继续暂停。
