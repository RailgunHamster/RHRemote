# 控制接口与配置同步

Windows 和 Android 的新增产品按钮通过 action ID 分发。HTTP 调用走同一个分发器，不能绕过设备离线、配对、自身连接、活动串流和配置验证等检查。

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

Windows 回归模式下，`device.pair` 可显式传入布尔参数 `testPairPending: true`，让已有授权的测试身份建立自己的新挂起请求，用于验证中继取消。仅 RHREMOTE_TEST_MODE=1 接受，正常模式返回 HTTP 422；服务端 PIN、证书、签名和授权检查继续执行。它不创建公开配对入口，也不代表未配对身份可以使用中继。

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

## 更新

Windows 使用 Velopack，启动前执行 Velopack 生命周期钩子。安装版每 6 小时检查一次，有更新时下载，由用户决定重启安装；活动串流期间不能安装。开发 exe 不是受管理安装，界面会标明。`updates.check/download/install/state` 均有接口。

Android 自动检查并下载；用户发起下载后打开系统 APK 安装界面，由用户完成确认。自动下载在设置页提示已就绪。校验包名、版本、长度、SHA-256 和与已安装应用一致的签名。新版本必须维持签名与递增 versionCode。

GitHub 和 Gitee 作为互备下载源，不把发布令牌放到客户端。Windows 使用 `releases.win.json`；Android 使用 schemaVersion 为 1、android 字段含 version/versionCode/packageName/tag/fileName/size/sha256 的 `releases.android.json`。包下载固定指向不可变版本 tag，先验证产物，再更新 latest 路由。

GitHub 和 Gitee 仓库均已公开，v0.2.1 安装包及 APK 已发布。开发分支的新功能不覆盖已有版本的安装包或更新索引；客户端不携带个人发布令牌。

## 设置与测试统计

设备设置按画面、音频、输入、高级分类，共 24 个字段，使用 settings.catalog 查询平台支持、范围及选项。音频支持开关、音量、主机继续播放、声道；stream.audio.toggle 和 stream.audio.volume 实时控制当前会话，不修改系统音量，保存的连接偏好在下次连接生效。

statistics.configure {enabled:true} 启用本机测试模式，每次连接自动记录逐帧文件；statistics.state 查询目录。完整协议、时间精度、显示时间含义和自动清理规则见 [STATISTICS.md](STATISTICS.md)。
# 远程快捷键和屏幕

`stream.shortcuts.list` 返回 Windows / Android 共用的快捷键清单。`stream.shortcut` 的 `shortcut` 参数使用清单中的 ID，例如 `ctrl-alt-delete`、`ctrl-alt-left`、`ctrl-alt-right`、`alt-tab`、`ctrl-shift-escape`。普通按键按顺序按下、反序释放，失败也释放本次操作按下的修饰键。

`stream.window.move` 接受 `direction: left / right`，发送远端的 Win+Shift+方向键。它移动当前获得焦点的远程窗口。

`stream.displays.refresh` 刷新屏幕；`stream.display.select` 接受 `displayId: auto / virtual / 屏幕列表中的 id`。一次仅捕获一块屏幕，通过 Sunshine 的 Ctrl+Alt+Shift+F1…F12 切换。自动模式优先可用实体屏；没有可用实体屏时准备客户端尺寸的自有虚拟屏。`displayMode` 是客户端×设备配置，参与 WebDAV 同步。

屏幕清单、虚拟屏尺寸准备及 Ctrl+Alt+Delete 由统一的 RHRemote-Sunshine 服务提供；旧版独立 RHRemoteHost 仍按同一协议兼容。Ctrl+Alt+Delete 由 Windows SendSAS 完成。接口只接受 Sunshine 已启用配对客户端的双向 TLS 认证；客户端固定校验配对时保存的主机证书。每次操作重新检查配对启用状态，配对密钥不参加同步。

同样鉴权的 `GET https://<host>:47995/v1/health` 返回 `{ok:true,result:{product:"RHRemote-Sunshine",serviceMode:"unified",servicePid,sunshine:{pid,sessionId,starts}}}`。`servicePid` 是 SCM 的 `SunshineService` 进程，同时也是 47995 监听者；Sunshine 引擎是它管理的子进程。健康接口不返回配对材料或配置密码。

Windows 在原有认证回环接口暴露这些操作；Android 工具栏和回环接口也使用同一动作分发器。Android 的网络操作异步返回接受状态，完成结果见 `stream.lastShortcut`、`stream.displayBusy`、`stream.displayError`。

Windows 的 `stream.controls.toggle` 展开／收起位于串流窗口顶部的悬浮栏；收起后保留可点击的开关。`state.toolbar` 返回显示状态、展开状态、位置、尺寸和所属串流窗口。`view.toolbar.snapshot` 导出本应用控制栏预览。

Android 的 `stream.controls.toggle` 控制顶部分类面板，开关坐标保持不变。`stream.controls.tab {tab: input / displays / audio}` 选择操作、屏幕、声音页；`stream.displays.open` 直接打开屏幕页。`state.stream.controlsTab` 与 `controlsLayout` 返回当前页、列数、开关/面板位置尺寸及可滚动状态。按钮和音量滑条继续使用既有 action；屏幕刷新在后台进行，保留仍可用显示器的选择，并使用本次清单里的最新 captureIndex。屏幕组件未安装时不生成虚假的显示器条目。

Windows 意外中断后，`statistics.phase=reconnecting`、`connecting=true`，恢复首帧后回到 `connected`。`statistics.reconnectAttempts` 是当前连续恢复次数；主动 `stream.disconnect` 会取消待执行的恢复。仅有已渲染会话能够自动恢复，首次连接失败不会无限重试。`stream.test.interrupt` 仅在已启用逐帧统计的活动 Windows 会话中可用，注入一次明确标记的模拟中断，走完整的底层终止及恢复路径。

Android 也支持 `stream.test.interrupt`：必须已启用 `statistics.configure` 测试模式、实际提交过首帧且当前没有恢复任务。接口记录 `test_connection_interrupt {synthetic:true}`，进入与意外终止相同的清理／选路／新 Game 交接路径；重复注入或未满足前置条件会被拒绝。Android 的新 Game 使用独立 Activity 实例，admission/recovery 保留继续防止并发连接；不能用上游 `singleTask` 复用即将结束的旧实例。

设备网络接口及参数见 [NETWORK.md](NETWORK.md)：`device.network.configure`、`device.network.diagnose`、`network.state`。设置分类新增 `network`；设备的多地址参与同步，诊断和当前路径保留在本机。
