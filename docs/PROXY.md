# 代理

在“设置 → 代理”选择协议（HTTP 或 HTTPS），填写主机和端口，勾选用途并保存。默认关闭；默认 HTTP、127.0.0.1、7890。Android 的 127.0.0.1 指平板自身，若代理运行于电脑，应填写平板能够访问的电脑地址及其允许的监听端口。

三个用途独立控制：更新覆盖检查信息、下载安装包与 HTTPS 重定向；配置同步覆盖 OPTIONS、GET、PUT、LOCK、UNLOCK；远程连接使用应用内中继承载原生 TCP/UDP，并在启用代理时关闭 QUIC 的 IP 直连传输。开启远程代理需要设备已保存 RHRemote 中继身份，缺失时应先在可达网络刷新设备。现有配对与服务端授权仍然有效，首次发现未知设备不会绕过代理直接探测。

协议指到代理本身的连接。HTTP 代理也可以通过 CONNECT 访问 HTTPS 更新源；HTTPS 代理要求代理监听端启用 TLS，且证书通过系统信任及主机名校验。7890 通常是 HTTP 代理端口。此设置不修改系统、Tailscale、VPN 或其他应用的代理。不支持代理用户名与密码。

代理设置保存在各客户端本机，不通过 WebDAV 同步。保存后才生效。连接、配对、诊断、同步或更新运行中不允许修改。代理不可用会报告错误；不悄悄改成直连。已下载的安装包仍按原有大小、SHA-256、签名规则验证。

操作接口：`proxy.state`、`proxy.edit {key,value}`、`proxy.save`、`proxy.cancel`。状态包含 `proxy`、`proxyDraft`、`proxyDirty`、`proxyError`、`proxyBusy`。
