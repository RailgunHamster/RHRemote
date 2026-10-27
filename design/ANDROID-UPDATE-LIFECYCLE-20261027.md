# Android 更新任务与页面生命周期

范围：Android 0.2.11-preview.32（versionCode 256）。Windows 保持 preview.42；OPPO 未操作，未发布。仍需完成正式安装升级及整个 UI 目标的其余验收。

## 问题和行为

旧更新模型、忙碌标记和完成回调都由 `RhHome` 持有。页面重建后新建的模型没有内存中的候选版本和任务状态，旧下载却继续运行；旧完成回调又依赖原 Activity。回归已复现“发现新版本”在重建后变成“尚未检查”：预期 `available`，实际 `idle`。

现在 `RhUpdateWork` 在应用进程内持有单一任务，模型只保存 Application context。当前页面订阅状态，销毁时只解除自己的订阅，旧页面不能解除新页面的订阅。后台工作不捕获 Activity；完成状态、安装交接和控件刷新在主线程处理，避免接口已显示空闲而按钮仍显示忙碌的短暂分歧。

当前规则：

- 检查、下载和校验只有一个进行中的操作。主按钮、旧动作接口及自动检查共享相同忙碌保护；重建页面不重新请求、不清空候选版本和进度。
- 允许更新期间切换主题。错误详情的展开状态只在进程内保留；候选版本、错误原因和重试动作来自同一模型。
- 用户发起的安装准备在后台完成时，等待真实的前台生命周期再交给系统安装界面；重复 `resumed` 不代表已经从安装界面返回。安装确认仍由用户操作。
- 明确关闭页面，或页面已经开始结束时，不发送未完成的安装交接。下载可以完成并保留，重新打开显示“安装更新”。进程退出不保存自动弹出安装界面的意图。
- 自动更新只检查并下载。包名、递增版本、长度、SHA-256 和签名校验保留；安装期间的活动串流保护保留。
- 页面进入后台后停止界面进度轮询，任务本身继续执行；回到前台重新显示当前进度，避免等待安装交接时一直刷新不可见控件。

此处参考 Android 对状态恢复和前后台回调的职责说明：[保存界面状态](https://developer.android.com/topic/libraries/architecture/saving-states)、[Activity 生命周期](https://developer.android.com/guide/components/activities/activity-lifecycle)。既有 HelpWire 参照继续用于设备与会话主流程，不将更新协议声称为 HelpWire 功能。

## 证据和边界

证据目录：`D:\RHRemoteTools\ui-update-lifecycle-review-20261027`。

- `update-recreation-before.xml`：原候选版本丢失的失败回归。其他中间失败日志保留，不能作为通过证据。
- 最终 267 项单元测试通过、无跳过。覆盖任务重建、保存进度、后台完成、前台进入、安装权限返回、重复回调、明确关闭、关闭与销毁之间的竞争、旧页面订阅退役和错误详情恢复；记录为 `unit-summary.json` 和 `android-paused-ui-build.log`。
- 原生测试在无窗口 `emulator-5580` 使用实际 `PcView`、共享动作分发器及真实主按钮。测试源通过包内连接工厂接入模拟器内的 HTTP 服务；候选包是本地签名的 x86_64 专用测试 APK，不是 GitHub／Gitee 的发布文件。
- 检查和下载期间各真实重建两次 Activity，仍是同一 `operationId`。首段字节到达后保留进度，不创建第二个下载。真实 APK 完成长度、哈希、版本和签名校验，随后在后台等待；明确关闭后重新打开，显示“安装更新”。再次检查的两个源均返回 503 时，保留下载包，主动作保持“重新检查”，两次主题重建保留详情。
- 最终横屏 1512×1008、竖屏 1008×1512、窄屏 540×1200／字体 1.3 均通过完整流程。每轮六次 Activity 重建、一次 APK 下载、一次成功元数据请求及两个失败源请求；共 18 次重建和 21 张自身渲染图。结果为 `native-delivery-*.log`、`delivery-*`、`verification-preview32.json`。
- 截图只渲染应用自己的 decor View。没有 computer use、系统输入、控制端口转发或读取控制令牌；未打开系统安装界面，未安装测试候选版本。模拟器配置和原下载目录在测试后恢复，连接配置按规范化文档逐字比较。
- 一次竖屏中间失败来自在 `super.onDestroy` 设置销毁标记后、完整销毁回调结束前读取任务状态。测试改为等待 UI 回调完成，失败记录为 `native-portrait-before-destroy-barrier.log`。最终源码另补充关闭请求阶段的保护，并有独立回归。

本轮只证明进程内页面生命周期、模拟器 HTTP 流程和包校验；不替代公网更新源、系统安装确认、OPPO、正式升级后配置保留或 Windows 安装验收。测试包与本地 ARM64 Release 的元数据、签名和哈希记录在最终验证 JSON 中。

本地 ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.32.apk`，SHA-256 `017E9E01605A20725BF20AB9B77B271266F14F0EC4CEA2A59ABC7C015B77CF76`；包名 `com.rhremote.client`，versionCode 256，签名 SHA-256 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。模拟器使用 x86_64 Debug 包；ARM64 的实机运行尚未覆盖。
