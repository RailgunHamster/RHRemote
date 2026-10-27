# Windows 控件测试接口

日常操作沿用 `/v1/action` 的共享业务动作目录；例如 `device.settings.open`、`settings.input`、`settings.save`、`updates.primary`。按钮与这些接口进入同一控制器，不必控制桌面来执行操作。

控件测试另增加五个动作，用于检查业务动作覆盖不到的焦点、弹出菜单及按键分发。它们复用同一 Windows 程序、Slint 控件及模态键盘处理函数，不另做一套界面。

| 动作 | 参数 | 作用 |
| --- | --- | --- |
| `view.ui.inspect` | `window` | 返回当前窗口实际已实例化的控件 |
| `view.ui.focus` | `control`, `window` | 聚焦 inspect 返回的可用控件 |
| `view.ui.key` | `key`, `shift`, `repeat`, `window` | 向应用自己的控件分发键盘事件 |
| `view.ui.scroll` | `control`, `pixels`, `window` | 滚动指定内容区，限制在内容边界内；负数向下 |
| `view.ui.result` | `id` | 读取异步请求结果 |

`window` 默认为 `main`，也可为实际存在的 `toolbar` / `picker` 会话窗口。支持的按键为 Tab、Enter、Escape、Space、Up、Down、Left、Right、Home、End。没有任意文本、系统快捷键或跨进程输入功能。输入内容通过原有业务接口修改。

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

采样先调用应用窗口自己的渲染器，使条件控件与焦点变更处理完成，再采集新的报告；只等事件队列空闲可能仍读到上一帧。该过程不会截取其他应用或系统桌面。布局复核通过既有 `view.snapshot` 导出应用自身的画面。

这些接口证明当前程序的实际控件、应用内键盘处理和业务动作的一致性。它们不能单独证明 Windows 系统按键分发、无障碍 UIA 焦点、物理显示输出或真实网络串流。`windowActive` 与 `systemInputInjected` 明确记录执行条件，不能把应用内事件写成系统输入测试。用户要求停止 computer use 后，不以此接口为由恢复桌面控制。

preview.36 的真实 winserver 会话复验覆盖工具栏和选择菜单的双向 Tab、Esc、分类、屏幕刷新、静音下的音量调整、统计开关及断开。父键盘作用域使用 `scope|toolbar` / `scope|picker` 标识，只提供遍历起点，不成为正常 Tab 停留点。等待首帧／恢复时的工具栏状态包含 `pending` / `bodyVisible`；收起面板仍可看到阶段和取消。HTTP 控制请求异步且限制为一个待处理请求，状态查询及断开不被慢控制阻塞。具体证据及未覆盖范围见 [会话复验](WINDOWS-SESSION-UI-20261027.md)。
