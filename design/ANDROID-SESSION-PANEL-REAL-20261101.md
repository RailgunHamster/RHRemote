# Android 会话控制的真实响应与分类反馈

记录 175–178，最终本地候选 178／396。仅使用 emulator-5580 的隔离包 `com.rhremote.client.uicheck172` 与 winserver 的临时测试身份。Windows 产品、正常 Android 129／347、OPPO 和公开发布未变。完整 UI 目标未完成。

## 保留的修改

会话初始化时准备收起的控制面板，首次展开复用已有控件。174 的按需创建策略在真实会话中把明显的创建等待移到了第一次点击，因此撤回这一策略；同一窗口固定配色、批量布局、模型更新不触发操作回调和原有动作接口保留。`controlsLayout.created` 与 `expanded` 独立：控件已准备时可以仍然收起，释放后为 false。

修复分类反馈的归属：声音页隐藏时收到的结果，切到声音页立即显示；切到其他分类立即隐藏；回到声音页保留当前结果；明确清除后不再恢复旧结果。回归最初发现这一失败，未删掉断言；随后保存最新反馈状态，并在切页时重新绑定。释放面板时移除其主体引用，迟到事件不能重新创建或触发操作。

保留 `session_controls_body_create`，并增加 chrome、input、displays、audio 与 bind_initial 分段统计。主体准备现在发生在初始化阶段，不能继续把它计作首次展开的创建耗时。

## 真实计时与范围

固定条件为 winserver、局域网、现有 1920×1080 物理屏、30 fps、H.264、手动 6 Mbps、模拟器软件解码。服务端配置保持 `encoder = software`，本轮不是 QSV／MF 或真实 Android 硬件解码的性能结论。客户端音频播放关闭，没有存储音频样本；服务端日志仍会出现音频捕获初始化，不能将其描述成整条链路不采集声音。

计时分开记录：

- `firstFrameSignalMs`：测试观察到客户端 `firstFrame=true`，包含轮询与主线程调度，非显示器首次呈现时间。
- `surfaceContentConfirmedMs`：自己的 SurfaceView 经 PixelCopy 确认有实际内容，包含观察调度和重试，非精确首次呈现时间。
- `requestToRootDrawMs`：展开请求到本应用根视图进入 OnDraw，且控制开关已可见；不证明合成器已经呈现像素。
- `dispatchAndIdleMs`：动作执行与 instrumentation 等待 UI 空闲，不能代替面板绘制时间。

同一个最终观察器 APK 的浅色对照：

| 分项 | 177 按需创建 | 178 初始化准备 |
| --- | ---: | ---: |
| 首次展开至根视图绘制回调 | 603 ms | 62 ms |
| 首次展开动作及 UI 空闲 | 1089 ms | 321 ms |
| 首帧状态观测 | 6254 ms | 6179 ms |
| 实际 Surface 内容确认 | 7813 ms | 7786 ms |

这组短测支持首次展开响应改善，没有观测到明显的连接首帧退步，但不构成整体启动或所有负载的无回归证明。178 的主体创建为 52.047 ms，发生在初始化期间。

178 深色完整控制会话也通过：首次展开至根视图绘制为 119 ms，主体初始化 132.078 ms，但首帧状态观测为 9629 ms，Surface 内容确认 11289 ms。该轮 Desktop 阶段约 2111 ms（显示准备约 1472 ms）、RTSP 握手约 1043 ms、视频建立约 660 ms、音频建立约 561 ms；其余调度／首帧等待仍在。不能将慢样本全部归于 UI，也未排除负载交互，更不能宣布首帧或连接稳定性已解决。

此前 177 深色按需创建的首次绘制曾为 1297 ms、等待 UI 空闲 1895 ms，说明慢展开并非只有观察器等待。这一轮的观察器 APK 与最终版不同，但计时探针和会话流程源码相同；不把它当作上述同 APK 的严格配对样本。

## 功能与布局验证

最终完整 JVM 为 67 个套件、624 项，无失败、错误或跳过。覆盖准备后的控件复用、最新模型与快照、分类反馈、清除、释放、键盘 Enter、开关与共享动作的一致性。

原生反馈场景使用同一个观察器：旧 175 在浅／深色、1／2 倍字号的四组场景中均失败，表现为所属页看不到结果、其他页留下旧结果；178 四组均通过，且没有发出任何操作回调。旧失败结果和截图原样保留。

普通、大字号及三种尺寸的几何检查，旧／新各 14 组通过；100 张对应 View.draw 图逐字节一致。检查继续包括顶部入口、开关、断开、滚动可达、十个悬浮键、空白触控穿透和颜色对比。这些图来自自己的原生 View，不是操作系统截图。

178 的浅色真实会话通过首帧、首次展开、复用、已附着窗口里的开关标签触摸和本地 Enter；深色完整会话另通过分类、音量状态、模式、移动／缩放、统计、悬浮控制及退出／继续连接。没有发送远端输入、切换物理屏、物理断网、播放音频或验证 OPPO。长时间动态串流与实体窗口验收仍保留。

## 未接受的尝试与观察器修正

176 尝试跳过平台 Switch 默认皮肤，保留原生交互并直接设置自己的轨道、滑块和焦点。功能与布局通过，但真实首次展开／空闲为 1097、275 ms，旧实现为 764、841 ms，波动过大；没有稳定收益证明，已撤回，不作为优化交付。

新增的离屏触控／焦点检查最初失败，固定旧版也复现。离屏 View 没有窗口焦点，而且同一个同步主线程回调不能证明延后点击已执行。验证改为已附着的真实 Game，等待 UI 空闲后核对模型；没有把 performClick 冒充真实触控通过。原失败全部保留。首版像素差异掩码还漏记了滚动后部分露出的开关，最终观察器记录真实控件边界，保留完整可见和对比度断言。

早期 baseline173 的测试 JSON 错印为 174，是观察器内联 BuildConfig 常量造成。实际 APK SHA 与 dumpsys 版本仍证明其为 173；原文件未覆盖，后续改读 PackageManager 实际版本。

## 保留的真实失败

- `winserver176-profiled02` 在 LAN 地址检查约 66 ms 后出现 `unexpected end of stream`，未进入串流，不能计为 UI 耗时样本。
- `winserver178-prepared02` 与 `winserver178-retained03` 均在首帧前 RTSP 握手 `-1`；两次均有 `launch_cleanup_reply=cancelled`。后一轮原生日志明确为 `SETUP expected=155 received=154 interrupted=0`。只保存白名单错误字段，没有存储原始协议正文。缺字节的来源尚未确定，不能归咎于主题或新的面板创建策略。
- 主线程仍出现 `RhShared.clientId` 类锁等待和 `KeyboardTranslator.KeyboardMapping` 的逐键位置映射调用；后者栈进入 InputManager Binder，代码在初始化循环中逐键调用 `getKeyCodeForKeyLocation`。锁持有者和系统调用内部等待仍未查清，没有声称达到机器码／硬件级根因。

预检还修正了一个判断依据：ENet 的 CONNECTED／DISCONNECTED 不是独立串流会话计数，不能以两者数量不相等认定仍有业务串流。本轮依据最新实际分配为一个会话、其后断开与编码器结束、无既存 RTSP 连接和未变的服务进程推定空闲；服务没有直接暴露会话数。未加入或结束他人的会话。

## 结束状态与证据

仅移除本轮临时授权，原有 8 个配对及鉴权文件字节一致。两台显示器各 15 个字段保持相同；引擎 PID 30252、辅助服务 PID 60240 和 starts=1 不变。隔离主包／测试包、端口转发、临时控制令牌与任务管理凭据均已删除。正常 Android 129／347 的三个配置及八个受保护文件未变，模拟器显示、密度、字号、旋转未变。没有重启服务、正常客户端升级、ARM Release 或公开发布。

最终隔离主 APK SHA-256：`FD1FECD13B163B36BE82D0DF7430D7635C345098EA8BBC080DE5FA43D539DEA9`。观察器：`7117F75D9590F6088256B8AD0BA9EDABCD4F59EAF15A123DEF2CBAF5D28A84DC`。

证据目录：`D:\RHRemoteTools\ui-panel-session175-review-20261031`。

- `prepared178/junit`、`prepared-build178-final.log`：最终 624 项与构建；初版 178 的失败另存 `prepared178-failure-junit`。
- `feedback178-profiled01`、`feedback178-prepared01`：同一观察器的旧失败／新通过及 32 张分类状态图。
- `geometry178-profiled01-*`、`geometry178-prepared01-*`、`geometry-comparison178.json`：14 组与 100 张字节对照。
- `actual-session-comparison175.json`、`actual-session-comparison178.json`、`candidate176-withdrawal.json`：真实计时、撤回及失败边界。
- `rtsp-native-errors178.json`、`failed-rtsp-cleanup178.json`：缺字节与两次按次取消回执。
- `host-layout-verification175.json`、`host-cleanup175.json`、`cleanup175.json`：显示、原授权及正常配置保护。
- `artifact-cleanup175/report.json`：按既有保留规则执行，无文件删除；此前冻结审计未重跑。

下一步继续定位主线程锁、输入映射系统调用、自然 HTTP／RTSP 截断及启动长尾，再补正常启动、实体 Windows、副屏／DPI、OPPO、物理网络与最终交付。当前范围不替代整套 UI 验收。
