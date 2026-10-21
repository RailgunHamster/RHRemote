# 测试统计

Windows 与 Android 的测试模式每次连接自动创建 `rhremote.statistics/v1` 目录。设置 → 关于和 `statistics.configure {enabled: true|false}` 共用操作；`statistics.state` 返回模式和最近记录目录。模式仅属于当前安装，不参与 WebDAV 同步。Windows `start-rust-client.ps1 -TestMode` / `-ApiOnlyInputTest` 启用；两端功能测试也自动启用。

每次保存实际连接配置、单调时钟的 UTC 锚点、连接阶段和按帧号关联的 JSONL：首个视频包、组帧完成、解码提交/返回、解码输出、渲染排队结束、渲染提交/返回、明确丢帧及原因。Windows 组帧时间由上游毫秒时钟换算，精度不是微秒。Android 额外通过只读 JNI 读取上游微秒时钟，并记录 MediaCodec 回调到达与框架报告的呈现时间。

Windows 的 render_return 只表示 CPU 渲染/Present 提交返回，不代表屏幕完成扫描。Android 框架报告的时间可早于提交（例如按 Choreographer 的请求时间呈现），回调到达时间另记，不能把二者混用为正的显示延迟。丢失帧号无法单独定位丢失于网络还是主机。协议主机处理耗时是总值；没有服务器逐帧捕获/编码细分埋点时，不编造该细分。

高频线程仅入有界队列，后台线程落盘；队列最多 65,536 条、单文件最多 512 MiB，结束写 trace_end 和丢记录计数。报告明确标出记录丢失或缺少结束标记。只记录时间、帧号、大小、配置与状态，不存像素、音频、键盘文本、URL 参数、配对密钥或令牌。

`tools/collect-statistics.ps1 -Platform windows|android` 导出并分析最新测试。OPPO 用 app-private run-as 导出，不读取应用令牌到结果。主机只读日志在结束后采集，用 SSH 往返时间估计时钟偏移和误差范围；主机自身阶段耗时不依赖跨机时钟对齐。主机日志中的 Creating encoder → Streaming bitrate 区间仅是这两个标记之间的时间，后者位于 avcodec_open2 之前，不能声称是完整编码器打开耗时。

输出 summary.json、frames.csv、Chrome Trace Event 格式 timeline.json 与独立 report.html。HTML 时间线支持缩放、平移、按帧号定位与事件详情，并给出平均/P95/P99/最大耗时和逐帧间隔。原始 JSONL 分析成功后删除；下次测试删除已分析的旧目录，只保留当前仍用于排查的结果。`--keep-raw` 为明确仍需要的对照创建 keep 标记，使用完必须清理。未结束/损坏记录先保留以便诊断，不悄悄当作完整统计。

时间轴和默认视区以当前 connect_action 为 0，明确标记首帧终点。分段表显示累计起止时间及本段耗时，合计与首帧指标一致；程序启动与连接前空闲不计入本次连接。原始 HTTP/初始化调用可能并行或嵌套，置于可展开明细，不能直接相加。已分析的报告可用 `analyze-statistics.py <目录> --refresh-report` 从报告内的事件刷新展示，不重新采集或恢复已删除的原始记录。

2026-10-21 实测：Windows 1920×1080/30/HEVC，去掉桌面鼠标模式的 3.5 秒提示等待和 RHRemote 连接前 1.5 秒等待后，连接至首帧渲染提交返回从 16.397 秒降到 9.817 秒；这是后续连接对照，不是冷启动或稳定帧率验收。之后仍有约 6.4 秒等待主机首包。Sunshine 重启后首连曾用 36.949 秒重测编码能力，需继续定位主机初始化；不能把该耗时归咎于 UI。

OPPO 实测记录 206 帧、零统计丢记录，连接至系统呈现回调到达 6.552 秒。独立的 winserver 控制台 FFmpeg 合成视频测试中，H.264 / HEVC 均成功编码 60 帧，但开始出帧前等待约 7.2 / 8.2 秒；该路径不使用 RHRemote、Moonlight 或桌面捕获，支持将剩余启动等待继续定位到主机 QSV 初始化。另两次设备/帧上下文实验失败，不把它们的时间当作有效编码性能结论。
# Sunshine 服务端诊断构建

`tools/prepare-sunshine.py` 固定 Sunshine `cb72dffa3233c5815cd5ba88f09f049dd679ba75`，默认生成带计时的行为对照版，`--optimized` 生成优化版。诊断版在 Sunshine 日志输出 `RHREMOTE_PHASE`，使用服务端单调时钟测量编码器探测、格式验证、采集重建、DDA 设备与 duplication 创建、编码器创建和首帧编码；不记录像素或声音。

`collect-statistics.ps1 -Platform windows -RunId <已结束的统计编号>` 可分析自动恢复之前的特定会话。服务端原生计时进入同一 timeline，服务端阶段耗时由单调时钟直接取得，跨机器对齐仍附带 SSH 估计误差。模拟中断结果和实际 Windows 解锁验收分别记录。
