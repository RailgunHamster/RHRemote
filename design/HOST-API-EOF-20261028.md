# 屏幕接口 EOF 与视频表面隔离验证

本轮继续使用应用接口、Instrumentation 和应用自身 SurfaceView；没有 computer use、系统输入或系统截图。Android 产品保持本地 preview.37，Windows 保持 preview.47；OPPO 未操作，未公开发布。

## 实服务响应截断

使用 emulator-5580 的既有配对证书，通过正式 RhHost.request 访问 winserver。健康接口确认统一服务 PID 43628、Sunshine PID 27488；服务文件 SHA-256 为 `C64828237B0C3BA3A0FF92DEEC2CADABB27C79C2DDA09AC45C8EE2DD70E99443`，与已验证安装包一致。

异常栈位于 Android Http1xStream.FixedLengthSource.read：收到 Content-Length 后读取正文提前结束，而不是连接超时。增加原始 SSLSocket 与 HttpsURLConnection 两条对照，均使用原客户端身份和相同主机证书固定校验，不导出私钥，不降低信任要求。

`host-display-rpc05/host-display-probe.json` 的六组请求中，原始 TLS 三次声明约 1.4 KB 正文但实际收到 0 字节；HttpURLConnection 四次同样失败。随后正式自动选屏 POST 也返回 EOF。此前另有完整成功样本，所以不能将其表述为接口一直不可用，或只归因于 Android HTTP 库。测试默认仅查询；显式 prepareAuto 只在已有可捕获物理屏时，以该屏当前尺寸请求 auto，不新建、缩放或切换屏幕。

一次探针因测试代码将 HTTP 状态行的 null 键直接转换为 JSONObject 而失败，已修正；该次不是产品缺陷。最终探针逐项检查所有原始／HTTP 响应长度，避免只依据最后一次请求成功判为通过。

## 服务端关闭流程修复

正式响应发送函数独立到 http_response.rs。旧发送逻辑的真实 TLS 回归在 200 UTF-8 多记录正文和 409 错误正文两项均失败：客户端检测到缺少 TLS close_notify 的非正常结束。现在合并响应头和有界正文发送，排入 close_notify 后 flush。服务端 23 项测试通过；此前失败日志保留。最终源码重新构建的 Release 运行时 SHA-256 为 `6372CC4B6AAD050CC94AA2C6C6A09075DA38D853E5EF525ACB733FB93074A556`；最早一次构建的 C9879480… 未用于部署。

这证明正常 TLS 结束通知的缺失已修正，**不单凭该测试认定实服务正文截断的全部根因已经解决**。没有放宽证书／身份规则、增加自动重试或加长超时。

随后通过已授权的远程 Windows 服务管理接口更新 winserver，仅替换 Rust 运行时；确认最后会话已断开，旧程序备份到受保护的 ProgramData，失败恢复方案已准备。未改 Sunshine 引擎及服务注册。统一服务变为 PID 60240，原设备 UUID、授权文件和 sunshine.conf 哈希均保留，RHVideo 前后 Running。SSH 连续在认证阶段被重置，没有修改 SSH、系统信任或防火墙配置。

`runtime-update-result01.json` 和 `host-display-rpc06/host-display-probe.json` 证明部署完成：同一组六次原始 TLS 与六次 Android HTTP 正文长度全部匹配，正式健康／屏幕读取／自动选屏也成功，共十五次请求通过。后继真实启动确认 `requested=true, beforeCapture=true`，选择 LG FULL HD／DISPLAY1。当前对照未再出现原有 EOF；不能以有限请求样本宣称今后所有网络故障都已消除。

## 生成画面隔离验证

RhVideoSurfaceDeviceTest 不使用远程桌面内容：在应用自己的 SurfaceView 绘制灰色基线，使用 c2.android.avc.encoder 编码三帧生成灰色 YUV，再由 c2.android.avc.decoder 分别解码到 Image 和新 SurfaceView。

真实原生测试通过：Canvas 与视频各 23814 个取样点全部非黑；三帧缓冲解码的 2760 个亮度样本均值 Y=200，表面收到三次呈现回调。没有重启模拟器、注入输入、播放声音或改变产品解码器选择。应用偏好前后相同。

这排除了“当前无窗口模拟器完全不能显示任何解码视频”的假设，但不代表此前两条真实远程会话已经通过。原非黑画面断言和失败样本继续保留。

## 黑屏已定位到实际大帧接收／组帧

修复 EOF 后的原 25 Mbps 两次真实串流仍失败。对应主机记录均完整：第三轮主机输出 316 个编码包，客户端只组装前十四帧；这些帧的 source 均为 0，是首个实际捕获尚未完成时的占位输入。主机第十五帧 source=1、编码约 108562 字节，随后真实画面均未在客户端完成组装。Native 日志明确报告不足以 FEC 恢复的缺包，反复请求 IDR；没有依据将其归为解码器慢或没有启动捕获。

第四轮另行将自己的解码输出临时指向 ImageReader，五秒未取到新图像，随后恢复原 Surface；该探针无亮度结果，不能当作“解码内容为黑”的证据。它属于独立诊断，不计作未经干预的完整串流验收。

只读接收参数显示请求缓冲约 2.1 MB、实际 524288 字节；Android guest 的 UDP InErrors／RcvbufErrors 前后为 0。主机发送返回不等于客户端收到，guest 计数也不能排除更早链路的丢失。当前尚未确定局域网、Windows 接收与 QEMU 转发中的具体丢点。

保持 1920×1080、30 帧、H.264，只将隔离模拟器本次参数改为 6 Mbps：第五轮取到实际桌面，20223 个取样点中 5792 个非黑，688 ms 内第五次取样通过；原生会话流程整体通过。按主机／客户端帧号对应，65 个组装帧中 49 个来自实际捕获源。不是只有占位帧或测试生成画面。该段主机记录只有五次新捕获，其余包含重复输入，不能将组装数量换算成独特动态画面 FPS。没有修改产品默认码率或用户画质，测试结束恢复原配置；该短测不代替持续动态 30 FPS 或更高码率验收。

## 浅深会话 UI 与主题重建

6 Mbps 浅色及深色原生完整流程均通过：三组面板、屏幕列表与选中项、静音音量、统计、模式／移动画面／缩放 API、快捷键菜单主题、非法动作反馈、先关菜单再收起面板、退出确认的继续连接、主动断开与回到设备页。未发送真实快捷键、切屏、移屏或播放声音；Back 仍是应用回调范围。

深色最早两次整条运行未通过“回到设备页”断言。`session-failure-home-instances.json` 证明真实 RESUMED 页面已经是 devices，而测试仍持有旧 connection 页对象。测试改为跟随当前活动 RhHome 后，第八轮完整通过；此处是测试引用缺陷，不计作产品导航缺陷。最终导出的逐帧元数据同时带本次 profile manifest 及其哈希，避免丢失实际码率信息。

## 证据

目录：`D:\RHRemoteTools\ui-session-api-review-20261028`。

- host-probe-native01～05.log、host-display-rpc01～05：正式接口、原始长度及间歇失败。
- host-response-before.log／host-response-after.log：旧关闭流程两项失败，新实现 23 项通过。
- host-response-release-build.log：本地 Release 构建。
- video-surface-native01.log、video-surface-generated01：生成内容的真实编解码、两张自身 Surface 图与亮度记录。
- host-response-release-final.log、runtime-update-plan01.json／result01.json、host-probe-native06.log：最终构建、部署、恢复边界和十五次正式请求通过。
- native-real-session03／04：修复接口后原 25 Mbps 的两次黑屏；对应 host-frames-*.jsonl、session04-native-packets.log 和 guest-udp-*.log 用于确定丢点。
- native-real-session05／08：6 Mbps 浅色、深色两条完整原生流程通过；自身 UI 与视频图分开保存。
- native-real-session06／07：主题重建后的测试旧引用失败，保留实际页面对照。所有 JUnit 失败仍按失败运行记录，不把子断言或最终成功覆盖为历史通过。

原 25 Mbps 的大帧缺失、真实输入路由、实际会话控制、长期画面及其他 UI 流程仍未完成。OPPO、Windows 新桌面预览和公开发布均未操作，完整 UI 目标保持未完成。
