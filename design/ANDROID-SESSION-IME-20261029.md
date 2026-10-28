# Android 会话键盘开关

本地候选 preview.72 / versionCode 290。测试仅使用已有专用 emulator-5580 和 winserver，未操作 OPPO、未发布。证据根目录：`D:\RHRemoteTools\ui-android-input72-review-20261029`。

## 发现与对照

preview.71 的实际“键盘”按钮会收起面板，但 `repeat02`、`repeat03` 在 6 秒内均未观察到 IME。`repeat03` 在同一会话显式聚焦 StreamView 并请求显示后成功。测试仍保留失败，没有把诊断请求算作按钮通过。

旧路径是 Game.toggleKeyboard 调用 InputMethodManager.toggleSoftInput，且界面自行翻转布尔值。Android 官方已将该 toggle API 标为弃用，焦点切换时不能据其调用推断实际状态；应明确请求显示或隐藏并观察窗口状态。[InputMethodManager 文档](https://developer.android.com/reference/android/view/inputmethod/InputMethodManager)、[键盘显示与焦点要求](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility)

首个显式请求候选能打开键盘，但连续操作暴露了另外两种失败：

- `repeat04`、`repeat06`：先观察到关闭，再次点击前却重新变为可见，按钮因而发出了关闭请求。
- `repeat05`：打开尚未可见时按返回，关闭在 13 ms 后被确认，随后键盘又出现；最终测试明确失败。

本机依赖 AndroidX core 1.16.0 的 classes.jar 经 javap 检查：SoftwareKeyboardControllerCompat.Impl30.show 先调用平台控制器，再调用 Impl20.show，后者通过 View.post 再请求 InputMethodManager 显示。官方源码也保留这一结构。[AndroidX 源码](https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/view/SoftwareKeyboardControllerCompat.java) 本地字节码记录为 `androidx-ime-bytecode.txt`。这解释了为什么不能只取消我们的轮询而保留库内部的显示排队，但不证明它是所有反复显示样本的唯一原因：`repeat06` 改为单次平台控制器请求仍失败。

## 实现

新增 RhSoftKeyboard，由当前 Game 会话独占。按钮与上游 Game 手势入口都调用当前实例的同一动作，不重新查找可能已被替换的全局会话。

- 使用单一 InputMethodManager 显示／隐藏路径；聚焦流画面后将显示任务放入该 View 队列。替换、返回取消和销毁均移除该任务，并使旧代号失效。
- 通过实际窗口 IME insets 读取可见值；动画尚未结束时不把目标值算作操作完成。没有 insets 时不拿请求目标冒充可见状态。
- 每次请求最多观察 2 秒；超时取消尚未确认的显示，面板显示失败及重试入口。观察只在操作待完成期间运行。
- 销毁取消观察和回调，并仅为本控制器发起过的显示请求执行隐藏；未请求显示的实例不关闭别处已有的键盘。
- 统计新增 ime_request、ime_observed、ime_request_end，记录本会话序号、目标、可见值及耗时，不记录文字。

没有修改生产视频、编码、网络或远端输入注入实现。测试中的 1080p30、6 Mbps、软件解码、声音关闭是临时 UI 验证条件，不代表默认画质或性能验收。

## 验证范围

完整 380 项单元测试通过，无失败、错误或跳过。新增 8 项键盘测试覆盖目标状态未显示、动画未结束、未知 insets、超时重试、旧回调、销毁，以及实际 WindowBackend 排队任务的替换和取消。

最终候选的 `repeat07` 为浅色完整流程：实际按钮打开两次、应用返回关闭、上游 Game 手势入口关闭，以及第三次显示尚未完成时返回取消，均通过；随后 600 ms 内未重新弹出。连接保留，展开收起开关位置未变。真实显示和隐藏确认分别为 444 / 398 ms、539 / 385 ms，取消为 24 ms；这些是本次状态确认时间，包含系统动画，不是网络输入时延。`session-ime-flow.json` 及对应统计保留结果。

同轮还通过五轮八种具名快捷键与移屏、安全注意序列、Escape、Alt+F4，共 47 步远端回执；物理屏 → 既有虚拟屏 → 原物理屏的服务端尺寸序列核对通过。它们不解释 preview.71 的 `light06` 偶发 Ctrl+A 样本，生产按键注入未改；该调查继续保留。

`repeat08` 深色主题再次完成同一 IME 检查和完整 47 步，JUnit 81.868 秒通过；浅色为 74.761 秒。深色显示／隐藏确认分别为 440 / 372 ms、463 / 393 ms，取消为 4 ms。两条完整统计均有 trace_end，丢失事件为 0，未达到容量上限。失败的 repeat02～repeat06 原样保留，其测试窗均确认结束，临时任务已移除。

不会将这些测试算成主机文字输入、外接键盘、OPPO、外网、系统键盘像素图或物理显示呈现通过。界面图仅由自身 View.draw 产生，不包含系统 IME；可见状态依据实际 IME insets 与动画结束。

## 配置及本地产物

`restore-proof.json` 核对日常 Windows 偏好、模拟器 shared 文件、原服务端授权和完整显示器目录属性均未改变。统一服务 PID 60240、Sunshine PID 30252、starts=1 保持原值，输入桌面为 Default。模拟器保持 1512×1008／240 dpi／字体 1.0。本轮没有重启模拟器、启停业务服务或修改系统输入法设置。

模拟器最终主 APK 的 SHA-256 为 `ab413111dfc657f411ac0620b1e76313d5a0a45bb0ee52e3b3225f3c954386ad`，已与设备上的 base.apk 核对一致；最终正向测试使用这一文件。总清单为证据目录中的 `verification-final.json`。

本地 ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.72.apk`，290，SHA-256 `1801cd9a58d81f07d4c657d10355a4f72564286e48febc013dc7e449109caad8`。apksigner 验证通过，签名 SHA-256 仍为 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`；视频／输入原生库 SHA-256 与 preview.71 相同。Windows 继续使用 preview.70。未公开发布，完整 UI 目标继续保持未完成。
