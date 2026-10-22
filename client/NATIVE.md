# RHRemote 原生客户端

界面按用户确认的 v2（RHVideo 配色、专注连接布局）实现。服务端使用现有 Sunshine。

| 平台 | 原生界面 / 串流 | 固定上游 |
| --- | --- | --- |
| Windows | Rust + Slint / 既有 Moonlight Qt 接收、解码与 SDL 呈现 | Moonlight Qt v6.1.0，`f786e94c7b2f943e24e65d7d74deb539b827fc84` |
| Android | Android 原生 View / MediaCodec 硬件解码与原生 Surface | Moonlight Android v12.2，`b48494cb96bff23d8886c4775cc4f39a1075495d` |

`windows/overlay` 与 `android/overlay` 是本项目维护的源文件；`tools/prepare-*.ps1` 记录对固定上游的集成修改，反复准备会从首次备份的上游文件生成，避免补丁重复叠加。所有界面、主机设置存储与触控代码保存在仓库中。

## 当前机器构建

```powershell
.\tools\build-rust-windows.ps1
.\tools\build-android.ps1
```

Windows 使用已安装的 Qt 6.7.2 / MSVC 与已有客户端依赖，源码位置默认 `D:\RHRemoteTools\moonlight-qt-v6.1-source`。现有串流实验改动保留，实验开关继续默认关闭。Android 使用 Gradle 9.7.1 / Android SDK 37 / NDK 29，源码默认 `D:\RHRemoteTools\moonlight-android-v12.2-source`。Gradle 使用用户已经配置的 Windows HTTPS 代理下载公开依赖，不改变系统代理设置。

Windows 需要 Rust 1.92.0。首次准备原生源码时，按 `client/README.md` 应用基础引擎补丁，再运行准备与构建脚本；上游子模块、Qt 和媒体库沿用固定版本。`client/crates/rhremote-ui` 是当前界面，旧 Qt 页面只是原生适配器中的遗留入口。

输出为 `dist/windows-rust/RHRemote.exe`、原生适配器 `dist/windows/RHRemote.exe` 与 `dist/android/RHRemote-v0.2.0.apk`。Windows 候选包由 `tools/package-windows-velopack.ps1` 生成，只收集显式运行库，排除配对配置、缓存、令牌和日志。原生适配器状态保存在用户数据目录，更新不会覆盖它。

Android 构建 arm64-v8a，应用标识 `com.rhremote.client`，与原版 Moonlight 并存。当前使用本机调试签名的开发版本；尚未作为正式生产签名分发。后续设备更新应保留同一签名，不卸载应用以免丢失其配对。

## 设置与输入

- 连接配置按客户端实例 ID × Sunshine UUID 保存；别名及主机左右键状态共用。其他客户端配置可显式复制到草稿。
- 画面、音频、输入、高级四类共 24 个字段，按平台实际能力显示，字段目录由 `settings.catalog` 返回。尺寸、帧率、码率可自定义。
- Windows 使用独立 RHRemote 配置目录，应用内连接前把选中电脑的设置交给原生 Session；不写 Sunshine 配置。
- Android 触控补偿只改变触屏生成的按钮事件；物理鼠标沿用上游处理。
- Android 轻点在手势完成时发主键，长按单独发副键，多指或断开时释放按键。
- 双指平移滚动、双指捏合缩放；放大后点“控制 → 移动画面”进行视区平移，“还原缩放”回到全画面。
- “控制”中提供直接点击 / 触控板、软键盘、统计与断开。
- 音频有开关、音量、主机继续播放和声道配置；活动串流可即时静音和调音量，只改变客户端 PCM 增益，不操作系统音量。测试仍静音；winserver 的临时全局禁音已撤销。
- Windows 与 Android 使用 `shared/icons/rhremote.svg` 的统一图标。`tools/build-icons.ps1` 生成 Windows ICO 和 Android 自适应资源。
- 设置 → 关于中可开关本机测试统计。测试模式每次连接自动记录独立逐帧文件，分析后清理；详见 `design/STATISTICS.md`。

## 验证

按用户要求，Windows 在本机副屏连接 winserver，Android 在 OPPO Pad mini 连接 winserver。`tools/tablet-interaction-probe.ps1` 是只在自有平板源屏显示的限时输入与动态测试窗口，记录自己窗口收到的按钮、滚轮与拖动；不做全局输入记录。性能仍按已有验证规范报告，不能仅凭 UI 完成或连接成功宣称稳定 30 / 60 FPS。

基于 Moonlight 的文件与集成改动保留 GPL v3 及上游版权。许可文本在 `client/LICENSE`；上游依赖与子模块也必须按各自许可证保留。
