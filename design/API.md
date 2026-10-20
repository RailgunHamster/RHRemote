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

动作全集以 `crates/rhremote-core/src/actions.json` 为准。部分动作只适用于特定平台：Windows 提供窗口最小化、最大化、关闭和本应用预览；Android 提供触屏控制面板、键盘、平移和缩放。不适用的动作返回错误，不能作为成功验收。

```json
{"id":"device.settings.open","args":{"uuid":"<Sunshine UUID>"}}
{"id":"settings.set","args":{"key":"resolution","value":[1920,1080]}}
{"id":"settings.set","args":{"key":"fps","value":30}}
{"id":"settings.set","args":{"key":"displayName","value":"开发机"}}
{"id":"settings.save","args":{}}
{"id":"device.connect","args":{"uuid":"<Sunshine UUID>"}}
```

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

目前两个仓库已创建为私有，安装包和 APK 还未正式发布。客户端不携带个人令牌；匿名自动更新需在公开发布完成并验证下载后才能验收。

## 设置与测试统计

设备设置按画面、音频、输入、高级分类，共 24 个字段，使用 settings.catalog 查询平台支持、范围及选项。音频支持开关、音量、主机继续播放、声道；stream.audio.toggle 和 stream.audio.volume 实时控制当前会话，不修改系统音量，保存的连接偏好在下次连接生效。

statistics.configure {enabled:true} 启用本机测试模式，每次连接自动记录逐帧文件；statistics.state 查询目录。完整协议、时间精度、显示时间含义和自动清理规则见 [STATISTICS.md](STATISTICS.md)。
