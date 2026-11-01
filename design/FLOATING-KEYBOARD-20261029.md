# 会话悬浮功能键

## 当前用户入口与本地交付核对

preview.101 已把两端开关固定到控制面板顶部，三个分类均可见，开启后直接收起面板并显示按键。用户保存并关闭后，Windows 日常客户端已原地升级到 preview.101，配置与配对文件保持一致，升级后保持关闭。最新包、回归、实际升级及仍未验收的边界见 [顶部入口记录](FLOATING-KEYBOARD-ENTRY-20261029.md)。下面 preview.93 的入口是旧版路径。

2026-10-29 核对：日常 Windows 已安装的 preview.93 包含悬浮按键，并非只存在于独立测试版。连接后进入“控制 → 操作 → 悬浮键盘”，默认关闭；半透明按键显示在远程画面两侧，控制面板展开时暂时隐藏。用户询问时曾误答 Windows 尚未安装，已更正。当前日常客户端正在运行，本轮没有关闭、升级或重新打开它。

Android ARM64 本地包已重新生成 `dist/android/RHRemote-v0.2.11-preview.100.apk`（versionCode 318），SHA-256 `F19563567A077520118FE5CE6737300AF833F32CB6C908E9A0B539951BCEA308`；签名校验通过，与 preview.87 相同。当前完整 JVM 回归 414 项通过。未操作 OPPO、未覆盖其安装、未公开发布；现有模拟器回执不能算作 OPPO 实机通过。交付核对为 `D:\RHRemoteTools\ui-floating-delivery100-review-20261029\delivery-audit.json`。

以下为之前各版的实现与验证记录。

最新 Windows preview.89 已在实体副屏取得全部 10 个按键的远端回执，修复退格和 Shift+Tab 缺字；浅色完整流程通过，深色后续控制接口／退出失败及剩余范围见[Windows 实测](WINDOWS-FLOATING-KEYBOARD-20261029.md)。Android 保持 preview.87，OPPO 未操作。功能入口仍为“控制 → 操作 → 悬浮键盘”，默认关闭，尚未发布。

后续 preview.78 的实际竖屏、会话内横转竖及按键回执见[方向与滚动验证](ANDROID-SESSION-ROTATION-20261029.md)。下面保留 preview.74 的初次实现和当时边界；当前剩余项以 TODO 和最新 Windows 实测为准。

本地候选 `0.2.11-preview.74`，Android versionCode 292。Windows 使用 Rust / Slint；Android 保持原生 View / Java。证据目录：`D:\RHRemoteTools\ui-floating-keyboard74-review-20261029`。未发布。

控制面板的“操作”分类中提供“悬浮键盘”开关，默认关闭。开启后收起面板，半透明按键浮在远程画面两侧：

- Esc 在左上，Tab 和 Shift+Tab 位于左侧偏上。
- Backspace、Delete 位于右上，Enter 在右侧中部。
- 方向键按倒 T 形排列在右下。

按键保留 48 dp 触摸高度和边缘留白；窄矮窗口保留导航键，无法容纳的极小窗口不建立按键区。开关是当前客户端的运行状态，不同步到其他设备或覆盖用户连接配置。系统输入法、控制面板和对话框展开时隐藏该层，避免遮挡；关闭、断开或销毁后不再接收按键。

每个按键使用共享 `stream.shortcut`，新增 Tab、Shift+Tab、方向键、Enter、Backspace 和 Delete 的共享键码；保留已有 Esc 及组合键。开关对应 `stream.keyboard.floating.set {enabled:boolean}`。Windows 测试 API 支持 `window:keyboard`，自身渲染导出对应 `view.keyboard.snapshot`。

Android 使用 Activity 内透明、不可点击容器，只有按钮本身接收触摸，不申请系统悬浮窗权限。Windows 悬浮窗从属于已验证 PID 的串流窗口，初次分配仅 1 像素、保持不激活；按键区域通过 SetWindowRgn 设置并读回，空白不属于该窗口，透明度通过 GetLayeredWindowAttributes 读回核对。窗口大小变化才重建布局／区域，不改视频解码、码率或服务端。

## 验证状态

完整 Android JVM 回归 386 项通过；随后补充开关忙碌时的禁用处理，最终定向回归 39 项通过。Windows Core 125 项和最终 UI 181 项通过（另有一项既有 UIA 用例忽略），包含实际 Slint 按钮回调发送全部 10 个按键、忙碌和隐藏窗口拒绝发送，以及不显示窗口的原生 SetWindowRgn / GetWindowRgn 多尺寸读回。原生时钟和配对持久化回归通过。

Android 的 `floating03`（浅色）和 `floating04`（深色）真实连接 winserver，各完成 12 步：通过应用触摸分发点击浮层空白处激活已验证的自有测试窗口，逐一点击 10 个悬浮键，并关闭该测试窗口。每一步均有远端事件计数、前台窗口归属及修饰键释放确认；开关来自实际 View，关闭后浮层隐藏。没有使用系统输入驱动客户端，也没有发送 Ctrl+Alt+Delete。

两轮首帧信号分别为 3817 ms、4554 ms，并核对到非黑解码像素。它们是 6 Mbps / 软件解码 / 无声音的模拟器 UI 验收，不能替代用户默认画质、OPPO 性能或长期流畅度结论。两个源测试任务均已结束并移除，正常 Windows 配置和主机配对文件 SHA 保持不变。页面 IME 测试的六尺寸／主题组合也通过，临时分辨率、字号、共享配置与外观偏好均已恢复。

早先 `floating01`、`floating02` 在发送按键前失败：测试错误地把原生 Switch 的 performClick 返回值当成操作结果。CompoundButton 没有 OnClickListener 时仍执行切换和 OnCheckedChangeListener，但可以返回 false。最终核对完整可见区域、共享动作后的状态和真实远端回执，失败证据保留。

深色实际预览为 `floating04/android-evidence/session-floating-keyboard-composite.png`：由本应用解码 SurfaceView 的 PixelCopy 与自身 View.draw 导出，不是系统屏幕截图。Windows 安装包及 ARM64 Release APK 已在 dist 生成，签名验证通过，没有发布或覆盖正在使用的客户端。

初次实现时 Windows 仅枚举到虚拟屏，没有向主屏或虚拟屏回退；因此当时未验收实体显示与回执。实体副屏后来恢复，最新 Windows 范围见本文开头。OPPO 仍暂停，Android 实机效果不计为通过。
