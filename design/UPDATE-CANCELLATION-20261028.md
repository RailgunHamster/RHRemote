# 双端更新取消与原位重试

本地 Windows `0.2.11-preview.65`、Android `0.2.11-preview.66 / 284` 补齐更新检查、下载及校验期间的取消。保持一个主按钮：检查更新 → 下载并安装 → 安装；只有工作进行中才出现辅助“取消”。取消后先等待清理，再允许下一次操作；安装交接已经开始时不再提供取消。

## 交互与实现

- 使用共享动作 `updates.cancel`，参数 `operationId` 为当前 `updates.state` 的整数编号。重复取消同一个仍在清理的操作不会重复启动工作；旧编号不能取消下一次更新。按钮、API 和实际控件测试使用同一处理路径。
- 取消期间主按钮显示“正在取消…”并禁用，原位取消按钮也禁用。结果为“已取消”，保留候选版本；检查取消后重新检查，下载取消后可再次下载。取消不当作网络失败，也不继续尝试另一更新源。
- 已校验完成的包保留，取消阻止自动进入安装；以后明确安装复用该包。未校验的临时文件在工作线程结束时删除，UI 不提前宣称清理结束。已有未保存表单和确认框保留。
- Windows 在注册操作后才进入更新器工作，取消控制不争用更新器的网络锁。网络请求与正文读取使用已有锁定依赖 reqwest／Tokio 的可取消 future；下载仍经过 Velopack 原来的长度与包校验。取消与成功校验同时完成时，Core 仍会阻止安装接续。系统 DNS 调用可能稍后返回，但不继续写包或进入安装。[reqwest 正文读取](https://docs.rs/reqwest/0.13.5/reqwest/struct.Response.html#method.chunk)、[Tokio 取消选择](https://docs.rs/tokio/1.53.1/tokio/macro.select.html)、[CancellationToken](https://docs.rs/tokio-util/0.7.20/tokio_util/sync/struct.CancellationToken.html)
- Android 将取消请求与 Activity 分离；连接断开在后台执行，读入、校验和提交前检查同一操作标识。页面重建继续显示取消状态。安装接续取消后不打开系统安装器；签名、哈希及版本校验保留。

取消按钮与主按钮保持固定间距，Android 至少 8dp；它不会在取消过程中变成第二个同名进度按钮。两端正常页面仍只有一个更新主动作。

## 当前证据

证据目录：`D:\RHRemoteTools\ui-update-cancel64-review-20261028`。未使用 Windows loopback 更新源，未操作 OPPO，未发布或应用 Windows 更新。

| 证据 | 范围 |
|---|---|
| `full-windows65.log` | Core 124、UI 173 项回归通过；另一个显式公网测试不随普通回归运行 |
| `public-network.log`、`public-network/` | 实际 Gitee 发布元数据及 0.2.10 的 47,196,362 字节包。首次收到 7,745 字节后取消，后台退出且 partial 删除；随后真实重试完成，SHA-256 与 GitHub／Gitee 发布信息一致。没有安装该旧版本，完整下载文件校验后已删除，保留元数据及证据 |
| `attempt03-label65/windows-result.json`、`windows-events.json` | 不激活的实体 DISPLAY2，三台日常设备配置副本，真实取消公网检查、原位重新检查及旧取消请求拒绝。4 次实际控件动作；导航项经自身 Focus／Enter 处理器，其余经实际按钮回调。没有改写更新源或注入 OS 输入 |
| `windows-layout65-corrected/` | 宽窄、浅深色的实际 Controller／MainView／Slint 控件，取消清理期间按钮状态与位置。网络结果为受控边界，不能当作实际包下载 |
| `android-full66.log` | 364 项 Android 单元测试通过，0 失败／错误／跳过；包括已有更新权限／安装返回及新取消、部分文件、清理等待、页面重建、保留已校验 APK 与重新安装接续 |
| `android-native-matrix66.json`、`native-images66/` | 专用模拟器 1512×1008、1008×1512、540×1200／字体 1.3 的三个原生流程；每轮含浅深主题与真实 Activity 重建、两次部分下载取消、旧编号拒绝和文件清理，18 张 View.draw 图片，按钮完整可见且至少间隔 8dp |
| `apk66.json`、`verification-final.json` | 正式 ARM64 APK 的版本、签名、哈希，Windows 包与实际测试 UI 的一致性，以及配置保留和剩余边界 |

Android 原生流程的 HTTP 是进程内受控连接，使用故意不进入校验的测试数据，每次实际写入 8 字节后暂停并取消。它验证真实 View、Activity、文件与状态，不声称是有效 APK、真实公网下载、系统安装或 OPPO 实测。Windows 的真实文件下载验证调用实际更新源实现，尚未覆盖主按钮驱动的完整下载到安装链路。

原生模拟器开始时 package／activity 服务不可用，安装失败。用户授权正常重启并保留数据后，同一 AVD 恢复；没有清除或重建数据。重启、覆盖安装和所有流程后共享配置 SHA-256 均保持 `fe08b5761c960bdd24fb980a58aac5ab4c58d8698bae1a3d2536270ae273a0d8`，尺寸／密度／字体恢复 1512×1008／240／1.0。Windows 日常偏好仍为 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`，隔离偏好也未改变，测试窗口已关闭。

证据修正保留在目录中：首次 Windows 导航测试误把 NavigationItem 当成 ActionButton，修正为该控件已有的 Focus／Enter 路径；最初“深色”布局直接设置 Palette 被绑定刷新覆盖，现经共享外观动作切换并断言实际主题；Android preview.65 图里两个按钮相邻无间距，preview.66 增加间距并强化原生断言后重跑全部三个布局。中间包未覆盖，最终交付仅使用下列版本。

## 本地包与边界

| 产物 | SHA-256 |
|---|---|
| Windows preview.65 Portable ZIP | `8d353268bdf55b9cbc937d946716889234ea5b6e7295bc1d612359ece1929090` |
| Windows preview.65 Setup | `868547a5fa0ea22d27d488f9b887e710d590a8042d34a10c8ec275d29f09f090` |
| Windows preview.65 nupkg | `17f23077e85cedbbe9d7c9d638e0ca58c9e451310d9b5633339bfa1c54a84fca` |
| Android preview.66 ARM64 APK | `0079b51b5a78bdf24b9bfd0c02a1166e447277529ee117248d95dbf938326a67` |

Windows UI SHA-256 `84e6d64e82e2522caed309da88b4c39e41f68a082a37ec7c4f3bd2f006d1a568` 与构建、测试副本、ZIP 和 nupkg 一致。262 个包文件无私有配置；原生串流组件沿用 preview.60。Android 签名仍为 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。

尚未验收 Windows 主按钮的完整真实下载→安装／安装取消、两端通过系统安装完成升级后的配置保留，以及 OPPO 对应流程。模拟器通过 ADB 覆盖安装保留配置，不等同于用户系统安装流程。串流、业务服务、画质和声音设置未修改；完整 UI 目标继续保留在 [规范](UI-FLOW-SPEC.md) 与 [TODO](../TODO.md)。
