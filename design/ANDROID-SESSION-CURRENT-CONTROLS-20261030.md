# 当前 Android 会话控制与完整模拟器包

最新本地 preview.129 已在 claw 通过浅色／100%、深色／200% 两轮完整 Game 会话，包含十五步实际控件、系统 IME 和六组边距检查；重复配对另复现旧部署的重复证书 401，服务更新尚待确认，见[当前 claw 实测与部署差异](ANDROID-CLAW-CURRENT-SESSION-20261030.md)。481 项 JVM、四个窗口组件场景及 ARM 候选仍见[分类与预览](ANDROID-SCREEN-CONTROLS-20261030.md)。未操作 OPPO；以下 winserver 失败及历史证据继续保留。

最新 preview.127 已在真实 Game 通过含控制按钮位置、隐藏回调和十个浮键的十五步原检查；preview.128 的 IME 状态修正通过 479 项 JVM，但完整实际轮在出帧后原生 `-1` 中断，未执行到 IME。随后八个实际窗口组件场景通过，同包旧版对照复现键盘误报及按钮移动；独立 ARM 候选已生成到 preview.128，未安装 OPPO 或发布。最新范围见[状态确认记录](ANDROID-IME-CONFIRMATION-20261030.md)、[原生窗口与候选](ANDROID-PLATFORM-WINDOW-20261030.md)。以下保留 preview.114 的历史基线。

本文保留 preview.114 的会话基线，证据目录为 `D:\RHRemoteTools\ui-session-live114-review-20261030`。当轮增加实际 Game 会话探针、完整模拟器构建入口及原生传输库检查，没有修改当轮正式 Java 交互实现或 ARM64 发布包。后续 preview.122 的完整集成轮完成控制探针后在系统 IME 检查失败，见[当前完整会话与失败边界](ANDROID-SESSION-INTEGRATION-20261030.md)；不把历史通过移作当前完整验收。OPPO、日常 Windows 和业务服务均未据此操作。

## 真实会话与失败样本

所有会话使用专用 emulator-5580 和原有 winserver 配对，临时 H.264／1080p30／6 Mbps／模拟器软件解码，声音关闭且不保存声音样本。正常配置与备份每轮还原。这是 UI 测试参数，不是默认画质、帧率或外网验收。

| 样本 | 安装包与字体 | 结果及实际链路 |
| --- | --- | --- |
| `live114-light01` | 最初的 x86_64 包，浅色／100% | 完整会话流程通过，控制探针 15 步通过；首帧 11476 ms，包含一次局域网 RTSP 失败后的恢复。成功路径为配置项 `tailscale_relay`，`path: unknown`。 |
| `live114-dark02` | 最初的 x86_64 包，深色／200% | 完整会话流程及控制探针通过；首帧 7550 ms。局域网只读请求出现 EOF，随后使用同一个 Tailscale 未确认路径配置项。 |
| `live114-complete04` | 补齐 Rust 库的 x86_64 包，浅色／100% | 完整会话流程及 15 步控制探针通过；首帧 6091 ms，实际 `lan / direct`，没有阶段失败或缺组件错误。 |
| `live114-complete05` | 同一完整包，深色／200% | **失败**：60 秒首帧观察预算结束时仍在恢复，尚未运行控制探针。保留原始记录、恢复界面与三个原生 trace；清理及配置还原通过。 |

最初两轮暴露了模拟器包的缺项，不能用它们证明内置打洞和中继可用。补齐后的深色大字号轮仍未通过，不把旧包深色通过移作新包验收。首次出画面时间受不同路由和失败重试影响，不能把这些数值当作启动优化的前后对照。

## 控制探针与已有完整流程

`RhSessionControlOriginProbe` 在实际连接的 Game 中，使用真实 Button、Switch 和 SeekBar 检查当前模式、统计、滚动开关、音量变化和还原；隐藏分类、收起面板后调用保留的旧控件，确认当前会话状态不变。重新打开面板，音量滑块和开关重新对齐实际模型。

三个分类分别核对顶部悬浮开关完整可见并启用；实际开启后显示十个键，打开控制面板时隐藏，关闭开关后保持关闭，控制／收起位置一致。检查有效按钮时先请求其进入滚动视口，再核对完整可见范围，避免只调用一个屏幕外对象。探针完成后比较原本地设置、同一个 Game 和同一个统计会话。

已有会话流程随后继续验证本地 Tab／方向键／Enter／Escape、快捷菜单及旧弹窗回调、屏幕目录、缩放还原、返回确认和主动断开后的设备页。按键仅在应用内分发；键盘导航探针在远端翻译边界截断正对照，没有发送远端按键。悬浮按键只核对显隐及布局，没有点击远端按键。

每个成功会话还完成六个实际横竖屏／系统栏边距样本，使用同一个 Game 和 trace，最后恢复原模拟器尺寸。深色旧包的 Game 资源实际为 fontScale 2，其他成功样本为 1。图像来自自身 View.draw 和自身解码 Surface 的 PixelCopy，没有 OS 截图或系统输入注入。

## 模拟器包缺项与修正

原 Debug 包缺少 `lib/x86_64/librhremote_transport.so`。实际路由记录两次报告 `dlopen failed`，分别跳过打洞和中继，然后回退到 Tailscale。正式 ARM64 preview.114 包始终包含该库，且本轮 SHA-256 保持不变。

新增 [build-android-emulator.ps1](../tools/build-android-emulator.ps1)：用对应 Rust target 和 NDK 构建 x86_64 库，再准备源码、构建 Debug 包，最后核对两个必需库的 ELF 架构。模拟器 APK 留在既有 Gradle 输出，不覆盖正式 ARM64 dist。操作说明见 [ANDROID-EMULATOR.md](../tools/ANDROID-EMULATOR.md)。

完整包的 19 份 DEX 与最初 Debug 包逐字节相同，原 Moonlight x86_64 库也相同；改动是补入缺失的 Rust 库。完整构建的 428 项 JVM 回归继续通过。`transport-load03` 在实际模拟器内调用正式 RhTunnel 的加载和 JNI 身份方法：独立缓存目录的临时密钥可复用并已删除，正常身份及其余 14 份文件不变，没有网络连接或服务端授权。

| 本地产物 | SHA-256 |
| --- | --- |
| 完整 x86_64 Debug APK | `E7961AEDAAB970511DEBD64C8B8538B54FC9631C7FBB3E3A00FCD09840AD42DE` |
| APK 内 x86_64 Rust 传输库 | `2D3D1CB10C89E57C834DD3C9D80F1BDDCE2BD620211BFDFC3E62EB17CDA00D65` |
| 编译后、NDK 去除调试信息前的 Rust 库 | `74F58A91C0BEF0941E1E48068DDF13076F35D384B3B16B7808602A6D01478BC7` |
| 原 ARM64 Release APK，保持不变 | `0E3E6E8C7F09145619D210A2CBF8EC70337637BC5DBF4EB5CCC09511F301971D` |

## 新失败的具体等待

`complete05-failure-summary.json` 对应最新失败：

1. 最初局域网预检通过，RTSP SETUP 响应却少一字节，客户端拒绝该报文并清理自己的启动请求。
2. 之后打洞耗时 13364 ms、中继耗时 13096 ms，均以 `deadline has elapsed` 结束；两项路由记录的 `timeoutMs` 为 8000，实际范围还需拆开初始化、连接和清理确认，不能据此直接缩短预算。
3. 这一轮路由解析累计 26600 ms，回退到 Tailscale 后又出现 RTSP SETUP 短报文。下一次恢复仍在解析时，测试的 60 秒观察预算结束。

过滤后的原生日志保留两种样本：SETUP expected=148／received=147，以及 expected=155／received=154；`interrupted=0`。这证明收到长度与声明长度不符，并不证明具体丢失位置已确定。错误发生在解码首帧之前，不能将这一轮记作大字号控制布局失败。恢复界面的取消按钮在实际 200% 字号窗口中完整可见并启用；连接可靠性和等待原因继续调查。

已检查当前客户端 RtspConnection.c：接收循环按 recv 的返回值累计 offset，读到 EOF 后直接传给解密检查，没有在这段路径主动减去一个字节。服务端 rtsp.cpp 的 respond／send 源码也按 message.size() 完整循环发送；这些源码检查不等同于已核对部署二进制的实际发送字节数，后续需继续对齐运行证据。

## 配置、服务与边界

每轮都确认自有 Activity 销毁、目标进程结束、14 份身份／数据库／偏好及原同步备份还原，系统字号回到 1.0。正常独立连接密钥保持原值；Sunshine 配对授权文件保持原哈希，服务与主进程没有重启。

完整包的正常请求包含 RhHost 既有的独立连接授权刷新；这期间 `transport-grants.json` 哈希发生变化。不将该缓存列为未改动文件，也没有用旧副本覆盖服务端授权。该流程使用原有配对及原模拟器独立身份，没有新增 Sunshine 配对。

SSH 显示快照先遇到命令长度限制，改为 stdin 后，QueryDisplayConfig 在非交互会话返回 Win32 error 5。失败记录均保留；只确认服务／进程状态，不将空显示列表当作显示布局未变的证据。真实会话仍通过现有主机接口读取屏幕目录，没有提权或部署新的采集服务。

内置打洞和中继的实际成功连接、外网、OPPO、物理触屏及完整 UI 目标都没有据此宣布完成。最新完整包深色首帧失败也继续保留在 [TODO](../TODO.md)。

实际浅色会话图还暴露了上游英文性能提示（Slow connection to PC／Reduce your bitrate），其中文、主题及安全边距仍需整理；没有将当前会话图片视作完整视觉验收。

最终 `final-audit.json` 的 67 项事实及完整性核对通过，`completePackageUiAcceptancePassed` 明确为 false。最初审计混用了编译后与 NDK 去除调试信息后的 Rust 库哈希，已分别对照 Cargo 产物、NDK 中间文件及 APK 条目核对，原报告保留在 `audit-attempt02-artifact-mismatch.json`。清理兜底没有发现待删除产物，删除逻辑字节数及实测释放量均为 0，检查后 D 盘可用 978002485248 字节。
