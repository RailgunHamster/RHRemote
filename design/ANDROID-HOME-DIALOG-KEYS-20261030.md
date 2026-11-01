# Android 主界面确认框键盘操作（preview.117）

主界面的未保存确认和同步移除确认统一支持本地 Esc。未保存确认的初始键盘焦点放在“继续编辑”，同步移除放在“取消”；Enter 保留原生按钮的处理方式，用户选择其他按钮后仍可正常保存或放弃。

## 问题与修正

preview.116 的主界面确认框没有 Esc 处理，未保存确认也没有指定初始按钮。新增 4 项回归在原实现全部失败，原始结果为证据目录的 `baseline116.xml`。活动会话和恢复提示已有自己的按键处理，这次没有改动它们。

`RhDialogKeys` 只为这两个主界面确认框建立按键行为：

- Esc 的按下和释放都在弹窗内消费，完整且未取消的按键才调用原确认 API。
- 没有对应按下的释放、已取消按键以及旧弹窗的事件不处理新决定；窗口失去焦点或移除时清除等待的 Esc。
- 键盘默认选择与危险操作分开；Enter 不增加新的提交逻辑，仍触发焦点按钮原有的单次操作。
- 窗口初次获得焦点后设置默认按钮。用户开始按键后不再调整，移除窗口时清理监听。

首次原生验证发现了 JVM 没覆盖的窗口时序：即刻请求按钮焦点后，平台仍把焦点放到消息 ScrollView。`dialog-keyboard117-focus02/.../profile-light-initial-focus.json` 记录了按钮可聚焦、非触摸模式，但实际焦点为 `android.widget.ScrollView`。最终改为在窗口获得焦点的交接完成后设置一次默认按钮，没有用延长等待来掩盖错误。

使用的是 [Android Dialog 的按键监听接口](https://developer.android.com/reference/android/app/Dialog#setOnKeyListener(android.content.DialogInterface.OnKeyListener))。按钮样式和原生 Enter 逻辑保留；没有新增业务按钮、确认步骤或专用提交 API。

## 动作对应

| 可见操作／按键 | 原有动作 |
| --- | --- |
| 未保存确认：Esc／继续编辑 | `navigation.resolve {choice:"stay"}` |
| 保存并继续 | `navigation.resolve {choice:"save"}` |
| 放弃更改 | `navigation.resolve {choice:"discard"}` |
| 同步移除确认：Esc／取消 | `sync.clear.resolve {choice:"cancel"}` |
| 明确选择移除 | 原有 `sync.clear.resolve {choice:"remove"}`，此次没有改动其实现 |

这些调用继续检查当前 Home 与当前弹窗的归属，不从按键回调直接保存、移除或跳页。

## 最终验证与边界

完整 443 项 JVM 通过，新增的 4 项检查 Esc、默认 Enter、取消／不配对／旧弹窗事件，以及取消同步移除不改变账户文件。

最终专用模拟器两轮各 8 个真实 PcView 流程，共 16 个：正常与 200% 字号，每轮均包含浅深主题下的连接设置、设备名称、WebDAV 未保存确认和同步移除确认。最终 Enter 和 Esc 均经 `Dialog.dispatchKeyEvent` 入口验证，并核对原确认 API 的结果；早期仅向焦点按钮分发 Enter 的两轮单独保留，不替代最终完整弹窗入口结果。

验证覆盖默认选择、当前编辑器恢复焦点、原生控件完整可见、已取消及无按下的 Esc、旧弹窗事件，以及当前按钮明确保存连接参数、放弃名称更改、取消 WebDAV 编辑。同步移除仅验证取消；原生确认流程只使用内存中的临时账户元数据，正常 WebDAV 账户文件内容前后相同，未解密凭据或发起同步。

两轮最终运行均恢复模拟器 15 个正常配置／身份文件、原备份与系统字号，进程已退出。没有操作 OPPO、日常 Windows 客户端、服务器或远程串流。

证据目录：`D:\RHRemoteTools\ui-dialog-keyboard117-review-20261030`。最终原生运行是 `dialog-keyboard117-native05-dialog` 和 `dialog-keyboard117-native06-dialog-font200`；首轮焦点失败及其诊断保留。最终签名、原生库对照、配置与包哈希见 `final-verification.json`。

本地 ARM64 候选为 `dist/android/RHRemote-v0.2.11-preview.117.apk`，版本代码 335，原签名，未公开发布。此项不等于外接物理键盘、OPPO、Tab 完整路径、Windows 或整套 UI 目标验收完成。
