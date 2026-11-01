# 自动码率第一阶段：配置与连接时预算

2026-10-31，本地候选 preview.156。自动码率整体仍未完成；此阶段没有实时带宽探测或会话内调码率，未升级日常客户端、未发布、未修改主机服务、未操作 OPPO。

## 已接入的行为

- Windows 与 Android 的每设备画面设置增加“自动码率（连接时）”，由既有 `settings.set` 接口操作。帮助文字明确本次连接后保持该起始码率。旧配置默认为手动，关闭自动时保留原手动值。
- 开启自动时保留已输入的合法手动值，并禁用手动码率控件。临时无效的手动输入在切回手动时恢复；自动保存使用最后一个合法手动值，不因已禁用字段而阻止保存。直接调用 `settings.input`、`settings.step` 或 `settings.set` 修改已禁用的手动码率同样被拒绝。
- 标志进入现有客户端 × 主机配置及配置同步、复制的类型。手动配置序列化时省略 `bitrateAuto:false`，保留旧手动格式；开启后保存 `true`。较旧客户端尚不认识新增字段，自动配置的跨端使用需要同时升级支持该字段的客户端，不能承诺旧客户端读取开启自动的新配置。
- 起始预算按画面像素数和帧率，以 1080p30 的 25 Mbps 比例向上取整，限制在 1–25 Mbps。已选中的局域网／公网或打洞直连／中继或未确认 Tailscale 路径分别使用 25／6／3 Mbps 的上限；外网同时遵守用户设置的 `remoteBitrateMbps`。它是保守估计，不是可用带宽测量，也不能保证慢于 3 Mbps 的网络可用。
- 已解析线路优先于主机名或环回代理地址。没有线路证据的未知主机名按外网预算，专用代理按中继预算。手动模式原有连接策略保持原行为。
- Windows 通过实际选路后的 `EffectivePolicy.bitrate_mbps` 进入原生连接参数；Android 通过 `RhProfile.applyNetwork` 写入 `PreferenceConfiguration.bitrate`，不是只改变设备卡片文字。

## 验证与范围

证据目录：`D:\RHRemoteTools\ui-bitrate156-review-20261031`。

- `windows-workspace03.log`：Release workspace 502 项通过，原有 5 项显式人工／日常配置测试保持忽略。新测试覆盖真实 Slint 按钮事件、滚动到控件、禁用状态、宽窄窗口／浅深主题、保存取消、无效输入恢复、持久化及设备隔离；没有物理窗口或系统输入验收。
- `windows-auto-overview01/`：对同一已通过测试可执行文件单独开启应用内快照，得到宽窄窗口与浅深主题四张概览图；抽看宽浅色、窄深色，码率显示“自动（连接时）”。这些是自身软件绘制快照，不是系统屏幕或串流画面。
- `android-release02.log` 与 `android-release-unit-final/`：ARM Release 构建及 62 组、561 项 JVM／Robolectric 测试通过。新测试覆盖实际原生 View 回调与编辑器禁用、跨分类、保存取消及设备隔离。主机 JVM 无法加载 Android 的原生库，码率参数测试复用现有仅屏蔽 MoonBridge 静态库加载的替身；没有把测试当作真实解码或网络串流通过。
- 两端共用 `client/shared/bitrate-cases.json` 的 18 项预算样本，覆盖局域网、主机名、Tailscale、打洞代理、中继、较小画面、外网上限及原手动值。Rust 校验实际网络策略，Java 校验进入连接偏好的 kbps 值，并验证未改写手动配置。
- 第一轮失败保留：Windows 测试未先滚动到屏外的新控件，改为通过实际 ScrollView 滚动后再点击；Android 主机 JVM 触发原生库加载，使用上述既有测试替身后通过。`windows-workspace01.log`、`windows-auto-debug02.log`、`android-unit-red01/` 未删除。
- Android 候选 `dist/android/RHRemote-v0.2.11-preview.156.apk`，versionCode 374，SHA-256 `6F8B3821FD3922029411A660327643CD82967ED843B8E4D3739736D58C6153E9`。仅生成本地包，未安装或上传。Windows 仅本次 Release 测试构建，未打包或升级日常便携版。
- `protected156.json`：沿用 155 的 8 个已授权只读哈希，日常客户端、原生引擎、偏好与同步本地文件、既有主机认证文件以及 preview.129 APK 均相同；没有读取配置内容。

## 尚未完成

运行中的实际反馈、服务端会话内编码器动态调节与回执、实际传输字节和卡顿对照、实体客户端验收均待完成。后续完成这些后，再推进单窗口捕获与双端选择入口，见 [总方案](AUTO-BITRATE-WINDOW-STREAMING.md)。本阶段通过项不构成整个自动码率或单窗口功能通过。
