# winserver Quick Sync 初始化对照

2026-10-24，运行引擎仍为 `5d5149ce`，SHA-256 为
`58ba527144ee849f96ba811ab1d4de02dd053394eab5900f72bc4f025d76c074`。
目标是验证硬件捕获／编码是否能避开软件路径已测到的 572 ms Map 等待。
本轮未进入串流或影片测量，不能据此评价硬件编码的持续帧率。

## 实际观测

只将既有 `encoder = software` 的值临时改为 `quicksync`，没有改动其他配置项。
该名称来自当前维护的 Sunshine 源码；`qsv` 不是它的配置选择值。
Arc B580、显卡驱动、RHVideo 等业务保持运行，未操作 OPPO。

Quick Sync 服务端探测从 12:10:28.151 持续到 12:14:28.790。
直接单调时钟跨度为 **240634.517 ms**。日志最终确认 H.264、HEVC 和 AV1 均可用；
这次不是编码器被判定不支持，而是初始化非常慢。

| 实际编码器创建 | encoder_create 墙钟跨度 |
|---|---:|
| h264_qsv | 39455.192 ms |
| hevc_qsv，第一次 | 39725.385 ms |
| av1_qsv，第一次 | 41552.828 ms |
| hevc_qsv，后续三次 | 34603.570 / 20456.204 / 11148.110 ms |
| av1_qsv，后续一次 | 20434.391 ms |

这七次创建共 207375.680 ms，不能把它们重复叠加到完整探测时长上。
同一保留日志中，先前软件编码启动探测为 2437.081 ms，两次 libx264 创建约 5 ms。
这不是严格交错重复的性能基线，也不是客户端首次连接时间。

控制接口在初始化期间尚未监听，因此试验控制器的 25 秒就绪等待失败。
原配置字节先自动写回，进程仍在用已加载的 Quick Sync 配置继续探测。
恢复保护最初要求存在 rtsp::handler，不能把尚未开放端口的初始化过程当作已证实空闲；
因此保留恢复任务并等待检查，没有强制结束任意进程。
随后核实精确服务／引擎 PID、创建时间、配置及空闲状态，正常重启回原配置。
12:15:24.774 恢复完成，临时任务已移除。

winserver 服务 PID 为 43600，四个配置和两项二进制哈希均与试验前相同，
UUID 及 6 个配对保留。claw 未切换编码器；客户端没有启动媒体或修改连接配置。

## 对照工具与一次失败

`tools/host-encoder-window.ps1` 保存原始字节及安全描述符，原子替换前检查哈希，
并在停止服务前注册有界恢复任务。只允许既有、唯一的受支持 encoder 行；
保留 BOM、换行、空白及注释。恢复遇到其他配置改动时拒绝覆盖。
活动媒体使服务重启推迟；原始配置字节仍优先恢复。
初始化中的例外必须匹配本轮新服务／子进程及创建时间、有效线程名称查询，且没有准入端口。
缺失 handler 或单一历史状态文件本身不能授权停止服务。

首次工具试验在 ACL 校验处中止，没有执行影片测试：Windows File.Replace 可以采用临时文件的
主组。该次原始字节已恢复，但旧工具未持久化原始完整安全描述符，不能宣称该次主组元数据
已精确复原。后续工具先为临时文件复制完整描述符，并在 lease 中保存原始描述符；
第二次试验已核对字节及完整描述符均恢复。
本机 PowerShell 7、Windows PowerShell 5.1 的纯文件回归通过；
winserver 上另用 SYSTEM 主组的独立临时文件验证实际替换及恢复，未用生产配置作该测试夹具。
实际验证了恢复任务预先注册与手动正常恢复，未将未触发的定时任务宣称为执行验收。

## 下一步代码与验收

现有 encoder_create 只包住完整 make_avcodec_encode_session，尚不能确定 39 秒在
FFmpeg 打开编码器、QSV 上下文派生、帧池或附加帧中的哪一步。
新增实际调用跨度：encoder_hardware_input、encoder_derive_prepare、
encoder_hardware_derive、encoder_frames_init、encoder_codec_options、
encoder_codec_open、encoder_software_device_init、encoder_frame_attach、
encoder_apply_colorspace。不改动原操作参数、错误码、回退或资源归属。
新 trace_encoder_call 保留引用、移动类型、void、错误码和异常，并在失败时正常闭合阶段。
新增真实日志／调用行为回归和统计导入回归，完整 MSYS2/UCRT64 构建及实机嵌套跨度仍待验证。

首轮新 CI 40062167046 在配置工具的权限描述符回归失败，尚未编译新 C++。
本机及 winserver 的成功不能代替该环境；File.Replace 后再次应用保存的描述符，并扩展
继承／受保护 DACL 夹具后已提交 80a3105。PowerShell 7、5.1 和 winserver SYSTEM 主组夹具
均通过，CI 40062482426 继续完整构建。3 项新增 C++ 实际日志测试仍以该 CI 结果为准。
18 项客户端统计导入／分析、24 项主机分析、11 项窗口分析通过。

受控采集工具随后增加独立的 StartupWaitSeconds（默认 25 秒，最高 300 秒），
并要求总恢复窗口至少比该观测预算多 60 秒。原软件服务恢复仍使用 25 秒就绪预算。
8 项有效／无效预算检查已加入文件回归，两种 PowerShell 均通过。
这只为诊断保留完整慢调用的结束记录，没有改变客户端或正式服务的超时行为，
也不将延长观测等待当作优化。

后续应先用这些跨度定位初始化内部调用，再比较实际媒体的启动时间和动态捕获。
不能用固定超时、跳过所有能力验证或关闭其他业务，替代对这个等待的优化。

私有原始证据仍供后续对照使用，未提交含环境日志的文件：

- `D:\RHRemoteTools\qsv-capture02-initialization-20261024.log`
- `D:\RHRemoteTools\qsv-initialization-analysis-20261024.json`
- `D:\RHRemoteTools\qsv-capture02-restored-20261024.json`
- `D:\RHRemoteTools\winserver-after-qsv-window-20261024.json`
- `D:\RHRemoteTools\qsv-window-remote-security-test-final-20261024.json`
