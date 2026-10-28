# 更新下载完成时的表单保护（本地 preview.54）

本轮继续 UI 流程整理。两端仍只有一个更新主按钮：检查更新 → 下载并安装 → 安装／重启并安装。修正的是下载期间继续操作页面之后，安装阶段如何接续；Windows preview.53 的连接设置间距与列对齐保留。

## 实际问题

用户点“下载并安装”后，下载可能持续较长时间。在此期间返回连接设置、设备资料或 WebDAV 页面编辑，旧版 Windows 在下载结束后直接发出 `window.close`；旧版 Android 则直接打开系统 APK 安装器。两条路径都没有重新检查后来出现的未保存输入。

Windows 复现日志 `draft-before.log` 明确失败于 `Downloading must not bypass the draft decision: window.close`。Android 的 `android-draft-before.xml` 记录了本应保留表单时产生的 APK `ACTION_VIEW`。不是通过禁用全部导航来规避这个问题。

## 修改后的行为

- 下载与校验期间仍可以切换页面、编辑设置。下载完成后重新检查当时的表单；有更改则复用“保存更改？”的保存、放弃、继续编辑三个选择。非法输入保持原值和错误提示，不关闭页面。
- “继续编辑”保留已校验的更新包；后来明确安装时复用该文件，不重复下载。已有确认框时不覆盖它。Android 在后台完成下载时也不会在其他页面上强行显示安装确认。
- 配对／连接、配置同步或添加设备等操作尚未完成时，保留更新包，安装按钮显示等待原因；操作结束后可用同一个按钮继续。
- Windows 内部将 `updates.prepare` 与最终 `updates.install` 分开。前者只检查、下载和校验，不启动 Velopack；后者只接收已准备好的包，并在最终短暂交接期间阻止新的设置修改、同步和连接。`updates.prepare` 是平台内部调用，不加入公共动作目录。
- Android 仍由用户在系统界面手动确认安装。此修改保留原有签名、哈希和版本校验，以及安装权限页面的返回流程。

所用 Velopack 版本源码的 `wait_exit_then_apply_updates` 会启动一个等待当前应用退出的辅助进程，等待上限为 60 秒；因此不能在用户仍要决定如何处理草稿之前调用它。本轮将该调用留到最终安装阶段。依据为本机固定依赖 `velopack-1.2.110-ge826545/src/manager.rs`，不是对安装速度的估计。

## 验证与证据

根目录：`D:\RHRemoteTools\ui-update-close-20261028`。

| 证据 | 已验证的范围 |
| --- | --- |
| `draft-before.log`、`draft-after.log` | Windows 的旧版明确失败及修正后通过 |
| `android-draft-before.xml`、`android-install-after02.log` | Android 下载中编辑的旧版失败及修正后的分支测试 |
| `all-tests54.log` | 123 项 core、152 项 UI 测试通过；包含三种草稿的保存／放弃／停留、非法输入、已有确认、同步／连接／添加阻塞、准备与安装失败重试、最终交接时的并发动作 |
| `windows-boundaries54`、`windows-boundaries54.log` | 实际 Controller、MainView 和 Slint 按钮调用，含浅深色、宽窄窗口及同步等待；外部更新和同步边界为受控夹具，不代表已调用真实安装器 |
| `android54-full.log`、`android54-unit-results` | 355 项单元测试通过，0 失败／错误／跳过 |
| `native-draft54-light01.log`、`native-draft54-dark01.log`、`native-light01`、`native-dark01` | 专用模拟器上的真实 PcView、View 点击和输入、HTTP APK 下载、实际签名／哈希／版本校验，以及原生保存提示；每轮 1 次检查、1 次下载，随后继续编辑及保存；没有打开或确认系统安装器 |
| `windows54-layout02/catalog.json` | 256 张组件自身渲染图；其中新增 44 张更新状态图，含下载、校验、失败、已就绪、等待同步和不支持更新的启动方式 |
| `update-preview.html` | 离线图集，区分实际控件流程截图与布局夹具；不启动 HTTP 服务 |
| `verification54.json` | 包版本、签名、哈希、配置保留及边界清单 |

模拟器仅使用 `emulator-5580`。安装的应用是 preview.54／278；单独生成的 preview.55／279 只是供 HTTP 夹具下载校验的候选文件，未安装、未放入发布目录、未公开发布。两轮结束后原共享配置逐字节恢复，SHA-256 为 `fe08b5761c960bdd24fb980a58aac5ab4c58d8698bae1a3d2536270ae273a0d8`；尺寸、密度及字体仍为 1512×1008／240／1.0。

## 本地交付与剩余范围

本地 Windows 便携／安装包和 Android ARM64 APK 为 `0.2.11-preview.54`，Android versionCode 为 278。Android 签名与既有版本一致。未操作 OPPO，未启动桌面预览窗口，未修改服务、串流内核、画质或声音设置；日常 Windows 偏好哈希仍为 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`。

包核对：Windows 便携 ZIP 的 SHA-256 为 `5ce9cbbd2981663a512acaa35c8b56dc226f466bb492cfd4d27bc30028c0f7d1`，包内 UI 为 `cdc0faf9107af0df85a17ff131bbf1acb4e70e4674ad5d1e946b748db91a22bb`，与本次 release 构建一致。262 个文件条目没有偏好、凭据或运行日志，原生引擎仍为 `6b7ed0380956d960eaa17180dfc2093e85e00c7f9d7f94af2c98e691c6880304`。Android ARM64 APK 为 `af98fc8d27fa6a5a5cdcb1ac0ff5a991c07c979fdf3d904c1034a941284cfc03`，签名指纹保持 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。

本轮没有实际应用 Windows 更新，没有执行 Android 系统安装确认，没有验收 ARM64 正式升级后的配置保留，也没有将本地夹具当作 GitHub／Gitee 真实下载通过。这些工作继续保留在 TODO 的更新流程和最终交付项中，完整 UI 目标尚未完成。本轮包只供本地预览，不发布。
