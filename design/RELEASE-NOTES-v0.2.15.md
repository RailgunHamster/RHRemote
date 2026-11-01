# RHRemote 0.2.15

- 新增 Ubuntu 24.04 amd64 的客户端、服务端 `.deb`，使用 X11 会话；包含独立的 RHRemote-Sunshine 用户服务、显示器识别与输入支持。
- Ubuntu Server 可显式初始化无桌面的独立 Xorg 桌面；不支持与其他图形会话共用输入。桌面版 Ubuntu 使用已有 Xorg 会话。
- Windows、Android、Linux 串流支持半透明悬浮键盘。打开顶部“控制”，启用“悬浮键盘”；方向键在右下，Tab / Shift+Tab 在左侧，其他按键沿边缘排列，空白区域允许操作远程画面。
- 修复服务端清理旧串流时误取消新重连请求的问题。桌面客户端恢复已出画面的会话时，先重试原连接路径，再尝试其他路线；暂时繁忙的回复采用有限重试。
- Ubuntu 软件包采用手动覆盖升级并保留配置；本版没有集成 Linux 自动安装更新。Wayland、Ubuntu 20.04、ARM 与实体 GPU 性能不属于本轮验收范围。

Windows / Android 客户端版本为 0.2.15。附带的 Windows Sunshine 服务端压缩包继续使用 0.2.11，不表示本版已更新 Windows 上部署的服务；本轮服务端修复在 Ubuntu 软件包中提供。
