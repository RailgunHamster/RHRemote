# Windows 客户端逐帧时间的精度边界

2026-10-24，当前正式 Windows 0.2.7 的原生二进制为 C2A90D11...E31E6。
收包和组帧时间的精度问题已确认，实际高精度采集修复尚未实现。

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

## 待完成的修复与回归

- 在实际收包和组帧位置读取高精度单调时钟，随对应帧经过 FEC、解包和解码入口传递。
- 保留普通协议超时与网络计时行为，不能只换全局毫秒时钟或继续回推另一个估计来冒充实际事件。
- 在记录中声明时钟域、精度／校准不确定性，缺少时间的旧端保持未知。
- 覆盖乱序、FEC 恢复、缺失时间、关闭统计、实际解码交接和文件结束；完整重建原生库及 Windows 引擎。
- 分析器只在新的明确测量契约和对应实际数据下标记高精度，再进行真实媒体回归、保留配置更新和正式发布。

这项保持未完成，不用测试意图、单个短测或旧报告的数字代替实现和验收。
