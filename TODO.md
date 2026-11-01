# RHRemote TODO

更新时间：2026-11-02。仅列未完成工作；已通过项目、旧版本发布记录和测试流水已移出本清单，证据保留在 design/ 文档及 Git 历史中。

当前按用户要求先完成 Ubuntu 客户端／服务端与悬浮键盘交付，其他 UI 整理暂停。Ubuntu 当前范围为 24.04 amd64 / X11，使用说明及边界见 [Linux](docs/LINUX.md)。

以 1080p30 为验收基线，优先 winserver 和 OPPO 外网使用；保留各设备独立参数与降低分辨率的选项。

## P0 双端 UI 完整验收

- [ ] 会话内左右键实时切换：Windows、Android 控制页增加开关，按远程设备 UUID 保存，切换时释放旧映射下按住的键，验证重连与同步后沿用。
- [ ] 完整悬浮键盘：将现有少量快捷键扩展为完整 PC 键盘，增加位置固定的悬浮显隐按钮，并由控制页选项决定该按钮是否显示；核对组合键、窄屏布局及输入法安全区域。
- [ ] Android 输入法浮标的 OPPO 验收：窗口调整方式及 IME 安全区域已修正并通过模拟回归，仍需实机核对输入法动画、横竖屏及浮标可点击。[本轮范围](design/ANDROID-TOUCH-IME-20261102.md)
- [ ] Linux/X11 输入观察：定位压力测试中单次“向上”按下事件漏记的来源；后续内核与窗口同步采样 100 次按键均完整，尚未复现原样本。[范围与记录](design/UBUNTU-AND-FLOATING-KEYBOARD-20261102.md)

- [ ] Android 页面响应：继续定位首次读取、其他写事务／主线程排队、Game 键盘映射 Binder 调用和原 172 输入分发 ANR，补齐冷缓存／真实硬件解码、持续串流和 OPPO 的 UI 响应。已有身份查询的无关锁等待与快照重复读的已验证范围见[181 记录](design/ANDROID-CONFIG-READ-20261101.md)；该轮预览存在未确定的整页灰色／after 帧差异，补充祖先视图、窗口焦点、绘制状态与布局边界，不能宣称完整像素通过。面板初始化、反馈及仍有 9.6 秒慢首帧的范围见[178 记录](design/ANDROID-SESSION-PANEL-REAL-20261101.md)。复现历史检查页可见性失败并确定根因，现有边界见[169 记录](design/ANDROID-VISIBILITY-OBSERVER-20261031.md)和[162 记录](design/ANDROID-HIDDEN-DIAGNOSTIC-BANNER-20261031.md)。

- [ ] 持续自动码率：继续完成实际链路反馈、会话内编码器调节及实际流量对照。双端每设备配置和连接时预算已随 0.2.11 发布，连接时上限仍不等于持续自适应。[第一阶段](design/AUTO-BITRATE-STARTING-RATE-20261031.md)
- [ ] 指定窗口剩余部署与实机验收：0.2.11 已发布双端入口、WGC 窗口源、输出比例及输入映射的预览实现；还需升级实际被控服务端，核对真实窗口画面、实体输入、关闭／最小化／跨屏 DPI、独立弹窗和同内容带宽。当前更换目标需断开后重新选择，会话内切换仍未实现。[实现与验证边界](design/WINDOW-STREAMING-AND-CLICK-SCROLL.md)
- [ ] Android 点击与滚动的 OPPO 验收：单指滚动倍率、启动死区及抖动处理已修正，待恢复 OPPO 后核对实机点击、横纵滚动手感、双指拖动及缩放。当前没有操作 OPPO。[本轮范围](design/ANDROID-TOUCH-IME-20261102.md)

- [ ] 连接与配对剩余等待：定位新实服务样本的 serverinfo／显示查询正文截断，补齐配对后台准备在实体窗口、真实慢线路和中继清理交接中的验收，以及其余连接／保存操作队列边界、冷缓存／较大设备列表的正常启动验收。Android PIN 阶段和重复操作反馈、真实取消及失败页重试的已验证范围见[173 记录](design/ANDROID-PAIRING-FEEDBACK-20261031.md)；Windows 可取消准备、阶段与控件范围见[配对准备](design/WINDOWS-PAIR-PREPARATION-20261031.md)，窗口分离与其余启动边界见[启动记录](design/WINDOWS-STARTUP-CHECKS-20261030.md)。
- [ ] 未知状态一致性：完成 Android 主动检查的真实多线路／打洞／中继、系统 DNS 取消及退出等待上限、真实 WebDAV 在检查期间改配置的联动验收；继续验证系统服务绑定／mDNS、真实系统切网和 VPN 底层变化时的观察失效，以及其余服务响应错误码分类；较新认证解除旧检查与离线操作的隔离验证范围见[168 记录](design/ANDROID-AUTHENTICATION-REFRESH-20261031.md)。生产定时线程到页面、默认网络受控回调和动作接口的一致性已通过隔离验证，范围见[167 记录](design/ANDROID-NETWORK-OBSERVATIONS-20261031.md)。后台全部地址失败的运行时原因、配对事实保留及无效响应崩溃修复已通过隔离轮询与原生 View 验证，范围见[165 记录](design/ANDROID-DISCOVERY-FAILURES-20261031.md)，不替代真实网络和 OPPO。已通过的认证反馈与旧标志处理见[155 记录](design/DEVICE-STATUS-FEEDBACK-20261031.md)，HTTP／固定证书 HTTPS 来源、回退分类与结果交错见[154 记录](design/ANDROID-DISCOVERY-PROVENANCE-20261031.md)，探测退出、过期提示、宽设置页及单线路耗时对照见[153 记录](design/ANDROID-INSPECTION-LIFETIME-20261031.md)。完成 Windows 实际跨端同步与原生检查联动、真实主机和跨网络的联合验收与耗时对照，补齐后台全部地址失败及其余失败原因；本次网络结果与既有身份错误的分离、按钮和连接接口一致性已通过本地对照，范围见[166 记录](design/WINDOWS-TRUST-REFRESH-20261031.md)。已通过的 13 项隔离原生 HTTP／mTLS 协议对照与 HTTP 401 修复见[151 记录](design/WINDOWS-NATIVE-PROTOCOL-20261031.md)，不替代真实主机验收。Android 入口及 binder 锁见[146 记录](design/ANDROID-DEVICE-CHECK-FLOW-20261031.md)，控件触摸／回车与排队归属见[147 记录](design/ANDROID-DEVICE-ACTION-INTENT-20261031.md)；物理输入及其余动态页面仍保留。Windows 同步／添加和改线的待确认状态见[导入记录](design/WINDOWS-IMPORTED-DEVICE-STATE-20261031.md)，启动路径见[记录](design/WINDOWS-STARTUP-CHECKS-20261030.md)。

历史通过范围与验证边界统一见[交互规范](design/UI-FLOW-SPEC.md)。软件后端、进程内重建和应用内按键不能替代实体窗口、系统杀进程、物理输入法、物理键盘与 OPPO 验收。

Windows preview.134 已在 claw 完成浅深两主题 119 项实际会话控件检查，覆盖分类、快捷菜单、悬浮键显隐、最小化恢复、受控中断后的替换连接与取消；这些范围不再列作未执行，详见[会话实测](design/WINDOWS-CLAW-CURRENT-SESSION-20261030.md)。当前 preview.135 的确认框可用状态及输入恢复见[修正与范围](design/WINDOWS-MODAL-ACCESSIBILITY-20261030.md)。物理输入／断网、完整按键回执、活动 UIA 焦点、跨屏与最终交付继续保留。

- [ ] 悬浮键盘剩余实机验收：补齐 Windows 跨屏 DPI、控制接口整体故障后的持续会话、当前版完整按键、普通启动与交付验证，以及 Android 实机横竖屏。首次从未确认首帧的旧失败仍继续调查。[后台最小化错误的修正、撤回方案及当前范围](design/WINDOWS-PASSIVE-MINIMIZE-20261029.md)；[早期原生栈及失败对照](design/WINDOWS-RESTORE-FOCUS-20261029.md)；[失败提示与重试范围](design/WINDOWS-KEYBOARD-FEEDBACK-20261029.md)；基本恢复及探针坐标差异见[记录](design/WINDOWS-RESTORE-20261029.md)；[声音回执与当前范围](design/WINDOWS-AUDIO-ACK-20261029.md)、[首帧回退修复](design/WINDOWS-FIRST-FRAME-STATE-20261029.md)、[最新采集](design/WINDOWS-NATIVE-STATE-20261029.md)、[原生控制与当前失败](design/WINDOWS-NATIVE-CONTROL-20261029.md)、[Windows 实测](design/WINDOWS-FLOATING-KEYBOARD-20261029.md)、[初次实现](design/FLOATING-KEYBOARD-20261029.md)、[Android 模拟器实际竖屏与会话内转向](design/ANDROID-SESSION-ROTATION-20261029.md)

当前先整理两端完整界面效果与可见预览，再继续下列业务流程。最新卡片、分类栏、浅深色与会话面板见 [界面记录](design/UI-VISUAL-20261028.md)，设备列表与连接设置就地切换见 [导航调整](design/DEVICE-SETTINGS-NAVIGATION-20261028.md)，其余页面返回及原生控件复验见 [统一返回](design/UI-NAVIGATION-20261028.md)；连接设置留白及列对齐见 [修正记录](design/SETTINGS-ALIGNMENT-20261028.md)；Android 控件的当前验证范围见[动态系统栏](design/ANDROID-SESSION-INSETS-20261030.md)、[大字号控制](design/ANDROID-LARGE-FONT-CONTROLS-20261030.md)、[设备工作区字号与宽窄转换](design/ANDROID-DEVICE-FONT-LAYOUT-20261030.md)；布局图不计为真实连接、更新或同步通过。

已定位的一条启动冲突链及当前候选验证边界见[启动清理记录](design/STARTUP-CLAIMED-CLEANUP-20261030.md)。自然 RTSP 少一个字节的最初原因仍待查，不能把后继 409 的解释当成全部连接问题已解决。

Android 当前候选 preview.158 已在 claw 通过两种主题／字号的完整实际会话，包含分类、十五步控件、系统 IME、六组边距及主动退出，受控故障恢复观察为 2.78 秒，范围和清理见[当前 claw 实测](design/ANDROID-CLAW-CURRENT-SESSION-20261031.md)；正常模拟器仍为 129，历史范围见[129 记录](design/ANDROID-CLAW-CURRENT-SESSION-20261030.md)。winserver 出帧后原生 `-1` 中断及恢复后的窗口归属仍需定位，保留[原失败](design/ANDROID-IME-CONFIRMATION-20261030.md)。其现有连接归属仍未确认，不加入或结束该会话。claw 已按授权完成引擎更新；本机 10:38 的管理员进程因新配对使旧预检失效而退出，刷新为保留 2 个客户端后，10:45 的新进程又因已有串流、90 秒内未取得连续空闲而退出，服务未停止，无待确认 UAC，真实更新和重复配对仍待完成，见[部署记录](design/SERVER-ENGINE-PAIRING-UPDATE-20261031.md)。连接可靠性、实际打洞／中继、持续解码负载及实机验收继续保留；此前长时间 UI 迟到见[原始记录](design/ANDROID-SESSION-INTEGRATION-20261030.md)。

- [ ] Android 原 25 Mbps 的大帧缺失：继续定位局域网、Windows 接收与模拟器转发中的具体丢点，补齐持续动态画面验收；临时 6 Mbps 的两种主题 UI 短测不替代默认画质或长期验收。[接口、帧号与原生复验](design/HOST-API-EOF-20261028.md)
- [ ] 连接流程：补齐本机、claw 和 Android 实机的配对与持久化边界及首帧前其余真实错误路径，定位模拟器自然查找失败及 RTSP 加密响应长度不足、409／初始 PING 超时的来源；受控错误注入不能代替真实身份／硬件失败验收。preview.84 已取得 SETUP 响应少一个字节的新样本；preview.85 的错误上下文、原生失败页及实际 loopback HTTP 拒绝见[记录](design/ANDROID-CONNECTION-ERRORS-20261029.md)，未认定真实连接问题解决。[旧回调与诊断](design/ANDROID-STARTUP-FAILURE-20261029.md)、[逐项规范](design/UI-FLOW-SPEC.md)、[添加准备与重试](design/ANDROID-ADD-READINESS-20261028.md)、[Windows 保存边界](design/WINDOWS-PAIRING-PERSISTENCE-20261028.md)、[视频等待取消与控件](design/WINDOWS-CONNECTION-CONTROLS-20261028.md)、[Windows 密钥与真实 PIN 错误复验](design/WINDOWS-PAIRING-KEY-20261028.md)
- [ ] 更新流程：补齐公开 GitHub／Gitee 更新源中的实际新版升级、Windows Setup 安装失败及取消边界，以及 Android 用户系统安装后的配置保留。两端单按钮、操作与包身份、原生 Android 页脚已完成本地验证，见[当前验证范围](design/UPDATE-ACTION-INTENT-20261031.md)。Windows 便携版实际更新与当前交付范围见[记录](design/WINDOWS-UPDATE-UPGRADE-20261029.md)。[其余取消与传输边界](design/UPDATE-CANCELLATION-20261028.md)、[逐项规范](design/UI-FLOW-SPEC.md)、[草稿与安装交接](design/UPDATE-DRAFT-HANDOFF-20261028.md)、[Android 页面生命周期](design/ANDROID-UPDATE-LIFECYCLE-20261027.md)
- [ ] 同步流程：完成其余网络／文件失败边界，补齐 Windows 正常运行构建中的对应中断、真实原生数据库／发现与完整配置恢复的组合、其余调度边界及 OPPO 验收。Android 完整快照后的缓存过滤、新发现和重新添加已通过本地及回环控件流程，公共同步仍为缺项合并，未新增远端删除，见[171 范围](design/ANDROID-DEVICE-MEMBERSHIP-20261031.md)。Windows 正式 ConfigSync／Controller 已在测试可执行文件中通过既有 DAV 服务的五个提交／本机保存边界进程终止、冷启动控件及手动恢复，设备恢复使用替身，没有物理窗口，见[Windows 进程记录与范围](design/WINDOWS-SYNC-PROCESS-EXIT-20261030.md)。Android 已用既有 DAV 服务通过五个提交／本机保存边界的真实进程退出、冷启动和手动恢复，自己的遗留锁自然到期后可恢复，见[进程退出记录](design/SYNC-COMMIT-PROCESS-EXIT-20261030.md)。Windows 实际 HTTPS、错误保留、四档间隔持久化及完整五分钟自动同步已通过，见[记录](design/WINDOWS-SYNC-HTTPS-TIMER-20261029.md)。Android 既有 WebDAV 服务提交后 TCP 响应丢失、待确认、页面重建及手动恢复已通过，见[真实服务记录](design/REAL-WEBDAV-RESPONSE-20261029.md)；物理断网仍保留在全局交互验收。Android 两轮完整 5 分钟计时及两端主操作层级见[记录](design/SYNC-ACTIONS-TIMER-20261029.md)。[待确认写入与历史终止验证边界](design/SYNC-UNCONFIRMED-WRITE-20261028.md)、[连接错误与本地断流回归](design/ANDROID-SYNC-ERRORS-20261029.md)、[HTTP 真实跨端证据](design/REAL-WEBDAV-CROSS-CLIENT-20261028.md)、[Android 提交顺序](design/ANDROID-SYNC-COMMIT-20261028.md)、[Windows 提交与控件](design/WINDOWS-SYNC-COMMIT-20261028.md)
- [ ] 会话控制：定位 Android 快捷键已发送但远端偶发缺少 Ctrl+A 回执的样本；补齐双端其余快捷键、声音实际播放、Windows 缩放，以及 Android 实机横竖屏、触控／滚动模式的中键及四向滚动。模拟器实际竖屏、会话内横转竖及中键／四向滚轮的远端回执见[记录](design/ANDROID-SESSION-ROTATION-20261029.md)。[Android 当前实测范围与未通过样本](design/ANDROID-SESSION-CONTROLS-20261028.md)、[会话键盘开关复验与边界](design/ANDROID-SESSION-IME-20261029.md)、[Windows 按键与移屏范围](design/WINDOWS-SESSION-INPUT-20261028.md)
- [ ] 全局交互：补齐 Windows 活动会话工具栏、菜单与实体窗口中的其余控件和键盘路径及 Android 实机输入法的地址键盘／中文候选和真实外接键盘路由，完成 Windows 活动会话与 Android 准备及诊断完成结果的其余主题边界；双端物理断网和外网恢复仍待验收；普通工作区的 100 组正反向 Tab／完整可见与日常配置副本导航范围见[170 记录](design/WINDOWS-WORKSPACE-KEYBOARD-20261031.md)；定位 Windows UIA 焦点，验证主界面长时遮挡和不同 DPI 双屏切换后的重绘。[已合并的原生事件与当前配对重试范围](design/WINDOWS-WINDOW-EVENTS-20261029.md)、[Windows 恢复与剩余边界](design/WINDOWS-RECOVERY-UI-20261028.md)、[恢复提示与取消](design/ANDROID-RECOVERY-UI-20261028.md)、[本地按键与旧回调](design/ANDROID-SESSION-KEYBOARD-20261028.md)、[会话焦点与音量输入](design/ANDROID-SESSION-NAVIGATION-20261028.md)、[主题与错误证据](design/UI-ERRORS-20261028.md)、[诊断任务与重建](design/ANDROID-DIAGNOSTIC-LIFECYCLE-20261028.md)、[诊断取消、详情及原生平台对照](design/ANDROID-DIAGNOSTIC-CANCELLATION-20261031.md)、[Windows 诊断队列与取消边界](design/WINDOWS-DIAGNOSTIC-TASK-20261031.md)、[Tab 验收](design/WINDOWS-TAB-20261027.md)、[Windows 当前预览](design/WINDOWS-PREVIEW48-MAIN-20261028.md)、[会话复验](design/WINDOWS-SESSION-UI-20261027.md)、[上下文与复制预览](design/UI-CONTEXT-20261027.md)、[补画修复](design/WINDOWS-REPAINT-20261027.md)
- [ ] 最终交付：完成日常配置副本的当前双端预览、两端正式安装 / 升级的剩余配置保留及完整流程证据。0.2.11 源码和三端安装包已发布 GitHub／Gitee，附件及更新源均已匿名校验；日常 Windows 仍为 preview.101，本轮没有安装客户端或替换现有服务。整体 UI 最终验收仍未完成。[版本说明](design/RELEASE-NOTES-v0.2.11.md)

## P0 连接与外网

- [ ] 首次连接 TLS 可靠性：定位 `applist` 握手约 7 秒超时的双端等待；为初始只读请求增加可取消、有总预算的暂时失败重试，保留证书／身份失败停止规则。继续查模拟器 SETUP expected=148／received=147、155／154，以及打洞／中继各约 13 秒、一轮路由 26.6 秒的具体阶段。178 复验仍有 LAN 地址检查正文 EOF 和 SETUP 155／154，按次取消已确认但缺字节来源未定，成功深色会话仍有 9629 ms 首帧状态观测，见[最新证据](design/ANDROID-SESSION-PANEL-REAL-20261101.md)；原失败保留在[早期样本](design/ANDROID-SESSION-CURRENT-CONTROLS-20261030.md)与[既有记录](design/RELEASE-VERIFICATION-v0.2.9.md)。
- [ ] 缩短断网后的画面中断：分别统计最后一帧、故障发现、网络恢复和后继首帧；补充原生 `-1` 终止的具体原因，核实 ENet 等检测等待后再调整策略。
- [ ] 连接优先级完整验收：按每台设备配置的顺序，验证局域网、公网直连、内置打洞、Tailscale、自建中继的逐路回退，以及用户取消、失败路径清理和立即重试。
- [ ] 独立外网完整验收：跨运营商、两端关闭 Tailscale，验证直连和强制中继、持续动态画面、输入、声音及网络变化恢复；核对本机服务版本并补齐所需更新。
- [ ] tc 中继容量与排队：定位主机发送返回之后的链路等待、组帧长尾和丢包；完成较长时间及跨运营商对照，明确可用码率范围。[记录](design/RELAY-THROUGHPUT-20261023.md)
- [ ] 仅允许 TCP443 的网络：核对共存证书影响、真实定时器运行、生产部署与回滚，完成受限网络实测，并保持既有思源业务正常。[方案](design/RELAY-443-PLAN.md)

## P0 会话与长期稳定性

- [ ] 重复配对剩余部署与验收：先核对本机当前串流已结束，再完成原授权引擎更新；此前管理员请求均已退出，不能视为仍在等待，不重复弹 UAC 或中断未知会话。复验本机和 claw 更新后的同一身份重复配对，以及 Android 实机、跨网络的取消／持久化边界，保留禁用身份、唯一身份与证书校验规则。[部署当前状态](design/SERVER-ENGINE-PAIRING-UPDATE-20261031.md)、[UI 验收](design/UI-FLOW-SPEC.md)
- [ ] 配对取消的剩余验收：完成 Android 实机及本机服务更新后的取消、立即重试和配对持久化验证。[记录](design/PAIRING-CANCELLATION.md)
- [ ] 首帧前取消的剩余验收：补齐本机服务、Android 实机和跨网络场景，确认按次取消会释放待 RTSP 槽位，立即重连没有残留 409。[记录](design/PENDING-LAUNCH-CANCELLATION.md)
- [ ] 会话结束可靠性：继续定位 OPPO 中继结束后 Sunshine 10 秒清理超时／重启、Windows 初始 PING 超时，以及 Windows 偶发 15 秒原生清理超时；补齐失败时的身份核对、断开确认和退出分段证据。preview.90 的连接队列修复及仍有 577 次渲染而 UI 首帧超时的新样本见[当前定位](design/WINDOWS-NATIVE-CONTROL-20261029.md)；preview.89 连续 54 次状态请求失败见[旧失败](design/WINDOWS-FLOATING-KEYBOARD-20261029.md)。[旧实测与统计边界](design/WINDOWS-SESSION-INPUT-20261028.md)
- [ ] Windows UI 长期运行：完成软件绘制界面长时间运行、多轮连接和退出的稳定性验证，继续跟踪旧呈现崩溃的根因。[运行记录](design/CLIENT-TRACE-LIFECYCLE-20261024.md)、[本轮边界](design/UI-CONTEXT-20261027.md)

## P1 启动与慢帧优化

- [ ] 显示准备中的 D3D 开销：定位设备创建／释放波动及交互式 Rust worker 生命周期，验证受桌面和显卡健康约束的设备复用。[记录](design/NATIVE-VIRTUAL-MODE.md)
- [ ] 虚拟屏模式变更的剩余慢路径：继续分析新建屏、特殊布局及兼容回退中的显示准备等待，保留模式事务的失败和恢复语义。
- [ ] 模式变化后的能力缓存：区分显示模式变化、DXGI factory 失效和真实设备变化，减少不必要的全量重探，同时保留新鲜捕获及设备健康检查。
- [ ] winserver QSV／MF 剩余等待：定位能力探测、编码器初始化、QSV Sync、MFT 事件及 GPU 资源／分页依赖的长尾，完成有效动态源下的 1080p30 对照。[记录](design/ENCODER-HARDWARE-WAITS-20261025.md)
- [ ] DXGI 采集断档：继续对应 Map 内部等待、源呈现空档和同次 CPU／GPU 资源依赖，区分采集、编码和网络造成的停顿。[记录](design/CAPTURE-WORK-20261024.md)
- [ ] Windows 打洞转中继的通道复用：减少可用通道的重复建立，保留配置顺序、UUID／证书校验、取消归属及完整端口释放。

## 实机验收与发布

- [ ] 持续动态画面：完成 OPPO 的局域网／外网长测，优先验收 winserver 的 1080p30；补齐本机、claw 的对应连接与性能边界，核对动态源有效性及真实新画面数量。
- [ ] Android 剩余交互：验收缩放、快捷键、物理屏／虚拟屏切换、启动前选屏和旧服务端回退；补齐外网冷启动的列表状态、配对持久化，以及本机／claw 对应场景。[记录](design/INITIAL-DISPLAY-LAUNCH.md)
- [ ] 统计与呈现验收：完成 Windows 直接收包／组帧计时的长期和跨网络验证、Android 动态长测的逐帧完整性及物理显示呈现核对；分端报告 API 响应、首帧提交、实际呈现和持续帧率。[计时](design/CLIENT-FRAME-CLOCK-20261024.md)、[帧统计](design/HOST-FRAME-TRACE-PLAN-20261024.md)
- [ ] 已发布组件的后续部署与长期验证：0.2.11 已完成客户端／原生组件打包、GitHub／Gitee 源码同步及更新下载校验；仍需完成必要的实际安装、服务部署、Windows 首帧预算及新组件长期回归。[记录](design/FIRST-FRAME-TIMEOUT-20261024.md)

## 优化与验收约束

- 客户端操作通过共享动作和实际控件测试接口，布局使用应用自身截图；不使用 computer use。Windows 预览默认使用实体副屏并保持不激活；用户明确允许当轮使用主屏时，可显式指定已枚举的物理主屏并记录授权，不自动回退或改用虚拟屏。
- 保留目前已验证的软件编码默认；优化采用相同有效输入和可比负载的前后对照。收益不明确，或关键指标退步时，不替换默认实现。
- 对照首帧、帧间隔 P99、端到端延迟、丢帧和 CPU／GPU／网络开销；平均帧率及静态画面不代替持续动态画面验收。
- 保留用户画质、声音、配对及同步配置；网络适配只改变本次连接的有效参数，测试结束还原临时参数和显示布局，业务服务保持运行。
- 路径判断依据实际连接证据；Tailscale 的 home DERP 字段不等于当前正在中继。失败样本及仍用于调查的统计证据保留，已用完的原始统计按既有规则清理。
