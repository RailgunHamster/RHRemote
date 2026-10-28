# Windows 连接中的控件与真实视频等待取消（preview.58）

本地 Windows preview.58 完成配对等待、取消后配对、首帧前错误与重试、活动会话控件及真实视频等待取消的本轮复验。Android 保持 preview.57，OPPO 未操作，未发布。完整 UI 目标仍按 UI-FLOW-SPEC 与 TODO 继续验收。

## 实际发现与修改

副屏启动器原先只设置 Slint 主窗口的位置，没有设置原生 SDL 窗口所用的 `RHREMOTE_CLIENT_DISPLAY_ORIGIN`。因此主界面虽在 DISPLAY2，视频及跟随视频的悬浮条仍可能出现在 DISPLAY1。`ui-connection57-review-20261028-attempt02/control-5.json` 记录该情况，窗口未激活，但 monitor 为 DISPLAY1。

现在 `Start-RHRemoteUiPreview.ps1` 根据同一次 QueryDisplayConfig 枚举，把物理显示器原点传给原生组件；它与加 64 像素留白的主窗口位置分开记录。新增环境变量同其他临时变量一起恢复，不改变日常启动方式。仍要求活动物理屏、禁止默认回退主屏，且保持不激活。本轮正常流媒体、编码器及 native 引擎均未改动。

实际视频等待阶段还暴露了测试接口的竞争：请求按包含文字的控件 ID 查找，“取消连接”在首帧到达后变为“断开”，先检查再聚焦的下一次请求可能找不到旧标识。这是接口采样／执行之间的竞争，不能据此断言普通鼠标点击也失败。

新增隔离测试接口 `view.ui.activate`：按 `actionId` 和精确 `argument`，在一个 UI 事件轮次内查找当前唯一、可用的 ActionButton，并执行它的控件回调。普通点击、键盘和无障碍默认动作与此接口共用按钮内部的同一个动作函数；没有直接绕到另一套业务逻辑，也不激活窗口或注入 OS 输入。

```json
{
  "id": "view.ui.activate",
  "args": {
    "window": "toolbar",
    "actionId": "stream.disconnect",
    "expectedPending": true,
    "requestId": "cancel-current-video-wait"
  }
}
```

`expectedPending` 只用于 toolbar，实际执行时重新检查；状态变化后拒绝执行。接口继续要求隔离预览及原有认证，禁用按钮、背景确认框及非唯一目标均不能执行。同一 requestId 的保留期、参数一致性和最多最近 64 项的幂等规则不变；动作结果返回实际控件／文字、执行前等待及首帧状态和分发记录。返回按钮已分发不等于网络清理已完成，仍须核对后续状态。

## 正向证据

最终流程目录：`D:\RHRemoteTools\ui-connection58-review-20261028-attempt02`。构建、单测与最终包校验在无后缀的 `ui-connection58-review-20261028`。

- 实体 DISPLAY2／C3273IUY，主窗口 `(-3776,64)`，原生显示器原点 `(-3840,0)`。8 组 `placement-*.json` 覆盖配对等待浅／深、错误浅／深、活动主界面浅／深及悬浮条浅／深，均为 DISPLAY2、windowActive=false。
- `result.json` 记录 **12** 次实际按钮动作、**16** 条双向 Tab 审计。8 个上下文中的可用控件均可达，两个方向顺序互逆，无不可见额外焦点。实际正常连接参数保持 1920×1080、30 FPS、H.264、25 Mbps，声音与远程输入关闭。
- 配对等待的取消结束后回到原设备并保留搜索，主机没有残留请求和新增授权。再次从实际配对按钮进入，完成正常 PIN 交换并保存。`pair-pin-proof.json` 只记录内存对照的布尔结果：同一次操作，界面快照、当前界面及所属原生进程 PIN 一致；未输出 PIN 对照值或哈希。
- 两个首帧前错误使用已有测试模式的 `testInitialFailure=identity/decoder`。它们在真实 RTSP 已建立、正式视频事件循环出帧前受控返回错误，检查错误页、清理及实际重试按钮；不代表制造了真实 TLS 身份或硬件解码器故障。两次实际重试均取得解码及渲染记录，并经实际悬浮条断开返回原设备／搜索。
- 会话面板会保留展开选择。驱动读取当前状态再决定是否展开，避免把“已展开”误当默认收起；没有修改产品来迎合该错误假设。
- `waiting-toolbar-activation.json`：执行的实际文字为“取消连接”，toolbarPendingBefore=true、connectionPhaseBefore=first-frame、connectionFirstFrameBefore=false，只分发一次 stream.disconnect，窗口未激活且位于副屏。
- `native-trace-summary.json` 对应取消进程 **114408**／记录 `1793177657639-d6e62732b065`：RTSP 阶段结束、connection_ready 存在，**render_return 从启动到退出为 0**，没有错误注入或人为视频延迟，记录正常结束且 lost_events=0。它补齐了此前仅在显示准备阶段取消、不能覆盖 RTSP 之后视频等待的缺口。随后正常连接再次取得首帧。

三次成功连接由测试轮询观察为 **3298／3682／6853 ms**，不宣称启动性能提升，也不是物理呈现延迟。最后一轮 native 的 host_display_prepare_worker 为约 **4752 ms**，表明该次主要等待在显示准备请求；这一客户端区间不能代替服务端 D3D／驱动内部原因的定位。原生引擎及默认参数未变，慢样本继续保留供后续分析。

已查看最终浅／深错误页自身绘制图，返回及重试按钮完整。全部图来自应用自身渲染，没有系统截图或 computer use。

## 回归、清理与早期失败

新接口四项真实 Slint 软件绘制回归在旧代码上均因未知操作失败；实现后通过。覆盖排队后文字变化、执行时条件变化、禁用、重复请求与确认框背景阻止，另加入参数校验。完整核心 **123**／UI **164** 项默认并行通过，无失败或跳过；三份 PowerShell 脚本语法检查通过。日志为 `atomic-before.log`、`atomic-after01.log`、`windows58-all-tests.log`。

每轮结束都等待本轮连接清理并关闭预览，只移除精确新增的测试授权，不修改其他客户端。最终 `host-cleanup.json` 恢复原 **8** 项及原始 SHA `177acc4890b4b3b5449745a36288d26f2f0613bf114c5e2edf3562f51163134a`；正常偏好仍为 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`，隔离偏好也恢复原字节。业务服务、OPPO 及正常 WebDAV 账户未操作。

六份最终原生统计均已分析。成功轮仅保留开头 8 秒的逐帧数据及全部非帧事件，取消和注入失败轮完整保留；原始与保留后的哈希、条数及字节记录见 `native-trace-summary.json`、`statistics-compaction.json`。不重复运行分析器覆盖原始计数。之前失败轮仍用于调查，未清理。

保留的未通过记录：

- `ui-connection57-review-20261028` 的一次配对报 PIN 不匹配，当时缺少 native／提交值的一致性记录，不能定因。后续增加内存布尔对照并通过，不能以成功替代首次失败。
- `.57-attempt02` 发现上述视频窗口落主屏；`.57-attempt03` 是驱动盲目切换已展开面板；`.57-attempt04` 是文字变化后旧控件标识失效。
- 无后缀 `.58` 的后半程触发窗口位置／焦点检查，当时未保存具体值，不能归因于换屏、激活或外部操作。随后补元数据的最终整条流程通过，保护条件未放宽；原始失败仍保留。

## 本地产物与剩余范围

Windows 便携 ZIP、Setup 和完整更新包在 `dist/velopack-releases-0.2.11-preview.58`，未安装更新或发布。便携 SHA 为 `2cdc9064d292e74460085695c4a838cca483ce0e6b88fa7a142b8acd858cfbe4`。ZIP、nupkg、测试程序与 Release 的 UI SHA 相同；原生引擎仍为 `.55` 的 `6711cac6f5e5d4dc711b2655b72a227ef612f676c249c5e24b27cedb65f9fa01`。271 项／262 文件，无私有配置、令牌、密钥或日志；详见 `verification58.json`。

本轮不是 Windows 全局 UIA／系统键盘、物理呈现、长期遮挡、自然网络故障或真实 TLS／硬件错误验收。恢复状态、会话其他分类及快捷键／声音／切屏实际效果，本机、claw、OPPO、跨网络、更新和同步的剩余范围仍保留。Android 当前包与行为保持 preview.57。
