# 会话操作的按钮与接口一致性

本地候选 Windows／Android `0.2.11-preview.81`，Android versionCode 299。证据目录：`D:\RHRemoteTools\ui-control-busy81-review-20261029`。未发布，OPPO 未操作。

## 复现及行为

屏幕切换等操作尚未结束时，控制面板已经禁用输入模式、键盘、移动画面、缩放和悬浮键盘等修改操作，但部分对应接口仍接受请求。Windows 可以直接启用悬浮键盘并收起面板；Android 可以改变已禁用的输入模式。这会使实际输入状态与用户所见的禁用状态不一致。

修改前，Android 使用真实 RhDisplay 工作线程和挂起响应复现：实际按钮均已禁用，`stream.mode.toggle` 仍成功。Windows 使用挂起的声音设置复现：`stream.keyboard.floating.set` 仍成功，`controls` 被收起。两端修改前的失败结果分别保留在 `before-RhSessionMenuTest.xml` 与 `before-core81.log`。

现在 Android 通过同一组修改操作检查连接和忙碌状态；冲突请求返回“上一项会话操作尚未完成”，不修改输入模式、滚动、缩放、键盘或悬浮开关，也不覆盖正在进行的操作提示。Windows 悬浮键盘接口检查同一忙碌状态，开始断开后也拒绝新切换。分类切换、收起和断开入口继续可用；普通远程触控输入未增加这项限制。

Android 自己的键盘请求是明确的取消例外：Game 手势可以反向取消本窗口尚未完成的 IME 请求，返回键继续可以取消。它不会允许键盘操作越过尚未结束的屏幕切换或安全注意键操作。没有改动服务端、画质、声音默认值、配对或持久配置。

## 现有验证

Android 完整 JVM 回归 397 项通过。新增回归在挂起屏幕切换期间检查七个实际 View 的禁用状态、六个接口的拒绝及状态不变，并检查分类切换和断开按钮仍可用；旧切屏结果在控制器销毁后不能发送输入。

模拟器 native01 使用既有配对身份真实连接 winserver，1080p30／6 Mbps／软件解码／无声，首帧信号 4031 ms，并确认到非黑解码像素。实际 Game／View 验证软键盘打开、返回收起、手势收起、待完成请求的返回取消，以及面板键盘导航、弹窗关闭和正常退出。共享配置、偏好和原备份均恢复。没有发送主机文本、Ctrl+Alt+Delete 或操作系统输入。

该原生样本验证修改后的正常会话和 IME 路径；挂起屏幕切换的拒绝边界来自 JVM 并发回归，不能把二者合称真实网络慢切屏验收。它也不替代外接键盘、OPPO、外网恢复或 Windows 实体悬浮窗验收。

Windows 第一次完整 Release 回归中，Core 125 项通过，UI 46 项因查找不到控件元数据而失败。该次命令漏设 `SLINT_EMIT_DEBUG_INFO=1`，没有按验收构建条件执行。保留 `rust81-full.log`，不把这一轮算作通过。构建脚本现增加该环境变量的 Cargo 缓存依赖，避免切换后复用错误的 Slint 生成代码；带元数据的完整重跑结果另存。

修正构建条件后的完整重跑为 Core 125 项、UI 181 项通过，另有一个既有用例忽略，日志为 `rust81-metadata-full.log`。Windows 并发回归同时检查忙碌期间和发起断开后拒绝悬浮键盘切换，保持展开状态不变；原有界面回归继续验证真实 Slint 控件回调及忙碌时禁用。该结果不代替物理窗口焦点、跨屏 DPI 或真实主机慢响应。

## 本地候选与恢复

| 产物 | SHA-256 |
| --- | --- |
| Android ARM64 Release APK | `551BF289634239F7559607D6FEC092AED407EE025657EFD4079F0E85E8DEC835` |
| Windows Portable ZIP | `A4A845B2DA93E58E79533F1D01CBE9FDDD89D8FE75C0736D5452318E8D01FACA` |
| Windows Setup | `8BC54986AD1D66A68705032199BE321DFC7DC9D6DC1E6A12A98E9F9BAE285A45` |
| Windows nupkg | `174E38E77061A3C6BD441E06CBA947B460DD8FF8F9D89F2A0FB76073CBB2D3CE` |

Android 包位于 `dist/android/RHRemote-v0.2.11-preview.81.apk`，版本和签名核对通过，Release 证书不变。Windows 包位于 `dist/velopack-releases-0.2.11-preview.81/`，ZIP 内 UI 与构建结果 SHA 一致（`B23C40C009EF61F27F1DED9585F8D6BA06FCECFB774C1541F9BBC0444BAB77B5`），原生引擎 SHA 保持 `D6A402A25D860AC0112FCA9DAF622F72E08F16312896566361BEB76A9F7B4389`，未包含私有配置。没有安装或发布 Windows 候选包。

当前仅识别到没有物理名称的 DISPLAY26 虚拟屏，本轮未启动 Windows 桌面预览。Android 原生测试运行的是对应 x86_64 Debug 包，不替代 ARM64 实机或系统安装升级验证。模拟器原始 shared／WebDAV 文件 SHA、日常 Windows 偏好及主机授权 SHA 保持不变；本机 SunshineService 仍运行原 PID 91848，日常客户端仍为 PID 43596。原生统计正常结束且 lost_events=0。核对分别保存在 `verification81-android.json`、`verification81-windows.json` 和 `local-monitors81.json`。
