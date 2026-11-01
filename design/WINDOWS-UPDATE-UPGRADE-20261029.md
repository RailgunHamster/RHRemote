# Windows 更新单按钮：真实升级及本机交付

日常 Windows 后续已在用户保存并关闭后，从 preview.93 原地升级到 **preview.101**；最终 UI、引擎和三份关键配置核对通过，升级后保持关闭。最新证据见[悬浮按键入口与交付](FLOATING-KEYBOARD-ENTRY-20261029.md)。以下 preview.93 / .94 记录为先前实际升级范围，不代表日常目录仍停在旧版。

本轮完成真实便携版文件升级、单按钮下载／取消／重试／自动重启，以及用户批准后的本机原地更新。证据根目录为 `D:\RHRemoteTools\ui-update-upgrade94-review-20261029`。没有操作 OPPO，没有公开发布，没有运行 Setup 安装器。

## 独立副本的实际文件升级

从正式 0.2.10 Portable 原件解包到独立 app 目录，用其真实 `Update.exe 1.2.0` 应用未经改写的 preview.93 nupkg。仅在副本运行 `--silent --norestart`，旧 UI 没有启动；更新器退出码 0，实际 manifest 与 UI 哈希均变为 preview.93。副本外的配置保持字节一致。

实施前核对了锁定 SDK 的调用代码，以及 [Velopack 1.2.0 进程路径限制](https://github.com/velopack/velopack/blob/1.2.0/src/bins/src/shared/util_windows.rs)和[便携版应用更新实现](https://github.com/velopack/velopack/blob/1.2.0/src/bins/src/commands/apply_windows_impl.rs)：停止进程以应用根目录为范围，便携版跳过卸载项和快捷方式改写。实际目标路径及 `.portable` 标记均在调用前核对，没有把日常应用根目录传给这次测试。

`offline-from-0.2.10/verification.json` 保存原件、目标二进制和配置核对。此步只验证更新器替换文件，不冒充旧版按钮已合并或旧版 UI 已完成升级操作。

## 实际单按钮下载、取消和重启

继续使用这个已升级的真实 preview.93 副本，更新目标为真实 preview.94 包，未改写包版本或 manifest。更新源使用已有显式测试模式允许的 localhost fixture；它服务的是最终 48,184,844 字节 nupkg，并保留正常长度、SHA 校验和真实 Velopack 应用流程。这不是 GitHub／Gitee 公开更新源的升级验收。

更新器有原生进度窗。为避免它出现在用户主屏，应用及更新器放在一个从未切换为输入桌面的独立测试桌面运行。使用 CreateProcess 的 desktop 参数，没有调用桌面切换或 OS 输入接口；继承规则见 [Microsoft 文档](https://learn.microsoft.com/en-us/windows/win32/winstation/thread-connection-to-a-desktop)。控件由实际 Slint 测试接口操作，截图为应用自身渲染。

1. 初始自动检查返回空 feed；明确发起检查后才由 fixture 提供 preview.94，避免后台自动下载抢先完成用户流程。
2. 点击实际“检查更新”主按钮，原位变为“下载并安装”，没有生成三个旧动作按钮。
3. 点击实际主按钮开始下载。在 1 MiB 暂停正文传输，实际页显示 2% 进度、主按钮禁用，只有任务进行中出现辅助“取消”。
4. 点击实际取消按钮，等待工作结束，确认目标包及 partial 均不存在，同一个主按钮可重试。
5. 放行传输，点击同一个主按钮重试。实际完整下载、校验、退出旧进程并由真实更新器自动启动新进程，manifest 和二进制均为 preview.94。

实际主按钮派发 3 次（检查 1 次、下载／重试 2 次），取消派发 1 次；资产请求 2 次，完整下载 1 次。初始 UI PID 179468，重启后 PID 115380。偏好字节及一个本次生成的原生身份 INI 均保持一致；没有把该隔离身份当成用户既有主机配对验收。

`button-upgrade-verification.json`、`restart-observations.json`、实际控件列表及 PNG 保存过程。`restartObservationMs:7146` 包含 QA 点击、轮询、核验及截图开销，不当作纯安装耗时或首帧指标。完成后关闭全部临时应用、更新器、fixture 与测试桌面句柄；当前输入桌面始终未主动切换。

随后在原始 Default 桌面的实体 DISPLAY2 再次启动实际已更新的 preview.94，确认未激活、只有一个更新主按钮、当前 manifest 与 UI 版本一致，截图已查看，再正常关闭。见 `physical-verification.json`。

最初外部 PowerShell 无法读取另一个桌面的 HWND 归属，检查在手动下载前停止；保留失败记录后，在同一桌面运行核验助手，沿用仍存活的原应用和 fixture，未重开或重做已开始的更新。一个 Windows PowerShell 助手未生成报告，不计作通过，随后当前 PowerShell 助手取得明确归属证明。取消连接在 fixture 产生一次 WinError 10053 traceback；它是未捕获的服务器端 ConnectionAbortedError，原记录保留。客户端取消、partial 删除和后继完整下载有独立证据，不能以服务端缺少一条终止日志替代它们。

## 已交付到用户正在使用的便携版

用户明确回复“已保存并关闭，升级”后，执行已准备的 `local-upgrade-93/Install-LocalPreview93.ps1 -Apply`。调用前确认日常目录没有任何运行进程，没有强制结束用户程序。目标使用此前已核对的 preview.93，未把尚未通过最小化／恢复复验的 preview.94 提升为日常候选。

当前日常路径 `D:\git\RHRemote\dist\RHRemote-win-Portable\current\RHRemote.exe` 已为 **0.2.11-preview.93**，UI SHA-256 为 `492887121F8FD4C39A932C8FEF8B89487DA5CB509D2C6843E707D4673D1951B8`。日常 PID **110380** 保持运行，实体副屏 DISPLAY2 可见；未开启隔离测试模式，更新页实际只显示一个主按钮。当前这一进程启用带认证的 localhost API（127.0.0.1:48701），令牌仅经进程环境传入，没有出现在升级日志中。

最后备份位于 `local-upgrade-93/final-backup-7bd9d89b8a53468f83de953e3d41a8f6`。程序替换后、首次启动前，preferences、原生配对 INI 和 WebDAV 本地文件均与备份字节一致。启动后设备仍为 RailgunHamster、winserver、Claw；本机自身条目继续禁连，winserver 和 claw 仍已配对。偏好及既有配对 INI 继续保持字节一致，没有发起远程会话。

WebDAV 文件启动后哈希变化已单独核对：URL、用户名、凭据引用、自动开关、间隔与合并基准全部相同；只有自动同步完成时间和 pending 字段的落盘发生变化。同步已完成且没有阻塞，不把正常同步记账改写误报为配置丢失，也没有用旧备份覆盖新完成时间。核验器未读取密码。见 `normal-sync-preservation.json`、`normal-final-verification.json`；原始整文件哈希不同的报告也保留。

日常窗口位置首次核对误将 PowerShell DPI 虚拟化后的 -1707／1707 与物理 -3840／3840 比较。按读回线程临时切到 per-monitor-v2、读后恢复，再次得到正确副屏物理坐标 (-3776,64)，没有移动用户窗口或更改显示配置。这提示此前悬浮键盘恢复时的区域断言也须先排除读取端 DPI 差异，但该旧失败仍未认定解决。

Setup 安装边界、公开更新源交付、Android 用户系统安装后的配置保留及其余 UI 工作继续在 TODO；本轮不代表整个 UI 目标完成。MSI 并非用户要求的交付物，不增加为完成门槛。
