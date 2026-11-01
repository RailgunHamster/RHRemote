# Android 表单与确认框的事件归属

本地 Android `0.2.11-preview.83 / 301`，Windows 保持 preview.81，未发布。证据目录：`D:\RHRemoteTools\ui-form-owner83-review-20261029`。OPPO 未操作。

## 修改前证据

设备设置、应用设置和确认框共享动作分发，但旧页面、旧确认框的监听器没有检查当前目标。这影响未保存更改和移除本机同步设置这两个明确提交点。

| 复现 | 修改前结果 |
| --- | --- |
| 保存确认框 A 选择继续编辑，再建立准备前往“关于”的确认框 B，调用 A 的保存回调 | B 被处理，当前草稿被保存并离开 |
| 新 RhHome 已编辑码率为 22，尚未销毁的旧编辑框产生 64 的文本事件 | 新草稿变为 64 |
| 移除确认框 A 取消，再建立 B，调用 A 的移除回调 | B 被处理，保存账户被清除 |

前两项原始失败位于 `before-RhFormFlowTest.xml`。移除用例首次遇到 Robolectric 没有 AndroidKeyStore；该次不能当作移除缺陷的证明。后续仅在 JVM 测试中装入已保存账户的占位数据，不运行加解密或网络，先遇到旧 OnShow 回调引用已清空 dialog 字段的异常，再等待 OnShow 完成后确认上述移除错误。对应证据为 `before-removal-RhFormFlowTest.xml` 与 `before-removal-settled-RhFormFlowTest.xml`；生产密码加密未修改。

## 当前行为

保存和移除的按钮、取消回调只接受仍属于当前对话框的来源；OnShow 也使用它自身的对话框引用，旧回调不能改写新对话框。页面的 UI 动作在主线程执行前检查未销毁、仍是当前 RhHome；旧编辑器不能把输入发给新页面。恢复到前台的页面重新登记当前目标，后台自动调度也检查页面归属。

正常动作继续使用原有共享 API，保存、放弃、继续编辑、移除和取消的参数没有改变。未改变证书、加密、网络策略、服务端或用户画质设置。

## 已验证范围

Android JVM 完整回归 402 项通过。新用例同时检查当前页面仍能编辑、当前确认框仍能保存或取消，避免全部屏蔽动作后产生假通过。

专用模拟器 native01 使用真实 PcView、RhHome、EditText 和 AlertDialog：创建临时表单设备，验证旧保存确认框不处理新决定、继续编辑保留 37、当前放弃正常返回“关于”；实际重建 Activity 后，旧编辑器的 64 不影响当前的 22，当前可改为 24，并通过新确认框正常保存。此时旧确认框也不能处理重建后的决定。真实回执为 `native01/android-evidence/form-owner-result.json`，自身 View.draw 图为 `current-save-decision.png`。该流程不启动串流或 WebDAV 同步。

临时设备只进入共享配置和 Home 的内存列表，不登记 Sunshine 配对或新网络连接；其共享文件、偏好和原备份由既有会话快照恢复。原生测试辅助代码曾因缺少异常处理声明编译失败，保留在 `android83-full.log`；修正后的辅助包构建为 `android83-native-build.log`。402 项 JVM 结果与原生辅助编译错误分别记录，没有把失败构建计为原生通过。

同步移除的旧回调边界来自 JVM 已保存账户夹具；native01 没有改动同步账户，也不替代真实 WebDAV／OPPO 验收。其余安装升级、物理键盘、外网及 Windows 实体显示仍保留在完整 UI 清单。

## 自动调度及最终核对

本轮增加当前页面归属检查后，重新运行既有真实五分钟调度流程 `wire-auto-83-clock01`。使用应用内受控 TCP／HTTP 夹具和专用临时账户，不连接既有 WebDAV 服务。5／15／30／60 分钟均经实际 Spinner 和保存按钮持久化，实际等满的间隔是 5 分钟；触发时墙钟 315934 ms、单调时钟 315575 ms，没有改写时间。到期前和请求挂起时分别真实重建页面，忙碌控件禁用、重复请求拒绝和结果回到完成状态均通过。关闭自动同步后再观察 35 秒，无新增请求。结果为 `wire-auto83-clock01/android-evidence/result.json`，原生总用时 357.55 秒；该证据不替代其他间隔的完整等待、既有服务、外网或 OPPO。

针对之前观察到的 OnShow／取消时序异常，追加专门用例后重跑表单类 14 项全部通过（`form83-final.log`、`final-RhFormFlowTest.xml`）；402 项完整回归和这次定向结果分开记录，不把新增用例写成已随原完整命令执行。

ARM64 Release 位于 `dist/android/RHRemote-v0.2.11-preview.83.apk`，SHA-256 `59F86F63E1177BD602272287F6C2284AAC41976D0881F01B3CC86FE0904EBCC9`。版本、签名及与上一版证书一致性检查通过；两个原生库与 preview.82 相同。原生 UI 及计时使用对应的 x86_64 Debug 包，ARM64 实机和用户系统安装尚未验证。包只在本地，未发布。

两轮结束后，模拟器 shared／WebDAV 文件、相关偏好和原备份恢复，尺寸／密度／字号恢复 1512×1008／240／1.0；日常 Windows 偏好和 winserver 授权文件 SHA 保持原值。本机 SunshineService 仍运行 PID 91848，日常客户端仍为 PID 43596。最终证据为 `verification83.json`、`package83.json` 和 `final-form83.json`。
