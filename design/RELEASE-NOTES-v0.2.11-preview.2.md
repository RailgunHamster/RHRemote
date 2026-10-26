# RHRemote 0.2.11-preview.2

本地客户端交互预览，尚未发布到 GitHub / Gitee。

- Windows 与 Android 的更新页合成一个随状态变化的主按钮，显示检查、下载进度、校验、安装和失败重试；Windows 下载不再阻塞普通导航或控制 API 的状态查询。
- 设备网络参数统一随连接设置保存或取消，保留未保存诊断。详情显示已保存的 Tailscale 与中继配置及最近选路，搜索支持备用地址。
- 减少重复导航入口，区分分类选中态与主要操作，修正 Android 添加地址校验、设置返回位置和深色下拉框可读性。
- 保留现有远程协议、服务端、画质、声音及连接路径策略。

验证与逐页记录见 [UI-INTERACTION-REVIEW-20261027.md](UI-INTERACTION-REVIEW-20261027.md)。Android ARM64 versionCode 为 227，系统安装仍需用户确认。
