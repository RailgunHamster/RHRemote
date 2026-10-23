# RHRemote Android 0.2.4-preview.2

修复设备发现期间可能影响 Tailscale 的进程网络绑定。VPN 活动时不再为 STUN 探测
临时将整个 RHRemote 应用绑定到 Wi-Fi，避免影响并发的地址检测和连接请求。
同时保留已上报的主机地址，检查 STUN 期间的 VPN 切换。

包含 preview.1 的备用地址检测、Tailscale 地址学习、冷启动配对状态及严格证书确认
修复，以及当前分支的 HTTPS 中继、连接恢复、启动前选屏和控制面板。

APK、测试 APK 编译成功，49 项 JVM 测试通过；新增四项 VPN 发现回归先在旧代码
全部失败，修复后通过。实际 OPPO 外网媒体、配对取消及网络切换仍待验收，不据此
宣称平板连接问题已完全解决。

签名与稳定版及 preview.1 一致，可直接覆盖安装并保留应用数据。Android versionCode
为 205，后续稳定版须至少 206。精确提交及校验值见 build.json 和 SHA256SUMS.txt。

这是单独的 GitHub 预览安装包，不替换稳定版 0.2.3 或双站稳定更新 feed。
源码继续同步 GitHub 和 Gitee。
