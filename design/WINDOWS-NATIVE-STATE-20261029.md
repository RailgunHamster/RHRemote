# Windows 首帧状态分段采集

后续 preview.92 对“已确认首帧后的回退”作了实际行为修复，见[修复与边界](WINDOWS-FIRST-FRAME-STATE-20261029.md)；本文保留 preview.91 的诊断基线。

Windows `0.2.11-preview.91` 补齐原生 localhost 控制接口与 Rust UI 读取的分段元数据。没有改变首帧判定、状态文件回退、自动重连、视频或声音逻辑；[preview.90 中已有 577 次渲染而 UI 首帧超时的失败](WINDOWS-NATIVE-CONTROL-20261029.md)仍未解决。

## 代码与记录

原来的 `.call().ok().and_then(into_json)` 会丢失请求失败类别。现在共享读取函数 `native_control::read_state` 保留结果和安全的元数据，供 UI 轮询和退出时的身份核对共同使用：

- `result` 区分 `decoded`、`http_error`、`transport_error`、`invalid_json`。
- `stage` 区分 `headers`（建立连接、发送请求及读取响应头）、`body`（读取正文及解析 JSON）、`complete`。
- 记录 HTTP 状态、ureq 错误枚举、I/O 错误枚举、可用的系统错误码、成功响应头耗时及总耗时。没有错误正文、完整 URL、请求头或响应内容。
- `native_state_read` 同时记录请求端口、预期／报告 PID、子进程存活、原始首帧／串流布尔值及 `http`／`file_or_empty` 来源。后者仍不细分“文件缺失”和“文件无法解析”。读取结果不替代原有 PID 身份校验。
- `/v1/state` 的 `statistics.nativeControl` 提供相同的当前观察；原生退出记录增加 `control.lastStateRequest`。这些状态不写入共享设备配置。

原生 `control_listening`／`control_listen_failed` 记录监听结果；`control_accepted` 记录是否已有排队连接和工作线程数量。每条连接具有本进程内的编号，随后记录开始、请求头读取、处理开始／结束及写回结束；写回包含排队字节数、剩余字节数和 socket 错误枚举。端点只记 `state`、`disconnect`、`other`，不记录原始路径或参数。原生记录沿用异步统计队列，Rust 记录沿用既有统计开关。

状态请求仍为连接 100 ms、读取／写入 400 ms、总计 500 ms；退出身份核对仍为连接 100 ms、总计 250 ms。没有增加重试或放宽时限来掩盖错误。

## 测试与构建修正

新增 6 个真实 localhost TCP 用例，覆盖成功 JSON、HTTP 403、错误 JSON、响应头停顿、正文停顿和关闭端口的实际错误；同时断言元数据不包含令牌、正文片段、认证头或 URL。

首轮关闭端口用例错误地假设 Windows 一定立即报告 `ConnectionRefused`，实际在 100 ms 和另一次 2 秒预算下均得到 `TimedOut`。诊断代码保留的是实际枚举；测试改为核对实际结果属于连接拒绝或连接超时，不把超时重新标作拒绝连接。原失败日志保留。最终核心 **129**、Windows UI **193** 项通过，另有 **1** 项既有 UIA 用例忽略。

代码复核还发现原生诊断中使用非 const `QJsonObject::operator[]` 可能插入字段，已改为只读 `value()`。此前的默认目录候选包没有用于实际连接，标为撤回；最终原生构建及帧时钟、配对持久化、二进制配对密钥、并发连接回归全部通过。

## 副屏实际采集

证据目录为 `D:\RHRemoteTools\ui-native-state91-review-20261029`。6 个独立运行均在实体 `DISPLAY2` 连接 winserver 的既有物理源屏幕，1080p30／25 Mbps／H.264／硬解／静音。临时身份和源测试窗逐轮创建、清理；没有操作 OPPO 或使用系统截图、系统输入。

前 5 轮 `observed-dark01`～`05` 使用明确记录来源的观察组合：新原生引擎与此前已编译的 preview.91 UI；后续 Rust 变更仅在测试用例中。该组合 UI SHA-256 为 `943CAA30613788A5E3E8498669551B0163AE9B067BDD0DA8F52591D67132A9A5`。最后 `observed-light01` 直接使用最终候选 ZIP 解包后的两个二进制，其 UI 哈希不同，因此没有将前 5 轮冒充最终安装包的整体验收。

全部 6 轮通过 40 次实际并发状态请求、10 个悬浮键回执、隐藏／开关及正常断开，合计 **240 次并发请求、60 个按键回执**；不是长期会话验收。

| 轮次 | UI 状态读取数／失败数 | 首次原生渲染提交到首次读取正确首帧 | 取走已排队连接数 |
|---|---:|---:|---:|
| observed-dark01 | 45／1 | 190.563 ms | 0 |
| observed-dark02 | 52／1 | 181.872 ms | 2 |
| observed-dark03 | 47／1 | 100.126 ms | 1 |
| observed-dark04 | 44／0 | 195.531 ms | 0 |
| observed-dark05 | 44／1 | 240.681 ms | 1 |
| observed-light01，最终候选包 | 50／1 | 11.985 ms | 2 |

合计 **282 次 UI 读取、5 次失败**。这 5 次都是 `ConnectionFailed / TimedOut`，约 105～108 ms；根据同机时间戳，它们全部在原生服务监听完成前已经结束，距离首次渲染仍有约 1.8～3.4 秒。后续读取和所有退出身份核对均恢复成功，没有把这些初始失败认定为视频故障。

表中时间只比较 CPU 渲染提交返回和状态读取，不代表显示器扫描、UI 重绘完成或整个连接的首帧时间。

实际记录到 **6** 条已排队连接被取走，证明 preview.90 的修复分支在真实原生会话中执行。没有工作线程上限拒绝或写回剩余缓冲；全部 native 文件具有统计结束记录，未报告丢事件或达到体积上限。正常响应最大约 2 KiB。这些证据不能解释前一轮的严重失败，也不构成视频性能或首次连接总时长的前后对照。

新增观察器持续核对端口、正确进程、直接 HTTP、UI 读取和实际渲染计数，严重不一致才对本次进程非侵入抓栈。本轮没有再次复现严重首帧不一致，因此抓栈失败分支仍未获得本轮真实失败覆盖；不能认定旧问题消失。

## 最终候选与清理

最终候选目录：`dist/release-candidates/0.2.11-preview.91-native-state-verified`。Portable SHA-256 为 `FFD6506B79F2AE4ACC30A45A8B24ED0E2E81B73D012A80BFB75AA33E3A964C49`；Setup 为 `F22A039EC399EF1C1B1B4F42EB23B7446CBEACD4EDE5D3347D31215FF78A7200`。UI 为 `5F45C2D7B79CC4669D191624C0FF112FECA4DF68E4DA492770A1E1F960092FE3`，原生引擎为 `6F1FEA50E30FCA9D5F02518E0516EA8F240E51C20D8F10A1E49649DC18107CB0`。ZIP 271 个条目，未发现私有配置。没有发布或替换日常客户端。

`final-cleanup91.json` 确认 6 轮临时 UI／原生进程、源测试窗和任务均已结束，修饰键释放。日常 PID 43596、正常 preferences 和 winserver 原 8 个配对状态哈希不变；远端追加核对没有残留测试窗，Sunshine 与 RHVideo 仍运行。12 份 JSONL 经内存比较，未发现控制令牌，未输出令牌内容。

下一步仍需在严重状态丢失时对齐客户端错误类别、原生监听／处理阶段和正确 PID，另外补齐状态文件与同次首帧里程碑的回退语义；不能通过放宽超时、跳过身份校验或把旧 PID 状态当作成功来消除表面错误。
