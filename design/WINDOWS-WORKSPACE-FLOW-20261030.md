# Windows 日常设备工作区流程复验

本轮新增可重复的完整导航验收，没有修改产品界面、构建新客户端或替换日常版本。Windows 本地候选继续为 preview.110，日常安装仍为已批准的 preview.101。

## 数据与执行范围

从日常 `preferences.json` 按公开的 `Saved` 数据结构建立隔离副本，保留三台设备的顺序、别名、首选／备用地址、当前客户端参数以及其他客户端配置。保留同步客户端标识以选择正确参数，不复制原生配对身份或 WebDAV 登录。

当前数据的显示名称为 RailgunHamster、winserver、Claw。各例只修改自己的临时偏好文件；每例结束核对日常源文件的原始字节。测试默认忽略，只有显式指定源配置与全新证据目录才运行，不在普通 CI 中读取用户数据。

使用正式 `MainWindow`、`MainView`、Controller、实际 Slint 控件和相同的动作参数／草稿归属绑定，通过应用内部指针及键盘事件执行操作。平台边界使用本地替身：可连接、在线、配对及本机标记是测试状态；连接直接返回受控错误。没有加载正常运行时、原生窗口、远程连接、更新器或 WebDAV 实现。

## 逐设备路径

四种组合：720×560 与 1180×780，浅色与深色。每种组合都操作三台设备，并完成：

1. 从真实列表行选择设备，编辑搜索，刷新后保留当前对象与搜索。
2. 打开连接设置，使用原生音量输入框编辑；直接点击另一台设备，在确认中继续编辑，再保存后切换。
3. 核对新设备显示自己的参数，切回原设备；从侧栏离开时继续编辑或放弃，返回后仍选中原设备并保留搜索。
4. 从实际连接按钮进入受控失败页，实际点击重试、返回设备，核对对象及配置没有改变。
5. 核对更新只有一个主要流程按钮；编辑 WebDAV 的 HTTP 地址后取消；切换外观、关于、更新分类并返回。
6. 注入本机状态后，实际点击禁用连接按钮，确认没有产生连接动作。

每个发生变更的实际控件都要求恰好产生一次对应动作。空搜索框再删除空内容不会产生变更事件，不把这种正常无操作当成按钮失效。

保存只改变目标设备的当前 Windows 参数。三个设备的其余参数、三个其他客户端的配置在每个检查点均核对；界面的设备标识、名称、搜索、音量输入，以及概览中显示的分辨率／帧率／码率也与 Controller 回读比较。

## 结果

- 最终 `final05` 四种组合通过，12 个逐设备流程，共 132 个检查点。
- 32 张实际软件绘制图片保留原始 SHA-256；离线 `preview.html` 可切换窗口、主题、设备概览和受控错误页。
- 完整 Rust 工作区 398 项通过、0 项失败，4 项按显式条件默认忽略。新增的日常数据流程已单独显式运行；其余三个忽略项不算本轮已执行。
- 日常偏好原始字节、日常客户端和前一轮已核对的配置文件哈希保持一致，日常客户端保持关闭。
- 最终 29 项事实／完整性检查通过。没有公开发布或操作 OPPO。

最初两次失败是夹具问题：对原本为空的搜索要求产生编辑事件，以及误用 Android 的全局返回动作 `navigation.back` 查找 Windows 返回按钮。Windows 实际按钮沿用 `devices.open`；修正为点击该实际页头按钮，没有更改产品动作来迎合测试。失败日志与初始图片独立保留。

证据目录：`D:\RHRemoteTools\ui-windows-workspace-review-20261030`。关键文件为 `final05/result.json`、各组合的 `result.json`、`workspace-tests-final.log`、`render-manifest.json`、`final-verification.json` 和 `preview.html`。

## 当前边界

QueryDisplayConfig 仍只返回无实体名称的 DISPLAY26 虚拟屏。本轮按现有约束使用软件后端，没有打开实体预览，也没有回退到主屏或虚拟屏显示窗口。

这些结果证明当前工作区控件、动作、状态绑定与隔离数据的对应关系，不代替 Windows 实体窗口、跨屏 DPI、长期遮挡重绘、UIA／实际硬件输入、真实启动／首帧和远程会话验收；完整 UI 目标与 TODO 继续保留。

复现时需为下列环境变量提供绝对路径，证据目录必须尚不存在：

```powershell
$env:RHREMOTE_WORKSPACE_FLOW_SOURCE = Join-Path $env:LOCALAPPDATA 'RHRemote\preferences.json'
$env:RHREMOTE_WORKSPACE_FLOW_EVIDENCE = 'D:\RHRemoteTools\new-workspace-flow-evidence'
cargo test -p rhremote-ui --lib --locked -j 4 workspace_flow_tests::daily_device_workspace_preserves_context_through_the_complete_ui_flow -- --ignored --exact --nocapture
```
