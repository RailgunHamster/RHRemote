# Android 0.2.9 发布与 OPPO 验证

源码／标签：`da6a30e5f58cb7c680ed7032fcd7dfbaeabc05d2` / `v0.2.9`。Android code223，非 debuggable，原签名不变；APK 共 19,879,214 字节，SHA-256 为 `862b5b7aacb1bfd86cd3e5c36366b8b89bf89ff666a77dc0d9c5d28ab6c0a514`。Windows feed 保持 0.2.7，继续引用 v0.2.7 的安装包。

GitHub 与 Gitee 均已公开发布。两站各八项资产和两个 latest feed 的匿名下载及 SHA-256 校验全部通过。184 项 Android Release JVM、13 项 Rust 传输、16 项发布工具回归通过；发布源码对应的七个 GitHub 工作流全部成功，包括 Windows／portable 服务端构建。

## 安装地址修复

0.2.8 的实际缓存为 code221、设备当时为 code220，OPPO 安装器却沿固定文件地址拒绝更新。同一包换地址后通过版本检查并完成安装。只读设备安装器字节码已排除 versionName 字符串比较；未读取其私有缓存证明内部具体键。完整历史和对照限制见 [0.2.8 验证](RELEASE-VERIFICATION-v0.2.8.md)。

0.2.9 在后台为校验通过的包创建 `RHRemote-installer-<versionCode>-<完整 SHA-256>.apk`，独立快照再次核对大小、哈希、包名、版本及签名；系统安装器得到相应的独立 FileProvider URI。不同版本不会改写已经导出的文件。损坏快照重建，成功安装后在应用启动时清理已完成版本的快照，保留其他文件。

OPPO 从 code222 候选直接调用正常产品 `updates.install`，没有先检查或下载，也没有测试 HTTP 工厂／USB 下载源。应用经手机流量热点从公开 Gitee 下载 19,879,214 字节，完整下载、校验和打开安装器的观察耗时 126.451 秒；动作返回 24 ms，61 次状态查询最大 76 ms。系统实际持有的授权 URI 含 code223 和上述完整 SHA-256。截屏确认通过了旧的“更高版本”错误分支，进入 OPPO 正常的人工安装提示。脚本结果为 `PASS_PUBLIC_PRIMARY_DOWNLOAD_VERIFY_INSTALLER_URI`；人工确认及安装后回归尚待本轮完成后补齐。

## Gitee 配额清理

上传明确返回附件超出仓库 1 GB 配额。按用户批准的源码 ZIP 范围，清理 v0.2.0、v0.2.1、v0.2.3～v0.2.8 共八份旧源码附件，释放 199,537,222 字节；只保留最新 v0.2.9 源码 ZIP。每份旧包先下载备份，并与 GitHub 对应包独立校验 SHA-256 一致。备份及逐项清理证明保存在 `D:\RHRemoteTools\gitee-source-backup-20261025` 和 `D:\RHRemoteTools\gitee-all-old-source-cleanup-result-20261025.json`。

删除后逐项复查，非目标附件名称、ID 和大小均未改变。旧安装包、Git 标签及源代码提交保留；当前 Windows 更新仍需使用旧 v0.2.7 安装包。

## 验收边界

此前 code219／220 在手机流量热点、客户端 VPN 为零的条件下，分别通过真实关闭 Wi-Fi 15／30 秒、回到原热点、独立中继自动恢复和主机实收输入。提交断档分别约 34.216／38.343 秒，不能称为无感恢复。code222 又经 Tailscale 路径完成视频和两组输入，首次提交约 5.450 秒。详细统计和失败样本见 [OPPO 外网验证](OPPO-WAN-VALIDATION-20261025.md)。

这些测试没有验收双方都关闭 Tailscale、跨运营商、生产 TCP443 入口及持续动态 30 FPS；临时 1080p30／2 Mbps 和静音不覆盖用户原配置。旧失败记录保留，不能用后来的成功改写。
