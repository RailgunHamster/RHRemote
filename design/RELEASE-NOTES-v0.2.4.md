# RHRemote Android 0.2.4

- 修复 Android 远程设备轮询没有尝试已同步备用地址的问题，保留局域网、Tailscale 和独立传输的连接顺序。
- 自动学习已配对主机的 Tailscale 地址，避免只保存局域网地址，离家后仍只检查旧地址。
- 修复已有配对在冷启动时被当成未配对；确认主机身份时严格核对证书和 UUID。
- 移除 VPN 发现期间整个应用的临时底层网络绑定，避免并发请求绕过 VPN；保留主机上报地址。
- 配对成功前立即保存本机证书；保存失败保留旧记录，重启、迟到发现及轮询不会恢复旧证书。
- 修复更新页面显示 `null`，明确显示尚未检查、已是最新版本、发现新版本或检查失败。

本次为 Android 正式更新通道发布，包含 preview.1 / preview.2 的修复；versionCode 206，
APK 签名与 0.2.3 一致，可覆盖升级。不会清除设备列表、配对及配置，安装仍需在系统界面手动确认。

Windows 本次仍为 [0.2.3](https://github.com/RailgunHamster/RHRemote/releases/tag/v0.2.3)，
此发布保留其更新 feed，自动更新包继续从原版本地址下载。
本次未重新部署服务端。OPPO 已确认 Tailscale 下可访问 Sunshine serverinfo；新版实际外网视频仍待升级后验收。
