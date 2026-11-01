# Windows 控件测试接口

日常操作沿用 `/v1/action` 的共享业务动作目录；例如 `device.settings.open`、`settings.input`、`settings.save`、`updates.primary`。按钮与这些接口进入同一控制器，不必控制桌面来执行操作。

控件测试另提供控件检查、精确执行、焦点、弹出菜单及按键分发。它们复用同一 Windows 程序、Slint 控件及模态键盘处理函数，不另做一套界面。

| 动作 | 参数 | 作用 |
| --- | --- | --- |
| `view.ui.inspect` | `window` | 返回当前窗口实际已实例化的控件 |
| `view.ui.focus` | `control`, `window` | 聚焦 inspect 返回的可用控件 |
| `view.ui.activate` | `actionId`, `control` 或 `argument`, `window` | 执行当前唯一匹配的真实按钮；重复动作或复合参数使用 inspect 返回的完整 control |
| `view.ui.key` | `key`, `shift`, `repeat`, `window` | 向应用自己的控件分发键盘事件 |
| `view.ui.scroll` | `control`, `pixels`, `window` | 滚动指定内容区，限制在内容边界内；负数向下 |
| `view.ui.result` | `id` | 读取异步请求结果 |

`window` 默认为 `main`，也可为实际存在的 `toolbar` / `picker` / `keyboard` 会话窗口。支持的按键为 Tab、Enter、Escape、Space、Up、Down、Left、Right、Home、End、PageUp、PageDown。没有任意文本、系统快捷键或跨进程输入功能。输入内容通过原有业务接口修改。

本地 preview.184 的连接设置及 WebDAV 保存错误分别提供 `scroll|profile-error`、`scroll|sync-error`；仅在错误存在时返回，长文本可通过 `view.ui.scroll` 阅读。存在溢出时可用 `view.ui.focus` 聚焦，再用 Up／Down／PageUp／PageDown／Home／End 阅读；相同错误的刷新保留位置，新的错误回到开头。错误原文仍由既有业务状态返回，`view.ui.inspect` 的控件标签为“保存错误详情”。PowerShell 调用工具同步支持两个翻页键。[布局、原生控件与接口验证](WINDOWS-SAVE-ERROR-20261101.md)

确认弹窗支持 Enter 或 Space 执行当前焦点选项，首次按下才执行，重复事件和松开不再次确认。自绘按钮、侧栏、设备行及菜单项同样消费但忽略 `repeat=true` 的 Enter／Space；普通按下仍执行一次。输入框中的重复空格及方向键仍传给原生编辑控件。`repeat` 表示按键自动重复，不是要求接口重试业务动作；未触发动作时 `commandIds` 为空，仍可正常返回 complete。

搜索文字在模型、设备列表和实际编辑框之间双向绑定；`devices.filter` 的 UI 编辑直接进入原 Controller 的本地校验与状态更新，不排在网络动作后等待。外部设置或清空查询后，后续按键应编辑新文本；重复输入及当前证明范围见[原生失败与修正](WINDOWS-SEARCH-REBIND-20261030.md)。

preview.133 将已确认的表单输入、勾选和选择值接回模型。`device.add.input`、`device.info.input/reset`、`sync.edit`、`settings.input/network.edit/set/step/route.move/route.toggle` 与搜索一样，在 UI 回调中只更新原控制器的内存草稿；配置动作保留来源草稿 ID。保存、同步、连接、更新及串流音量继续异步执行。外部回填、取消、草稿更换后还需检查同一控件的后续回调，不能仅检查顶层状态；失败对照和实际副屏验证见[表单刷新记录](WINDOWS-FORM-REBIND-20261030.md)。

确认键与原生窗口关闭请求复用按钮的 Commands 回调和后台队列，确认请求的 ID 在回调时从其来源界面捕获。控件请求 `complete` 表示该次事件已投递／回调已发出，不代表保存、连接或清理完成；调用方还须等控制器状态，并在保存场景核对持久文件。`commandIds` 由共享回调记录一次，不另为确认键追加记录。[队列边界与验证](WINDOWS-MODAL-QUEUE-20261030.md)

接口只在显式 `RHREMOTE_TEST_MODE=1`、且 `RHREMOTE_DATA_DIR` 指向已存在并与日常数据目录不同的隔离配置时启用。即便误把日常目录写进环境变量，规范化路径相同也不会启用。这些请求仍需既有控制接口令牌。隔离预览启动时不激活窗口；事件不经过系统 SendInput，也不要求将窗口置于前台。

## 请求与结果

Windows 隔离预览通过 `Start-RHRemoteUiPreview.ps1` 启动到指定实体副屏，保持不激活；程序要求 `RHREMOTE_TEST_MONITOR` 和 `RHREMOTE_TEST_WINDOW_ORIGIN`，不能默认开到主屏。启动器根据 QueryDisplayConfig 的物理像素坐标设置位置。当前目标是左侧 `\\.\DISPLAY2`（C3273IUY）。历史启动脚本没有这些参数，不应继续使用。

`focus` 可显式请求 `activateWindow=true`，只允许隔离预览的 main 窗口，并等待恢复与布局就绪；默认关闭。结果新增 `nativeWindowHandle`、`monitor`、`physicalPosition`，供只读核对准确窗口。用户要求避免屏幕干扰后，不自动使用激活选项。UIA 焦点工具默认拒绝执行，不能把这个开关作为继续反复激活的许可。[副屏规则及未通过范围](WINDOWS-UIA-20261027.md)

动作提交后返回 `queued`；UI 线程依次执行，状态变为 `running`，最后为 `complete` 或 `failed`。最多等待 8 个请求，保留最近 64 条记录。可传入 1–80 位字母、数字、横线或下划线组成的 `requestId`；在保留窗口内，相同 ID 和参数返回原请求，不重复执行。相同 ID 改参数会拒绝。超时后按返回的 ID 查询结果，避免重新创建一次动作。

成功结果包含控件 `id`、`kind`、`label`、可用 / 焦点 / 勾选 / 展开状态、逻辑尺寸，以及滚动区的当前位置与范围。输入框的文字和密码不出现在控件结果中。`commandIds` 只记录本次 UI 请求期间触发的动作名称，不包含参数。`blockedByModal` 标明当前确认框对背景控件的限制；缺失、重复标识或禁用控件不会获得焦点。

标识由组件种类、动作及参数产生，例如：

```text
action|settings.save||保存
action|navigation.resolve|stay|继续编辑
choice|视频编码
input|画面宽度
check|连接路径：局域网
scroll|form
```

测试脚本应先 inspect，从当前页面选择标识，避免依赖固定坐标。

`activate` 始终要求 `actionId`。省略 `control` 时按动作和简单 `argument` 查找，仍拒绝多个匹配。传入 `control` 时精确匹配当前按钮 ID，由真实控件回调提供参数，不再解析请求中的复合参数；例如设备排序的 `uuid|up`。`control` 必须与动作对应，不能同时提供非空 `argument`。完整 ID 包含当前文案，文案改变或控件移除后需要重新 inspect；未提供 control 的既有语义匹配继续支持文案变化。

精确定位保留可见性、启用状态、对话框、当前会话及 `expectedPending` 检查，不激活窗口、不发送系统输入。两处取消入口可以各自定位，不会任选其中一个；重复 `requestId` 仍按原参数去重。

```powershell
& .\tools\Invoke-RHRemoteUiTest.ps1 -Port 48775 -Operation inspect
& .\tools\Invoke-RHRemoteUiTest.ps1 -Port 48775 -Operation focus -Control 'choice|视频编码'
& .\tools\Invoke-RHRemoteUiTest.ps1 -Port 48775 -Operation key -Key Enter
& .\tools\Invoke-RHRemoteUiTest.ps1 -Port 48775 -Operation key -Key Escape
```

`Invoke-RHRemoteUiTest.ps1` 使用既有令牌文件，自动轮询结果并在失败时抛错；不打印令牌。完整键盘流程可用 `Test-RHRemoteUiKeyboard.ps1` 验证，必须指定隔离数据目录，并核对测试前后配置文件的 SHA-256。

`Test-RHRemoteTabOrder.ps1` 对设备页、连接设置五分类、应用设置四分类、设备资料及添加页执行双向 Tab 遍历。它根据 inspect 结果比较实际可用控件集合，记录漏项并核对正反向顺序；不以显式聚焦每个目标代替 Tab 可达性。传入 `-DeviceUuid`、`-ReportPath` 和对应控制端口，仅对无草稿的隔离预览运行。

## 应用截图请求

`view.snapshot`、`view.toolbar.snapshot`、`view.picker.snapshot` 只导出应用自身的对应窗口。preview.38 返回 `result.id`、`window`、`status`；同一 ID 的状态也在 `/v1/state` 的 `preview` 中。调用方等待自己的 ID 达到 `complete` 且 `ok=true` 后再取文件；`queued`／`running` 不能当作成功。失败及关闭取消为 `failed`／`cancelled`，有具体错误，不复用旧路径，也不覆盖页面提示。

可传 `requestId`（1–80 位字母、数字、横线或下划线），省略则生成。最多一项请求排队或生成；当前 ID 的同窗口重试返回原状态，不重新执行，更换窗口会拒绝。仅保留当前请求结果；其他请求替换后不要重用旧 ID。返回的固定文件路径会在同窗口下次截图时更新，保存证据应复制到独立文件。

`Export-RHRemoteView.ps1` 提供这条完整路径：指定 `-Window`、`-Port` 和新的 `-DestinationPath`，自动等待 ID、核对实际窗口及 PNG 尺寸，再提交证据文件；失败不留下冒充成功的最终文件，也不覆盖已有证据。结果附有实际 UI 页面／分类和文件 SHA-256，可检查取到的是否为预期页面。[原因与运行验证](WINDOWS-APPEARANCE-20261027.md)

## 证据边界

无窗口流程也复用正式状态绑定：`view_flow_tests` 将真实 Controller 状态送入应用同一个 `MainView`，点击实际 Slint 控件后核对唯一 `Commands` 动作，并通过正式 `args_for` 与 Controller 检查结果及临时偏好文件。运行 `cargo test -p rhremote-ui --lib view_flow_tests --locked` 即可，不创建 Win32 窗口。外部平台边界使用明确替身，不能把这些流程当作真实 WebDAV、更新安装或串流验收。[流程与滚动修复](UI-CONTROLLER-FLOWS-20261028.md)

采样先调用应用窗口自己的渲染器，使条件控件与焦点变更处理完成，再采集新的报告；只等事件队列空闲可能仍读到上一帧。该过程不会截取其他应用或系统桌面。布局复核通过既有 `view.snapshot` 导出应用自身的画面。

这些接口证明当前程序的实际控件、应用内键盘处理和业务动作的一致性。它们不能单独证明 Windows 系统按键分发、无障碍 UIA 焦点、物理显示输出或真实网络串流。`windowActive` 与 `systemInputInjected` 明确记录执行条件，不能把应用内事件写成系统输入测试。用户要求停止 computer use 后，不以此接口为由恢复桌面控制。

preview.36 的真实 winserver 会话复验覆盖工具栏和选择菜单的双向 Tab、Esc、分类、屏幕刷新、静音下的音量调整、统计开关及断开。父键盘作用域使用 `scope|toolbar` / `scope|picker` 标识，只提供遍历起点，不成为正常 Tab 停留点。等待首帧／恢复时的工具栏状态包含 `pending` / `bodyVisible`；收起面板仍可看到阶段和取消。HTTP 控制请求异步且限制为一个待处理请求，状态查询及断开不被慢控制阻塞。具体证据及未覆盖范围见 [会话复验](WINDOWS-SESSION-UI-20261027.md)。
