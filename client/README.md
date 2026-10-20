# RHRemote 客户端实验

基于 Moonlight v6.1.0，提交 `f786e94c7b2f943e24e65d7d74deb539b827fc84`。
完整本地源码在 `D:\RHRemoteTools\moonlight-qt-v6.1-source`，分支为 `codex/rhremote-lan-pacing`。
上游依赖固定在该提交的子模块版本，包含嵌套的 ENet；编译使用 Qt 6.7.2 和 MSVC。
完整修改保存在 [补丁](../patches/moonlight-6.1.0-rhremote.patch)，应用前需检出上述版本并递归初始化子模块。
客户端源码及补丁沿用上游的 GPL v3 许可，许可文本见本目录 `LICENSE`。原版客户端保持在 `D:\RHRemoteTools\Moonlight-6.1.0`，实验客户端在 `D:\RHRemoteTools\Moonlight-RHRemote-6.1.0`。

实验开关：

- `RHREMOTE_STABLE_PACING=1`：启用整数刷新比例的固定呈现节奏。例如 60 Hz 显示 30 FPS 时，每两次刷新出一帧。
- `RHREMOTE_WAKE_RECOVERY=1`：第十版以实际唤醒间隔判断迟到（超过 1.5 个刷新周期）；若已有帧，立即呈现一帧，再恢复正常间距。保留启动余量要求，不连续补播、不重复帧，也不隐藏真正的队列下溢。对应 `-RecoverMissedVsync`，要求固定节奏开启。
- `RHREMOTE_PACING_RESERVE=1` 或 `2`：启动时保留相应到帧余量，增加一至两帧排队延迟。真实队列下溢会留下可测的停顿，不用重复帧掩盖。第四版在下一帧到达后立即恢复，不重新攒启动余量。
- `RHREMOTE_PRESENT_VSYNC=1`：在 VSync 开启时，用 D3D11 SyncInterval 1 提交显示。当前短测没有减少显示缺失，增加了排队延迟，保持实验性质。
- `RHREMOTE_PRESENT_INTERVAL=2`：第八版在同步提交开启时，按两个刷新周期保持一帧；运行时要求显示刷新率恰为串流 FPS 的两倍。可选 1–4，默认 1；不匹配的请求拒绝激活。对应测试参数为 `-PresentInterval 2`，仍属实验。
- `RHREMOTE_STOP_EVENT=Local\RHRemote.Stop.<run_id>`：可选本机测试停止接口。测试脚本在导出完成后发送信号，客户端执行正常断开、退出应用和清理；不使用外部网络端口。
- `RHREMOTE_TRACE_STALLS=1`：第五版的稀疏异常时间戳，记录收帧、解码、垂直同步和渲染阶段，每个模块最多 256 条。`tools/analyze-stalls.py <run_id>` 与同轮 PresentMon 的 QPC 时钟对齐，保留关联分析，不改变验收结果。
  第九版进一步记录应用接收路径的首包间隔、重组完成间隔、重组耗时与解码队列年龄，以及 VSync 原生等待和 pacing 锁等待。首包时间不是网卡抓包时间，不能单独区分网络晚到与接收线程晚处理。
- `RHREMOTE_CLIENT_DISPLAY_ORIGIN=-3840,0`：第六版按物理原点精确匹配 SDL 显示器；未匹配或歧义时失败，不默默落到主屏。坐标必须根据当前布局核对。
- `RHREMOTE_BENCHMARK_NO_INPUT=1`：第六版被动呈现测试不捕获或转发键鼠、触摸和手柄，正常远程操作默认不变。此模式的成绩不代表输入延迟通过。
- `RHREMOTE_BENCHMARK_TOPMOST=1`：第十一版将本轮串流窗口置顶，配合输入隔离避免本地窗口遮挡；对应 `-BenchmarkTopmost`，只用于测试。
- `RHREMOTE_DECODED_SNAPSHOT=<absolute PNG path>`：第十一版在第 90 个解码帧保存一次画面，默认关闭。对应 `-DecodedSnapshot`，文件在本轮 `raw/source-frame.png`；用于预热核对源内容和全屏尺寸，不代表画质验收。
- `RHREMOTE_D3D11_COMPOSED_BLIT=1`：第七版临时使用单缓冲 SDR BitBlt 路径，要求 VSync；用于对照独立翻转与 Windows 合成的显示停顿。默认仍为原来的 flip model。BitBlt 增加复制工作，不预先作为 4K60 推荐配置。
- `RHREMOTE_D3D11_GPU_PRIORITY=1..7`：第十二版通过 `IDXGIDevice::SetGPUThreadPriority` 设置当前客户端 D3D11 设备的相对优先级，并读取确认；不使用绝对或进程实时调度等级。默认不设置，能力探测不设置，设备释放后结束。对应 `-GpuRelativePriority`（默认 0 表示不覆盖），目前以 3 做短测，未证明改善。该功能与服务器进程调度类别只读查询是不同接口和范围。

默认关闭固定节奏实验。第六版 winserver 独立源屏、副屏无边框轮显示 29.975 FPS，p99 33.520 ms，约 178 秒里有四次 66.7 ms 显示间隔，未满足稳定性门槛。提交节奏最大 37.496 ms；这四次显示异常附近没有收帧、解码或渲染异常日志。合成路径第一轮无 >50 ms，后两轮各一次 56.247 / 50.011 ms，尚未形成可靠候选。原 flip / SyncInterval 2 与 DXVA2 后端对照也失败。第十版正在实测迟到唤醒恢复；该策略不能消除原生驱动等待或上游帧缺失。p99 与约 65–73 ms 排队延迟仍有取舍，不能把单轮成绩当作日常推荐，也不能用提交帧率替代屏幕显示帧率。

`tools/build-cadence-test.cmd` 编译执行 `test-cadence.cpp`，验证刷新匹配、跨刷新边界的到帧抖动、有界余量、真实下溢和迟到恢复不补播。当前第十二版完整构建日志在 `D:\RHRemoteTools\moonlight-build-v12.log`，元数据在 `results/environment/moonlight-rhremote-build-v12.json`；旧补丁与外部二进制归档至第十一版。第十二版不改变 pacing 算法。

示例短测：

```powershell
.\tools\optimize-run.ps1 -RunId '<new_run_id>' -HostName winserver -Resolution 1920x1080 -Preset superfast -StablePacing -StableReserveFrames 2 -SynchronizedPresent -ComposedBlit -TraceStalls -BenchmarkNoInput -BenchmarkTopmost -DecodedSnapshot -ClientDisplayOrigin '-3840,0' -QtWindowGeometry '1280x600-3800+100' -DisplayMode borderless -IsolatedSource -KioskSource -MoonlightDir 'D:\RHRemoteTools\Moonlight-RHRemote-6.1.0' -QuitMethod event -WarmupSec 60 -MeasureSec 180
```

当前主要推进 winserver；claw 保持原 4 vCPU，用户接受其较慢表现，不再执行扩容方案。保留后台业务，winserver 先通过 1080p30 的短测与长测，再升至 60 FPS 和更高分辨率。`-PauseKline` 只用于用户单独授权的短暂业务对照，不属于普通测试流程。

`-DecoderBackend dxva2` 使用上游原有的 `D3D11VA_ENABLED=0` 开关对照 DXVA2 硬件后端，结束恢复原环境变量；不能与 D3D11 的同步提交或 BitBlt 实验同时请求。`tools/capture-stream-metadata.py` 使用已安装 Npcap 被动记录固定主机视频流的包时间和长度，最长 300 秒，无视频载荷；独立观察点仍不能区分源端、网络与接收驱动的全部因素。

`-MinThreads 0` 不覆盖主机原有最低编码线程设置；其它值用于单独对照。claw 的 `-MinThreads 1` 有效短测降低了整机 CPU 均值，但主机处理延迟增大、显示帧率下降，不作为推荐。`-TraceForeground` 部署只读采样器，记录前台进程名、窗口类及尺寸，不记录标题或注入输入；必须确认任务 Running，临时任务由 finally 收集并移除。扩容方案 `profiles/claw-8vcpu-trial-plan.json` 已标记取消且未执行。

每轮保存候选参数、原始采样、有效性、停止方式和恢复核对。只有日志确认实验生效、源画面有效、呈现与恢复通过时，才有资格继续重复及长时间测试。查看 [报告](../results/report.md) 获取当前证据。

`-IsolatedSource` 在启动串流前临时设置源显示尺寸，失败则拒绝启动。当前 Amyuni 驱动支持 1600×900 / 1920×1080，未列出 1280×720；不能把编码器缩小后的 720p 当作有效的同尺寸源测试。`-KioskSource` 只用 Edge 全屏命令行，不改浏览器或系统策略；测试页通过本轮随机路径向 `127.0.0.1:39170` 提交统计，服务只监听回环地址，并有最长运行时间和 finally 清理。采集器读取 `C:\RHRemoteTools\runs\workload-http` 的原始导出。旧下载流程仍保留供普通页面使用。

第十二版相对优先级 3 的历史 180 秒测量按原 PresentMon 阈值失败，不能推荐为日常默认。用户已手动启动管理员 `tools/capture-display-trace.ps1` 并完成默认优先级的 120+180 秒对照，240 秒具名 CPU/GPU/DWM 录制及清理均通过。五个 PresentMon NA 均有底层硬件平面 / VSync 标识对应；完整窗口的 5340 次关联更新最大间隔 34.207 ms，无 >50 ms。token 销毁事件早于显示确认 8–31 微秒，与监测完成时序误判相符。保留所有原始数据和阈值 FAIL，正式显示验收为 INCONCLUSIVE，不能凭此称为长期跑稳或据此修改历史分数。

时间关联现使用 `CPUStartQPCTimeInMs + MsCPUBusy` 作为提交时间，再加 `MsUntilDisplayed` 得显示时间。原生工具只离线读取六类相关显示事件并保存 TDH 元数据与数字载荷，不要求新的录制或管理员权限；与 xperf 的 1226 条记录核对通过。编译后可对已有 ETL 使用：

```powershell
.\tools\build-display-etl-reader.cmd
.\tools\read-display-trace.ps1 -RunId '<existing_run_with_etl>'
python .\tools\analyze-overlay-trace.py '<existing_run_with_etl>' --event-file display-events.jsonl
.\tools\analyze-run.ps1 -RunId '<existing_run_with_etl>'
```

已有 `display-events.jsonl` 时拒绝覆盖；本轮结果已保存。原生输出不是物理面板或内容帧测量，后续仍需可靠的重复和长测，以及输入延迟、画质和音频验证。
