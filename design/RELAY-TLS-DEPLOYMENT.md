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

尚需完成：主机端 HTTPS 登记与实际串流回归、默认入口切换、Android 构建，以及跨网络验证。33449 仍可能被只允许 TCP 443 的网络阻止；本次部署不能代表这类受限网络已经兼容，后续需要单独处理标准 443 共存或可用的独立域名入口。
