# Windows 首次恢复激活：原生栈与未通过的对照

最新 preview.105 已确认后台最小化参数的激活副作用，恢复原 owner 也通过；104 的手工 Z 序方案已撤回。见[更正后的因果对照](WINDOWS-PASSIVE-MINIMIZE-20261029.md)。以下中间结论保留历史范围。

后续 preview.104 已通过移除跨进程 owner 得到新的恢复对照，最终浅深两轮通过六次恢复；另两个主 UI 激活样本仍未归因。最新范围见[窗口关系修正](WINDOWS-OVERLAY-FOCUS-20261029.md)。下文保留 preview.97 时点的失败和诊断，不作为当前候选的完整结论。

本记录保留 preview.97 时点的诊断与进程状态。日常客户端后来已升级到 preview.101，见[当前交付](FLOATING-KEYBOARD-ENTRY-20261029.md)；下面的首次恢复焦点问题仍未认定解决。

本轮没有解决首次恢复时串流窗口成为前台的问题。日常客户端仍是 preview.93；本地 preview.97 只新增一个隔离诊断接口，没有部署或公开发布。上一轮悬浮键盘提示／重试的通过范围仍按原记录保留，不能把本轮诊断执行完成写成整个 UI 验收通过。

证据目录：`D:\RHRemoteTools\ui-window-focus96-review-20261029`。

## 已缩小的范围

| 对照 | 结果及范围 |
|---|---|
| 自建普通 Win32 窗口、带所属弹窗、缩放前后恢复 | 采样未成为前台；不代表 SDL 或 RHRemote 的行为 |
| preview.95：未缩放、关闭悬浮键盘后的首次恢复 | 仍成为前台；因此不依赖键盘重试或缩放前置操作 |
| 同一会话后续两组恢复 | 采样未成为前台；失败主要出现在第一次恢复 |
| preview.96：悬浮窗口 show 期间临时添加 NOACTIVATE | 首次恢复仍失败，该生产改法已完整撤回，相关模块已移除 |
| preview.97：使用窗口原有 placement、异步恢复且不请求激活 | 首次恢复仍失败；只保留为明确选择的诊断命令，不替换默认流程 |
| 原生 STARTUPINFO | `STARTF_USESHOWWINDOW=false`、showCommand=0，未证实启动参数覆盖是原因 |

`after-dark01` 的早期对照没有实际执行恢复：PowerShell 的 `measure` 别名优先于同名函数，阶段记录为 0。其旧 runner 绿色结果已由 `after-dark01-invalid-audit.json` 明确作废。后续函数已重命名，强制核对三个真实最小化／恢复阶段。`after-dark02` 等用于收集现象的 runner 通过仅涵盖执行和清理；独立 `passivityPassed` 为 false，不能合并为恢复通过。

## 从代码地址还原的调用链

仅在私有原生诊断构建中，对本进程、本线程的实际串流 HWND 安装有限的窗口消息回调，最多记录 64 次显示／激活／焦点消息及代码栈。安装成功、窗口归属及测试模式都有单独事件证明。它没有发送输入，也没有记录按键内容、环境或进程内存。

`after-dark06-llvm-focus.json` 使用匹配的 RHRemote 和 SDL2 PDB 还原真实激活事件；同时保留代码偏移、模块、源代码行及 `sdl-activation-disassembly.txt`：

```
Session::execInternal                    session.cpp:2132（诊断构建行号）
  SDL_WaitEventTimeout_REAL              SDL_events.c:1148
  SDL_WaitEventTimeout_Device            SDL_events.c:1012
  SDL_PumpEventsInternal                 SDL_events.c:926
  WIN_PumpEvents                        SDL_windowsevents.c:1926
  Windows 消息处理／窗口显示内部路径
  本窗口 WM_ACTIVATE、WM_SETFOCUS
```

机器码中 SDL2+0xe6e97 是消息泵内的间接调用，栈返回点为 +0xe6e9d。该栈证明激活在原生消息线程处理排队的恢复操作期间发生，不能据此声称 SDL 主动调用了抢前台函数，也不能归因到悬浮窗口或显卡。Windows 内部路径和首次恢复特有状态仍需继续区分。

第一种 SDL 消息 hook 没有取得激活样本，不用它作因果证据。其后一次只改新头文件而未触发 session.cpp 重编译，输出哈希未变，保留构建日志说明这一限制；后续显式更新编译依赖后，自己的窗口回调有安装成功事件并取得实际栈。DbgHelp 本地符号查询曾返回 126，相关输出不作为已解析的符号；采用 LLVM 读取精确 PDB 的 JSON 结果。

## 采用与未采用的改动

winit 0.30.13 的本地源码显示后续 show 可使用 SW_SHOW，这只是候选机制；临时保护没有改善实际失败，因此未保留该生产改法。[对应官方源码](https://github.com/rust-windowing/winit/blob/v0.30.13/src/platform_impl/windows/window_state.rs)

本地 preview.97 的 `view.ui.native-window {processId,command:"restore-placement"}` 仍要求隔离预览、当前存活子进程、已确认的窗口和 HWND 归属；读取原有 WINDOWPLACEMENT，仅修改 showCmd／异步标志，不混用工作区与屏幕坐标。保留原 restore 命令用于对照。微软分别说明了[异步显示请求的排队行为](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-showwindowasync)和[placement 坐标及异步标志](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-windowplacement)。接口接受不代表操作已完成，也不代表本轮已通过非激活验收。

## 检查与恢复

- 最终 preview.97 在 4 个测试线程下 Core 132、UI 215 项通过，另 1 项既有 UIA 跳过。此前 preview.96 默认并发全量有 4 条测试在夹具发布 PID 前的 3 秒等待超时；日志保留，不能写成默认并发全量通过。
- 8 个隔离 case 的授权均只移除本次身份，服务器原 8 个授权哈希、本机偏好均不变；UI 进程均退出、清理错误为 0。未启动源端输入探针，远端按键数为 0；未操作 OPPO、未停止业务服务。
- `final-audit-focus96.json` 验证 16 个 JSONL 无控制令牌匹配；只剩日常 PID 110380。
- 临时原生源码、EXE、PDB 已恢复，原引擎 SHA-256 仍为 `6F1FEA50E30FCA9D5F02518E0516EA8F240E51C20D8F10A1E49649DC18107CB0`，分发目录未变。私有诊断二进制、PDB、源代码快照仅留在证据目录。

首次键盘未显示、首次从未确认首帧、跨屏 DPI、真实输入和 Android 实机等剩余 UI 要求没有因此完成，继续按 TODO 验收。
