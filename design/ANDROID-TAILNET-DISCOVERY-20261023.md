# Android 外网离线排查

用户报告：OPPO 在外网开启 Tailscale 后，所有 RHRemote 设备仍显示离线。

## 已观察到的状态

2026-10-23 22:11–22:19（北京时间），本机 Tailscale 处于 Running。
winserver、claw 在线，三台电脑的下列地址均返回 HTTP 200 和预期 Sunshine UUID：

| 主机 | Tailscale 地址 | Sunshine UUID |
| --- | --- | --- |
| winserver | 100.73.139.109 | C3448560-C539-6209-F595-88C1E8D2769B |
| claw | 100.122.230.22 | C653B40F-832A-8905-799A-E107A93759EA |
| RailgunHamster | 100.115.85.155 | AEB4CA42-2046-A809-7F90-634CC7645127 |

本机 Tailscale 看到 OPPO Pad Mini（100.90.74.73）为 Online=false，最后在线
2026-10-23T10:12:28.1Z，即北京时间 18:12。一次三秒 Tailscale ping 超时。
这说明当时这个 OPPO 节点不可达，不能据此确定平板是否停用了 VPN、使用了其他身份、
或遇到控制连接问题。已向用户询问平板 Tailscale 页面状态，等待答复。
以上也不是从蜂窝网络到三台服务器的视频验收。

## 确认并修复的客户端遗漏

1. 原生 ComputerManagerService 的列表轮询只尝试上游四个地址槽位，未读取
   RHRemote 保存、同步的备用地址。连接按钮走另一套多地址解析，因此可以出现
   列表显示离线而备用地址实际可达。
2. 已配对主机的 `/v1/network` 返回 Tailscale 地址，但 Android 原有自动学习只保存
   `transport.address`，忽略 `addresses`；没有内置传输扩展的旧主机完全不学习 VPN 地址。

现在保存的直连地址参加原有并行轮询，继续通过原来的 NvHTTP、UUID 和配对证书逻辑。
后台轮询不创建或切换媒体隧道。配对主机的网络信息通过现有双向 TLS 获取，合并
Tailscale 和内置传输地址，保留用户主地址、手动地址、别名和各客户端配置；总地址数仍
最多八个，IPv6 方括号形式去重。学习按主机限频三十秒，重复轮询不延后下次学习时刻。

## 验证与边界

- `tools/build-android.ps1 -BuildTests` 成功：APK、测试 APK、42 项 JVM 测试，零失败。
- 新增真实 discovery 方法回归，只替换 HTTP 响应：旧 LAN 不可达，首个 VPN 地址
  返回其他 UUID 被拒绝，第二个匹配 UUID 成功并变为 ONLINE；全是错误 UUID 时失败。
- 测试覆盖备用 Tailscale/IPv6 进入轮询、内置身份不被当成主机名、网络事实学习与
  配置保留；旧主机没有 transport 字段也能学习 Tailscale 地址。
- 本次构建 APK SHA256：
  `FA0D2AD0E5C482B72E6C333AD8EC263D9B50B5F6052054312B0E969801809863`。
- 尚未操作、安装或实测外网 OPPO，未修改稳定版发布资产。新 APK 仍是开发构建，
  版本元数据沿用 0.2.3；不要用它覆盖已发布的同名稳定资产。

待完成：确认当前 OPPO 的 Tailscale 身份及连接状态，读取实际客户端保存地址与
选路结果；安装修复版后验证外网列表、真实视频和网络切换。服务器端可达与 JVM
回归不能替代这一步。

## 22:31 后续证据及连接入口修复

用户确认平板显示已连接，IP 仍为 100.90.74.73。随后本机和 tc 的 Tailscale
网络图均更新为 OPPO Online=true；本机 disco ping 收到约 213 ms 回应，TSMP
收到约 348 ms 回应。应当保留前面的离线观测及时间，不能继续把平板描述为当前离线。
这些探测仍不能证明 OPPO 应用到 Sunshine 的 TCP/视频通路；已请求用户在平板浏览器
访问 `http://100.73.139.109:47989/serverinfo`，待答复。已知旧无线调试端口 46367
通过 Tailscale 的一次 TCP 检查超时，未建立 ADB 或操作平板。

另确认冷启动遗漏：原生数据库保存配对证书，但不保存瞬时 `pairState` 和 HTTPS
端口。UI 将空的配对状态直接当作未配对，可能让已配对设备再次进入配对入口。
现在有本机证书且状态未知时，连接按钮会先确认配对状态；仅在状态或 HTTPS 端口缺失时
读取新鲜 serverinfo，保留选中地址及证书并核对 UUID。已有完整状态不增加此查询。
普通媒体重连和纯网络诊断不经过这个 UI 入口，所以不会因此增加一次查询。

这个确认使用单独的严格证书校验选项。它拒绝与已保存证书不同的链，失败时不使用
上游为重新配对提供的 HTTP 降级。原生普通发现/明确重新配对流程保留上游行为。
已知 NOT_PAIRED 状态仍显示配对，不能因为有旧证书就假定授权有效。

最新 `tools/build-android.ps1 -BuildTests` 成功，45 项 JVM 测试零失败，包括真实
loopback TLS 的正确证书/错误证书及无 HTTP 降级测试，以及冷启动状态刷新、UUID
不符拒绝、已有状态无额外请求。最新 APK SHA256：
`2A9DD9A182A5C9AFFED3EEBD0A01CD27DAD886FA1C86DE06E03A61A47BC2ECCC`。
这替代本地开发构建，不改变已发布稳定资产；尚未安装到 OPPO。

## 可下载的预览包

GitHub 已发布 [0.2.4-preview.1](https://github.com/RailgunHamster/RHRemote/releases/tag/v0.2.4-preview.1)，
精确源码 `12e840f069a1f7f84561c37e994ff66cae1ef809`，Android versionCode 204。
[APK](https://github.com/RailgunHamster/RHRemote/releases/download/v0.2.4-preview.1/RHRemote-Android-0.2.4-preview.1-arm64-v8a.apk)
大小 26,350,431 字节，SHA256
`f28891df910216b2a208e95a790829f0afe37c542e9bcf308222604c49b25ebd`。
签名证书 SHA256 与原稳定版一致：
`2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。
45 项 JVM 测试、8 项发布前置校验测试通过；匿名下载四个发布资产，大小和哈希全部一致。

发布元数据确认为 prerelease=true。发布后的 GitHub latest 仍为原 0.2.3、同一 release ID；
GitHub 和 Gitee 的 Windows / Android 四份稳定更新 feed 的哈希与发布前一致。
核对记录：`results/clients-v2/github-preview-downloads-v0.2.4-preview.1.json`。
Gitee 预览发布的 latest 行为尚未验收，所以本次只在 GitHub 放预览安装包，源码继续双站同步。
后续稳定版必须使用至少 205 的 Android versionCode，才能从此预览继续升级。

已知无线调试端口不可达，尚未替用户安装，实际外网媒体和网络切换仍未验收。

## 发现流程的 VPN 竞争修复

继续检查固定的 Android 上游源码时确认，`populateExternalAddress` 在 VPN 活动时会为
STUN 临时调用 `bindProcessToNetwork` 绑定底层网络，再恢复默认绑定。这不是线程局部
行为：并发的 RHRemote 地址探测、HTTPS 或传输组件可能在该时段创建 socket。
[Android 接口文档](https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network))
说明这种绑定会影响此后创建的 socket 及 DNS 查询，恢复默认绑定也不会替换已创建的
socket。原锁只约束这段发现代码，不能约束其他网络组件。

四个测试先在旧实现执行并全部失败：VPN 情况观察到两次进程绑定；已上报的主机地址
被客户端 STUN 地址覆盖；普通网络及网络切换测试未触发预期检查。证据为
`D:\RHRemoteTools\android-vpn-discovery-before-20261023.xml`。

现在 VPN 期间不使用客户端 STUN 地址推断远程主机的公网地址，不修改进程绑定；
非 VPN 的原 STUN 发现保留，并在保存结果前再次检查 VPN 状态，避免网络切换造成
错误地址。已有主机地址不覆盖，手动公网地址、Tailscale 和独立 QUIC 选路保留。
非 VPN STUN 的原生 UDP 接收上限为三秒，DNS 时间另计；这不等于完整发现请求上限，
后续仍需统计和检查发现阶段的等待。

修复后同四个测试全部通过，完整 49 项 JVM 测试零失败，APK / 测试 APK 构建成功。
这是代码路径和竞争条件的回归证据，未证明此前 OPPO 的实际故障恰好由这条路径触发。
本机浏览器测试结果及实际外网媒体仍待用户反馈。

包含该修复的 [preview.2](https://github.com/RailgunHamster/RHRemote/releases/tag/v0.2.4-preview.2)
已发布，精确源码 `4d6039d8283ecc75e1530905f2c0b53701ec4735`，Android versionCode 205。
[APK](https://github.com/RailgunHamster/RHRemote/releases/download/v0.2.4-preview.2/RHRemote-Android-0.2.4-preview.2-arm64-v8a.apk)
大小 26,350,435 字节，SHA256
`e8da87fd2c2934290677424d477871e32d765bd2b79be01b3cecc07c14477768`。
签名仍与 0.2.3 / preview.1 一致，匿名下载四资产及预览通道、稳定入口共十项检查全部通过。
记录为 `results/clients-v2/github-preview-downloads-v0.2.4-preview.2.json`。
后续稳定版 Android versionCode 要至少 206；前文 205 是发布 preview.1 时的历史下限。

## 用户确认版本与 Sunshine 可达

用户已在 OPPO 浏览器打开 `http://100.73.139.109:47989/serverinfo` 并看到 XML，
也确认同一 Tailscale 能访问家中 SMB 和其他服务。不能继续以基础 VPN 不通解释 RHRemote 离线；
这个结果证明到 winserver Sunshine HTTP 的可达性，未验证视频 UDP。

用户实际仍使用 0.2.3，未安装预览版。预览版没有写入正式 feed，因此原版“检查更新”得不到这些修复。
更新页直接拼接 JSONObject.NULL，造成可用版本显示文字 `null`；现已改为仅在确有版本时显示，
初始显示“尚未检查更新”，检查失败的结果保留在更新页。新增四项真实 feed 解析及页面回归，
包括无更新、较高版本、镜像回退及双源失败。连同配对持久化，64 项 JVM 测试零失败，双 APK 构建成功。

准备发布 Android 0.2.4 / code206 到两个正式更新源，签名与 0.2.3 一致。Windows 仍为 0.2.3，
新发布携带原 Windows feed / RELEASES，Windows 包继续从原版本下载。
这次发包只交付已完成的修复，不宣称 OPPO 外网媒体已经验收，不操作或安装到用户平板。
