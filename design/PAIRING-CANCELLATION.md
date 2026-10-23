# 配对取消与立即重试

2026-10-23，开发候选。Windows 局域网及独立中继的取消已验证，两台远程主机已部署；Android 和本机主机服务仍有验收项。

Windows 原先取消配对只终止原生进程。Sunshine 的未完成会话最多保留五分钟，而 Moonlight 在 Windows / Android 中均固定使用 uniqueid=0123456789ABCDEF，因此旧会话会阻止立即重试，返回 409。现有 /unpair 请求不是 Sunshine 的取消接口，不能依赖它清理请求。

RHRemote 每次配对使用独立的随机十六进制 uniqueid。它只用于这次配对的 HTTP 会话，之后的串流保留原有协议行为。旧 Sunshine 可以忽略扩展，但独立编号仍避免同一编号占用阻止新请求。

客户端只向 serverinfo 明确包含 RHPairingCancel=1 的主机发送此扩展。旧主机仅使用独立随机编号来避免重试冲突，不发送不认识的取消命令；原有过期清理继续有效。

支持的 Sunshine 扩展使用 GET /pair，参数为 uniqueid、phrase=rhcancel、rhcancel_salt、rhcancel_sig。取消签名为客户端配对证书对应私钥生成的 SHA-256 签名，编码为十六进制；被签名的 UTF-8 文本严格如下，没有末尾换行：

```text
RHREMOTE-PAIR-CANCEL-1
<uniqueid>
<该次 getservercert 请求的 salt 十六进制原文>
```

uniqueid 只允许不超过 128 字符的非空十六进制文本，salt 必须为 32 字符，签名长度与字符集在验签前受限。服务端在同一会话锁内核对原始 salt，并使用该未完成会话的客户端证书验签。成功后仅移除此会话，向仍等待 PIN 的请求返回取消状态。已输入 PIN 但握手未完成的会话也可以由签名者取消；完成配对后会话已移除，此请求不能撤销任何已有授权。

无效、过期、错编号、错 salt、错密钥或重放到新 salt 的请求返回 403，不清理其他会话。成功返回 XML status_code=200、cancelled=1、paired=0。管理网页的操作员取消接口继续保留原有语义。

Windows 在发送初始请求前将该次签名原子写入受控文件，Rust 父进程可在原生进程退出后发送取消，无需导出或读取私钥。取消与失败后的清理在后台执行，不阻塞下一次配对；下一次使用新的编号，因此延迟的旧取消不会影响新请求。客户端不因清理失败删除已有配对、重启服务或调用管理网页。

服务端回归包括真实 HTTP 挂起请求的取消与立即重试、其他客户端隔离、非法证明、PIN 后阶段和旧证明重放。下面区分本地回归与实际验证，不能把源代码和测试用例存在当作验收通过。

Windows 原生引擎及 Rust UI 已构建；35 项 Windows UI 库测试通过，包括端点与编号绑定、旧证明隔离、端口映射、重定向拒绝和旧主机兼容。Android 的 APK / 测试 APK 构建及 37 项 JVM 测试通过；新增按次请求所有权，取消后不能再提交迟到的 HTTP 请求，旧后台线程也不能覆盖新配对结果。Android 只进行了本地构建，没有操作 OPPO。

清理为有界后台操作：网络已经中断、旧主机未声明支持或旧隧道已退出时，可能只能等待服务端过期清理；新尝试的独立编号仍避免共享旧会话。

## Windows 实际验收

服务端 CI 39908691983 对源码 7332952e926092604674c0e978ebc72b7a2d3923 的构建通过，40 项配对及鉴权回归全部通过，包含实际生产 HTTP 处理器的挂起请求、签名取消、能力声明、立即重试及无效证明不得落入破坏性错误处理。构建产物逐文件校验后部署到 winserver 和 claw，两个服务均声明 RHPairingCancel=1，并连接现有 HTTPS 中继。

测试使用新建、受保护的 Windows 用户数据目录。预置空 INI 阻止兼容导入器复制真实客户端的凭据，原生引擎自行生成测试身份，私钥只由原生引擎保管和使用；测试没有提交 PIN。每台主机连续建立两次未完成配对，第一次取消后立即开始第二次，不等旧后台清理完成。pair.cancel API 返回该次 pairId，测试模式的按次报告记录服务端 cancelled=1 的实际确认，测试后应用正常退出。

| 主机 | 两次连接动作受理 | 两次取消动作返回 | 两次后台清理确认 |
| --- | --- | --- | --- |
| winserver | 28 / 25 ms | 33 / 34 ms | 5 / 5 ms |
| claw | 27 / 30 ms | 34 / 33 ms | 6 / 6 ms |

四次尝试的编号不同，服务端均确认清理，立即重试未出现 409。安装前后及测试后的 Sunshine 配对状态文件哈希相同，UUID、原配对和配置保留。既有 Windows 身份随后经 HTTPS 中继连接两台主机收到真实画面，统计均正常结束且静音；原连接偏好已恢复。取消耗时不代表首帧时间，本轮视频首帧仍为约 5.80 / 2.56 秒。

后台清理报告仅在 RHREMOTE_TEST_MODE=1 时写入按次文件，包含编号、结果和耗时，不包含 PIN、签名或私钥；正常使用不会累积这些报告。原始视频统计分析完成后按既有流程清理，必要报告保留用于启动耗时分析。

## Windows 独立中继取消

继续使用正常产品动作和现有已配对身份，强制走正式 HTTPS 中继，未另起诊断 Sunshine 代理或复制任何私钥。旧原生 CLI 在发现已配对时会直接退出，因此新增仅供回归的 `device.pair {testPairPending: true}`：Rust 只在 RHREMOTE_TEST_MODE=1 时接受；原生端还同时要求配对操作及显式回归标记。它只允许已配对客户端进入自己的新配对请求，不改变服务端 PIN、配对证书、签名验证或传输授权。测试没有向服务端提交 PIN，没有新增或移除可信客户端。

| 主机 | 两次配对动作受理 | 两次取消动作返回 | 两次服务端清理确认 |
| --- | --- | --- | --- |
| winserver | 902 / 81 ms | 43 / 37 ms | 116 / 109 ms |
| claw | 427 / 83 ms | 51 / 38 ms | 110 / 108 ms |

两次尝试使用不同编号，第一次取消后未等待清理报告便发起下一次。四次实际请求均经 relay 路径，服务端确认 cancelled=1，立即重试没有 409。原配对文件哈希与之前基线相同，仍为 6 / 4 个可信客户端。随后恢复正常运行模式，实测相同回归参数被 HTTP 422 拒绝；正常模式不会因这个参数启动新的原生配对请求。

此项覆盖已有授权的中继路径上的挂起请求、签名取消和立即重试，不证明全新、未授权客户端能直接经中继完成首次配对。Android 仍未实机验证。

## 代理地址与主机身份

代码审查发现不同主机都可能使用本地传输地址 127.77.0.2，原生缓存按地址匹配时不能仅凭该地址选择主机。Windows 配对现在传递选定 UUID 和地址；原生 ComputerSeeker 在构造及后续匹配中先排除其他 UUID。原有 TLS 和服务端 UUID 校验继续保留，未指定目标的上游界面行为保持原样。

36 项 Windows UI 库回归通过；原生引擎与 Rust UI 的 release 构建成功。恢复正常模式后，winserver、claw 均经 HTTPS 中继收到实际视频，统计正常结束、静音且偏好已恢复；首帧分别为 8241.273 / 3072.239 ms，不能据此宣布启动耗时已解决。原生引擎 SHA256 为 7A59114BF4D317954BF96D1F4787B77A6FA425D7963F4A7C4E8401DEF5D34D3D，Rust UI 为 3256CC352E9E3707A1B157AF7F2893999CBA97D9A1C13035C9AC458E0C9DDF0C。

新增证据：D:\RHRemoteTools\pair-cancel-relay-acceptance-20261023.json、pair-pending-production-guard-20261023.json、winserver-pair-relay-state-after-20261023.json、claw-pair-relay-state-after-20261023.json 和 windows-pair-cancel-target-bound-both-tls-relay-validation-20261023.json。

证据位于本机 D:\RHRemoteTools\pair-cancel-acceptance-20261023\result.json、install-winserver-pair-cancel-7332952e-20261023-result.json、install-claw-pair-cancel-7332952e-20261023-result.json，以及 windows-pair-cancel-deployed-both-tls-relay-validation-20261023.json。Android 安装与实机操作保持暂停；本轮未替换 RailgunHamster 的主机服务，也未发布新稳定版。
