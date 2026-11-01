# 原生设备检查的状态边界与下一步

更新：preview.151 已用实际原生引擎和隔离回环 HTTP／mTLS 服务完成 13 项协议对照，并修复 HTTP 401 被误报为未确认；[原因、证据与剩余范围](WINDOWS-NATIVE-PROTOCOL-20261031.md)。以下初始源码推断保留，不代表这些场景仍只有静态分析。

2026-10-31 初始源码核对。随后 preview.145 已加入结构化报告、Controller 与控件分类，当前实现与尚未通过的实际协议范围见[记录](WINDOWS-DEVICE-CHECK-RESULTS-20261031.md)；以下保留初始发现和验证要求。当前 Rust UI 通过原生 `list` 子进程检查设备；UI 的待确认状态已能保留本机信任，但仍缺少具体失败原因。

## 当前代码证据

- `D:\RHRemoteTools\moonlight-qt-v6.1-source\app\cli\listapps.cpp` 的 `ComputerFound`：已配对时，`RHREMOTE_PROBE_OPERATION` 快速路径先调用 `RhHost::request(..., "/v1/network")` 写入元数据，再退出 0。这发生在实际应用列表获取之前；因此以前 Rust 注释中的“认证应用列表请求成功”不是这个快速路径的准确描述。
- 同文件：未配对分支和查找超时都退出 -1，Rust 无法只靠退出码区分二者。
- `app/backend/nvcomputer.cpp` 的 `pairState` 来自 serverinfo 的 `PairStatus`。
- `app/backend/computermanager.cpp::tryPollComputer` 使用固定目标地址，检查返回 UUID，然后应用 `NvHTTP::getServerInfo(..., true)` 的结果。
- `app/backend/nvhttp.cpp::getServerInfo`：有固定证书时优先 HTTPS；收到 401 时会回退到 HTTP 的 serverinfo。没有固定证书时先 HTTP，发现端口后如有证书再 HTTPS。单凭最终的 `PS_NOT_PAIRED` 仍丢失了“原本没有证书”和“当前 HTTPS 认证失败”的区别。
- `client/crates/rhremote-ui/src/platform.rs::probe` 给原生子进程五秒期限。如果 serverinfo 已返回已配对，但后续辅助网络元数据请求超时，当前子进程可能在输出成功前被结束，最终只能保留为待确认。**这是从代码顺序得到的可能路径，尚未取得对应真实耗时样本。**

## 后续实现要求

在原有 serverinfo 请求上记录结构化结果，携带本次随机检查编号、目标 UUID、是否有本地固定证书、实际 HTTP／HTTPS 来源、认证拒绝与检查阶段及耗时。不能把 401 回退后的 HTTP 结果当作新的已认证事实，也不能新增一次完整网络探测来取得同一事实。

将配对事实与可选网络元数据分开，避免辅助接口延迟掩盖已完成的身份检查；保留网络元数据的功能、UUID／证书检查和取消归属。Rust 需要拒绝错误编号／UUID、残缺或过期报告，并把未配对、已有本机配对但认证失败、线路失败和取消分别呈现在设备状态中。

验证必须覆盖 HTTP-only 的首次设备、HTTPS 成功、401 回退、证书／UUID 不符、慢辅助接口、取消与旧结果，并取得实际原生引擎样本；不得用普通非零退出或模拟成功替代协议证据。这些工作继续列在 [TODO](../TODO.md)，不计为 preview.144 已完成范围。
