# Windows 客户端逐帧时间的精度边界

2026-10-24，当前正式 Windows 0.2.7 的原生二进制为 C2A90D11...E31E6。
旧收包和组帧时间的精度问题已确认，直接采样已在新原生候选中实现并完成短轮次真实串流。
正式客户端的安装包仍为旧原生版本；新候选的部署、完整产品回归及正式发布尚待完成。

## 已确认的代码路径

原生 FFmpeg submitDecodeUnit 先取 RhStatistics::now 和 LiGetMillis，
再用两者与 receiveTimeMs / enqueueTimeMs 的差值回推 packet_first / frame_assembled。
当前 CML 的 LiGetMillis 直接调用 PltGetMillis，Windows 实现为 GetTickCount64。
微软说明该函数通常只有 [10–16 ms 分辨率](https://learn.microsoft.com/en-us/windows/win32/api/sysinfoapi/nf-sysinfoapi-gettickcount64)。
因此，旧记录中由这两个字段计算的 reassembly、decoder_queue 和相应阶段间隔都是估计，
不能把某个约 15 ms 的值直接当作真实线程等待。回推锚点分开取样时失去调度还会引入额外误差。

decode_submit、decode_submit_return、decode_output、render_submit、render_return
使用直接的高精度 RhStatistics::now；服务端 capture work 也使用独立的主机单调时钟。
这些调用跨度、实际渲染事件间隔，以及服务端 Map 572 ms 的证据不因上述毫秒字段而失效。
首帧 render_return 也不是通过这两个毫秒字段得到的。

窗口分析现在明确将 receive/assembly 高精度标为未证明，保留原始估计值并说明边界。
没有改写旧 raw 时间，或把记录完整性与测量精度混在一起。

主报告分析器和 HTML 页面也已补上相同边界：Windows 旧格式的两段首帧收包／组帧间隔
显示“估计”，并说明逐帧 reassembly、decoder_queue、client_pipeline 的相关精度未证明。
实际首帧总耗时继续取直接 connect_action → render_return，不改成估计，也不将 Android
无依据地归入 Windows 的旧时钟路径。未制造 recorder warning，原始统计清理规则保持。

20 项客户端分析回归及 11 项窗口分析通过。对已有有效源 LAN 报告保留 raw 后重新分析，
首帧仍为 3169.647 ms；隔离无头 Chrome 实际加载该 HTML，核对估计标签及直接解码／渲染
标签的区别，页面无脚本错误。证据为私有 `statistics-clock-limit-dom-20261024.html`。
这些报告改动尚未作为新客户端正式发布；新生产事件的验证见下文。

## 直接采样的实现和实测

`tools/patch-frame-clock.py` 在固定 Windows 上游生成源中接入本项目维护的
`RhFrameClock.c/h`。统计开启时，在 LiStartConnection 创建线程之前配置记录器时钟回调；
统计关闭时回调为空，不读取高精度时钟。调用者只允许在线程停止时配置回调。
普通 PltGetMillis、receiveTimeMs、enqueueTimeMs、超时和原生内置平均统计均保持。

UDP 接收调用返回后立即取微秒时间，随真正接受的数据包进入 FEC 队列。
乱序和合成的恢复包继承当前 FEC 块首个接受包的时间；解包器保留该帧开始时的采样值，
在完整解码单元就绪、入队之前再采样一次。两个值随 DECODE_UNIT 到解码入口，
直接写入 packet_first / frame_assembled；不再用解码线程当前时间回推。
它们和直接解码／渲染事件使用同一个 RhStatistics 时钟，不需要跨域校准。
事件行中的 thread 是上报的解码线程；原始采样线程 ID 尚未随元数据传递，不能据此推断
收包线程调度。采样点是软件调用边界，不能解释为网卡到包或物理扫描输出。

每段记录声明 `rh-cml-frame-clock-v1`、时钟域、微秒单位和两个实际采样位置；每帧携带相同契约。
分析器要求声明和所有收包／组帧点均匹配、成对且时间有效，才移除“估计”标签。
混合旧端、缺失或反向时间保持未证明；新端缺失时间显式记录 frame_clock_missing，
保留诊断数据，不制造替代估计。Android 的现有微秒链路不受此 Windows 适配影响。

完整 MSVC / Qt 原生构建通过，新候选 SHA-256 为
`6b7ed0380956d960eaa17180dfc2093e85e00c7f9d7f94af2c98e691c6880304`。
独立 C 测试重新编译真实 CML RtpVideoQueue 和 Reed-Solomon，验证开启／关闭、
乱序、缺失时间、校验包先到且真正丢失一个数据包的 FEC 恢复、完整恢复载荷及连续帧状态。
该测试的控制／日志／解包出口为桩，不能代替实际解码；真实串流随后覆盖了后续交接。
23 项客户端分析与 12 项窗口分析回归通过。

新原生进程局域网连接 winserver，首帧后约 30.24 秒，记录 879 帧，所有帧直接计时契约通过，
文件正常结束、没有统计丢失或负时长。组帧平均 0.0689 ms、最大 0.335 ms；
解码前排队平均 0.0562 ms、最大 0.157 ms。这是本轮短测，不能证明动态画面稳定 30 FPS，
也不能将不同场景旧数据中的 15 ms 全部归因于粗时钟。
未测 Rust UI 操作至首帧；进程启动到观察到首帧约 3.82 秒不等同于完整连接耗时。
测试静音、未请求显示准备，未改变普通客户端的配对 INI，独立进程正常结束，OPPO 未操作。
证据在私有 `direct-frame-clock-live-20261024.json`，原始 trace 为
`1792822202654-ea151b2f185d`，保留供后续对照。

## 记录器本身的独立短测

用当前真实 rhstatistics.h 和 Qt 6.7.2 / MSVC Release 编译独立测试程序，
两个线程分别模拟 30 FPS 的接收／解码事件和渲染事件，每个模式 30 秒、6300 次调用。
测量包含参数构造、事件序列化、入队及可能发生的操作系统抢占；不是实际视频性能。

| 模式 | P99 调用耗时 | 最大值 | 超过 1 ms |
|---|---:|---:|---:|
| 统计关闭 | 15.2 µs | 1183.4 µs | 1 |
| 统计开启 | 47.0 µs | 192.0 µs | 0 |

开启后的真实输出 6302 行，含 begin 和一个 footer；lost_events=0，未封顶。
本轮没有重现 15 ms 的记录器调用，不能据此排除实际媒体压力下的锁等待／线程抢占，
也没有凭这个轻负载结果替换客户端热路径或宣称所有场景开销小于 1 ms。
证据及编译源在 `D:\RHRemoteTools\statistics-overhead-20261024`；
原始元数据在本机 `%LOCALAPPDATA%\RHRemote\diagnostics\statistics-overhead-20261024`。

## 后续验收

- 将已验证候选接入普通客户端，验证真实连接动作、分析页面与原有配置保留。
- 使用新时钟继续动态影片、压力及丢包场景，区分真实排队、网络与主机捕获等待。
- 完成必要的完整产品回归、安装包更新及双站正式发布；当前短测不代替长期性能验收。

这项保持未完成，不用测试意图、单个短测或旧报告的数字代替实现和验收。
