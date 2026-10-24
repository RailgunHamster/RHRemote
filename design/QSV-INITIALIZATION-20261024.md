# winserver Quick Sync 初始化对照

2026-10-24，第一轮运行引擎为 `5d5149ce`，SHA-256 为
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

## 嵌套调用实测

`80a3105` 的完整 CI 40062482426 成功，95 项引擎回归零失败，包含三项
真实日志／引用／移动结果／异常闭合测试。147 个包内文件校验后部署至 winserver 和 claw，
配置及 6 / 4 个配对保留。两台引擎 SHA-256 为
`5988843cf283c5a000bb6caf88567fa55f4065f300ae5e64cf8d72ec138b6ff0`。

winserver 第二轮完整探测为 **222705.417 ms**，七次 encoder_create 共 **191541.905 ms**。
其中实际 `av_hwdevice_ctx_create_derived` 调用合计 **167946.963 ms**，占完整探测 **75.4%**。
这里是 D3D11 到 QSV 的派生；帧池和附加帧不是这次的主要等待。

| 编码器 | 创建总计 ms | 上下文派生 ms | avcodec_open2 ms |
|---|---:|---:|---:|
| h264_qsv | 23235.814 | 22593.724 | 609.879 |
| hevc_qsv，第一次 | 41470.878 | 35202.291 | 6241.892 |
| av1_qsv，第一次 | 22023.290 | 19843.355 | 2158.203 |
| hevc_qsv，第二次 | 24640.341 | 18659.358 | 5972.006 |
| hevc_qsv，第三次 | 23183.913 | 22161.025 | 998.100 |
| hevc_qsv，第四次 | 19140.049 | 17724.747 | 1407.162 |
| av1_qsv，第二次 | 37847.620 | 31762.463 | 6055.455 |

硬件输入准备约 6–7 ms，帧池初始化约 0.2–23 ms，附加帧约 1–6 ms。
识别到的阶段均闭合，但普通服务日志没有独立的丢事件计数，不能宣称完整日志系统无损。
新增测量本身没有优化性能，不能把 39 秒到 23 秒的单次差异归因于改进。
该对照未启动媒体，也不能解释每一次历史首帧等待都来自同一个调用。

原始软件配置字节及保存的完整安全描述符恢复，四个持久文件、二进制和配对均保持，
恢复任务已移除。结果见私有 `qsv-nested01-analysis-20261024.json`。

## 独立 SDK 对照与显式 MF 候选

winserver 既有 FFmpeg 8.1 的独立进程仅生成黑色视频，不捕获桌面、不使用音频，未改服务配置。
同一 Intel D3D11 → QSV 派生、CPU NV12 输入的一帧编码分别约 14.746 / 18.037 秒成功；
带时间的日志中，两次 MFX 标记之间约 12.642 秒。dispatcher 日志明确显示
完整候选枚举及 low latency mode disabled，但无内部调用时间，不能把整段归给某一个 SDK 函数。
另一个 GPU hwupload 试验因纹理池参数错误结束，没有成功编码，不能作为性能结果。

独立 h264_mf、强制硬件的一帧编码约 7.269 秒成功，实际激活 Intel H.264 Encoder MFT；
这是不同 FFmpeg 构建的 CPU 输入对照，不能代替本产品的 GPU 输入或持续串流验收。
Sunshine 的实际 mediafoundation 受控试验在厂商预检直接拒绝 Intel，未创建 h264_mf，
随后回退到慢 QSV；120 秒就绪等待结束后已恢复软件配置，未开始影片或客户端串流。
最新恢复服务 PID 41132，配对 6 个、配置哈希不变、任务移除。

本次源码只允许显式选择 mediafoundation 的 Intel H.264 SDR 4:2:0 候选继续实际编码、
首 IDR 和码流校验；自动选择和其他格式规则保持。新增三项策略回归，完整 CI 和真实
GPU 输入／媒体尚待验证。该 MF 路径的固定 GOP、丢包后的恢复同样需要实际测试。
它是候选替代路径，不等于 QSV 的派生等待已经解决。

## 产品 MF 对照结论

门控提交 `604a5ec9` 的完整 CI 40067551621 最终成功，98 项实际引擎回归通过，
含三项新的 Intel MF 策略测试。147 文件校验后已部署 winserver，6 个配对及配置保留，
引擎 SHA-256 为 `fc57d72709e19fae85f7067df1974b5aedc047a10512b12a0346ae81a95d1eb2`。
mf02 服务启动探测约 5.207 秒，实际 H.264 编码及首 IDR 校验成功；初次产品串流被
客户端总计 15 秒首帧期限提前终止，原因和修复见 `FIRST-FRAME-TIMEOUT-20261024.md`。

修复后的 mf03 真正创建 h264_mf，并完成约 151.196 秒的 1080p30 / 5 Mbps / LAN
静音动态影片对照。源时钟速度 0.999707、测量段播放器丢帧约 0.587%，通过既有源筛选。
操作到首帧提交返回 19.680 秒，主机仍有约 10.045 秒 HTTP launch 等待；这条路径未解决启动耗时。
所选窗口主机记录完整、客户端完整、零统计丢失；整段主机文件末尾八次提交未记录输出，
不能宣称整份记录完整，原始数据保持。

| 所选动态窗口 | 实测 |
|---|---:|
| 客户端 render_return | 26.890 / 秒；P99 间隔 96.922 ms；最大 947.518 ms |
| 主机 send_return | 28.413 / 秒；P99 间隔 96.939 ms；最大 944.572 ms |
| 主机提交到编码输出 | 平均 333.773 ms；P99 502.278 ms；最大 1571.194 ms |
| 主机 encode 调用 | 平均 14.669 ms；P99 88.330 ms；最大 906.895 ms |
| 客户端组帧 / 解码 | 平均 0.112 / 0.279 ms，均为新的直接计时 |

帧率低且明显内部缓冲，MF 当前不作为默认替代路径。不能用通过功能校验、独立一帧测试或
避免 RAM Map 推断动态性能更好。八个未匹配输入都是最后的 4834–4841 帧；
后续须在真正关闭时补已提交但未交付输出的终止元数据，不能给旧日志事后补事件冒充实测。
QSV 上下文派生及软件捕获断档仍需继续优化。

试验结束后恢复原始软件配置、持久文件和 6 个配对，恢复任务移除；最新服务 PID 23944。
客户端连接配置、同步设置、显示布局、动态源均恢复，OPPO 未操作。
证据为私有 `intel-mf03-frameclock-window-20261024.json`、
`host-frame-intel-mf03-frameclock-summary-20261024.json` 及 `winserver-after-mf-natural03-20261024.json`。

固定 FFmpeg 源码的 mfenc.c 用 VT_UI4 设置 CODECAPI_AVLowLatencyMode，
并未检查该 SetValue 的结果。
[微软的属性说明](https://learn.microsoft.com/en-us/windows/win32/medfound/codecapi-avlowlatencymode)
规定编码器使用 VT_BOOL，H.264 解码器才是所列的 VT_UI4 特例。
随后隔离探测在 winserver 的 B580 上创建自有 D3D11 设备和两个独立 Intel H.264 MFT。
每种类型使用新 transform，异步解锁、设备管理器及输出类型设置都成功。两个实例均为：

| 设置方式 | SetValue | 设置后 GetValue | 设置输出类型后 GetValue |
|---|---|---|---|
| VT_UI4(1) | S_OK | VT_BOOL false | VT_BOOL false |
| VT_BOOL(VARIANT_TRUE) | S_OK | VT_BOOL true | VT_BOOL true |

这证明实际编码器忽略了旧参数类型；尚未证明它就是约 334 ms 缓冲的全部根因。
探测约 9.285 秒完成，输入样本为零，不捕获桌面或声音、不修改服务。
证据 `D:\RHRemoteTools\mf-latency-property-probe-20261024.json`，探测程序 SHA-256
`3fa804f082f7d35472a21361e7ef557f9031040eaa333da1583b74ee500d6075`。

`server/ffmpeg/` 现在维护对应源码修正：锁定完整源码归档、源文件、原配置、静态库和原 MF
成员哈希，用原 ABI 配置重新编译 mfenc.c；只在构建目录的库副本替换编译器产物。
库的其他 213 个成员须按原顺序逐项保持字节相同，包括合法的重复 basename。
九项输入／归档保护回归已通过，完整 MSYS2/UCRT64 构建、真实函数 CodecAPI 参数回归、
新包部署及实际动态媒体前后对照仍待执行。旧 mf03 原始证据保持不变。

首轮完整构建 40074312440 及后续 40074633218 均在新回归程序链接时失败：
FFmpeg 的 random_seed 依赖 BCrypt，独立测试目标漏链接该库。源码编译已成功，
这些失败构建没有被当作通过，也没有部署。5d79c70 补齐链接库并将参数回归提前执行。

随后本机已在独立 `C:\msys64` 补齐 UCRT64 工具链，GCC 为 16.2.0；
基础归档及官方摘要均已校验，未改系统 PATH、注册表或服务。
独立编译暴露了 private internal.h 的搜索路径依赖：公共 common.h 从已打包目录进入，
其内部头可能错误命中 codec 组件。调整为优先使用同一固定源码的头文件，
仍使用原包的 config.h / avconfig.h。新源码与原始源码现在通过同一正式 CMake 测试目标比较：
修正版本成功，原版本按预期返回 3；已实际编译、链接并运行，不是源码字符串检查。
负例返回其他错误不会被接受，此对照同时纳入后续 CI。
本地证据 `D:\RHRemoteTools\mf-abi-regression-local-formal-targets03-20261024.log`。
5d79c70d 的完整 CI 40076569884 随后成功，已下载实际测试 XML：100 项引擎测试、
零失败／错误／禁用，包括 SubmittedRelease 和 SubmittedReleaseLifetime。
其 SHA-256 为 `fbe3badae4346e9d8082ec6ea4405aed98f87bcc27e3f65ea214ac496552b916`。
fbadb907 增加上述独立头文件顺序修正及 CI 原版反例，40077991343 的 Linux 独立回归及
ThreadSanitizer 已成功，完整 Windows 构建仍在运行。尚未验证或部署该提交的完整安装包，
也没有新的动态媒体性能结果；winserver 继续使用之前已验证的 604a5ec9 和原软件编码配置。

### MF03 启动等待的进一步分解

已取回其完整不可变主机初始化日志并校验 SHA-256
`0f8f7ff41db01b1440a389b4404931965d4d54f5988272a4176484eb5c59e65c`。
连接触发的 encoder_probe 为 10003.791 ms，包括实际验证、测试输入以及显示捕获重建。
随后实际串流的 encoder_codec_open 为 2103.582 ms，encoder_frames_init 为 286.902 ms；
first_encode 首次调用为 1233.812 ms。逐帧记录显示首个输入到首个编码输出为 1911.899 ms。
video_initial_ping 开始到首个 send_end 为 6492.020 ms。
这些均在同一主机单调时钟内测得；probe、create、open、first_encode 和首包跨度有父子／
包含关系，不能相加。客户端 protocol-ready 到 render_return 的 6444.333 ms 使用客户端时钟，
与主机区间是两个独立边界，不能把它们相减当作网络延迟。
证据 `D:\RHRemoteTools\mf03-startup-host-substages-20261024.json`。

另一个有 API 内计时的独立 SDK 探测在同一 B580 上约 7.926 秒完成，无输入样本：

| API | 实测调用时间 |
|---|---|
| MFStartup | 0.174 ms |
| D3D11CreateDevice | 562.999 ms |
| MFTEnumEx | 8.880 ms |
| IMFActivate::ActivateObject | 首次 1758.456 ms，后续 771.298–785.729 ms |
| ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER) | 362.684–489.544 ms |
| SetOutputType | 99.459–122.096 ms |
| ShutdownObject | 130.455–229.151 ms |

它再次确认整数设置后读回 false、布尔设置后读回 true，两个候选均一致。
这是独立实例的 API 成本，不能代替实际 Sunshine 内部跨度；后续实例共享进程／驱动预热，
也不能把两种类型之间的耗时差当作参数类型带来的初始化加速。至少该探测中枚举只需约 9 ms，
主要成本在实例激活和设备交接；下一步应沿该方向验证，不能仅凭总耗时归因于网络或发现。
源码／程序、结果及摘要位于私有工具目录的 `mf-property-call-timing02-20261024.*`，
程序 SHA-256 为 `e57e7db50da51594836e2e7e79db6accd5553a4cee55953d73f357f1f48a7f8a`。
探测正常退出，未改变服务、显示模式或业务配置，未捕获桌面或声音。

随后独立 GPU 输入对照确认：固定 16 帧 hwupload 池在纹理创建处报 80070057，
尚未进入编码器；改为与 Sunshine 相同的动态池方式后，D3D11 输入的一帧编码约
11.253 秒成功，实际仍为 Intel MFT。90 帧生成视频、固定 GOP 120、主动请求第 15 / 45 帧
为关键帧的对照约 15.925 秒完成，FFprobe 检出关键帧 0 / 15 / 45，码流共三个 IDR slice NAL。
这些总耗时包含初始化和退出，既不是持续 FPS，也不证明实际网络丢包恢复已通过。
全部独立进程和临时任务已结束，未更改服务配置，未捕获桌面或声音。

为避免继续引用最新版代替实际依赖，已核对本项目固定 build-deps
`a9a9277cdafe8a0ff9f197915fe43b383ed4f36b` 对应的 FFmpeg 子模块为
`38b88335f99e76ed89ff3c93f877fdefce736c13`。
该版本的 [hwcontext_qsv.c](https://github.com/FFmpeg/FFmpeg/blob/38b88335f99e76ed89ff3c93f877fdefce736c13/libavutil/hwcontext_qsv.c)
确实先 MFXEnumImplementations 再 MFXCreateSession；
[qsv.c](https://github.com/FFmpeg/FFmpeg/blob/38b88335f99e76ed89ff3c93f877fdefce736c13/libavcodec/qsv.c)
在编码器会话初始化中也采用枚举／创建流程。仅优化基设备构造可能留下编码器的再次枚举，
不能把移动等待位置当作消除了等待。固定源码确认调用结构，内部耗时仍须同构建实测。

## 测量实现与后续验收

早期 encoder_create 只包住完整 make_avcodec_encode_session，不能细分等待。
新增实际调用跨度：encoder_hardware_input、encoder_derive_prepare、
encoder_hardware_derive、encoder_frames_init、encoder_codec_options、
encoder_codec_open、encoder_software_device_init、encoder_frame_attach、
encoder_apply_colorspace。不改动原操作参数、错误码、回退或资源归属。
新 trace_encoder_call 保留引用、移动类型、void、错误码和异常，并在失败时正常闭合阶段。
新增真实日志／调用行为回归和统计导入回归，完整 MSYS2/UCRT64 构建及上述嵌套跨度已验证。

首轮新 CI 40062167046 在配置工具的权限描述符回归失败，尚未编译新 C++。
本机及 winserver 的成功不能代替该环境；File.Replace 后再次应用保存的描述符，并扩展
继承／受保护 DACL 夹具后已提交 80a3105。PowerShell 7、5.1 和 winserver SYSTEM 主组夹具
均通过，CI 40062482426 最终成功，三项新增 C++ 实际日志测试通过。
客户端统计导入／分析随后扩展为 20 项，24 项主机分析、11 项窗口分析通过。

受控采集工具随后增加独立的 StartupWaitSeconds（默认 25 秒，最高 300 秒），
并要求总恢复窗口至少比该观测预算多 60 秒。原软件服务恢复仍使用 25 秒就绪预算。
8 项有效／无效预算检查已加入文件回归，两种 PowerShell 均通过。
这只为诊断保留完整慢调用的结束记录，没有改变客户端或正式服务的超时行为，
也不将延长观测等待当作优化。

后续应继续细分 QSV dispatcher 的同构建调用，或在通过完整构建后验证 MF 候选的实际媒体。
避免仅将枚举成本从设备派生挪到编码器打开，也不能跳过必要的设备健康及能力检查。
不能用固定超时、跳过所有能力验证或关闭其他业务，替代对这个等待的优化。

私有原始证据仍供后续对照使用，未提交含环境日志的文件：

- `D:\RHRemoteTools\qsv-capture02-initialization-20261024.log`
- `D:\RHRemoteTools\qsv-initialization-analysis-20261024.json`
- `D:\RHRemoteTools\qsv-capture02-restored-20261024.json`
- `D:\RHRemoteTools\winserver-after-qsv-window-20261024.json`
- `D:\RHRemoteTools\qsv-window-remote-security-test-final-20261024.json`
- `D:\RHRemoteTools\qsv-nested01-analysis-20261024.json`
- `D:\RHRemoteTools\mf-natural01-analysis-20261024.json`
- `D:\RHRemoteTools\winserver-after-mf-natural01-20261024.json`
- `D:\RHRemoteTools\ffmpeg-mf-gpuinput-dynamicpool02-20261024.json`
- `D:\RHRemoteTools\ffmpeg-mf-idr-control03-frame-verification-20261024.json`
