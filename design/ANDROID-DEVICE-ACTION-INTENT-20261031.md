# Android 设备操作的输入归属

2026-10-31，本地候选 preview.147／365。沿用已选的设备列表与详情工作区，修正按住按钮期间设备或操作改变时的误操作。状态与入口的前一轮整理见[146 记录](ANDROID-DEVICE-CHECK-FLOW-20261031.md)。

## 行为

设备页的连接／检查、配对、连接设置、编辑资料、上移／下移以及设备行，绑定明确的动作和参数。触摸或确认键按下时保存当前归属；松手时不再临时读取最新 selected 或 chosen() 来决定目标。

- 按住检查，后台变为已配对：旧按压取消，不发起连接。
- 按住 A 的连接或编辑，选中设备变为 B：旧按压取消。再次点击使用当前 B。
- A → B → A、按钮禁用后恢复、父区域隐藏后恢复：不会复活中间已经失效的按压。
- 设备行由选择设备变为连接设置侧栏时，旧按压不进入另一个操作。
- 仅名称更新、动作及 UUID 不变：正常点击保留，执行一次。
- 已经完成的回车动作保留原目标；尚未执行的触摸排队点击在绑定改变后取消。不会为了统一测试结果而给回车增加异步延迟。

RhBoundAction 保存不可变的动作／JSON 参数副本和单调递增的绑定版本，保留 Android Button／LinearLayout 原有触摸、键盘及无障碍入口。取消、失焦、隐藏、禁用和卸载会使旧输入失效；默认 View 排队点击先于本次释放清理，清理只处理自身 press 对象。元数据刷新若未改变实际绑定，不取消正常操作。

这些控件仍调用原有 RhHome／RhActions API：`devices.refresh(uuid)`、`device.connect`、`device.pair`、`device.settings.open`、`device.info.open`、`device.move` 和 `device.select`。没有增加绕过服务端身份、证书或配对确认的连接方式。

## 实测与证据

证据根目录：`D:\RHRemoteTools\ui-device-intent147-review-20261031`。

- `red-intent.log`／`red-intent-results.xml`：原实现四项检查中三项失败，实际收到由检查变成连接、目标变 B 和切回 A 后仍执行的动作。仅名称刷新原本就应通过。
- 最终 Release **505** 项通过，0 失败／错误／忽略。新增 13 项包含真实 View 事件、其他设备按钮、父区域与启用状态往返、取消、排队回调、回车重复和参数副本。其余原有 Controller、更新、同步和会话检查也通过；详见 `release-test-counts147.json`。
- `native-intent-01.log`／`native-intent-01/result.json`：专用 emulator-5580 的独立 `com.rhremote.client.uicheck147` 包，在附着到真实 Android 窗口的 View 上通过 **52** 个场景。浅深两主题、触摸／回车和十三种变化，均先检查控件附着且可见。事件直接分发给自身控件，没有向系统输入分发器注入输入。六个已经完成的原目标动作与五十二次新的明确点击，共 **58** 次动作边界记录。
- `native-layout-01`：相同生产控件另通过 **64** 种浅深／宽窄／字号／设备状态布局及 **88** 次动作分发。包含文字、单行状态标签的实际内容区、48dp 按钮和父容器边界；图来自原生 View 自身 Canvas。
- 共 **72** 张图：附着窗口操作后的八张图包含滚动与焦点状态，不能当作首页初始布局；其余六十四张为独立布局场景。[预览与结果](D:/RHRemoteTools/ui-device-intent147-review-20261031/gallery147.html)

附着窗口使用的 RhUiFixtureActivity 只在 `src/debug`，没有初始化正常客户端。最终 ARM64 Release 清单和 Release 类目录均不含此 Activity。隔离包取回证据后已移除，模拟器原 129／347 未升级，没有改全局显示或字号。

本地 Release APK 为 `dist/android/RHRemote-v0.2.11-preview.147.apk`，versionCode **365**，非 debuggable，SHA-256 为 `615E0CB51083D74844A7C6A705B7750A5216909D7984678013C78183836AB3AB`，签名与 129 一致。选定源码、包与验证摘要在 `source-manifest147.json`、`release-package147.json`、`final-audit147.json`。原有八项二进制／配置／主机授权／APK 摘要以及上一版 146 APK 均未变。

清理策略明确保护模拟器仍安装的正常 129 签名包，避免新候选产出后将它当作超期产物删除；当前 147、上一版 146 和在用回退基线 129 分别保留。

## 剩余范围

本轮没有真实远程会话、系统输入注入、物理键盘／触摸或 OPPO 验收；API 动作接收端记录参数，没有连接两台真实主机来证明控件归属。没有替换日常 Windows 客户端、重启服务或公开发布。

按逐设备网络配置执行完整检查、Android 的 HTTP／HTTPS 来源与错误分类、其余动态页面的操作归属以及完整交付继续保留。现有诊断会通过 RhNetwork.resolve 进入共享 RhTunnel；接入并行完整检查前，还需要核对通道归属、取消后的清理和热通道复用，避免旧检查收尾影响新连接，或使检查后再次建立同一中继而增加等待。没有直接把诊断函数当作新的检查按钮后端。完整 UI 目标未完成。
