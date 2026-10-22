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

新增字段扩展已有同步 schema v2；旧客户端严格校验未知字段，因此 Windows 和 Android 均需更新到 0.2.2 后再同步新设置。配对密钥、同步密码、诊断结果和当前路径不参与同步。

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

局域网底层上的 Tailscale 串流不能代替蜂窝网络验收。OPPO 实机操作依用户要求暂停；恢复后应验证蜂窝网络、分应用 VPN、Wi-Fi/蜂窝切换和实际远程输入。免安装 Tailscale 的独立组网方案不属于本版已实现能力。
