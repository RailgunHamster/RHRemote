# 更新主按钮：实现与实际交付的差异

后续已完成真实便携版升级、单按钮下载／取消重试／自动重启，并按用户“已保存并关闭，升级”的授权将日常客户端原地更新为 preview.93。下文保留当时发现交付差异的记录；[当前交付与保留验证](WINDOWS-UPDATE-UPGRADE-20261029.md)为准，日常客户端已不再是 0.2.10。

用户再次指出更新页仍有三个按钮。本轮确认日常运行路径 `dist/RHRemote-win-Portable/current` 的 `sq.version` 为 **0.2.10**，UI SHA-256 为 `E569DD758BFC0971176F0FBA527A77E799FF49C440ABED33711A9BBD92A06D5F`。`v0.2.10` 标签的实际 Slint 源码仍并排显示 `updates.check`、`updates.download`、`updates.install` 三个按钮。此前合并后的实现一直保留在未发布候选包中，未交付到日常运行版本，不能把候选测试说成用户已获得修复。

当前 Windows 与 Android 源码均只显示 `updates.primary` 一个主按钮；三个底层动作继续作为 API 保留。初始为“检查更新”，发现更新后在原位变为“下载并安装”，下载／校验阶段显示进度；已准备但延期安装时，同一按钮显示 Windows“重启并安装”或 Android“安装更新”。正常下载完成会继续安装前检查；Android 的系统安装仍由用户确认。[下载后的草稿保护](UPDATE-DRAFT-HANDOFF-20261028.md)保留。

本轮重新启动最终 Windows preview.93 解包候选，放在实体 DISPLAY2，使用日常偏好的隔离副本，没有复制 WebDAV 凭据或原生配对身份。实际控件检查仅发现一个 `updates.primary`，没有三个旧动作按钮；点击真实主按钮派发一次共享动作，公网检查完成后为 `current`，仍只有一个主按钮。此轮没有发现更高版本，没有下载或启动安装，不能算完整升级通过。

证据：`D:\RHRemoteTools\ui-update-one-button93-review-20261029`，含前后控件列表、实际点击回执、更新状态和应用自身截图。已查看页面，唯一主按钮位于固定页脚。预览结束后正常配置哈希不变，日常 PID 196036 未操作，OPPO 未操作。

可安装的本地预览包为 `dist/velopack-releases-0.2.11-preview.93/RHRemote-win-Setup.exe`，SHA-256 `77E209D60D9A247F327EDFF5FE03D4AFC051AFA14AE6417EBC74D5070D6BA7E2`。本轮没有覆盖正在使用的 0.2.10，也没有公开发布；正式更新源交付与完整安装后的配置保留仍在 TODO。
