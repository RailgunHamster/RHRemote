# Windows 确认框归属

本地 Windows preview.86，Android 保持 preview.85。证据目录：`D:\RHRemoteTools\ui-windows-decisions86-review-20261029`。候选包尚未安装或发布。

## 复现与修改

原确认动作只携带“保存／放弃／继续编辑”或“移除／取消”。动作在队列中等待时，如果原确认已经取消，又出现一次新确认，旧动作会处理新决定。修改前的两项回归都失败：旧保存操作保存了当前草稿并去了另一目的页；旧移除操作清除了当前同步账户。记录见 `before-core86.log`。

现在每次新的确认都有本次运行内的独立 requestId。重复请求同一个未关闭的移除框保留原编号，重新打开才产生新编号。编号只用于当前交互，不写入偏好或同步文档。

Slint 主界面从**已显示的确认框**捕获编号，在进入动作队列时固定下来；不在出队时改用控制器的新编号。鼠标、Enter 与 Escape 共用相同的业务动作和编号检查。控制器在消费确认前的同一状态锁中检查编号，旧动作返回错误，保留新确认、草稿和已保存数据。

即使控制器在两次界面刷新之间关闭并重新打开了同类确认框，新的编号也会重置选择和焦点：未保存更改回到“继续编辑”，移除账户回到“取消”。现有按钮、位置与 HelpWire D 配色继续保留，没有增加确认步骤。

交互原则参考微软[确认框指南](https://learn.microsoft.com/en-us/windows/win32/uxguide/mess-confirm)：确认应针对用户刚发起的具体操作，对有风险的操作使用安全默认项。这份文档是 Windows 7 时代的交互指南，本轮只借鉴原则，界面外观仍用已选设计。

## 证据范围

- 完整 Release 回归：Core **127 项**、Windows UI **183 项**通过，另有 1 项既有 UIA 用例忽略，见 `rust86-full01.log`。使用 `SLINT_EMIT_DEBUG_INFO=1`，实际控件元数据保留；没有绕过旧回归。
- 新的 Slint 回归使用正式 MainView 绑定、实际按钮、应用内指针分发和正式队列参数捕获函数。分别测试“先点击、后替换确认”与“控制器已替换、旧界面尚未刷新”两种次序。旧鼠标动作和旧界面的 Enter 均不能确认后来那次导航；当前按钮能正常保存原设备并切到另一设备，后者参数保持原值。
- 移除测试使用内存中的专用 WebDAV 平台边界，不连接既有服务。旧鼠标／Enter 没有发出移除；新框默认取消保持账户，当前“移除”恰好执行一次。
- 三张图为正式 Slint 软件渲染的测试状态，位于 `slint-full01`。它们不是桌面截图，也不是日常三台设备配置的用户预览；没有使用 computer use、激活窗口或注入系统输入。

这证明本轮确认归属和默认焦点规则，不代替真实 WebDAV 移除、Windows 系统安装、实体屏 DPI、物理键盘或整套 UI 验收。

## 包与接口

`dist/velopack-releases-0.2.11-preview.86` 已生成便携包、安装包和 Velopack 完整更新包。Portable SHA-256 为 `A486F752D3D5C4642B5B271AC606B96AE250DF67488E5DAD9B419EFC19481589`；UI 为 `BF312930C2C19102F11549A742831C73006C03C7D65CD69B962B17CE49F1A4F7`。包内 262 个文件，没有运行配置、证书、密钥、令牌或日志；原生引擎仍为 `D6A402A25D860AC0112FCA9DAF622F72E08F16312896566361BEB76A9F7B4389`。完整记录见 `package86.json`。

Windows 的 `navigation.resolve`、`sync.clear.resolve` 接受可选 requestId，来自当前 `navigationPrompt.requestId` 或 `syncClearPrompt.requestId`。过期或格式不符时拒绝操作；省略仍表示明确处理当前确认，以兼容现有控制调用。产品 UI 始终携带其看到的编号。该编号与 `view.ui.*` 队列请求的幂等 requestId 分属不同动作，不能混用。[接口说明](API.md)

当前仍只识别到 DISPLAY26 虚拟屏，未启动桌面预览或回退主屏。日常 Windows 客户端和服务保持运行，OPPO 未操作，完整目标继续按 UI-FLOW-SPEC 验收。
