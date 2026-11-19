# 双向剪贴板同步（阶段一：Windows 客户端 ⇄ Windows 主机）

更新时间：2026-11-19。

串流期间，本机复制的内容可直接在远端粘贴，远端复制的内容也可直接在本机粘贴。做法参照 RDP 剪贴板重定向：复制时只通告格式清单，对端以延迟渲染接管剪贴板；只有程序真正粘贴时才传输数据。

## 范围

| 类别 | 格式 | 阶段 |
| --- | --- | --- |
| 文本 | `CF_UNICODETEXT`（对端系统自动派生 `CF_TEXT`/`CF_OEMTEXT`）、`CF_LOCALE` | 一 |
| 富文本 | `HTML Format`、`Rich Text Format` 等注册格式 | 一 |
| 图片 | `CF_DIBV5`（无则 `CF_DIB`）、`PNG` 等注册格式；`CF_BITMAP` 由系统合成 | 一 |
| 其他内存格式 | Office／Excel 等程序私有的注册格式，按格式名原样转发 | 一 |
| 文件／文件夹 | `CF_HDROP` ⇄ `FileGroupDescriptorW` + `FileContents` | 二 |
| Android、Ubuntu/X11、Linux 主机 | 同一协议，按 MIME 映射 | 三 |

不转发句柄或进程相关格式：`CF_BITMAP`、`CF_METAFILEPICT`、`CF_PALETTE`、`CF_ENHMETAFILE`、`CF_HDROP`（阶段二前）、owner-display、私有及 GDI 范围，以及 OLE／Shell／拖放类注册格式（`Ole Private Data`、`Object Descriptor`、`Embed Source`、`Shell IDList Array`、`FileGroupDescriptorW` 等）。两端使用同一份格式策略：主机 `server/sunshine/src/rhremote_clipboard.h`，客户端 `client/windows/overlay/app/gui/rhclipboardsync.h`，修改时保持一致。每次最多通告 64 个格式。

## 协议

使用 Sunshine 已配对的 HTTPS 端口（47984），与 `/rhremote/windows` 相同的双向 TLS 身份及撤销检查；局域网、打洞、Tailscale、中继线路都已转发该端口。每个请求都重新检查配对仍启用，且该证书当前持有运行中的串流；未串流返回 403。拒绝带 `Origin`／`Referer` 的请求。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/rhremote/clipboard/events?v=1&epoch&cursor&wait` | 长轮询（最长 25 秒）。返回 `{ok,epoch,cursor,events[]}`，事件为 `formats {seq,formats}`、`render {request,format}`、`cancel {request}` |
| POST | `/rhremote/clipboard/formats` | `{"version":1,"formats":[{"id":13},{"id":49161,"name":"HTML Format"}]}`，主机清空剪贴板并以延迟渲染接管 |
| GET | `/rhremote/clipboard/data?v=1&seq&format&offset` | 读取主机某格式的一个分块（最大 4 MiB），响应头 `X-RHRemote-Total`／`X-RHRemote-Offset`；`seq` 不再是当前剪贴板时返回 410 |
| POST | `/rhremote/clipboard/render?v=1&request&offset&total` | 客户端按分块上传主机粘贴所需的数据；`&error=1` 表示本机无法提供；主机已放弃时返回 410 |

`epoch` 是主机进程内的随机标识，主机重启后客户端从头读取事件。同一客户端只保留最新的 `formats` 事件。旧服务端对 events 返回 404，客户端停止同步并保留原有“粘贴本机文本／取回远端文本”快捷键。

## 行为

- 连接建立后，客户端先通告本机当前剪贴板（与 RDP 相同，本机优先）；之后两端任一侧复制都会通告对端。
- 自己写入剪贴板引起的变化（所有者为自身窗口）不再通告，避免回环。多台客户端同时连接同一主机时，一台客户端的内容会经主机转通告其他客户端。
- 粘贴时数据按 4 MiB 分块传输，不设固定大小上限；只拒绝大于接收端可用物理内存一半或 4 GiB 的单个格式。
- 粘贴的程序最多等待 10 秒。超时后本次粘贴失败，传输在后台继续；完成后自动放入剪贴板，提示“请再次粘贴”。15 秒没有任何进展视为失败，对端收到 `cancel`。
- 主机锁屏或处于安全桌面时不通告、不提供数据；解锁后补发通告。
- 串流结束时客户端放弃尚未渲染的远端格式；主机在该客户端串流结束后清空仍由它提供的延迟格式。

## 设置与状态

- 连接设置“输入”分类新增 `clipboardSync`（同步剪贴板，默认开启，下次连接生效），按“客户端 × 设备”保存并参与 WebDAV 同步。为兼容旧版客户端，配置中只在关闭时写出该字段。Windows 客户端以环境变量 `RHREMOTE_CLIPBOARD_SYNC` 传给原生串流进程。
- 原生进程 `/v1/state` 增加 `clipboard {enabled, supported(unknown/yes/no), phase(idle/toLocal/toRemote), bytes, total, background, lastDirection, message, error, atMs}`，属于运行时状态，不进入配置同步。
- 原生进程 `POST /v1/clipboard/cancel` 取消正在进行的上传及后台下载。

## 实现位置

| 组件 | 文件 |
| --- | --- |
| 主机协议核心（与平台无关，含单元测试） | `server/sunshine/src/rhremote_clipboard.h`、`server/sunshine/tests/unit/test_rhremote_clipboard_sync.cpp` |
| 主机 Windows 剪贴板线程 | `server/sunshine/src/platform/windows/clipboard_sync.{h,cpp}` |
| 主机接口 | `server/sunshine/src/nvhttp.cpp` |
| Windows 客户端 | `client/windows/overlay/app/gui/rhclipboardsync.h`，由 `rhcontrol.h` 随串流启动／停止 |
| 设置 | `client/crates/rhremote-core/src/{lib.rs,settings.json}`、`client/crates/rhremote-ui/src/platform.rs` |

## 验证

- 已通过：主机 16 项协议单元测试（格式选择与解析、长轮询、通告去重、epoch、分块渲染、内存上限、后台完成、取消、超时、串流结束清理、快照缓存）。
- 已通过：3 项真实 Win32 剪贴板测试，运行在独立的非交互窗口站上（`tools/test-clipboard-isolated.ps1`，每个窗口站有独立剪贴板，不读写登录用户的剪贴板；测试本身拒绝在 `WinSta0` 运行）：主机复制被通告并按 seq 读取、过期 seq 返回 410；客户端通告后延迟渲染，粘贴时分块取得 Unicode 文本和 HTML，渲染结果保留，另一客户端收到转通告、来源客户端不回环；渲染失败时粘贴为空；来源客户端串流结束后延迟格式被移除。
- 已通过：`rhremote-core` 配置默认值与序列化测试；`rhremote-ui` 工具栏剪贴板状态测试；Windows 原生客户端（Qt 6.7.2 / MSVC）在隔离的源码副本中完整编译链接，新增代码无警告。
- 空的格式清单不通告：连接时本机剪贴板为空、或一端被清空，都不会清掉另一端的剪贴板。
- 待完成：实际部署到主机后的端到端验收——文本／HTML／图片／Excel 双向粘贴、SYSTEM 引擎作为剪贴板所有者时桌面程序的延迟渲染（代码已为相关消息放开 UIPI 过滤）、大图后台传输与再次粘贴、锁屏、断线重连、旧服务端回退、中继线路。若实测发现 SYSTEM 引擎的延迟渲染受限，改为由引擎以登录用户身份启动剪贴板子进程。
