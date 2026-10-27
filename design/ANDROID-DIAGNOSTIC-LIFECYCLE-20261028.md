# Android 诊断任务与页面生命周期

本地 Android preview.36／versionCode 260。Windows 保持 preview.47，未启动桌面测试窗口，未操作 OPPO 或业务服务，未公开发布。

## 原因与修改

原来的 `device.network.diagnose` 把忙碌标记保存在 RhHome，并在后台闭包里持有 Activity。重建后的新 RhHome 会把标记重置为 false，旧任务仍在运行；旧回调也会直接向后来打开的页面写入“诊断完成”。`diagnostic-before.xml` 记录两个回归分别因“重建忘记忙碌”和“关于页状态被覆盖”失败。

新增 `RhDiagnosticWork`，使用 Application context 持有一个进程内任务，提交时冻结设备、配置和地址草稿。当前页面订阅结果，旧页面销毁只退订自己的监听者，不会移除新订阅。重建与重新打开页面都不重新发起请求；重复动作继续被忙碌保护拒绝。执行器拒绝任务后释放忙碌并保留可重试错误。

完成时仅更新当前网络页的诊断按钮、报告及本页状态，不再重建整份设置表单；保留正在编辑的地址控件、内容、滚动和光标。其他页面不接收这条完成提示。结果按诊断设备 UUID 保留，避免以其他连接的最新选路覆盖已完成的诊断报告。

界面错误改为说明实际处理方式。UUID 不符时显示“设备标识与已保存的设备不符，请检查连接地址”，不会在已经诊断后再次提示运行诊断。原始异常说明和逐地址结果仍可由接口读取。诊断的 UUID／证书检查、路由顺序、网络预算及连接参数均未放宽。

## 接口状态

仍使用原共享动作 `device.network.diagnose`。Home 状态新增 `diagnostics`：`operationId`、`uuid`、`busy`、`phase`、`error`、`detail`、`result`。`phase` 为 idle／running／completed／error；`error` 用于界面，`detail` 保留原始说明，`result` 为本次诊断快照。原 `network` 仍提供选路结果；`networkBusy` 同时包含连接准备和诊断忙碌，重建不会解除重复操作保护。

该状态只保存在当前进程，不加入配置同步或 Activity Bundle。系统终止进程后的任务反馈仍在后续范围。

## 验证

证据目录：`D:\RHRemoteTools\ui-diagnostic-review-20261028`。

- 最终 284 项单元测试通过，无失败、错误或跳过，见 `unit-summary-final.json` 和 `android36-final-build.log`。新增两个完整页面回归，以及输入快照、Application context、最新订阅者、失败重试、队列拒绝等任务边界。单元测试中的网络边界由 Robolectric shadow 暂缓，不算真实网络传输。
- 专用无窗口模拟器 emulator-5580：1512×1008、1008×1512、540×1200／字体 1.3，各通过成功和 UUID 不符两个原生流程，共六条，12 次真实 PcView 重建、27 张自身 Canvas 绘制图和六份结果 JSON。每条流程两次主题重建，等待画面覆盖浅深色；最终结果图为原浅色主题，诊断结果其余深色边界仍需补齐。
- 原生测试暂缓应用自己的工作队列，在诊断排队时重建页面；随后释放队列，调用正式 `RhNetwork.diagnose` 和模拟器 loopback HTTP／TCP。每条流程实际 serverinfo 请求恰好一次。成功结果仍使用提交时的地址，新输入和光标不被完成回调覆盖；UUID 不符保留明确错误，关于页反馈不变，返回网络页可查看结果。这不证明真实 WAN 请求进行期间的重建或网络性能。
- 所有按钮通过实际 View.performClick 或共享动作，编辑通过自身控件／动作；没有 UiAutomation、系统输入、computer use 或系统截图。只访问专用模拟器的 loopback 测试服务，没有 Sunshine 配对、串流或声音。
- 最早一轮原生流程在最终配置核对时失败：测试样本缺少默认元数据／本端配置，且预期 JSON 对象被主题操作引用并改变。补齐样本并在操作前冻结预期值后重新完整执行矩阵；失败日志和九张早期图保留在 `native-landscape-initial`。这些辅助失败不算新的产品缺陷。
- 模拟器共享文件 SHA-256 前后均为 `fe08b5761c960bdd24fb980a58aac5ab4c58d8698bae1a3d2536270ae273a0d8`；恢复 1512×1008、density 240、字体 1.0。临时设备、主题及自动更新检查时间均恢复。

## 产物与边界

ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.36.apk`，包名 com.rhremote.client、versionCode 260、非 Debuggable，签名维持原 SHA-256 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。APK、代码及最终证据哈希见 `verification-diagnostic.json`；预览候选只在本地准备。

本轮没有验证 OPPO、真实远程主机、UDP 媒体、进程被系统终止、正式 APK 覆盖安装或全部主题／连接／会话流程。完整 UI 目标继续按 UI-FLOW-SPEC 和 TODO 验收。
