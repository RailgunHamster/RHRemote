# Windows 会话按键回执、窗口移屏及退出统计

本轮 Windows 为本地 preview.70，Android 保持 preview.69。未发布、未覆盖日常 Windows 客户端，未操作 OPPO。Android 专用模拟器已正常恢复，包管理和 Activity 服务可运行；本轮只通过它对已配对的 winserver 做只读接口检查。

## 实际操作与证据

证据根目录：`D:\RHRemoteTools\ui-session-input70-review-20261028`。最终两轮为 `attempt06`、`attempt07`，汇总为 `verification-final.json`。

Windows 从日常三台设备的公开配置生成隔离副本，在实体 DISPLAY2 上保持不激活。通过实际 Slint 按钮和菜单行的 focus / Enter 测试接口执行操作；没有使用 computer use、系统截图或系统输入驱动客户端界面。发送到远端的鼠标和按键使用正式 RHRemote 输入通道。

远端临时 WinForms 窗口位于 winserver 当前交互式 Session 1。`tools/session-input-probe.ps1` 只允许新建的指定证据目录，打开前检查本地输入已闲置及原有两块屏幕；点击前确认落点属于该窗口，发送按键前确认它仍在前台。只记录具名测试组合键和鼠标计数，不记录任意输入内容。四个 Ctrl+Alt 方向键仅在该窗口位于前台时注册，关闭或失焦即注销，避免测试触发其他应用的旋转等行为。

每轮完成 29 次实际控件派发，分别得到以下服务端结果：

| 操作 | 实际回执 |
| --- | --- |
| Ctrl+Alt+Left / Right / Up / Down | 四项各收到一次。 |
| Ctrl+C / A / S / F | 四项各收到一次。 |
| 窗口右移、左移 | 测试窗口从物理 DISPLAY1 移到既有虚拟 DISPLAY25，再返回 DISPLAY1；没有新增或调整屏幕。 |
| Ctrl+Alt+Delete、Escape | 正式安全注意序列接口成功后，输入桌面从 Default 变为无法访问（错误 5），Escape 后恢复可访问的 Default。观察没有取得受保护桌面的名称或图像，不把名称推断写成实测值。 |
| Alt+F4 | 仅关闭该前台测试窗口，远端记录一次，结束原因为 alt-f4。 |

上述八个具名组合键完成后，各次采样的修饰键残留为 0；测试窗口结束时也为 0。安全注意序列前后原生客户端 PID 不变：两轮分别为 157320、114512。没有进行密码解锁，因此不扩展为本轮解锁恢复或其余快捷键验收。

`probe-*.json`、`control-*.json`、`session-input-result.json` 是操作证据；`cleanup.json` 才包括整条会话的退出结果。最终两轮均为 actionsPassed / fullPassed，测试窗口已结束、临时计划任务已删除、临时授权已移除。不能仅凭中途动作结果把退出也计为成功。

## 退出统计与仍未解决的超时

早先 `attempt04` 的全部上述动作有回执，但断开后原生进程继续收帧，约 15 秒后被既有清理上限强制结束。该样本没有通过整条会话验收。原始动作结果中曾写入 passed；后续 `frame-and-cleanup-evidence.json` 明确标记 endToEndSessionPassed=false，原始材料保留，不用它替代最终结果。

preview.70 给 `native_cleanup_end` 增加 control 分段记录：身份状态请求次数、是否核对到本次进程、是否见到其他进程、断开请求次数、确认结果、HTTP 状态及总清理毫秒数。仅记录受限错误分类，不写 URL、响应正文、令牌或凭据。进程归属校验、重试间隔、15 秒上限、传输资源的持有时机及退出确认规则均未改变。

| 样本 | 结果 |
| --- | --- |
| attempt04 / preview.68 | 原生清理超时，forced=true、confirmed=false，仍待定位。 |
| attempt05 / preview.70 | 动作回执采集未通过；其退出单独得到身份核对、一次断开确认及正常退出，489 ms。 |
| attempt06 / preview.70 | 完整流程通过；一次身份核对、一次断开确认，393 ms，正常退出并确认传输清理。 |
| attempt07 / preview.70 | 完整复跑通过；同上，504 ms。 |

后两轮没有重现超时。这些数字证明本次正常退出路径，并不证明原先偶发问题已经修复，也不能证明它一定由安全桌面切换造成。对应 TODO 保留。

两轮原生记录分别有 377 / 405 次解码输出及 377 / 405 次 render_return，均正常写出 trace_end，lost_events=0、size_limit_reached=false。它们只用于确认测试期间有解码和渲染提交；不代替源像素匹配、物理呈现、动态 FPS 或端到端延迟验收。没有用未校准的跨机器时钟计算输入时延。

## 失败材料与观测边界

- attempt01：测试窗 C# 事件拼写错误，未打开窗口、未发送按键；修正后以 Windows PowerShell 5.1 编译检查通过。
- attempt02：脚本把原生 activeProfile 没有提供的 fps 当作实际值校验。改为检查本次请求 manifest 的 30 fps，以及原生返回的 1920×1080、实际声音关闭；不声称实测达到 30 fps。
- attempt03：SMB 状态文件读取发生共享冲突，正常测试未继续；补做停止窗口并保留单独清理证据。
- attempt05：首个快捷键回执等待到期；远端事件文件后来确认已收到一次，但不能据此把整轮改判通过。驱动初次清理观察不完整，随后明确停止临时窗口并确认 ended。
- 临时窗口原先每 100 ms 替换状态文件；读取端增加共享及有限重试，写入端改为保持文件身份、共享写入并刷新，状态增加自身 UTC。此调整用于减少观测层的共享冲突和旧文件对象风险，没有据此认定真实输入延迟已解决。改动后重新编译，并以新目录完成上述两轮；所有旧失败材料保留。

## 回归、配置与产物

Windows UI 单元回归 177 项通过、1 项既有公开源测试保持忽略。原有退出测试同时核对正常确认和错误进程不能收到断开请求。原生引擎及视频实现没有修改，SHA-256 仍为 `65442e80437ffa5268f6d4227c026dbce4d233fb365f809de3798f2f51e85e7b`。

最终只读检查确认两块屏幕的完整目录属性未变化，统一服务 PID 60240、Sunshine PID 30252 及 starts=1 保持不变，输入桌面为 Default。只移除各轮自己的授权后，原有八个授权的文件 SHA-256 均恢复为 `177acc4890b4b3b5449745a36288d26f2f0613bf114c5e2edf3562f51163134a`。

日常 Windows preferences SHA-256 仍为 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`，最终测试副本也已恢复 seed，控制端口 48790 已关闭。声音全程关闭，未改画质和业务服务。模拟器 shared 配置 SHA-256 仍为 `fe08b5761c960bdd24fb980a58aac5ab4c58d8698bae1a3d2536270ae273a0d8`，保持 1512×1008、240 dpi、字体 1.0；没有再重启、清数据或测试 OPPO。

本地 Windows 产物位于 `dist/velopack-releases-0.2.11-preview.70`：

- Portable SHA-256：`3750ba94c7c685e958eceb6c51d3b0743389dbec99df85695a8d6bf83d7b41f2`。
- Setup SHA-256：`c53524a61f0d055c6b911f9e415c067623d4d6338cdc9614ae61e450800fa5bd`。
- 主 UI SHA-256：`7519ff29b8f44bd8562c684083dbb4197bcba522ed3892cd781dc3705bb285f7`。

Portable 中 UI 与实际复验文件一致，原生引擎一致，未包含运行时凭据或偏好文件。尚未执行该安装包的系统升级流程，也未公开发布。Android 实际串流操作、Windows 其余快捷键、双端声音及缩放、其他 UI / 同步 / 更新验收继续保留在 TODO。
