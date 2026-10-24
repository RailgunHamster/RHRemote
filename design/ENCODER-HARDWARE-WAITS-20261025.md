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
尚待取得发生等待的 Intel 缓冲区创建／资源类型、分页依赖 fence 及竞争来源，
再决定是应用资源使用、驱动路径还是硬件映射配置的可优化点；未声称已解释所有长尾或达到不可优化。
