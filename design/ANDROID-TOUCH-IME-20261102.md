# Android 输入法浮标与单指滚动

2026-11-02，测试包 `0.2.16-preview.1`，Android versionCode 422。

## 定位

`RhSoftKeyboard.WindowBackend` 原先只请求输入法显示，没有指定窗口调整方式。全屏串流视图获取焦点后，系统可能按默认策略平移整个窗口，使顶部控制浮标移出屏幕。`RhStreamPanel` 的安全区域也只处理系统栏和刘海，没有考虑输入法。

Android 官方对 [窗口调整方式](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#SOFT_INPUT_ADJUST_PAN) 和 [IME insets](https://developer.android.com/develop/ui/views/layout/sw-keyboard) 的说明与这条代码路径一致。本轮未连接 OPPO，未取得其系统窗口平移轨迹，不能把模拟验证写成 OPPO 实机复现。

单指滚动与默认双指滚动有明确的计算差异：单指使用 `3 / density`，默认双指使用 `3`，两者输入都是视图像素。例如 density=2 时，同样移动 10 像素，前者只有 15 个高精度滚轮单位，后者为 30。旧单指还先累积 12dp 死区，跨过后一次发出全部累计位移，造成起步迟钝后跳动。单指四向滚动把横向抖动也直接送到远端；默认双指路径只滚动纵向。

## 修改

- Android 11 及以上请求输入法前采用 `ADJUST_NOTHING`，保持串流窗口位置和画面尺寸。旧系统使用 AndroidX 兼容观察所需的 `ADJUST_RESIZE`，避免 `ADJUST_PAN`。结束时恢复原调整标志，保留其余输入法状态位。
- 控制浮标监听 IME、系统栏和刘海的联合安全区域及输入法动画，按可用高度约束位置，保持原动作接口 `stream.controls.toggle`。
- 单指滚动与双指共用 3 单位/视图像素的高精度滚轮换算，并保留小数余量。单指使用系统触摸阈值，启动时扣除死区，随后连续发送增量。
- 以起步方向过滤明显横纵向滑动中的侧向抖动；斜向手势仍可同时滚动两轴。处理 MotionEvent 批次内样本，保留方向反转。
- 保留点击、长按、双指拖动、缩放、交换按钮及反向滚动含义；没有加入惯性动画或改变服务端编码、网络与画质参数。

## 验证与交付

相关 Debug 回归 103 项通过。新增覆盖高密度屏的单双指增量对照、起步不跳动、横向抖动、斜向及批次内反转、小数余量、IME 窗口标志恢复，以及横竖屏中反复显示/隐藏输入法后浮标完整可见、原按钮仍发出相同动作。

Release APK 已编译、签名验证通过，签名与 0.2.15 一致，未设置 debuggable，ABI 为 arm64-v8a。完整 Release 单元测试 688 项全部通过，无失败、错误或跳过。

SHA-256：`440cb07e166d8f729e489f442363e4333344f3bc863b4ab12eaa327d4990fb22`。

测试包存放在 `dist/android/RHRemote-v0.2.16-preview.1.apk`，另已复制并核对共享目录 `\\server-home\public\Software\RHRemote\preview\RHRemote-Android-0.2.16-preview.1-arm64-v8a.apk`，可用原签名覆盖安装。源提交只包含这次修正，没有纳入暂停中的其他 UI 改动。

本轮不操作 OPPO。winserver 上原专用 emulator-5580 未运行；其余可见模拟器属于其他项目，本轮未操作。真实输入法动画、远端应用滚动及弱网下的主观手感仍需 OPPO 复验。此测试包未替换正式更新源的 0.2.15。

完整 108 键悬浮键盘、独立悬浮开关和会话内按设备记忆左右键属于另外的待完成功能，此测试包没有声称包含它们。
