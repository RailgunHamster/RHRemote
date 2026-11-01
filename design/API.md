# 控制接口与配置同步

0.2.11 新增 Windows / Android `device.windows.open {uuid}`、`device.windows.close`、`device.window.connect {uuid, source}`；窗口列表是运行时状态，不能同步。已配对 HTTPS `GET /rhremote/windows` 返回短期、证书绑定的窗口源，不导出 HWND。旧服务端不支持时明确提示，连接不能回退到桌面。Android 新增 `stream.click-scroll.set {enabled}`：轻点点击、单指横纵滚动、双指平行拖动，捏合保留缩放。详见[功能与验证边界](WINDOW-STREAMING-AND-CLICK-SCROLL.md)。

正常结束且已显示画面的连接进入 `ended`，清空错误；首帧前异常结束与明确的终止错误仍为 `failed`，意外断线恢复仍使用原 `reconnecting` 流程。

Windows 和 Android 的新增产品按钮通过 action ID 分发。HTTP 调用走同一个分发器，不能绕过设备离线、配对、自身连接、活动串流和配置验证等检查。

Android 本地候选 185 为连接设置、同步设置及配置复制的长错误增加独立原生滚动区。原错误文字、保存／取消／复制动作和 API 返回保持；同值更新保留阅读位置，新错误归零。原生控件验证和范围见[长错误记录](ANDROID-SAVE-ERROR-20261101.md)。

Android 本地候选 181 的配置快照在同一份已验证文档中读取各设备参数，已有本机身份的查询不再等待另一配置事务；首次身份生成和配置写事务仍串行。接口、配置格式、每端参数归属及保存／取消规则保持，读取仍检查最新文件，没有配置缓存。该改动的受控原生验证、撤回的解析尝试和未通过的完整像素范围见[配置锁记录](ANDROID-CONFIG-READ-20261101.md)。

## 连接方式

Windows 开发启动器为 `tools/start-rust-client.ps1`。HTTP 监听仅绑定 IPv4 loopback，生产启动只有显式设置 `RHREMOTE_CONTROL_PORT` 才开启；令牌由 `RHREMOTE_CONTROL_TOKEN` 传入，至少 32 字符。令牌不进入同步文档。

Android 应用运行时监听 `127.0.0.1:48752`，64 字符随机令牌保存在应用私有文件 `files/rhremote-control-token.txt`。开发测试使用 `tools/connect-android-control.ps1` 经已授权的 OPPO ADB 通道转发端口，不开放局域网防火墙规则。

请求头：`Authorization: Bearer <token>`。拒绝带 Origin 的请求；JSON 请求体需要 Content-Length，最大 8192 字节。密码只作为配置动作输入，不回显到 API 状态和结果。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/v1/state` | 当前页面、设备、独立配置、同步及更新状态 |
| GET | `/v1/actions` | action 目录及参数说明 |
| POST | `/v1/action` | `{"id":"...","args":{...}}` |

动作全集以 `client/crates/rhremote-core/src/actions.json` 为准。部分动作只适用于特定平台：Windows 提供窗口最小化、最大化、关闭和本应用预览；Android 提供触屏控制面板、键盘、平移和缩放。不适用的动作返回错误，不能作为成功验收。

Android 本地 preview.182 的设备概览将标题和导航固定在正文滚动区之外。“连接设置”继续使用绑定当前 UUID 的 `device.settings.open`，窄屏“返回设备”使用 `devices.open`；未保存切换继续由 `navigation.resolve {choice: stay / discard / save}` 处理。本轮没有新增动作或改变参数，实际按钮、三种选择和滚动恢复的验证范围见[设备导航](ANDROID-DEVICE-NAVIGATION-20261101.md)。

Android 本地 preview.183 在可用宽度 600 dp 起改用侧边导航，较窄窗口保留底部导航；高度不足时侧栏可滚动。仍使用 `devices.open / device.add.open / app.settings.open / appearance.toggle`，设备分类和保存继续使用原 `settings.*`。尺寸变化保留当前页、分类、同一个正文编辑控件和草稿，没有新增动作参数。[实际页面、编辑与保存范围](ANDROID-SETTINGS-VIEWPORT-20261101.md)

Windows 本地 preview.184 保留完整保存错误，长内容在固定操作栏内单独滚动。测试控件为 `scroll|profile-error`、`scroll|sync-error`，使用既有 `view.ui.focus / scroll / key`；`view.ui.key` 及 PowerShell 工具新增 PageUp／PageDown，业务保存／取消参数不变。[控件接口](UI-TEST-API.md)、[验证与未完成范围](WINDOWS-SAVE-ERROR-20261101.md)

本地 preview.172 增加双端 `devices.filter.clear {expectedQuery}`，参数必须为当前 `state.deviceQuery` 的字符串快照；缺失、错误类型或过期查询均拒绝，不清掉后来输入。只清除本机筛选，保留当前设备、连接设置草稿与持久配置；空设备页的添加入口复用 `device.add.open`。原有 `devices.filter {query}` 继续用于编辑搜索。[界面、动作与验证范围](UI-EMPTY-WORKSPACE-20261031.md)

Windows 的隔离预览另提供 `view.ui.inspect / focus / key / scroll / result`，检查实际控件的焦点、下拉菜单及应用内按键分发。业务操作继续使用原动作；控件测试不发送系统输入、不激活窗口，不读取输入框内容。启用条件、异步结果及证据边界见 [控件测试接口](UI-TEST-API.md)。

Windows preview.86 起，`navigation.resolve`、`sync.clear.resolve` 的参数可增加 `requestId`，分别取当前状态中的 `navigationPrompt.requestId`、`syncClearPrompt.requestId`。产品按钮及模态键盘始终携带当时显示的编号，过期编号会被拒绝；省略该参数仍表示处理当前确认。编号只在本次运行内有效，不进入同步文件。这是业务确认的编号，与 `view.ui.*` 测试队列的幂等请求编号不同。[确认流程与验证](WINDOWS-CONFIRMATION-OWNERSHIP-20261029.md)

Windows preview.88 起，可修改连接设置的 `settings.*` 动作（`settings.catalog` 除外）接受可选 `draftId`，取当前状态的 `profileDraftId`。产品控件入队时始终携带当时渲染的编号；过期、空或错误类型的编号返回 `ok:false, ignored:true, reason:"stale_profile_draft"`，当前表单和配置不变。新开／重开草稿及应用复制配置后更换编号，普通编辑和分类切换保持编号。省略参数仍操作当前草稿，保持即时 API 兼容；编号不进入持久化或 WebDAV 文档。[草稿归属与验证](WINDOWS-PROFILE-DRAFT-20261029.md)

Windows 回归模式下，`device.pair` 可显式传入布尔参数 `testPairPending: true`，让已有授权的测试身份建立自己的新挂起请求，用于验证中继取消。仅 RHREMOTE_TEST_MODE=1 接受，正常模式返回 HTTP 422；服务端 PIN、证书、签名和授权检查继续执行。它不创建公开配对入口，也不代表未配对身份可以使用中继。

Windows preview.142 起，`device.pair` 接收后在后台准备线路。`connection.phase` 依次为 `preparing`、`contacting`、`pairing`，公开 `pairPin` 仅在本次协议开始后可见；签名凭证就绪不代表服务端已确认，最终成功仍由本次 PIN 交换结果决定。`pair.cancel` 在准备阶段也可调用，接收取消后仍需等待清理完成才可重试；阶段和清理测试范围见[记录](WINDOWS-PAIR-PREPARATION-20261031.md)。这些阶段只描述 Windows 当前实现，不改变 Android 的现有状态约定。

Android 本地候选 preview.173 在 PIN 等待页同时公开 `connection.phase=pairing` 与 `admission.phase=pairing`。重复连接、配对、安装更新和网络诊断仍返回 `ok:false`，错误说明实际持有者为连接准备、配对、清理、恢复或串流；拒绝不创建第二个任务。`pair.cancel` 接收后，等待实际清理完成才释放占用，恢复设备选择与搜索。此增量只修正阶段和反馈，不代表间歇 HTTP／TLS 截断或主线程 ANR 已解决。[实服务与回归范围](ANDROID-PAIRING-FEEDBACK-20261031.md)

```json
{"id":"device.settings.open","args":{"uuid":"<Sunshine UUID>"}}
{"id":"settings.set","args":{"key":"resolution","value":[1920,1080]}}
{"id":"settings.set","args":{"key":"fps","value":30}}
{"id":"settings.set","args":{"key":"displayName","value":"开发机"}}
{"id":"settings.save","args":{}}
{"id":"device.connect","args":{"uuid":"<Sunshine UUID>"}}
{"id":"device.move","args":{"uuid":"<Sunshine UUID>","direction":"up"}}
```

设备卡片的上移、下移按钮调用 `device.move`，direction 只能是 `up` 或 `down`；到达边界保持原位。顺序立即保存，刷新和重启不重排。共享文档 `devices` 数组的顺序就是显示顺序，不另加依赖本机分辨率的排序字段；WebDAV 对顺序做三方合并，同时更改为不同顺序时返回 `deviceOrder` 冲突。离线和本机设备同样参与排序。同步过程中拒绝移动，避免覆盖正在合并的数据。

设备配置支持尺寸、帧率、码率、编码、鼠标主副键补偿、触控板模式及统计。先编辑草稿，保存才持久化；取消保留原值。`displayName` 是可同步的设备别名，留空恢复原始主机名；不改变 Sunshine 主机名、UUID、地址或配对身份。`settings.save` 也可直接传入可选 displayName。

每次安装首次启动产生持久化随机 `saved.clientId`，不同 Windows、不同安卓设备分别识别。画质和输入偏好按“客户端 ID × Sunshine UUID”保存。`saved.profiles` 是当前客户端生效值，`saved.clientProfiles` 保留各客户端的配置集合。`settings.copy_from` 的 clientId 指定其他客户端，只复制当前远程设备的画质/输入设置到草稿；保存才生效，不改变源客户端或共享主机资料。配对 PIN 可由接口提供，或由客户端生成；Android 和 Windows 的取消配对均有动作。

`input.gesture` 支持 tap、secondary、drag、scroll；Android 另支持 pinch。坐标是接收端解码区域像素，Android 尺寸由 `state.stream.surface` 返回。拖动终点为 toX/toY，滚轮量为 delta，缩放倍数为 factor。输入必须有已连接的串流，所有生成的按键都需释放。

## Android 添加设备状态

`device.add.submit` 的单次请求包含本地准备和主机查找。`state.add.phase` 为 `preparing`、`probing` 或 `editing`：准备最多等待本地组件 5 秒，就绪后自动继续；组件已就绪时直接查找。准备／查找期间拒绝重复提交及输入变更，`device.add.cancel` 可取消。超时或失败回到 editing，保留 `address` 和 `error`，由调用者明确重试；取消后的迟到结果不会保存。

## WebDAV

填写已有的 HTTP 或 HTTPS 目录、用户名和密码。目录中的固定文件名为 `rhremote-config-v1.json`。Windows 密码使用 Windows Credential Manager，Android 使用 Android Keystore 的 AES-GCM 加密；这些值都不进入便携配置。

`sync.configure` 保存本机同步设置；密码留空时只允许保留同一服务器、同一账号的已有凭据。`sync.clear` 移除本机同步设置。`sync.run` 获取远端并合并；Android 是异步任务，需轮询 syncBusy 和状态。自动同步可选每 5、15、30、60 分钟，默认 5 分钟；`sync.configure.intervalMinutes` 和 `state.sync.intervalMinutes` 对应此值。间隔及账号属于本机设置，不参与同步。手动同步成功会重新计算下次时间，失败按所选间隔重试，遇到冲突等待用户选择。

同步 schema v2 包含 schemaVersion、devices、clients、profiles 和 dark。保留原文件名作为存储路径，内部版本由 schemaVersion 判断。结构为：

```json
{
  "schemaVersion": 2,
  "devices": [{"uuid":"host-uuid","name":"winserver","displayName":"开发机","address":"192.168.0.104","swapButtons":true}],
  "clients": {"client-pc":{"name":"PC","platform":"windows"},"client-pad":{"name":"OPPO","platform":"android"}},
  "profiles": {
    "client-pc":{"host-uuid":{"width":1920,"height":1080,"fps":30,"bitrateMbps":25,"codec":"HEVC","trackpad":false,"statistics":false}},
    "client-pad":{"host-uuid":{"width":2520,"height":1680,"fps":30,"bitrateMbps":25,"codec":"HEVC","trackpad":false,"statistics":false}}
  },
  "dark": false
}
```

设备名称、别名、地址和主机交换左右键的事实共用；尺寸、帧率、码率、编码、输入模式和统计按客户端隔离。同步保留所有客户端配置，但只读取本机 clientId 对应分组。新 Windows 默认 1920×1080/30，新 Android 默认 2520×1680/30；明确保存过的旧设置原样迁移到本机分组。旧版远端 v1 配置无法确认归属，保留为“旧版配置”供手动复制，不自动覆盖本机尺寸。首次迁移保留本机备份。卸载或清空应用数据会产生新的客户端 ID；升级保留身份。

运行时 isSelf、online、paired、证书、密钥、令牌与同步账号不属于此格式。缺失项不推断为删除，本机设备也包含在同步集合中；自身识别由客户端本地 Sunshine UUID 重新计算。

双方与上次成功同步的基线做三方合并。不同项直接合并，同一项同时修改时保留待解决状态，先不 PUT。`sync.resolve` 的 resolution 为 local 或 remote，只选择冲突项的来源；期间任一端再变化就要求重新检查。上传使用强 ETag / If-Match；首次创建使用 If-None-Match，避免并发覆盖。应用前保存本机与远端快照，各保留最近 20 份。

同步审阅的 `state.sync.conflictItems[]` 保留 `key`、`label`、`local`、`remote`，并增加 `fields[]`，每项为 `{key,label,local,remote}`。设备资料与客户端连接配置按原始配置值找出变化字段，页面按参数分行、本机／远端分列显示；完整原始内容不属于审阅接口。`fields` 不表示逐字段解决冲突，`sync.resolve` 仍按原有冲突项选择一端。字段控件标识为 `sync-review-local|<item.key>|<field.key>`／`sync-review-remote|<item.key>|<field.key>`。主题等简短冲突可继续使用两侧摘要。

`state.sync.reviewId` 是本机当前审阅快照的不透明标识，不参与 WebDAV 配置。两端界面的保留按钮调用 `sync.resolve {resolution,reviewId}`，入口及后台执行均核对该标识；过期、空或非字符串标识在访问凭据和 DAV 之前被拒绝，不清除较新的审阅。相同本机配置、远端配置和 ETag 的重复检查保留标识，任一改变会生成新标识。此检查补充原有配置／ETag 核对。为兼容既有接口调用，省略 `reviewId` 时仍执行原有核对；界面总会为有效审阅带上标识。

等待处理冲突时，正常计时器、动作入口与后台任务均不启动自动同步；`sync.run {automatic:true}` 返回 `skipped:true`、`reason:"sync_conflict"`。`automatic` 提供时必须为布尔值，字符串或 null 不会被解释成手动同步。冲突页的手动按钮称为“重新同步”，仍执行完整同步流程；若继续存在冲突，上传等待用户选择。

`profileCopy.fields[]` 增加稳定的 `key`。开启自动码率时，保留的 `bitrateMbps` 显示为“手动码率”及“手动值，未启用”，不表示实际连接码率。自动码率当前仅接通连接时预算，持续反馈调节仍待完成，见[范围](AUTO-BITRATE-STARTING-RATE-20261031.md)。

## 更新

Windows 使用 Velopack，启动前执行 Velopack 生命周期钩子。安装版每 6 小时检查一次，有更新时下载，由用户决定重启安装；活动串流期间不能安装。开发 exe 不是受管理安装，界面会标明。`updates.check/download/install/state` 均有接口。

Android 自动检查并下载；用户发起下载后打开系统 APK 安装界面，由用户完成确认。自动下载在设置页提示已就绪。校验包名、版本、长度、SHA-256 和与已安装应用一致的签名。新版本必须维持签名与递增 versionCode。

Android 的 `updates.primary` 与页面主按钮使用同一进程内更新任务。`updates.state` 和 `state.updates` 另返回 `operationId`（本进程内递增）、`busy`、`awaitingForeground`；`state.updatesBusy` 来自同一任务。页面或主题重建不重复检查、下载，不清空候选版本、进度和错误。任务完成与控件刷新在主线程提交；重复动作在忙碌时返回错误。

两端更新页仍只有一个主按钮。`state.updates.intent` 是不透明的当前操作快照，包含动作、任务编号与所选安装包标识；主按钮把它作为 `expectedIntent` 发送给 `updates.primary`。`updates.check/download/install` 也接受此可选参数。若快照已过期，在提示保存草稿、联网或开始安装前拒绝，并提示重新点击。调用方不要自行拼接；未提供参数的旧接口继续兼容。说明文字、进度和镜像变化不会改变同一安装包的身份。取消按钮固定发送按压开始时的 `operationId`，不能取消后来启动的任务。

Windows 从更新源选定新版后，已缓存包必须对应当前选定附件，并核对长度及 SHA-256（旧索引退回 SHA-1）。不会因为其他版本的包已下载就跳过准备或安装它。缓存核对在更新工作线程进行，应用启动与页面刷新不读取整包内容；本次安装前再次核对选定的缓存。

用户发起的安装准备若在后台完成，等待实际回到前台再打开系统界面；同一次前台状态的重复回调不消费安装返回标记。明确关闭页面会放弃未发送的安装意图，下载仍可完成并保留，重新打开后由用户点击“安装更新”。进程退出后只恢复已下载包的校验记录，不持久化自动打开安装界面的意图。

GitHub 和 Gitee 作为互备下载源，不把发布令牌放到客户端。Windows 使用 `releases.win.json`；Android 使用 schemaVersion 为 1、android 字段含 version/versionCode/packageName/tag/fileName/size/sha256 的 `releases.android.json`。包下载固定指向不可变版本 tag，先验证产物，再更新 latest 路由。

GitHub 和 Gitee 仓库均已公开，v0.2.1 安装包及 APK 已发布。开发分支的新功能不覆盖已有版本的安装包或更新索引；客户端不携带个人发布令牌。

## 设置与测试统计

设备设置按画面、音频、输入、高级分类，共 24 个字段，使用 settings.catalog 查询平台支持、范围及选项。音频支持开关、音量、主机继续播放、声道；stream.audio.toggle 和 stream.audio.volume 实时控制当前会话，不修改系统音量，保存的连接偏好在下次连接生效。

Windows 的声音控制成功回执同时更新本次首帧回退记录，避免下一次读取失败时被旧声音值覆盖；提交前后均核对当前存活子进程 PID 与启动标识。失败和旧启动的迟到回执不更新记录，不写入日常偏好。[实现与验收边界](WINDOWS-AUDIO-ACK-20261029.md)

statistics.configure {enabled:true} 启用本机测试模式，每次连接自动记录逐帧文件；statistics.state 查询目录。完整协议、时间精度、显示时间含义和自动清理规则见 [STATISTICS.md](STATISTICS.md)。

Windows 的 `statistics.nativeControl` 是本次原生状态读取的诊断元数据：读取结果、阶段、耗时、HTTP／I/O 错误类别、端口和进程归属；不含令牌、错误正文或完整响应。退出统计的 `control.lastStateRequest` 使用同一格式。此字段不改变连接状态判断，也不参与配置同步。[字段含义与真实采集边界](WINDOWS-NATIVE-STATE-20261029.md)

`statistics.firstFrameFromMilestone:true` 表示同次存活进程已确认过首帧，而本次状态读取回退到更早的已连接文件；原始标志仍在 `nativeControl.firstFrame`，不会伪造首次出帧。测试专用 `view.ui.native-state-fault {processId,polls}` 只允许隔离预览中本次已出帧 PID、1～8 次模拟读取失败，正常客户端禁用。[规则与实测](WINDOWS-FIRST-FRAME-STATE-20261029.md)

测试专用 `view.ui.native-window {processId,command}` 只在隔离 Windows 预览中作用于当前存活且已出帧的原生子进程，command 为 inspect／minimize／restore；本地 preview.95 增加 resize，需附物理像素 width（256–3840）／height（160–2160），且新窗口区域必须仍在当前显示器内。不接受任意窗口句柄，操作不请求前台激活。返回本次 HWND、原生最小化／可见标志、客户区及屏幕区域。命令接受不等于已完成，调用方须继续 inspect 核对；外部区域读回需使用一致的 DPI 上下文。[已通过的基本恢复及剩余范围](WINDOWS-RESTORE-20261029.md)

Windows 悬浮键盘开关和失败后的“重试”共用 `stream.keyboard.floating.set {enabled:boolean}`。每次显式 true 都生成新 `floatingKeyboardRequest`；此字段和 `floatingKeyboardSession` 仅属本次运行。`floatingKeyboardError` 是独立的本地显示错误，不覆盖远端 `controlAction`。界面只接收同会话、同请求的反馈，失败展开操作页；同一错误重复上报不会强行展开用户已收起的控制栏。关闭或重试清除该错误。统计 `floating_keyboard_state` 仅记录变化后的会话、请求、进程及允许列举的状态码，不记录原始错误或操作参数。

本地 preview.97 的同一隔离窗口接口还接受 `command:"restore-placement"`，读取该窗口原有 placement 并异步请求显示；沿用全部归属限制。它只用于与原 restore 对照，首次恢复仍可能成为前台，不作为已通过非激活保证。[实际失败与调用栈](WINDOWS-RESTORE-FOCUS-20261029.md)
# 远程快捷键和屏幕

`stream.shortcuts.list` 返回 Windows / Android 共用的快捷键清单。`stream.shortcut` 的 `shortcut` 参数使用清单中的 ID，例如 `ctrl-alt-delete`、`ctrl-alt-left`、`ctrl-alt-right`、`alt-tab`、`ctrl-shift-escape`。普通按键按顺序按下、反序释放，失败也释放本次操作按下的修饰键。

`stream.window.move` 接受 `direction: left / right`，发送远端的 Win+Shift+方向键。它移动当前获得焦点的远程窗口。

`stream.displays.refresh` 刷新屏幕；`stream.display.select` 接受 `displayId: auto / virtual / 屏幕列表中的 id`。一次仅捕获一块屏幕，通过 Sunshine 的 Ctrl+Alt+Shift+F1…F12 切换。自动模式优先可用实体屏；没有可用实体屏时准备客户端尺寸的自有虚拟屏。`displayMode` 是客户端×设备配置，参与 WebDAV 同步。

屏幕清单、虚拟屏尺寸准备及 Ctrl+Alt+Delete 由统一的 RHRemote-Sunshine 服务提供；旧版独立 RHRemoteHost 仍按同一协议兼容。Ctrl+Alt+Delete 由 Windows SendSAS 完成。接口只接受 Sunshine 已启用配对客户端的双向 TLS 认证；客户端固定校验配对时保存的主机证书。每次操作重新检查配对启用状态，配对密钥不参加同步。

同样鉴权的 `GET https://<host>:47995/v1/health` 返回 `{ok:true,result:{product:"RHRemote-Sunshine",serviceMode:"unified",servicePid,sunshine:{pid,sessionId,starts}}}`。`servicePid` 是 SCM 的 `SunshineService` 进程，同时也是 47995 监听者；Sunshine 引擎是它管理的子进程。健康接口不返回配对材料或配置密码。

Windows 在原有认证回环接口暴露这些操作；Android 工具栏和回环接口也使用同一动作分发器。Android 的网络操作异步返回接受状态，完成结果见 `stream.lastShortcut`、`stream.displayBusy`、`stream.displayError`。

Windows 的 `stream.controls.toggle` 展开／收起位于串流窗口顶部的悬浮栏；收起后保留可点击的开关。`state.toolbar` 返回显示状态、展开状态、位置、尺寸和所属串流窗口。`view.toolbar.snapshot` 导出本应用控制栏预览。

Android 的 `stream.controls.toggle` 控制顶部分类面板，开关坐标保持不变。`stream.controls.tab {tab: input / displays / audio}` 选择操作、屏幕、声音页；`stream.displays.open` 直接打开屏幕页。`state.stream.controlsTab` 与 `controlsLayout` 返回当前页、列数、开关/面板位置尺寸及可滚动状态。按钮和音量滑条继续使用既有 action；屏幕刷新在后台进行，保留仍可用显示器的选择，并使用本次清单里的最新 captureIndex。屏幕组件未安装时不生成虚假的显示器条目。

Android 本地候选 178 保留 `controlsLayout.created`，表示面板主体已准备，与 `expanded` 的可见性分开。主体在会话初始化时创建，首次展开复用；释放后为 false。分类选择、音量及模式继续使用原有动作，此字段不参与配置同步。174 的按需创建在实服务中出现慢展开，已撤回。`session_controls_body_create` 及 chrome、input、displays、audio、bind_initial 分段记录初始化；测试的根视图 OnDraw 与 UI 空闲分别报告，均不能代替像素首次呈现。[真实响应、反馈归属与剩余限制](ANDROID-SESSION-PANEL-REAL-20261101.md)

Android 操作页的“滚动模式”使用 `stream.scroll.set {enabled: boolean}`，`state.stream.scrollMode` 返回开关状态。每次会话默认关闭，不写入设备配置或 WebDAV。开启后单指轻点发送中键按下／松开，不受左右键交换影响；单指上下左右滑动发送纵横滚轮，停止发送鼠标拖动。滑动方向遵循本机该设备的 `reverseScroll` 设置，双指缩放继续可用。切换模式会释放未松开的按钮；启用滚动模式会关闭移动画面，切换直接点击／触控板或开启移动画面会退出滚动模式。未连接或会话操作忙碌时拒绝修改。

同一模式适用于 Android `input.gesture`：`tap` 为中键，`drag` 的起终点差值为四向滚动；触控板模式保持原鼠标位置。显式 `scroll` 接受 `axis: vertical / horizontal`，默认为 vertical，`delta` 是有符号高精度滚轮量。Android 特有动作由应用的 `/v1/actions` 目录补充。

Android 控制页另提供“点击与滚动”：`stream.click-scroll.set {enabled: boolean}`，状态为 `state.stream.clickScrollMode`。单指轻点发送原有逻辑主键，单指滑动发送纵横滚轮，双指平移按住主键拖动；双指缩放继续缩放画面。拖动从第一根手指所在位置开始，任一手指抬起或取消都会释放按键，第三根手指加入会取消本次手势。左右键交换和滚动方向沿用当前设备设置。启用时退出触控板、中键滚动和移动画面；这些模式开启时会退出“点击与滚动”。与原滚动开关一样，它仅作用于当前会话。

新模式下，`input.gesture {gesture:"swipe", fingers:1, x, y, toX, toY}` 对应单指滚动，`fingers:2` 对应双指拖动；显式 `drag` 仍表示鼠标拖动。未开启该模式或 fingers 不为 1／2 时拒绝 swipe。新开关同样遵守连接就绪及操作忙碌的限制。

Android 控制面板展开时聚焦固定开关，收起时恢复展开前的画面焦点。控件聚焦后的 Tab／Shift+Tab、方向键、Enter 和 Escape 在本地分发，Tab 只遍历可见、可用的操作控件；本地开始的按键在面板收起后松开也不会转发远端。远程画面聚焦时保留 Moonlight 的输入路径，已发往远端的修饰键松开不被新面板截留。音量的键盘与无障碍改值同样调用 `stream.audio.volume`；触摸拖动仍在松开时只提交一次，模型刷新不触发新动作。应用内与实际串流验证的范围见 [会话导航](ANDROID-SESSION-NAVIGATION-20261028.md)，不等同于外接键盘实机验收。

Windows 意外中断后，`statistics.phase=reconnecting`、`connecting=true`，恢复首帧后回到 `connected`。`statistics.reconnectAttempts` 是当前连续恢复次数；主动 `stream.disconnect` 会取消待执行的恢复。仅有已渲染会话能够自动恢复，首次连接失败不会无限重试。`stream.test.interrupt` 仅在已启用逐帧统计的活动 Windows 会话中可用，注入一次明确标记的模拟中断，走完整的底层终止及恢复路径。

Android 也支持 `stream.test.interrupt`：必须已启用 `statistics.configure` 测试模式、实际提交过首帧且当前没有恢复任务。接口记录 `test_connection_interrupt {synthetic:true}`，进入与意外终止相同的清理／选路／新 Game 交接路径；重复注入或未满足前置条件会被拒绝。Android 的新 Game 使用独立 Activity 实例，admission/recovery 保留继续防止并发连接；不能用上游 `singleTask` 复用即将结束的旧实例。

Android 同步状态增加 `phase=interrupted`、`interrupted` 和 `automaticBlocked`。新进程发现未完成任务记录时，保持 `busy=false`，显示传输结果未确认并暂停自动同步；`sync.run {automatic:true}` 返回 `skipped=true, reason=sync_interrupted`，不创建 worker。表单编辑、取消、保存及主题切换保留该状态；明确手动同步或移除同步设置后处理。记录仅含本机任务编号、时间及 pending，不参与 WebDAV 同步。

Windows 同步的 `phase` 增加 `applying`、`restoring`、`recording`；完成前 `syncBusy=true`。`awaitingLocalApply` 表示本实例待应用结果，私有 `pendingApply` 保留未确认标记；收尾失败或重开待核对时 `automaticBlocked=true`，自动 `sync.run` 返回 `skipped=true, reason=sync_interrupted`。原有单次同步动作负责本机保存、回读、设备状态恢复与平台确认，全部通过才更新基准和 `lastSyncMs`。不增加用户确认按钮或公开低层提交动作。

Android 同步只有在本机配置与主题持久化、完成记录回读均通过后才推进 `lastSyncMs` 和合并基准。`awaitingLocalApply` 表示本实例有待应用的传输结果，工作阶段包含 `applying` 与 `recording`。应用／完成记录失败保留旧基准和 pending，`automaticBlocked=true`；主线程完成回调前仍为忙碌。所有步骤属于原有 `sync.run`／`sync.resolve` 操作，不增加用户确认动作。

Android 自动恢复的 `stream.feedback` 提供 `visible`、`title`、`detail` 和 `cancelAction`，与恢复任务持有的原生提示一致。实际取消按钮以及提示框内的 Back／Escape 均走 `stream.disconnect`；旧提示框回调检查任务归属，不能取消新恢复。提示在交接、离开、失败或取消时关闭，原有清理完成前仍禁止并发连接。

设备网络接口及参数见 [NETWORK.md](NETWORK.md)：`device.network.configure`、`device.network.diagnose`、`network.state`。设置分类新增 `network`；设备的多地址参与同步，诊断和当前路径保留在本机。

Android 本地 preview.149 增加 `device.network.cancel {operationId, expectedAction?}` 及 `device.network.details.toggle {uuid, operationId, expectedExpanded?}`；实际诊断按钮携带 `expectedIntent`／`expectedOperationId`。`diagnostics` 提供取消、清理及失败重试阶段，离开网络页仍可从任务条取消；`diagnosticDetailsExpanded` 是页面展开状态。清理完成前禁止并发串流及安装更新，详情开关不发起网络请求。[参数与实测范围](ANDROID-DIAGNOSTIC-CANCELLATION-20261031.md)

Windows 本地 preview.150 接入相同的取消／详情动作；`device.network.diagnose` 立即返回 `{accepted:true,operationId}`，最终结果从主状态 `diagnostics` 读取。Windows 实际开始按钮额外携带 `draftId`；`diagnosticRequestIntent` 是当前所选网络设置快照，`diagnostics.requestIntent` 是任务接受时的快照。任务阶段及清理完成前的 busy 与 Android 同义，不进入 Saved／WebDAV；关窗会等待诊断清理。[队列复现、归属及验证边界](WINDOWS-DIAGNOSTIC-TASK-20261031.md)

Android 主界面未保存确认的 Esc 使用 `navigation.resolve {choice:"stay"}`，同步移除确认的 Esc 使用 `sync.clear.resolve {choice:"cancel"}`。两者保留完整按键及当前弹窗归属检查；初始键盘选择分别为“继续编辑”和“取消”，Enter 仍触发焦点按钮已有的动作。对应状态仍是 `navigationPrompt`／`syncClearPrompt`，没有额外的提交接口。[实际弹窗入口与验证边界](ANDROID-HOME-DIALOG-KEYS-20261030.md)

Windows preview.144 的 `devicesPending` 也覆盖共享配置导入、公开设备信息添加和线路设置变化；这项待确认状态不进入 Saved／WebDAV。Saved 的 `deviceMembershipAuthoritative` 仅用于本机启动恢复，在应用共享列表后保存，防止原生身份缓存恢复不属于该列表的卡片；共享文档没有这个字段。自己的同步记录仍可编辑，本机身份核对后禁用连接。[状态与验证边界](WINDOWS-IMPORTED-DEVICE-STATE-20261031.md)

Android 本地 preview.171 在接受完整配置替换时，使用本机 `removedSharedDevices` 记录明确移除的 UUID，并以 `deviceMembershipAuthoritative` 标记记录初始化；两项均不进入 WebDAV。工作区恢复、迟到发现与导出过滤该集合，保留正常新发现；原有 `device.add.*` 成功提交或完整配置再次包含该设备后允许恢复，不删除原生证书／配对库。公共同步仍遵守“缺项不推断为删除”，没有新增跨端删除动作；回环原生测试的完整替换输入由测试提供。[行为和验收边界](ANDROID-DEVICE-MEMBERSHIP-20261031.md)

Windows preview.145 的公共状态增加运行时 `deviceChecks`，按 UUID 返回 `phase`、`outcome`、`source`、`hasCertificate`、`elapsedUs`，不进入 Saved／WebDAV。`phase` 为 `route_selection`、`server_info` 或 `native_process`；`elapsedUs` 是对应阶段的持续时间，不是首帧。`outcome` 区分配对、未配对、认证拒绝、证书不符、TLS 失败、设备标识不符、信息无效、线路不可达、超时和未确认。相同 `device.pair` 支持“配对／重新配对”，相同 `devices.refresh(uuid)` 支持重新检查；设备标识／信息检查未通过时，Core 同样拒绝连接及配对，修正地址或重新核对后解除旧诊断。[来源、操作与验收边界](WINDOWS-DEVICE-CHECK-RESULTS-20261031.md)

Windows preview.166 增加运行时 `deviceTrustChecks`，以相同结构按 UUID 保留尚未解除的认证拒绝、证书不符、保存身份仍需配对、设备标识不符和无效设备信息。`deviceChecks` 继续报告本次检查；后续超时、不可达或安全连接未完成不清除前一项身份问题。页面同时说明本次与上次结果；无法到达时以原 `devices.refresh(uuid)` 检查连接。`device.connect` 在 Core 同样拒绝尚需重新配对的身份，不能通过接口绕过按钮；身份不符／信息无效继续拒绝连接及配对。后续已验证结果、重新配对成功可解除旧记录；改变地址、线路设置或共享配置中的对应端点使两份观察一起失效，别名编辑保留。两份运行时记录均不进入 Saved／WebDAV。[交互、对照与剩余范围](WINDOWS-TRUST-REFRESH-20261031.md)

Android preview.146 同样支持 `devices.refresh(uuid)`，省略 uuid 才刷新列表；结果通知仍由现有发现服务负责。目标校验与按钮共用 RhHome，刷新排队成功不表示网络检查已完成。状态里的 `pairingStatus` 区分 `unknown`、`stored`、`verified`、`unpaired`，每台设备还返回 `primaryAction`／`primaryLabel`；没有本机证书的 PAIRED 声明不返回 `paired:true` 或 `canConnect:true`。这里沿用已有 ComputerDetails 的结果来源，不新增 HTTPS 证明；未知设备仍允许用户明确请求配对，由原 admission 执行线路与身份检查。[入口、队列和验证边界](ANDROID-DEVICE-CHECK-FLOW-20261031.md)

Android preview.167 的设备 check 增加网络范围：`staleReason` 区分 `network_changed`／`configuration_changed`；旧结果的 `previousObservationId` 仅供定位，不表示本轮成功。仍需处理的身份错误在切网后保留 outcome，并返回 `networkStale:true`、`reachabilityCurrent:false`，主入口要求重新检查。`device.connect` 同样依据 canConnect 拒绝跳过配对或检查；保留身份错误且网络待确认时，`device.pair` 也先检查。普通首次配对的显式入口及保存身份的重新验证流程保持。所有新字段只存在于当前进程，不参加配置同步。[回调、接口和验证范围](ANDROID-NETWORK-OBSERVATIONS-20261031.md)

Android preview.168 在后台发现回调中以当前配置、固定证书、默认网络代次及 UUID 核对较新的 HTTPS 认证，解除对应设备的旧检查缓存；HTTP 回退与过期结果不能解除。主动检查历史结果保持，设备行使用更新后的事实。保存身份仍需配对且当前离线／未确认时，primaryAction 为 device.check，device.pair 在创建准备任务前同样拒绝；确认在线后恢复配对。普通无证书设备的显式首次配对入口保持。均为运行时处理，不修改配对证书或同步配置。[流程、原生验证与范围](ANDROID-AUTHENTICATION-REFRESH-20261031.md)
