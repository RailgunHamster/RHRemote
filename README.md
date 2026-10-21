# RHRemote

Windows 与 Android 远程桌面客户端，兼容 Sunshine，串流复用 Moonlight 开源核心。

- Windows：Rust + Slint 界面，Moonlight Qt 接收、硬件解码与 SDL 渲染。
- Android：原生 View + Java，MediaCodec 解码与 Surface 呈现。
- 每个客户端实例 × 远程设备独立连接参数；设备支持别名、主副键补偿。
- 画面、音频、输入、高级四类连接设置；支持静音、音量、触控板、直接点击、捏合缩放与双指滚动。
- WebDAV 支持 HTTP / HTTPS；自动同步可选 5、15、30、60 分钟。密码及配对密钥留在本机。
- Windows 使用 Velopack 更新，Android 下载 APK 后由用户确认安装；GitHub 与 Gitee 互为下载源。

## 下载

[GitHub 发布页](https://github.com/RailgunHamster/RHRemote/releases) · [Gitee 发布页](https://gitee.com/railgunhamster/RHRemote/releases)

Windows 使用 `RHRemote-win-Setup.exe` 安装，或解压 Portable.zip 后运行。Android 使用 arm64-v8a APK，包名为 `com.rhremote.client`，可与原版 Moonlight 并存。升级保留同一应用身份和签名，不要先卸载。

当前 v0.2.1 是开发预览版。Android 暂用开发签名；Windows 尚无代码签名证书。连接成功不代表稳定 30 / 60 FPS 验收，首次连接的主机编码检测耗时仍在排查。

开发中的快捷键与屏幕切换支持 Windows / Android：Ctrl+Alt+Delete、Ctrl+Alt+方向键、Alt+Tab、任务管理器、常见编辑快捷键，以及 Win+Shift+左／右移动远程窗口。Windows 控制区域在连接后显示；Android 使用串流工具栏。每个操作都有对应动作接口。

被控端保留 Sunshine，新增可选 Rust 辅助服务 RHRemoteHost，用于安全操作序列、屏幕清单和自有虚拟屏尺寸管理。构建运行 `tools/build-host.ps1`，在被控端管理员 PowerShell 运行 `dist/host/install-host.ps1 -SourceDirectory <组件目录> -EnableSecureAttention`。它使用已有 Sunshine 配对证书，不增加账号密码；安装目录和操作文件仅 SYSTEM / 管理员可写，47995 端口仅允许局域网。卸载使用同目录 `uninstall-host.ps1`，保留备份和数据。

虚拟屏需要已安装的 Amyuni IDD 驱动；不会接管其他程序的虚拟屏。自动选择依赖 Windows 报告的屏幕可用状态：有些显示器关电源后仍被 Windows 视为连接，此时需要手动选择虚拟屏。实体屏的分辨率、位置和主屏状态不随客户端调整。

## 构建与接口

固定上游版本、依赖及构建说明见 [apps/README.md](apps/README.md) 和 [client/README.md](client/README.md)。Windows 需要 Rust 1.92.0、Qt 6.7.2 / MSVC；Android 使用原生 Java 与固定 Moonlight Android v12.2 源码。

```powershell
.\tools\build-rust-windows.ps1
.\tools\build-android.ps1 -BuildTests
```

脚本默认使用开发环境路径；可通过 SourceDir、QtDir 等参数指定已有上游源码和依赖目录。

产品按钮与本地 HTTP API 使用同一个操作分发器，见 [API 与同步协议](design/API.md)。测试模式记录连接各阶段及逐帧元数据，见 [Statistics](design/STATISTICS.md)。诊断数据、真实设备快照、账号、配对证书、私钥与令牌不属于源码或发布包。

## 许可

基于 Moonlight Qt、Moonlight Android 及其依赖，保留原有版权和 GPL v3 许可。服务端 Sunshine 单独安装和配置。许可文本见 [LICENSE](LICENSE)。本项目不代表 Moonlight 或 Sunshine 官方构建。

设备卡片的 ↑ / ↓ 按钮调整显示顺序，立即保存，并随 WebDAV 配置同步到其他客户端。刷新和重启保持顺序；同时修改产生冲突时可选择保留哪一端。
