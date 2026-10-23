# 独立中继 HTTPS 部署

2026-10-23 开发候选，尚未发布为稳定安装包。

tc 的正式入口为 `https://101.43.203.147:33449/`。原 TCP 443 由思源容器占用；本次没有接管该端口或替换其证书。HTTPS 位于独立的 `rhremote-tls.service`，使用现有 Caddy 程序，配置、进程账号、管理端口和证书存储均独立。原 HTTP 33447 / 签名登记 33448 保留兼容。

部署命令：

```sh
bash tools/deploy-relay-tls.sh 101.43.203.147 33449
```

脚本会核对现有 HTTP 重定向和 Caddy 运行配置与保存配置是否一致，只增加 `/.well-known/acme-challenge/*` 的转发，其余 HTTP 路径继续重定向到原 8443。共享 Caddy 配置先备份、验证，再热加载；失败时仅在文件仍属于本次修改时恢复，避免覆盖并发修改。

独立服务先申请测试 CA 证书，验证公网 HTTP-01 挑战通路，再切换到正式 CA。证书与 ACME 账号分别存储在 `/var/lib/rhremote-tls/staging` 和 `production`，私钥不导出、不上传仓库。正式证书为 Let’s Encrypt 的 shortlived IP 证书，续期由该进程内的 Caddy 证书管理执行，不修改既有 acme.sh 账号或任务。裸 IP TLS 客户端通常不发送 SNI，因此该实例显式配置 `default_sni`，没有关闭证书验证。

- 证书产品与有效期依据：[Let’s Encrypt IP 证书说明](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)。
- 挑战端口与 profile 配置依据：[Caddy TLS issuer 文档](https://caddyserver.com/docs/caddyfile/directives/tls)。

HTTPS 的视频 WebSocket 与登记请求共用一个入口：`/relay` 由原中继处理，`/rhremote/v1/grants` 转发到本机签名登记服务。主机不再把 HTTPS 443 的登记请求误发到 444。HTTP 旧入口仍采用相邻端口；URL 不允许凭据、查询参数、片段或额外路径。

`/rhremote/health` 当前由原登记进程返回，其中旧字段 `outerRelayTls=false` 描述该进程自身的 HTTP 监听，不代表前面的 HTTPS 连接未加密；验证前端 TLS 应检查实际连接的证书链和协议。本机已在无自定义信任回调的 SslStream 中验证 IP、系统信任链和 TLS 1.3。证书 SAN 为该公网 IP，签发者为 Let’s Encrypt YE2，当前到期时间为 2026-10-29 19:38:49 UTC。私钥和证书不会打进客户端或主机安装包。

只有客户端切到 HTTPS 的第一次视频验证已成功：Windows → winserver，强制中继、禁用该客户端的 IP 直传，1080p H.264、静音，收到实际画面且统计完整结束。该样本首帧 render_return 为 5339.62 ms；当时主机仍配置旧 HTTP 入口，不能据此称两端中继外层均已升级，也不能宣称启动耗时已稳定。

winserver 和 claw 随后均更新了主机组件，SHA256 为 `4E01E7773A4DBA9CC3FD2E482C06CA09803A9550961DB258EA0CAAF3E9680FDA`。服务 UUID、配对和其他配置保留，两个服务进程均建立了到 33449 的 TCP 连接，主机的 relayUrl 现为 HTTPS，签名登记走同一 origin 下的 `/rhremote/v1/grants`。HTTP 旧端口兼容及 HTTPS 同源 / IPv6 / 凭据和路径拒绝的回归均通过，主机库共 10 项测试通过。

两端均为 HTTPS 后，Windows 强制中继测试如下。客户端禁用该传输的 IP 直传，H.264、1920×1080、目标 30 FPS，收到首帧后再记录约 8 秒。声音关闭，测试结束恢复客户端原连接偏好和统计开关，原始数据用于后续启动波动分析。

| 主机 | 连接动作受理 | 首帧 render_return | 记录渲染帧数 | trace_end |
| --- | ---: | ---: | ---: | --- |
| winserver | 26 ms | 5667.00 ms | 370 | 有 |
| claw | 22 ms | 2867.27 ms | 127 | 有 |

这仍是两台家庭局域网设备经公网 tc 中继的视频，未模拟不同运营商或蜂窝网络。短窗静态桌面不能代替固定 30 FPS / 动态负载验收，也不能把上述首帧当作稳定上限。客户端单边 TLS 那段 5.34 秒的细分统计中，显示准备约 1424 ms，D3D 创建约 772 ms、销毁约 641 ms；协议就绪后等待首视频包约 996 ms。启动优化尚未完成。

本机证书验证记录为 `D:\RHRemoteTools\relay-tls-certificate-20261023.json`，双向 TLS 视频记录为 `D:\RHRemoteTools\windows-both-tls-relay-validation-20261023.json`。主机安装器自带原部署备份，切换前的网络配置另存于其受保护备份目录。未更新本机 RailgunHamster 的主机服务，也未操作 OPPO 或发布新稳定安装包。

客户端默认入口已改为 HTTPS。Windows 首次启动升级时，原 preferences.json 备份为 preferences.pre-https-relay.json；Android 对应备份为 rhremote-shared.pre-https-relay.json。本次迁移只更新当前客户端保存的旧官方 HTTP 地址（有无末尾斜线均可），保留自定义地址、连接顺序、画质、声音和其他客户端的配置。迁移版本仅在本地保存，不加入 WebDAV 格式；升级完成后，用户明确改回 HTTP 的设置不会在下次启动时再次被迁移。

同步仍按原有三方比较判断实际修改。只有新旧官方地址不同、其余配置完全一致且原本会冲突的情况自动选择 HTTPS；自定义中继或并发画质修改继续要求处理冲突。Windows 核心、UI 和传输共 65 项测试通过，Android 的 APK、测试 APK 构建和 30 项 JVM 测试通过。构建未连接 OPPO，不能代替 Android 真机升级和串流验收。

Windows 受管理客户端已运行该候选。本机真实 preferences.json 的备份与迁移前文件逐字节相同，迁移后与“只替换当前客户端旧官方地址并增加本地迁移标记”的预期完整对象相等，其余设置未变化。随后分别经 HTTPS 中继连接 winserver 和 claw，实际首帧为 3849.37 / 2830.61 ms，记录渲染帧 368 / 132，均有 trace_end 且声音关闭。测试后原配置和统计开关已恢复，迁移验证再次通过。此轮依然是家庭网络经公网中继，不代表蜂窝、仅 443 网络或固定动态帧率验收。记录为 D:\RHRemoteTools\default-https-migration-runtime-20261023.json 和 D:\RHRemoteTools\windows-default-https-both-tls-relay-validation-20261023.json。

尚需完成跨网络验证。33449 仍可能被只允许 TCP 443 的网络阻止；本次部署不能代表这类受限网络已经兼容，后续需要单独处理标准 443 共存或可用的独立域名入口。
