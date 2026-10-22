# 外网连接与诊断

Windows 和 Android 保留 Sunshine / Moonlight 串流协议，使用已有 Tailscale 网络提供寻址、NAT 穿透和加密中继。RHRemote 负责选择设备地址、校验 UUID、按网络设置连接参数，并显示失败阶段。无需把 Sunshine 管理页面转发到公网。

## 地址与配置

设备资料保留 `address` 首选地址，增加可选的 `addresses` 备用地址数组，总计最多 8 个。接受默认端口上的 IP（含 IPv6）或主机名，不接受 URL、账号、路径或自定义端口。地址、别名、主机鼠标补偿和排序随 WebDAV 同步。自身设备可以同步和编辑，但不能连接自身。

Windows 可以从本机 Tailscale 的节点列表发现名称完全匹配的候选地址，发现结果也必须通过 Sunshine UUID 检查；候选地址不会建立新的信任。连接仍由原生客户端校验已有配对证书。Android 使用同步的地址列表，未配对时仍走原有 PIN 配对流程。

每个客户端 × 设备独立保存：

| 设置 | 默认 | 行为 |
| --- | --- | --- |
| `networkMode` | `auto` | `auto` / `lan` / `tailscale` / `internet`；限制本次使用的地址类型 |
| `packetSizeAuto` | `true` | 自动识别局域网、VPN；Tailscale 和外网使用 1024 字节 |
| `packetSize` | `1392` | 关闭自动分包后的手动值；底层协议仍可能限制外网分包 |
| `remoteBitrateMbps` | `0` | 0 沿用画面码率，否则限制外网连接码率；不覆盖原画质配置 |

地址并行探测，优先局域网，其次 Tailscale 和其他地址。存在可用备用地址时，只给更优先的地址短暂等待，不逐一等待每个超时。Windows 原生会话仅监测本次选定地址，避免又转回失效的局域网地址。初始连接和已有会话的自动恢复都会重新选址。

新增字段扩展已有同步 schema v2；旧客户端严格校验未知字段，因此 Windows 和 Android 均需更新到 0.2.3 后再同步新设置。配对密钥、同步密码、诊断结果和当前路径不参与同步。

## 直连与中继

协调/发现与媒体中继分开：打洞成功，实际数据走直连；打洞失败，Tailscale 使用可用 Peer Relay / DERP 持续转发加密数据。只做发现的服务器不能替代真正的流量中继。RHRemote 不重新实现一套未经验证的 NAT 穿透协议。

部署方可以复用已有 DERP，或按 [Tailscale 官方说明](https://tailscale.com/docs/features/peer-relay) 配置 Peer Relay；后者需要额外的 tailnet grant，不能只在主机开启 UDP 端口就宣称已启用。现有 DERP 服务不需要为本功能重装。

`tailscale status` 的 `Relay` 是 home DERP 信息，不用它判断本次连接正在中继。Windows 诊断通过 `tailscale ping` 返回的实际路径标注直连 / DERP；仅表示诊断时刻，运行期间可能变化。Android 无法访问另一个应用的 Tailscale 私有控制接口，因此路径未知时如实显示未知。

## API

所有新按钮复用 `/v1/action`：

```json
{"id":"device.network.configure","args":{"uuid":"主机 UUID","address":"主机名或局域网 IP","addresses":["Tailscale IP"]}}
{"id":"device.network.diagnose","args":{"uuid":"主机 UUID"}}
{"id":"network.state","args":{}}
{"id":"settings.tab","args":{"tab":"network"}}
{"id":"settings.set","args":{"key":"networkMode","value":"tailscale"}}
```

`device.network.diagnose` 可传入一次性的 `address` / `addresses`，用于验证备用地址，无需修改设备保存的地址。诊断使用设置页当前草稿的网络选择；正常连接使用已保存的参数。Windows 运行主机端诊断前需断开串流。

统一主机服务新增接口，均要求已有 Sunshine 配对证书进行双向 TLS：

- `GET /v1/network`：返回本机 Tailscale 地址与运行状态，不返回令牌、节点私钥或完整状态文件。
- `GET /v1/network/probe`：签发绑定来源 IP、有效 8 秒、单次使用的 UDP 探测票据。UDP 47995 仅回复匹配票据，回复长度不大于请求；无票据报文不响应。最多保留 32 个待用票据。

探测结果分别记录 serverinfo UUID、TCP 47984/47989/47995/48010、诊断 UDP 双向结果。诊断 UDP 可达不等于视频 UDP 端口已通过，收到串流首帧才是媒体验证。失败不把设备删除，也不重建配对证书。

## 主机规则与部署

`tools/configure-network.ps1` 默认只输出计划，`-Apply` 在管理员权限下安装规则。Tailscale 规则同时限制远端地址范围和 Tailscale 隧道网卡，仅放行串流和配对后辅助接口；47990 管理页面不在新增规则中。局域网规则保留。

统一安装器将新增规则计入回滚清单，安装失败恢复服务并移除本次新增规则。先安装 Tailscale，再安装/重新应用主机网络规则。没有 Tailscale 网卡的电脑仍可使用局域网。

## 验证边界

开发验证包括 Windows 经 Tailscale 地址连接 winserver、失效首选地址回退、鉴权 UDP 双向探测、Android 编译及 JVM 测试，以及已有公网 DERP 的独立临时节点转发测试。独立 DERP 测试不采集屏幕、声音或现有节点密钥。

局域网底层上的 Tailscale 串流不能代替蜂窝网络验收。OPPO 实机操作依用户要求暂停；恢复后应验证蜂窝网络、分应用 VPN、Wi-Fi/蜂窝切换和实际远程输入。免安装 Tailscale 的独立组网方案不属于本版已实现能力，但仍是外网 P0 的未完成需求。

## 稳定版 0.2.3 的限制与开发目标

当前 `internet` 模式仅选择公网地址并调整分包和码率，不提供设备注册、打洞或独立中继。没有可达公网端点且不使用 Tailscale 时，现有版本无法自动连接。tc 上的现有 DERP 不会自动接管普通 Sunshine / Moonlight 的 TCP、UDP 流量。

拟在 tc 上另设 RHRemote 协调 / 中继服务，与已有 DERP 共存。统一主机服务和客户端主动向公网服务建立连接，以设备身份和已有配对关系鉴权；协调打洞成功后走直连，失败后由中继持续转发端到端加密的数据。协调发现和实际媒体转发必须分别实现。客户端需要传输适配，不能只增加一个服务器地址就声称支持中继；服务端转发不解码、不转码。

服务端和 Windows 传输组件沿用 Rust，Android UI 保留原生 View + Java；具体传输实现、端口、身份协议与密钥管理需在开发时确定并验证，不将本节作为已部署能力。验收必须关闭两端 Tailscale，并覆盖跨网络直连、强制中继及全部必要 TCP / UDP 通道。

### 2026-10-23 开发进度（尚未发布）

源码增加共享 Rust QUIC 传输组件，使用固定端点公钥建立端到端加密连接，打洞失败后经自建 iroh relay 转发；不创建 VPN 适配器。Android 通过 JNI 使用该组件，界面仍为原生 View + Java。`rhr:<64 位十六进制端点公钥>` 是可同步的公开地址，私钥保存在对应安装的私有数据目录，不能参与同步或安装包。

主机统一服务后台启动传输，不阻塞局域网就绪。未授权的隧道只可访问服务器信息、原有 Sunshine 配对 HTTPS 和要求配对证书的辅助接口；媒体 TCP / UDP 需先由已有 Sunshine 配对证书通过 mTLS 绑定客户端端点身份。撤销配对后重新检查授权。管理网页 47990 永不转发。主机签发有期限的公开身份登记，由独立 Rust 中继校验已登记主机的签名，防止把它变成任意匿名转发服务。

客户端代理仅监听 `127.77.0.2`，自动选择完整可用的端口偏移组，避免本机 Sunshine、已有进程和 Hyper-V 预留端口冲突；原生 HTTP、TCP、UDP 和 ENet 适配只作用于该地址。媒体及控制通道限制分包，数据包边界不变，服务器不转码。tc 已部署独立 Rust 身份登记中继，33447 转发、33448 校验主机签名，登记 winserver / claw 两台主机，测试节点的根权限已撤除。原 DERP 未改动；外层当前为 HTTP，内部 QUIC 数据仍端到端加密，界面与诊断不得写成外层 HTTPS 已开启。

每个客户端 × 远程设备配置增加 `routeOrder` 和 `relayUrl`，随原有 WebDAV 客户端配置同步。`routeOrder` 按顺序列出已启用路径，移除代表停用，至少保留一项；支持通过 `settings.route.move`、`settings.route.toggle` 和 `settings.set` API 操作。默认顺序为局域网、公网直连、内置打洞直连、Tailscale 直连、自建中继、Tailscale 中继 / 未确认路径。Android 无法确认外部 VPN 的实际路径时归入未确认项，不冒充直连。

合成数据已实际经 tc 完成每方向 1 MiB 往返校验，两个传输端关闭所有 IP 直连通道。随后 Windows 与 OPPO 已完成真实中继视频基础验证，Windows 也验证内置直连；具体测量与未完成项见 [2026-10-23 验证记录](WAN-VALIDATION-20261023.md)。首次媒体失败回退、断线恢复和蜂窝网络仍未验收。稳定发布 0.2.3 不包含这批开发中能力。
