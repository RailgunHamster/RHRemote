# winserver 编码等待：代码、调度与硬件证据

## 已确认与尚未确认

1080p30 的帧周期为 33.333 ms。既有 QSVphysical01 的编码调用平均 32.940 ms、P99 110.645 ms；
MFphysical01 平均 24.989 ms，其中实际 MFT GetEvent 平均 20.434 ms。
这些是主机 CPU 调用跨度，不能直接解释为硬件编码时间。P99 是分布的第 99 百分位，不是一个执行阶段。
此前两轮均在已有 LG 物理屏上出现发送前的慢帧，单纯更换虚拟屏不能解释全部问题。

固定 FFmpeg 的 `mfenc.c:88` 使用 `GetEvent(..., 0, ...)`；无待取事件时会阻塞。
`METransformNeedInput` 与 `METransformHaveOutput` 的等待必须继续区分。
[Microsoft GetEvent 文档](https://learn.microsoft.com/en-us/windows/win32/api/mfobjects/nf-mfobjects-imfmediaeventgenerator-getevent)
和 [异步 MFT 文档](https://learn.microsoft.com/en-us/windows/win32/medfound/asynchronous-mfts)说明了这一语义。

固定 `qsvenc.c` 在 `EncodeFrameAsync` 返回 busy 时保留原 500 微秒休眠；
`ff_qsv_encode` 在队列达到 async_depth 后以原 1000 ms 超时调用 SyncOperation。
本项目 H.264 的 async_depth 为 1，因此提交后的同步包含等待输出的时间。
实际 sleep 的调度超时、GPU 队列等待及执行须逐帧对齐；本轮新增的交集及其边界见下文。
[Intel 编码流程](https://intel.github.io/libvpl/v2.10/programming_guide/VPL_prg_encoding.html)
要求在读取异步输出前完成同步；不能只删除等待来获得正确的低延迟输出。

## 硬件只读核对

原始证据位于 `D:\RHRemoteTools`，不公开包含系统信息的原始性能记录。

| 证据 | 结果 |
| --- | --- |
| `encoder-hardware-preflight-20261025.json` | B580 DEV_E20B，驱动 32.0.101.8864；Windows Server 2025 build 26100；24 逻辑处理器 |
| `encoder-pci-topology-20261025.json` | MSI MS-7C37 实体平台；GPU 前存在 Intel 内部上游／下游桥；HypervisorPresent 不能据此认定系统为虚拟机 |
| Windows 端点属性 | CurrentLinkSpeed/Width 和 MaxLinkSpeed/Width 均为 1，不能单独作为整条外部链路带宽结论 |
| `igcl-pci-host-20261025.json` | 精确已安装 IntelControlLib.dll 的只读 ctlPciGetProperties/State 均成功：实际及最大 Gen4 ×8，15,753,846,152 bytes/s；LUID 0:0007B262 |
| 同次 IGCL 查询 | resizable_bar_supported=true、resizable_bar_enabled=false |
| `encoder-bar-resources-20261025.json` | Windows PnP 分配范围含 0xD0000000–0xDFFFFFFF，268,435,456 bytes，符合 small BAR 现状 |

IGCL DLL SHA256 `8A15A60F722313F5AF9144081FB99A0D5E1DECF89D8F1FCD850025111EB6FB9B`；
只读工具 SHA256 `9632EB53D992BF103F626C5C335511A144D02FF92156388F11EEB4E0970E7ED3`；
Intel 官方头文件 SHA256 `4951C8144D4F8ECC82D30E244043D3C145B7D2C6F82D5C87F70B12F120D8E361`。
工具只调用 Init、EnumerateDevices、GetDeviceProperties、PciGetProperties、PciGetState、Close，
不创建媒体帧、不编码、不写硬件或驱动设置。

[Intel B580 规格](https://www.intel.com/content/www/us/en/products/sku/241598/intel-arc-b580-graphics/specifications.html)
为 PCIe 4.0 ×8。当前没有已证实的 PCIe 链路降速。
[Intel B 系列安装说明](https://www.intel.com/content/www/us/en/support/articles/000091128/graphics/intel-arc-dedicated-graphics-family.html)
要求开启 Resizable BAR 以获得最佳性能。ReBAR 关闭是已确认的配置问题，尚未证明是本次慢帧的原因。
需在用户安排可重启维护窗口并完成固件设置前后对照后才能作因果结论；本轮未修改 BIOS、驱动或重启机器。

## 跟踪边界

自定义 WPR profile 已在服务端通过实际 profiledetails 校验：CPU context switch、ReadyThread、采样堆栈、
DPC/中断及 DxgKrnl 0x277 GPU 队列事件；两个采集器各 256×256 KiB 缓冲。
无音频或网络载荷 provider。每个记录使用独立随机实例；开始前注册只针对该实例的 90 秒 SYSTEM 结束任务。
不停止已有 NT Kernel Logger 或 Intel 诊断会话，不修改采样频率、安全策略、频率或功率。
原始 ETL 包含常规系统进程、映像及文件元数据，只保存在私有诊断目录；不公开原始 ETL、命令行、DLL 或 PDB。

原生辅助工具在同一台服务器采样 std::chrono::steady_clock、QPC 和精确系统时间的前后夹逼点。
首轮 8 个点的 steady_us 与 QPC/10 相符，首次夹逼宽度 2 微秒，其余不足 1 微秒；
实测对齐时仍需检查每次记录前后锚点，不能将客户端时钟直接减去服务器时钟。

本地 qsvenc.o 反汇编用于校对真实调用路径，但不把本地对象称作服务器运行中的二进制。
最终需要精确已部署包的哈希、模块基址、调用地址／堆栈以及 GPU 队列事件共同支持归因。
闭源驱动缺少私有符号时，明确记录模块及偏移；不能凭反汇编猜出 GPU 微码内部时间。

本文件尚未给出 GPU 饱和、驱动错误、ReBAR 或 SDK 调度的最终根因结论。

## MFhardware01：实际 CPU 等待与已部署机器码

dc757ee3 正常完整配置在已有 LG 物理屏进行静音 1080p30、LAN 5 Mbps 的 150.508 秒动态源诊断。
源时钟比率 0.999910、播放器新增丢帧 3（0.0656%）；全部配置、同步、源任务、显示和编码窗口均恢复，
服务已恢复 software，配置 SHA256 仍为 `A82558AD...D9B4224`。
渲染返回 27.263 次/秒，仍有 122 个间隔超过 100 ms；观察器记录期间的样本不作无观察器性能对照。
窗口内 4256 次编码平均 22.179 ms，GetEvent 合计平均 20.562 ms/编码。

WPR ETL SHA256 `B4517CBC072FAAFAAFFF42FB2D8D4432643072301313E7812322313720F81DF9`，
保存 234,051,533 bytes，记录／专用结束任务均已清理。核对后发现该 ETL 只含内核事件，没有 DxgKrnl 事件，
不能用于 GPU 队列结论。之后改用独立命名的 logman 采集器，在 QSVsdk02 媒体窗口中收到 GPU 事件，
见下文。原始记录及未知 schema 保留，不能将不完整解码称作全项通过。

64 个真实 CSwitch 的新／旧 TID 和 CPU 顺序精确匹配原始 QPC 与 xperf 导出，
换算偏移范围不足 1 微秒；独立原生 steady/QPC 前后锚点另行保留。
主线程 PID 39732 / TID 34504 的已完成调度区间与实际 MFT API 时间交集为：

| API | 交集调用数 | 平均 wall | 平均 off-CPU | 平均 ready 等待 | 平均运行 |
| --- | ---: | ---: | ---: | ---: | ---: |
| GetEvent | 2826 | 10.0501 ms | 9.9031 ms | 0.1036 ms | 0.1470 ms |
| ProcessInput | 1413 | 2.5710 ms | 1.7750 ms | 0.0079 ms | 0.7960 ms |
| ProcessOutput | 1413 | 0.06851 ms | 0.00017 ms | 0.00002 ms | 0.06834 ms |

GetEvent 的 98.54% 是 off-CPU；ready 是 off-CPU 的子集，不能重复相加。
区间边界未完成的调度段和 trace 边缘不纳入该表。
`xperf -stacktimeshifting` 导出的是 switch-in 对应堆栈，不能写成现场采样的等待入口堆栈。
ReadyThread 的唤醒进程字段可能是中断所在上下文，不能借此归咎某个业务进程。

已校验实际 Sunshine 二进制 SHA256 `4C82DBFC...05AE9B71`，首选映像基址 `0x140000000`，
本轮加载基址 `0x7ff6a9130000`。堆栈返回地址 `0x7ff6a93b1456` 的 RVA 为 `0x281456`，
精确落在编入的 `mf_wait_events` / `rhremote_mf_get_event` 的虚函数调用返回点；源码对应
固定 FFmpeg `mfenc.c:88` 及 `mf-trace-impl.h:28`，机器指令为 `call *0x18(%rax)`。

本轮 Intel `mfx_mft_h264ve_64.dll` 的 SHA256 为
`2B469F08B8DAE6E084FA4B496D4BCA31240970236E72060DB8676C0B21851FD8`，版本 `26.05.06.7eb2a756`。
加载基址 `0x7ffe41510000`；慢调用反复出现 `0x7ffe4158fe2e`（RVA `0x7fe2e`）。
复制并按哈希核对该已安装 DLL 后，反汇编确认：

```text
+0x7fe1b  cmp edi, 0xc00d3e80     ; MF_E_NO_EVENTS_AVAILABLE
+0x7fe23  mov ecx, 1
+0x7fe28  call [Sleep 的已核对导入槽]
+0x7fe2e  ...
+0x7fe38  jmp +0x7fd70           ; 重新取事件
```

主机原始记录与调度交集匹配到 19,137 次此返回地址的 DelayExecution，平均 1.4636 ms、
P99 2.506 ms、最大 5.755 ms；合计约 28.008 秒，占完整落在 GetEvent 内的 off-CPU 区间的 99.99%。
这确认当前 Intel MFT 的阻塞 GetEvent 实现采用 Sleep(1) 轮询，解释了等待的具体代码机制。
尚不能说轮询本身制造了全部约 90 ms 慢调用；仍需解释事件为何一直未到达以及生产该事件的硬件队列。
不能用删除等待、修改驱动二进制或跳过未完成输出来声称修复。

详细 CPU 证据、逐段交集、模块映射和机器码保存在
`D:\RHRemoteTools\encoder-etw-mfhardware01-20261025\mf-api-cpu-overlap.json`、
`mft-sleep-poll-proof.json` 及 `D:\RHRemoteTools\mft-getevent-poll-machine-code-20261025.txt`。

## QSV：实际提交、同步及 GPU 命令

逐次 SDK 观察器的首轮干净 CI 40109543182 暴露了 CMake BYPRODUCTS 后缀错误，已在
`b9a3fbb5abe69deb396f797e9c7de903461064a0` 修正，并用真实全新 Ninja 图验证新版通过、原声明失败。
CI 40111500694 完整成功，110 项引擎回归、163 个包文件、62 项实际存储布局全部核对后部署。
其余 211 个 FFmpeg codec 成员的原始字节和顺序保持不变。六个配对、配置和设备 UUID 保留。
实际运行的 sunshine.exe SHA256 为 `6FB9A9DA22583F00FAE0A14067079AB70AF7C63FE10B57D05349490B16580E92`。

QSVsdk01 因动态源准备超时而失败，其 1991 次 SDK 调用只作静态画面诊断。
QSVsdk02 收到 5855 次实际提交和 5855 次同步调用，全部返回成功、记录零丢弃、没有 device-busy 重试：

| 实际调用 | 平均 | P99 | 最大 |
| --- | ---: | ---: | ---: |
| EncodeFrameAsync | 0.057879 ms | 0.154 ms | 4.925 ms |
| SyncOperation | 22.593881 ms | 95.771 ms | 499.174 ms |

这排除了该轮主要时间花在提交或 busy 休眠上的解释；等待发生在异步提交之后。
源时钟比率为 0.93250，播放器新增 638 个丢帧（13.947%），故不能作为动态帧率验收。
原任务总时限未充分预留准备时间，结束标记滞后，该用例保持 FAIL；独立核对确认本轮播放器和任务已不存在，
软件默认配置、客户端参数、同步和显示布局已恢复，没有将清理完成反写为测试通过。

原始主机记录 SHA256 为 `C878259F284190BC3BFF7B6405311E40E676B6971D7E95D32C7B753008F079DF`；
SDK 分析位于 `D:\RHRemoteTools\host-frame-raw\1792862077497537100-1\analysis-qsv-sdk`。

同次 logman GPU ETL SHA256 为 `E6938F1B59857A95382600DEA23DED49DA30E2AD0A878ACE52354CAA7E5061AA`。
512 MiB 环形文件只保留末尾 16.5805 秒，不能写成完整 32 秒。
4,906,654 个原始事件均保留；本机 TDH 对 503 个 1142 事件缺少 schema，原始载荷未丢弃。
所需 QueuePacket、DmaPacket、SelectContext2 等布局解码零错误；全项元数据仍不是 PASS。

PID 6720 的三个实际提交上下文分别使用 node 0、0、1。
通过同主机 QPC/steady 锚点，将 SyncOperation 与 GPU 调度事件对齐后，在记录边界内得到 521 次同步；
其中 495 次在输入转换开始至同步结束内只有一个完整 node 1 命令。此关联依据时间与上下文，未声称有硬件帧 ID。

| 495 次时间关联样本 | 平均 | P99 | 最大 |
| --- | ---: | ---: | ---: |
| node 1 DMA 调度跨度 | 11.4317 ms | 16.3392 ms | 16.5898 ms |
| 命令排队至首次 DMA | 0.6517 ms | 4.0614 ms | 27.9498 ms |
| node 1 完成至 SyncOperation 返回 | 0.2560 ms | 2.6328 ms | 4.6001 ms |

DMA 调度跨度不是 GPU 纯算术或固定功能单元时间，不能把两个概念等同。
该段中同步返回大致跟随 GPU 命令完成，没有发现 GPU 已完成后 SDK 又平均压住几十毫秒的证据。
最慢 499 ms 和整轮约 96 ms 的 P99 不在保留的 GPU 区间内，不能据此解释这些长尾。
证据为 `D:\RHRemoteTools\encoder-gpu-qsvsdk02-20261025\sdk-gpu-linked.json`、
`sunshine-gpu-queues.json` 及逐命令／逐帧 JSONL。

只读 D3DKMTQueryAdapterInfo(KMTQAITYPE_NODEMETADATA) 针对精确 LUID `0:0007B262` 查询，
驱动报告 node 0 为 3D、node 1/4 为 VideoDecode、node 2/7 为 Copy、node 3 为 VideoProcessing。
该元数据查询不提交媒体或 GPU 工作。工具 SHA256 为 `9E71929B5660153C7A3B2527D44E8BEA5E7BA99BBBD730D4DE17C3CCD22259CB`，
结果在 `D:\RHRemoteTools\gpu-node-metadata-20261025.json`。
[Microsoft 的节点类型规则](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/d3dkmdt/ne-d3dkmdt-dxgk_engine_type)
允许兼有编码／解码能力的节点报告其主要功能，因此不能仅凭 VideoDecode 标签断言 QSV 没有使用硬件编码。

## 业务负载、源准备与下一段缺失证据

同次主机记录内，两个新 ffmpeg 进程于 17:16:48 UTC 启动；前后各 30 秒并排除启动附近 1 秒后，
同步等待平均 12.49 → 22.25 ms，P99 21.414 → 86.428 ms。
这是时间相关证据，尚非受控因果：两个进程启动在 GPU 记录结束之后；499.174 ms 的最大值更早就已出现。
进程身份使用创建时间和父 PID 核对，不凭后来复用的 PID 归因，不公开命令行或业务输入内容。
`load-timeline.json` 保留整轮十秒分桶及限制说明。17:22 的 CPU／磁盘快照只代表采样当时，不当作历史负载。

源准备分段显示：该轮 276,134,947 字节文件 SHA256 检查约 47.244 秒，显示查询约 25.57 ms，
窗口辅助代码编译约 240.63 ms。47 秒不能仅凭墙钟时间断言都是磁盘 I/O，仍需 CPU／磁盘时间线。
之后独立将同一资产复制至 C 盘并核对精确 SHA256，复制约 1.386 秒、目的文件哈希约 0.396 秒；
这是源与诊断介质调整，不是编码器性能修复或受控磁盘因果结论。
后续媒体任务总预算分别预留准备、播放和清理；原始失败用例保持不变。

QSVsdk03 在媒体开始前因客户端候选地址缺失失败，未开始新的 CPU/GPU 记录，配置、显示和参数已恢复。
其服务启动能力探测为 74.969 秒；客户端网络解析则在 0 ms 内结束，attempts 为空，属于两个不同阶段。
当发现层把当前 address 更新为 Tailscale 后，Controller 仅附加 saved.addresses、漏掉 saved.address，
导致仅启用 LAN 时没有任何候选地址。连接、配对、诊断已统一合并保存的主地址和额外地址，
保持发现层当前主地址、容量上限、原 UUID 验证及同步配置；增加完整容量及三条操作路径的回归。

下一轮需要同时间记录真实 SDK 调用、CPU running/ready/wait、GPU 队列和磁盘 I/O，
覆盖实际长尾后才区分硬件工作、资源竞争、驱动事件及线程调度。
本轮不暂停业务，不修改 BIOS／驱动，不把缺少最慢帧的证据称作全部定位完成。

## QSVsdk04：已定位到驱动 Lock2 中的显存分页等待

地址合并修复通过 33 项核心／66 项 UI 回归及 Release 构建，本机测试 UI 为
`D2D1B3EF0C1CA805DCC997C810838596058F6D8E3998B6EFBFC9C04C95BEDE6D`，原生端保持 `6B7ED038...`。
候选包含原有未提交的设置目录编辑，未把它纳入本次源码提交或正式安装包。
初次更换 UI 的整个 INI／Saved 合并守卫失败，未保留该次起始 Saved 快照，不能补称完全相同；
随后以同一候选独立重启复核，Saved、证书／密钥及三个主机证书的指纹不变，INI 亦字节相同。
实际网络诊断以 4 ms 命中保存的 LAN 地址，后续同一 LAN 已完成真实媒体连接。

本轮副屏媒体用例正常结束，测量 71.422 秒，源时钟比率 1.000237，源新增丢帧 3（0.1355%）。
主机 1394 次实际 QSV 调用记录完整、零丢弃；整轮提交平均 0.056599 ms，同步平均 63.179954 ms，
P99 167.130 ms、最大 769.191 ms。有效测量窗口主机发送约 15.801 次/秒、客户端渲染返回约 15.787 次/秒；
分析的是有观察器／ETW 开销的诊断，不作为无观察器性能验收，更未达到稳定 30 FPS。
界面观察到首帧约 10.770 秒；按实际操作事件至 render_return 为 10.580 秒，两个端点不能混写。

同主机 CPU ETL SHA256 `0BF5D490F2E547AFADA2753CE02E97FA64C9F960E3AD8788FC078668345B4AA2`；
GPU ETL SHA256 `D7059C052E83C3BCDA505519A035BFA5079174D74955716670F83CEEB9D5DADE`，
保留 38.07697 秒，GPU 原始头部 BuffersLost/EventsLost 均为零。
1447 个 1142 事件仍缺本机 TDH schema，原始载荷保留；所用命令、显存及分页布局解码零错误。
本轮减少 provider 关键词后遗漏 SelectContext2，故不能对本轮命令贴物理 node 标签。
不能把原先依赖 node 的分析得到零匹配写成没有 GPU 工作；随后使用真实上下文、提交线程及分页包身份分析。

实际 sunshine.exe 加载基址 `0x7ff634700000`；真实堆栈中的返回地址 `0x7ff6349b4062` 对应 RVA `0x2b4062`。
精确 b9a3fbb5 二进制反汇编显示其前为 `call MFXVideoCORE_SyncOperation`，原始超时仍为 1000 ms。
该唯一调用线程 PID 34000 / TID 49656 有 689 个匹配返回堆栈。
64 个 CSwitch 锚点与原始 QPC 精确对应，偏移范围不足 1 微秒。
566 次完整同步跨度中平均 wall 79.265 ms、off-CPU 79.189 ms、运行 0.0759 ms，
ready 仅 0.0263 ms；99.904% 时间为阻塞，不能解释为主线程平均一直抢不到 CPU。

实际 GPU QueuePacket178 的 PID/TID 标识编码命令上下文的提交线程为 44824 / 37828。
397 帧有唯一该上下文命令可关联，同步平均 92.378 ms，其中相应工作线程在 VidMm 调用栈中阻塞平均 76.424 ms，
占这些同步跨度总时间的 82.73%。此为嵌套区间，不能再相加。
帧 511 的分解可逐事件核对：

| 相对 SyncOperation 开始的阶段 | 时间 |
| --- | ---: |
| 到实际 SDK 工作线程提交后续 GPU 命令 | 212.0557 ms |
| 上述区间中工作线程 off-CPU | 210.543 ms |
| 其中 ready 等待（已包含在 off-CPU） | 0.057 ms |
| 上述区间中工作线程运行 | 1.5127 ms |
| 命令提交至首次 DMA | 0.0877 ms |
| DMA 调度跨度 | 8.3004 ms |
| 最后该命令完成至 Sync 返回 | 约 0.274 ms |
| 整次 SyncOperation | 220.723 ms |

该工作线程的长阻塞栈穿过 Intel `igd11dxva64.dll`、`igddxvacommon64.dll`、D3D11 和 win32u。
精确已复制 win32u.dll 的导出表及系统调用桩确认 `NtGdiDdDDILock2+0x14`，不是按地址猜 API。
随后用微软公开符号匹配精确 dxgkrnl.sys／dxgmms2.sys：ETL 加载记录与文件的映像大小、checksum、
TimeDateStamp 全部相符，PDB GUID／age 匹配。实际调用链为：

```text
Intel DXVA driver → NtGdiDdDDILock2
→ dxgkrnl!DxgkLock2 → DxgkLock2Internal
→ dxgmms2!VidMmLock → VIDMM_GLOBAL::Lock2 → LockCommon
→ QueueDeferredCommand → WaitForFence → WaitForFences
→ KeWaitForSingleObject
```

`WaitForFences+0x213` 的返回点机器码之前确为导入的 `KeWaitForSingleObject` 调用。
这是编码命令真正提交前的显存分配锁定／映射等待；
[Microsoft LOCK2 定义](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/d3dkmthk/ns-d3dkmthk-_d3dkmt_lock2)
描述了锁定分配及取得 CPU 虚拟地址的参数。没有据此宣称知道 Intel 私有缓冲区的格式或用途。

同一 GPU ETL 的分页事件提供了更直接的身份匹配：1317 个由这两个工作线程提交的包均为 op=208、queue_type=1，
完整包以 PagingQueue + PagingQueuePacket + SequenceId 连接，平均跨度 22.966 ms、P99 92.123 ms、最大 115.104 ms。
其中 1315 次可与唯一对应线程的完整 VidMm 阻塞区间匹配，包完成至线程再次运行平均约 164 微秒。
包中记录的 ExecutionTime100ns 全为零，不当作实际零耗时；op=208 未取得官方枚举名称，保留数值。
帧 511 在该等待中涉及四次 VidMm 阻塞，其中两段约 75.56 / 100.05 ms。

只读 QueryStatistics 另确认 B580 的五个段中，一个本地 fully-CPU-visible 段的 commit limit 约 224.4 MB，
另一本地非 fully-visible 段约 12.54 GB；ReBAR 关闭与较小 CPU 可见区一致。
这仍不是 ReBAR 造成分页等待的受控证据，亦不能把分页事件数量当作整张显卡显存已耗尽的证明。
该查询在媒体之后，动态 resident 数值不反填为媒体时段；ETW 与接口段 ID 的编号也不盲目等同。

核心证据位于 `D:\RHRemoteTools\encoder-gpu-qsvsdk04-20261025\fence-analysis-conclusion.json`、
`paging-wait-linked.json`、`worker-delay-examples.json`，以及 `D:\RHRemoteTools\qsvsdk04-vidmm-wait-machine-code-20261025.txt`。
CPU 导出改为流式筛选，不产生超过 10 GB 的展开 CSV；已删除首轮可再生成的 10.23 GB CSV，保留原始 ETL 和归因证据。
旧 CPU 导出工具的一次追加导出在保留新的全线程区间后因既有输出已存在而拒绝覆盖，未改写为成功；
后续跨线程与分页分析分别独立完成、校验原有 API 结果不变。

本轮参数、显示、同步、媒体源、流、CPU/GPU 采集及其结束任务全部恢复／结束，
最终配置及四份持久状态哈希与部署前一致，继续保持 software 默认。
尚待取得发生等待的 Intel 缓冲区创建／资源类型及分页依赖 fence，
再决定是应用资源使用、驱动路径还是硬件映射配置的可优化点；未声称已解释所有长尾或达到不可优化。

## QSVsdk04 续查：分页线程本身在等什么

继续离线读取同一份原始 CPU ETL，没有新增媒体采集或暂停业务。
原生读取器先用 TDH 校验 StackWalk 的 EventTimeStamp、StackProcess、StackThread 字段及 64 位布局，
提取 354139 条目标线程栈，读取完成且零属性错误。
原始时间戳与既有 CSwitch 的同线程、同 CPU 时间交叉校验，采用 ±1.1 微秒内唯一匹配；
将栈用于该次切回前结束的 off-CPU 区间，而不是把切回事件误当作阻塞开始。
System PID 4 / TID 1416 的 132662 个完整区间均找到唯一栈，无缺失或歧义。
映像、加载地址和精确公共 PDB 沿用上节的实际服务器身份，不用本机另一版本的系统二进制代替。

同一 38.077 秒 GPU 记录覆盖区间内，该线程的分类如下。
off-CPU 包含已经就绪、等待再次调度的时间，表中 ready 是其子集，不能再次相加；
这些是共享线程整段的累计值，不全部归属于 RHRemote，也不能逐项加到某个编码帧上。

| 栈分类 | 完整区间数 | off-CPU 累计 | 其中 ready 累计 |
| --- | ---: | ---: | ---: |
| WaitDmaBufferNotBusy | 82898 | 13396.811 ms | 1637.949 ms |
| 等待所有分页引擎空闲 | 20584 | 4192.531 ms | 360.313 ms |
| 调度器事件等待 | 2316 | 2354.745 ms | 49.401 ms |
| 分页 fence 等待 | 2247 | 458.375 ms | 39.706 ms |
| 其他／未解析 | 250 | 30.412 ms | 3.641 ms |

已解析的典型路径为：

```text
CommitVirtualAddressRange → UpdatePageTable → FlushGpuVaTlb
→ FlushPagingBufferInternal → VidSchSubmitPagingCommand
→ VidMmAcquireDmaBuffer → WaitDmaBufferNotBusy
→ VidSchWaitForCompletionEvent → VidSchWaitForEvents
```

同段还出现 PageInDeviceInternal、VIDMM_DEVICE::Resume 和 EvictResources 路径。
这确认了显存管理／分页命令链中的缓冲区及完成等待；不能据此把等待命名为纯视频编码运算，
也不能推断整张显卡 VRAM 已满或已定位某个 Intel 私有资源。
[Microsoft 设备分页队列说明](https://learn.microsoft.com/en-us/windows-hardware/drivers/display/device-paging-queues)
说明了页表更新、驻留请求及其 fence 的异步依赖，但文档本身不能代替该机器的等待证据。

复核数据为 `encoder-etw-qsvsdk04-20261025\paging-worker-stack-timestamp-proof.json`、
`paging-worker-wait-stack-analysis.json`、`paging-worker-off-stack-intervals.jsonl` 及 `paging-worker-public-symbols.txt`。
原始栈读取器及分析脚本仍留在私有诊断目录，原始 ETL 与帧记录未改写。

## 选中前排队与实际竞争进程

对原来标为 PagingQueuePacket/Info 的事件进一步核对机器码：
`VidMmWorkerThreadProc` 调用 `McTemplateK0ppx_EtwWriteTransfer`，后者加载符号 `EventPagingQueueSelect`；
实际事件描述符字节 `44 01 00 11 00 00 b2 00 01 00 00 00 00 00 00 40` 的 ID 为 324。
工作线程发出该事件后才进入 `ProcessDeferredCommand`。
因此该点表示 VidMm 线程选中分页请求，不能冒充 GPU 开始执行。

与 SDK Lock2 等待关联的 1317 个 op=208 包，提交到选中平均 20.688 ms、P99 90.393 ms、最大 112.539 ms；
选中到完成平均 2.278 ms、P99 5.724 ms、最大 9.553 ms。
平均包跨度 22.966 ms 中约 90.08% 位于选中之前。
这个拆分包含各自设备队列的依赖及共享服务等待，尚不能把全部选中前时间称为可运行请求遭到抢占。
同一精确二进制的 `ProcessDeferredCommand` 数值分派也将 op=208 导向 `LockInternal`，
仍保留数值，不给它编造官方枚举名称。

1317 次自己的提交至选中区间中，1316 次穿插了同一 System 线程选中的其他进程请求，
每次平均 3.276 个，P99 为 7、最大 12。观察到的主要请求方如下。
请求用 PagingQueue + Packet + Sequence + 已完成包时间识别，避免包地址／序号完成后重用产生假关联。

| 进程身份 | 该共享线程选中的包数 | 穿插于自己等待区间的独立包数 |
| --- | ---: | ---: |
| ffmpeg.exe PID 45336 | 4561 | 2291 |
| ffmpeg.exe PID 47640 | 2064 | 1145 |
| testhost.exe PID 40576 | 1327 | 862 |

两个 ffmpeg 的创建时间由同次采集的前后只读快照确认早于事件；
短命 testhost 的身份由同一原始 CPU ETL 的 Process Start、PID、父 PID 和映像名称核实，未读取业务命令行。
这证明同一个分页服务线程处理了跨进程请求，不证明暂停某个进程就能消除全部卡顿。
仍需确认自己的请求何时满足依赖，以及实际缓冲区创建／映射方式；本轮没有停止这些进程。

再用同一原始事件的实际 Info／Stop PID、TID、包身份及时间核对 9556 个完整处理区间，
均在 PID 4 / TID 1416，且相邻选中到完成的区间没有重叠。
自己的选中前平均 20.688 ms 中，6.360 ms 与其他进程的完整处理区间重合（30.74%），
0.022 ms 与自己的上一包重合，其余 14.307 ms 位于完整包处理区间之外。
后者继续交叉该线程的调度栈：平均 5.762 ms 是分页 DMA 缓冲区忙等待，
2.050 ms 是等待所有分页引擎空闲，0.270 ms 是其他事件／fence 等待，0.010 ms 未解析，
剩余 6.215 ms 不在完整 off-CPU 区间内，不独立宣称为已证明的 CPU 运算时间。
这里的 off-CPU 仍含 ready 子集；自己的请求区间可能互相重叠，所有汇总都是按请求求交后累计，
不是独占的整条时间线，也不是移除其他进程后可直接节省的时间。
这进一步限制了“全是后台转码引起”的结论，并给出了包外分页等待的位置。

`paging-service-overlap-20261025.json` 与 `paging-unscoped-gap-attribution-20261025.json`
保留了独立包区间的核对、逐类累计、长尾示例、输入哈希及分析边界。
该轮 SDK 请求共涉及 24 个本地 Alloc 指针；没有它们的创建／类型映射，
不能仅凭数量把它们叫作某种输入纹理、码流缓冲或稳定的资源身份。

证据为 `encoder-gpu-qsvsdk04-20261025\paging-competition-v2.json`、
`encoder-etw-qsvsdk04-20261025\paging-actor-40576-process-identity.jsonl`，以及同目录的
`vidmm-worker-thread-machine-code.txt`、`process-deferred-command-machine-code.txt`、
`paging-info-emitter-machine-code.txt`、`paging-queue-select-descriptor.txt`。

## SDK 到 Lock2 的实际 D3D11 接口

继续用服务器实际 d3d11.dll 校对用户态调用链，SHA256 为
`842825A67E1466BEAC7CB93F1A7E63052CC8121C6321548F851E9180D30289C0`。
同次 CPU ETL 的开始／结束映像记录均为 PID 34000、基址 0x7ffec8f70000、
SizeOfImage 0x25d000、checksum 0x27131e，与复制文件一致。
该映像的 ETL 时间戳为零，未谎称与 PE 的可重现构建哈希时间戳相等。
RSDS 与下载的公共 PDB GUID `A1BE8FF5CA5330B18C65B9B00A20A73C`、age 1 匹配。

记录中的 RVA 0x2249d / 0x2262b 分别为 `CContext::DecoderExtension+0x3d`、
`CDecodeContext::DecodeExtension+0x12b`，调用处机器码进入 Intel 驱动回调。
Intel 驱动再回调 RVA 0x15929 的 `NDXGI::CDevice::Lock2CB+0x49`，
经 RVA 0x158a1 的 `CallAndLogImpl<D3DKMT_LOCK2>+0x11` 调用先前确认的 NtGdiDdDDILock2。
这把实际接口落实到 SDK 扩展调用内部，不能把它简单替换成 Sunshine 自己的 D3D11 Map 调用。
接口名带 Decoder 不等于本轮正在解码；未捕获私有扩展功能号／GUID，仍不猜测具体资源用途。
Intel 的公开 [VPL GPU Runtime](https://github.com/intel/vpl-gpu-rt) 说明该开源实现使用 Linux／VAAPI，
因此不拿其中另一套平台源码冒充此 Windows DLL 的精确实现。

证据为 `D:\RHRemoteTools\d3d11-qsv-lock-caller-identity-20261025.json` 和
`d3d11-qsv-lock-caller-machine-code02-20261025.txt`。
第一次使用不明确模块表达式的反汇编没有解析成功，未计为证据；第二次使用验证后的数值地址成功。
下一次需要在连接／编码器资源创建之前启动采集，并核对资源及 context 生命周期元数据是否实际收到，
不能继续使用缺少创建记录的指针去推断资源类型。

## QSVsdk04 续查：谁把分页线程唤醒

对同一原始 CPU ETL 做两遍只读分析。第一遍按实际 Thread provider / opcode 50，
通过 TDH 读取 TThreadId=1416 的 132617 条 ReadyThread；第二遍按原始 QPC 时间戳和 CPU 精确匹配 StackWalk，
没有从进程名字推测唤醒者。共取得 132453 个唯一唤醒栈，164 个有不同栈的歧义点被排除，未任选其一。
两遍均读取 12553069 个事件，属性／布局错误为零；与 xperf 的全部 132617 个 ReadyThread
按同 CPU、目标线程及 ±1.1 微秒的整数显示精度交叉校验成功。
初次直接 round 的派生分析在半微秒边界漏掉 6692 个 CSV 关联，修正量化处理后完整匹配；
原始时间戳和两遍读取结果未变，旧的保守分析仍保留，最终使用 v2。

结合上节的等待栈，最常见的分页 DMA 缓冲忙等待唤醒栈出现 64751 次，路径为：

```text
ntoskrnl!KiExecuteAllDpcs
→ dxgkrnl!DpiFdoDpcForIsr
→ igdkmdnd64.sys RVA 0x3a35d / 0x65096e
→ dxgkrnl!DxgNotifyDpcCB
→ dxgmms2!VidSchDdiNotifyDpc
→ VidSchiProcessDpcDmaPacket → VidSchiProcessDpcCompletedPacket
→ ntoskrnl!KeSetEvent → KiExitDispatcher
→ KiProcessThreadWaitList → EtwTraceReadyThread
```

图中为调用方向，原始栈保留反向展开地址。等待所有分页引擎空闲和分页 fence 的最常见唤醒栈分别出现 16328 / 1777 次，
还包含 `VidSchiProcessCompletedQueuePacketInternal → VidSchiCompleteSignalSyncObject
→ VidSchiUnwaitMonitoredFences → UnblockCpuWaiter`。
这些是各类最常见的完整栈数量，不等同于整类全部事件数；同栈多个函数的计数不能相加。

机器码另确认 dxgmms2 的这两个信号分支实际调用导入的 KeSetEvent：RVA 0xb069 和 0x37221，
调用后各有五字节 NOP；未推断该补丁槽或运行时返回地址调整的私有机制。
ntoskrnl 的实际 RVA 0x22fb9f 为 KeSetEvent+0x11f、0x2d12b9 为 EtwTraceReadyThread+0x129。
精确复制文件与 ETL 的加载范围、checksum、PE 时间戳均核对一致，
ntoskrnl SHA256 为 `866BC13D7872CC9F7F1807D1D6BFAC63ED9B761651C653258EC972EED493BD95`，
公共 PDB GUID `F9533515F98A1E8105A1491384F2EFC1`、age 1；
Intel KMD SHA256 为 `B4FC87250B89BE2DFD5F6480888073E247F688F2FDCA5B72E280932A3BA5508D`，
版本 32.0.101.8864。Microsoft 符号源没有该 Intel 私有 PDB，只保留实际 RVA、展开函数范围及间接调用机器码，
没有编造 Intel 函数名称。首次离线查询使用 nt 模块别名未列出模块；改用实际 ntoskrnl 模块名后核对成功。

该结果将“分页线程阻塞后被显卡完成处理唤醒”落实到实际调用链。
仍不能据此确认哪个物理引擎、哪个精确 fence、GPU 指令耗时或 Intel 私有资源类型。
DPC 可以借用当前进程／线程上下文，列表中的 qemu、python、java 等名字不是这些进程导致完成事件的证据。
本轮所有分析使用既有记录，没有新增 ETW 会话、媒体、音频、业务暂停或权限提升。

证据位于 `D:\RHRemoteTools\encoder-etw-qsvsdk04-20261025\paging-worker-waking-stacks.jsonl`、
`paging-worker-waking-stack-proof-v2.json`、`paging-worker-waking-stack-resolved.json` 和
`paging-waking-dpc-module-identity.json`；二进制身份与机器码另保存在同一私有诊断根目录。
原始 ETL、二进制、PDB 和包含进程信息的记录不纳入公开仓库。

## 诊断代码修正与下一次实测边界

原先的“Using realtime GPU priority”在设置 API 返回前记录，无法证明设置成功。
现在保留原来的请求值和策略，另外记录 setter 原始 NTSTATUS、getter 原始 NTSTATUS 和实际读回值；
查询失败／接口不存在时为 unknown／unavailable，不能用初始化的零值冒充最低优先级。
调用分别记为 `gpu_process_priority_set`、`gpu_process_priority_query` 阶段，
`RHREMOTE_GPU_PRIORITY` 日志通过服务端收集器保留到 statistics 时间线。
[Microsoft 读回接口](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/d3dkmthk/nf-d3dkmthk-d3dkmtgetprocessschedulingpriorityclass)
确认了此查询的参数及返回类型；一次外部只读进程查询被拒绝访问，没有通过提升权限绕过。

本机完整引擎重编及 116 项引擎回归、31 项 statistics 回归通过，新增六项覆盖失败／缺失／有效零值和成功读回。
覆盖率缓存清理的组合命令被自动审批拒绝，未执行删除；测试改为独立覆盖率输出目录后成功，未出现 gcov 错误。
这项修改改善诊断可信度，不宣称提高了帧率。026439b8 / CI 40124792844 完整成功，
实际 116 项引擎测试（含全部六项新回归）、163 文件、62 个存储 ABI 及最终归档中 211 个未改成员核对后部署 winserver。
安装前后四份持久状态哈希、六个配对和 UUID 一致，中继恢复，仍保持 software 默认；备份位于
`C:\ProgramData\RHRemote-Sunshine\backups\20261025-035713-c827c646`。
第一次 SCP 连接在远端文件创建前终止，经只读确认原服务 PID 和版本仍未变后重试成功，未重复安装。
新引擎 PID 6384 两次记录均为 requested=5、set_status=0、query_status=0、actual=5；
设置阶段为 15／23 微秒，查询各 3 微秒。这证明新进程这次启动的实际优先级，不能反填为旧 QSVsdk04 的值。
当前收集器到 statistics 的真实导入也验证保留两条实际值和四个阶段；原 CPU ETL 及主机逐帧文件 SHA256 未改变。
证据为 `D:\RHRemoteTools\gpu-priority-026439b8-package-verified-20261025.json`、
`winserver-gpu-priority-026439b8-deployed-20261025.json`、`gpu-priority-deployed-observation-proof-20261025.json`。

准备的轻量 H.264 → HEVC → H.264 对照因本机物理副屏不再是活动显示器，在启动前校验即终止。
没有切换到主屏／虚拟屏，也没有启动该轮编码窗口、源播放器或媒体会话。
该次独立低频观察器已结束，不能把未执行的试验记为通过。
用户随后明确允许使用主屏，普通客户端按原 Saved／原生 INI 不变的条件重启到主屏；
新的 primary01 三轮对照使用当前 026439b8，60 秒媒体窗口、静音、低频 CPU／GPU 观察，不启用重型内核／GPU ETW。
结果以新的独立记录和源有效性筛选为准，不沿用原先未执行的状态或指标。

## 主屏三轮有效源对照：H.264 → HEVC → H.264

三轮均为 Windows → winserver，现有 LG FULL HD 物理屏、1080p30、LAN、5 Mbps、1024 字节分包、静音，
服务器为 026439b8，客户端 UI／原生 SHA256 与前述候选一致。每轮有约 60 秒对应的实际逐帧窗口，
播放器时钟分别为实时的 1.000032／1.000040／0.999990，窗口内播放器新增丢帧均为零。
主机记录及 SDK 调用均完整、零丢失，所有对应主机帧号均在客户端窗口中找到。
本轮不启用内核／GPU ETW，只保留约 1 Hz CPU／GPU 计数；这是带轻量观察的对照，仍未达到稳定 30 FPS。

| 指标 | H.264 A | HEVC B | H.264 C |
| --- | ---: | ---: | ---: |
| 主机输出 FPS | 13.631 | 12.357 | 13.386 |
| 捕获回调 FPS | 29.902 | 29.922 | 29.949 |
| QSV 提交平均 ms | 0.023 | 0.060 | 0.034 |
| QSV SyncOperation 平均 ms | 72.892 | 80.378 | 74.095 |
| QSV SyncOperation P99 ms | 109.697 | 113.263 | 113.275 |
| 格式转换平均 ms | 0.429 | 0.453 | 0.507 |
| 客户端首包至渲染调用返回平均 ms | 1.104 | 1.083 | 1.188 |
| 整机 CPU 计数平均 % | 12.356 | 15.543 | 15.742 |
| 连接动作至首帧渲染调用返回 s | 5.528 | 8.219 | 4.091 |

同一主机的原始 QPC 将低频观察与实际发送窗口匹配，保守排除跨越窗口边界的计数区间，
分别保留 59／59／60 个样本；所有参与 GPU 计数的 PID 均有唯一创建时间，身份读取错误为零。
CPU 的 P99 分别为 18.996／23.585／24.856%，不能称为 CPU 跑满。
两个后台 ffmpeg 在三轮中都持续存在，未暂停；不能把它们存在本身当作唯一因果解释。
具体 LUID／phys／eng／engtype 实例保留在私有分析，未把 Windows 的 VideoDecode 计数名称直接当作实际编码节点。
三轮都卡在同一个同步 API 等待区间，HEVC 未改善；等待区间本身仍包含驱动、调度、资源依赖及 GPU 工作，不能等同于 GPU 指令执行时间。

启动的慢点也落实到当前服务端阶段：

| 服务端阶段 ms（父子阶段不能重复相加） | H.264 A | HEVC B | H.264 C |
| --- | ---: | ---: | ---: |
| 捕获前枚举刷新 | 773.797 | 627.507 | 493.051 |
| 编码帧池初始化 | 100.201 | 220.211 | 180.829 |
| avcodec_open2（encoder_codec_open） | 283.867 | 3289.952 | 238.532 |
| 编码器创建总计 | 403.094 | 3527.582 | 445.499 |
| 首次 encode 调用 | 371.046 | 753.706 | 405.607 |

这些不能解释全部首帧时间。连接准备还包含主机独立显示辅助查询，其新鲜捕获检查为
976.952／1166.330／1139.220 ms，其中设备创建 569.122／647.267／609.086 ms，
释放 399.206／507.479／521.714 ms；该 worker 与客户端初始化并行，不能把两者整段简单相加。
客户端协议就绪后等待首包为 2825.133／6388.869／2346.118 ms，仍需继续划清剩余空隙。
首次客户端 QML 加载为 543.819 ms，后两轮约 40 ms，也保留为另一个冷启动项。
不把这些不同阶段或重叠区间笼统归为网络、Moonlight 或显卡型号问题。

服务恢复默认 software 的重启会截断引擎自身 sunshine.log，原收集器因此漏掉刚结束的串流启动阶段。
现改为先核实服务实际由 RHRemoteHost.exe --sunshine-service 托管，再读取跨引擎重启保留的包装服务日志，
最多最后 8000 行，保留原来的敏感信息过滤和普通字符串传输；独立 Sunshine 或缺少包装日志时使用原路径。
统一服务仍可沿用 SunshineService 名称，不能仅按服务名判断。配置明确标为采集时快照，不当作历史试验配置。
PowerShell 7／5.1 的真实临时文件回归通过，覆盖两个服务名称、错误宿主、缺失日志、回退、限量与隐私过滤。
三份原始采集快照未覆盖；新的保留日志与原 SSH 时钟边界组合为独立证据后，重新生成对应 statistics 报告。
所有主机逐帧 SHA256 仍与传输结果相同。跨机对齐是带不确定度的 SSH 估计，未据此计算单向网络延迟。

三轮媒体、播放器及任务已停止，设备连接配置、自动同步和显示布局恢复。
临时 QSV 窗口于 2026-10-24 20:15:25 UTC 恢复原配置哈希
`A82558ADB6C7F94A248BAE57DBA9FCBB402F8A55EDE84D494647732DDA9B4224`，独立恢复任务已删除。
当时服务 PID 48104／引擎 PID 48416；后续试验会产生新的进程身份，不能沿用此 PID。
证据为 `D:\RHRemoteTools\qsv-codec-primary01-analysis-20261025.json`、
`qsv-codec-primary01-collected-20261025.json`、`qsv-codec-primary01-window-restore-20261025.json`，
各段 statistics report.html 保留客户端与服务端时间线和完整性说明。

## 资源生命周期采集的边界

另开独立 lifecycle01 试验，在替换 QSV 服务之前启动 DxgKrnl 的 Base／References／Resource／Memory／VidMmWorker 事件，
使用有大小上限的顺序文件，避免循环覆盖最早的资源创建记录。实际文件在 512 MiB 提前结束，
仅保留服务启动最初 37.610 秒；实际串流尚未开始，不能用这段数据给后来的媒体帧归因。
ETL 头部为 25 个丢失缓冲、零 reported events lost，缺少 1142/v1/Info 的本机 TDH 元数据（1080 条原始负载仍保留）；
因此既不称完整采集，也不把 events lost=0 单独当作零丢失。
只读确认该随机名记录会话已不存在，文件可独占读取且恰好达到上限后，移除了该次自己的恢复任务；
原失败状态和大小上限证据保留。媒体／源／显示布局／连接参数都正常恢复，服务又回到原 software 配置，六个配对不变。

这段有限的启动记录确实拿到了 Device／Context 的创建及 NodeOrdinal，以及 117 个实际观察到的 backing-store 全局指针的先前创建记录，
但它们属于该进程的启动探测阶段，不能关联为旧 QSVsdk04 或本次后续串流的资源。
另外确认 DeviceAllocation 的真实事件结构同时给出 hVidMmAlloc 与 hVidMmGlobalAlloc，
下一步按创建／销毁时间及设备身份使用这个明确映射，继续避免比较两类指针的数值相似度。
新的 resource02 只保留 Resource(0x40)，仍在编码器初始化前启用，并以新的 ID 单独检查完整性；
不增加文件上限来掩盖高频事件过量的问题。

私有证据为 `D:\RHRemoteTools\qsv-lifecycle01-cap-closure-proof-20261025.json`、
`encoder-lifecycle-qsv-lifecycle01-20261025\lifecycle-metadata-proof.json`、
`qsv-lifecycle01-collected-20261025.json` 及原始 ETL；不将这些系统原始记录纳入公开源码。

resource02 已结束并恢复：Resource(0x40) 的顺序 ETL 为 515112960 字节，低于上限，
SHA256 `1340FEC1A3E07750E27FC0741EFDCD312682D68ED816159CEEF9949D52C11B5E`。
独立读取 2928164 个事件，状态成功，事件／缓冲丢失和元数据错误均为零；保留 162.384 秒，
同机 QPC 范围完整包含实际主机逐帧记录的 85.271 秒。
这证明本轮资源事件观察的覆盖范围，不表示这些事件足以计算排队依赖或 GPU 执行时间。
媒体窗口播放器新增五个丢帧（约 0.268%）、时钟为实时的 0.999655，符合本轮源筛选；
带 Resource 记录的输出约 13.780 FPS，不作为无分析器的性能验收结果。

新进程 PID 19700 的 DeviceAllocation 创建事件有 6376 条，均能在创建时找到唯一对应的全局分配元数据；
446 个有观察到的映射区间与本次主机记录相交，其中 405 个在本次记录期间创建。
这些是资源实例／创建映射数量，不是每帧分配数，不能仅凭尺寸给未知缓冲区命名。
实际记录包含 1920×1088、数值格式 842094158 的三组新映射，以及多种一维／私有资源；
用途仍需与具体 SDK 调用关联，不能认作全是码流或输入纹理。

第一版离线派生分析错误地按创建进程筛掉了其他进程字段下的销毁事件，出现大量假重叠，未用于结论。
v2 从同一原始记录补入与自有指针／设备／适配器明确匹配的 18206 条结束事件，
其中 15572 条的事件进程字段为 System(4)。这再次说明当前事件进程字段不能直接充当资源所有者。
修正后所有 6376 个已观察创建映射均关联成功，创建重叠和全局映射缺失均为零。
仍有一条本地结束事件无法配上已观察到的自有创建；另外的全局结束事件落在自有生命周期之外，
属于指针复用／其他所有者边界，未拿来延长旧资源身份。
因此完整资源身份覆盖仍未宣称通过，实际因果资源和物理引擎归因继续保留未知。
旧的 v1、修正补充和原始文件均保留，最终使用
`encoder-lifecycle-qsv-resource02-20261025\resource-lifetime-proof-v2.json`。

Resource 事件自身没有 SDK 工作线程的阻塞栈及队列 readiness；下一轮应在保留这些创建／销毁元数据的同时，
限量记录与 SyncOperation 重叠的 Lock2／backing-store 引用及 CPU 等待，按观察到的生命周期连接，
不能把旧 QSVsdk04 的指针与新进程的资源跨轮拼接。
本轮媒体、源和记录任务均停止，显示与用户参数恢复，software 配置哈希仍为前述原值。
恢复记录为 `D:\RHRemoteTools\qsv-resource02-window-restore-20261025.json`，当时服务 PID 44176；
下一次试验必须重新读取进程身份。

## QSVjoint05：实际每帧、资源与 CPU 完成唤醒同次对应

这次仍为当前 026439b8、Windows 主屏、现有物理 1080p 屏、30 FPS 请求、LAN 5 Mbps、静音，
业务未暂停。改用 WPR 的事件 ID 过滤，在编码器初始化前保留资源生命周期，
实际媒体稳定后再启动 CPU／码流队列的限时重叠记录。
短测已实际验证指定事件存在、未选事件缺席，并非只检查了 XML；三份原样 profile 纳入 `tools/profiles/`。
资源／队列／CPU ETL 分别为 54680964／51467546／250597040 字节，各自按 SHA256 验证传输，
记录、媒体、源、显示布局、连接参数及 software 默认均恢复，所有独立停止／恢复任务移除。

资源 ETL 独立读取 1191039 个事件，实际数字字段解码零错误；队列 ETL 读取 2887223 个事件，
CPU ETL 读取 11767013 个事件，三份均为零事件／缓冲丢失、零选定事件元数据错误。
资源记录 161.754 秒。CPU 原始 QPC 与 xperf 逐项匹配的 64 个上下文切换锚点全部一致，
换算显示精度范围仅 0.9 微秒；没有用跨机 UTC 相减来算网络或 GPU 延迟。
客户端／主机逐帧记录也完整、零丢失。源播放器时钟为实时的 1.000183，新增丢帧 22 个，
约 0.998%，接近预设 1% 上限，仅用于此次联合定位。媒体窗口 71.536 秒输出 26.098 FPS，
捕获回调 26.767 FPS；分析器负载、源丢帧和业务负载都存在，不能当作已稳定 30 FPS 或优化收益。

本轮映像重新核对，没有沿用旧进程的函数地址。实际 sunshine.exe SHA256 仍为
`143143FFFD194EDBF3D9C369337AECD8C4C39E406F8CD58FA088426A3DCA8B24`，
机器码中的两个 MFXVideoCORE_SyncOperation 调用返回 RVA 现为 0x2b46a2／0x2b46f2，
不同于旧 b9a3fbb5 的地址。实际栈唯一命中调用线程 34604。
ntoskrnl、dxgkrnl、dxgmms2、Intel KMD 及四个相关用户模块的本轮开始／结束映像记录与所用 PE 身份核对。
通用映像读取器另外有两条映像记录未解析，未把整个读取器宣称为成功；选定八个模块的成对身份均通过。
用户 DLL 在 ETL 中为零的时间戳没有冒充与 PE 可重现构建时间戳相等。

CPU 窗口内 1260 次实际 SyncOperation 平均 28.896 ms，其中 off-CPU 为 28.845 ms，
ready 部分仅 0.015 ms，真正在线运行约 0.052 ms；off-CPU 占 99.821%。
同窗口 EncodeFrameAsync 平均仅 0.058 ms。整段媒体窗口的 Sync 平均 26.965 ms／P99 116.509 ms，
与较短 CPU 窗口的均值使用了不同的边界，不能混作同一组样本。

直接从原始 ETL 取得调用线程的 5168 次 ReadyThread。其事件头进程／线程字段为 unknown，
没有拿它们推测唤醒者；实际 StackProcess／StackThread 和栈负载可用。
其中 5038 组是相同原始 QPC、CPU、负载 PID／TID 的一份纯内核栈加一份纯用户栈，另外 130 组为单栈。
先前保守的 v1 将不同片段排除；v2 只按上述明确互补条件组合，所有原始片段保留，未任意选取一份。
按实际栈查证，1260 次 Sync 的终端等待均由本进程 SDK 工作线程唤醒：50464 共 1208 次、32820 共 52 次。
公共 PDB 及本轮 PE 身份将内核路径落实为
`NtAlertThreadByThreadId → KeAlertThreadByThreadIdEx → KiExitDispatcher → KiProcessThreadWaitList → EtwTraceReadyThread`，
用户片段含实际 libmfx64-gen 调用。不能把其他类型等待的全部 ReadyThread 都算作编码完成。

进一步将这两个 SDK 线程的 464 段 dxgmms2 等待逐条交叉：459 段的原始栈负载与 xperf 展开完全相等，
另外五段不一致被排除；456 段还唯一匹配同线程、接近的起止边界及实际分页包身份，三段未唯一匹配而排除。
这些 456 次等待都有本次实际 DeviceAllocation 的本地／全局分配映射及对应全局元数据。
没有将旧 QSVsdk04 或 resource02 的指针借过来使用。实际涉及以下三类私有缓冲：

| 分配大小 | 声明宽度 × 高度 | 数值 Format |
| --- | --- | ---: |
| 4 KiB | 448 × 1 | 0 |
| 20 KiB | 20480 × 1 | 0 |
| 512 KiB | 522240 × 1 | 0 |

这说明该长尾不要求一个巨大的 4K 输入纹理才会出现；这些字段仍不能证明它们在私有编码器中的用途。
公共符号再次确认 SDK 线程的内核阻塞路径是
`VidMmLock → VIDMM_GLOBAL::Lock2 → LockCommon → QueueDeferredCommand → WaitForFence(s)`，
上层仍为本轮实际 Intel DXVA 扩展／D3D11 Lock2 回调。

第 428 帧提供了完整的慢例：SyncOperation 为 250.524 ms，调用线程 off-CPU 250.461 ms，
ready 仅 0.011 ms。其完成工作线程为 50464，帧等待范围内四次已验证资源阻塞共 237.392 ms，
涉及上述三类资源，其中一个 4 KiB 缓冲单次阻塞 146.190 ms。
这四个分页请求提交到被选中合计 224.496 ms，选中到完成约 12.806 ms；
后者仍不是 GPU 指令执行时间。第 494 帧的 Sync 为 246.469 ms，匹配资源阻塞为 237.073 ms。
这把两个慢帧的大部分等待落实到了具体资源和阶段。

全部 CPU 窗口中，已匹配资源等待只覆盖部分帧，不能说每帧全部时间都由这三类阻塞造成；
其余排队／硬件工作及分页请求在被选中之前的依赖仍继续定位。
帧与工作线程／资源的关联使用实际完成唤醒和时间范围，不冒充 GPU 硬件帧编号。
本轮核对还确认源码已请求 low_power=1、low_delay_brc=1 和 VCM，不能再建议把本已开启的开关打开。
下一步先利用现有 FFmpeg 在 GetVideoParam 后输出的 VDENC／码率控制参数，限时读回实际生效值，
再设计能区分原因的编码策略对照，不凭这些请求值认定驱动实际采用了同样设置。

主要证据为 `D:\RHRemoteTools\qsv-joint05-collected-20261025.json`、
`encoder-etw-qsvjoint05-20261025\sdk-resource-wait-linked-proof-v2.json`、
`encoder-waking-proof-v2.json`、`selected-image-identity-proof.json`，
以及 `encoder-filtered-resource-qsvjoint05-20261025\resource-link-proof.json`。
原始 ETL、实际机器码、逐帧关联及保守版本保存在私有诊断目录；公开仓库只保存 profile 和分析说明。

## QSV 实际参数与 joint05 慢帧的提交边界

限时将现有引擎的 `min_log_level` 调为 0，读取固定版本 FFmpeg 的
`MFXVideoENCODE_GetVideoParam → dump_video_param` 输出，避免把初始化前的请求日志当成实际参数。
静音、主屏、现有物理 1080p 屏，没有启用动态源、WPR 或暂停业务。
同一实际串流仅有一个 H.264 创建锚点和一个对应 FFmpeg context，实际读回为：

| 参数 | 实际值 |
| --- | --- |
| TargetUsage / RateControlMethod | 4 / VCM |
| VDENC / LowDelayBRC | ON / ON |
| GopRefDist / NumRefFrame / MaxDecFrameBuffering | 1 / 1 / 1 |
| BitrateLimit / MBBRC / ExtBRC | OFF / OFF / OFF |
| TargetKbps / MaxKbps | 3427 / 3428 |
| BufferSizeInKB / InitialDelayInKB | 857 / 428 |

这只证明该次 H.264 串流的实际参数，不能反填为早先 joint05 或其他 codec 的实际读回。
不能再把“打开 VDENC／LowDelayBRC”作为尚未执行的修复。
更早的 HEVC／AV1 日志属于启动探测，没有与实际 H.264 串流混用。

第一次采集因旧守卫只检查原始日志末尾而误报未创建硬件 H.264，测试结果仍保留 FAIL。
恢复服务时日志超过 5 MB，被 supervisor 轮换到了 `sunshine-service.previous.log`；
只读 current 文件也找不到刚才的会话。通过已恢复 lease 的进程身份、试验配置哈希、
会话起止 UTC 和 previous 文件哈希，独立恢复参数证据，没有再次启动串流。
原配置哈希仍为 `A82558ADB6C7F94A248BAE57DBA9FCBB402F8A55EDE84D494647732DDA9B4224`，
六个配对、software 默认及原用户参数均保留，恢复任务已移除。

修复 `tools/diagnose-host-startup.ps1`：仅对确认属于统一服务的日志按 previous → current 收集，
先过滤再限制最多 8000 条证据，避免无关 D3D Debug 输出挤掉编码器和阶段锚点。
非自有服务仍使用原引擎日志。PowerShell 7.6.6 / Windows PowerShell 5.1 均通过；
旧 449eb3d 在“9000 条无关输出”和“恢复后日志轮换”两个反例中均漏证据，修复后均保留。
真实服务只读回采也重新找到该次 H.264 创建锚点及四条 codec 阶段记录。

完整客户端记录的首帧提交为 **4871.062 ms**，脚本轮询发现首帧为 5254 ms，API 接受为 8 ms，
端点不同。客户端准备 1514.554 ms、协议握手 151.850 ms、握手完成至首包 3151.370 ms，
首包至解码输出 10.496 ms、随后渲染提交 1.926 ms。
服务端同一会话的本机阶段为输出探测设备创建 808.297 ms、帧池初始化 163.695 ms、
codec open 357.038 ms、first_encode 391.980 ms。
父子阶段不能相加；跨机 UTC 没有精确校准，不能强行拼成网络单向延迟。
本次有详细日志、服务预热及静态桌面短段，不是稳定 FPS 或受控启动优化对照。

joint05 队列严格解码共 2887110 条选定事件，零字段解码错误、零事件／缓冲丢失。
第 428 帧前后各 10 ms 的 17702 条事件，与同次资源／CPU 证据对应。
完成线程 50464 的 context 创建、结束及同 PID 提交均核实，NodeOrdinal 为 1。
该 context 的序列 582 在本帧范围内形成以下边界：

| 相对 Sync 开始的时间 | 实际事件 |
| ---: | --- |
| 238.4570 ms | 最后一次已匹配资源分页包完成 |
| 238.7392 ms | 同一完成线程提交队列包 582 |
| 238.8149 ms | 对应 DMA Start |
| 250.3850 ms | 对应 DMA Stop |
| 250.4025 ms | 对应 Queue Stop |
| 250.5240 ms | SyncOperation 返回 |

context、序列、queue packet、DMA submission/completion ID 均对应，未发生 preempt/timeout。
提交到 DMA Start 仅 0.0757 ms，DMA 调度跨度 11.5701 ms。
这将本例大头进一步落实到提交前的资源准备；DMA 调度跨度仍不是 GPU 指令时间，
也没有冒充硬件内部帧编号。节点可能兼有编码／解码能力，不能把计数器标签解释成实际工作类型，见
[Microsoft 节点类型规则](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/d3dkmdt/ne-d3dkmdt-dxgk_engine_type)。

同一帧的系统分页线程 1416 匹配到 635 段唯一的原始同 CPU／TID 切入栈。
重新核对本轮 PE、checksum、size、SHA256，并以匹配的公共 PDB
`6453F1270496E88F8EE627B38147192C1` 新解析全部 112 个实际 dxgmms2 地址。
该帧中分页线程在 `WaitDmaBufferNotBusy` 栈上的 off-CPU 重合为 74.675 ms，
在等待所有分页引擎的栈上为 25.664 ms。
最长 4 KiB 请求提交到选中为 144.585 ms，其中上述两类 off-CPU 重合为 37.991 / 10.456 ms，
ready 子集约 5.461 ms；其余约 95.899 ms 属于该线程运行或尚未解释的部分，
不能全部称为等待硬件、CPU 不足或其他业务造成。请求资格／fence 依赖继续定位，
未宣称唯一根因或已无法优化。

新增私有证据：`qsv-paramreadback01-retained-actual-parameters02-20261025.json`、
`qsv-paramreadback01-startup-analysis-20261025.json`、
`encoder-filtered-queue-qsvjoint05-20261025/slowframe428-worker-queue-proof.json`、
`encoder-etw-qsvjoint05-20261025/slowframe428-paging-worker-proof-v2.json`。
保守 v1 与完整公共符号的 v2 均保留，最终采用 v2 分类；稳定 1080p30 仍未验收。
