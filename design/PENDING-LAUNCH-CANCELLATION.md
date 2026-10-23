# 待 RTSP 握手请求的归属取消（进行中）

客户端在已接受的 HTTP launch/resume 内退出，会留下 Sunshine 等待 RTSP 的槽位。
轻量归属进程测试已证明：原生正常结束约 163 ms，立即重试仍返回 409；等待到退出后
11 秒再试，约 1.63 秒收到实际画面。现有默认 ping_timeout 为 10 秒，实验仅给出
拒绝与恢复的观测边界，不将客户端自行等待当作已精确测量的服务端过期点。
详细证据及测试脚本限制见 [WINDOWS-PARENT-LIFETIME-20261024.md](WINDOWS-PARENT-LIFETIME-20261024.md)。

这与取消配对挑战不同，也不能调用当前全局 /cancel：后者会结束所有串流、应用并
恢复显示配置。目标是让退出、取消和选路回退只释放本次尚未移交的请求。

## 实现与兼容要求

- 新主机通过能力字段明确声明支持；旧主机继续按原协议连接，不能伪造取消成功。
- Windows / Android 每次实际 launch/resume 使用独立、不可预测的请求归属凭据，
  取消仍需已配对的 TLS，不能仅凭可同步的设备 UUID 或 client uniqueid 取消。
  复用配对授权、撤销及证书校验，不降低 TLS 验证。凭据不能进入普通请求日志或逐帧记录。
- 请求准备中取消、服务端已经接受但客户端尚未拿到响应、已进入 RTSP、重复取消、
  取消迟到和旧请求重试分别有明确状态；不能只覆盖成功收到 HTTP 响应后的退出。
- 只释放仍属于该请求的待握手槽位；被 RTSP 移交或后来替换的请求不能被迟到取消
  或旧超时回调移除。已开始的本次串流继续走现有原生正常关闭流程。
- 非法、过期或其他请求的凭据不能改变槽位、应用、其他串流或显示配置；内部暂存
  取消记录具有数量和时间上限，不产生无界缓存。
- Windows 的退出监听、按钮和取消接口共用同一请求生命周期；Android 的按钮、
  连接占用、HTTP/JNI 取消、恢复和逐路回退也必须共用，不留下不可立即重试的窗口。

## 服务端并发检查

修改前 rtsp.cpp 使用 launch_event 的 view/pop 和 raised_timer，HTTP 请求、RTSP
事件循环及超时回调会从不同上下文访问。添加条件取消前需要审查并统一同步边界：

- 比较归属和取出槽位必须是同一个原子操作，旧回调不能弹出新请求。
- Asio timer 的 expires/cancel 与回调串行化，不能无保护地从新增 HTTP 线程操作。
- 已取得 session 指针的 RTSP socket 仍可能继续 ANNOUNCE；取消与串流移交须有
  唯一赢家，不能出现取消后又创建旧串流，或锁住回调再递归调用清理造成死锁。
- 新的证书归属检查不能依赖可被其他 TLS 握手覆盖的全局 last_verified_client_cert。
  需要实际请求身份或等效的、经过认证的独立请求授权机制。

## 完成依据

加入状态与竞争回归，覆盖上述各阶段、非法身份、迟到回调、旧请求、新请求及旧主机。
通过完整 Windows MSYS2 构建、引擎回归与相应客户端测试后再部署。
保留原有配对、配置和显示布局；用同一轻量归属进程场景确认立即重试获得实际画面，
并经 Windows LAN / HTTPS 中继验证。Android 当前仅做自动化，OPPO 实机仍暂停，
实机未恢复前不得以 Windows 结果勾选 Android 验收。

## 原子槽位基础（构建验证中）

新增有数量和时间上限的请求登记表，并接入 RTSP 的发布、接入、ANNOUNCE、
控制接管清理、证书撤销和关闭路径。过期与取消在同一把锁内比较实际请求，
不再由跨线程操作的 Asio timer 弹出槽位。ANNOUNCE 分配串流前原子认领，
旧 socket 即使仍持有 shared_ptr，也不能重新启动已取消或过期的请求。

加入十组回归，包含各 200 次取消与发布／认领的真实线程竞争。旧客户端可以
继续无凭据连接。源码已提交，Linux 独立回归及 ThreadSanitizer 通过，完整 Windows
CI 40032402942 仍在构建；尚未部署这一阶段。

## HTTPS 接入（新增源码，待完整构建）

实际 TLS 握手的证书／名称与具体 HTTP Request 弱引用绑定，数量上限 256；
不再使用 last_verified 全局身份。launch/resume 和原有全局 cancel 移到单个工作线程，
最多排队两项，HTTPS 线程继续处理按次取消。关闭时先停止请求受理并排空工作线程，
再停止 HTTP，避免延迟 Response 析构访问已销毁的 Server。
主机状态和应用读取也进入同一队列，防止把 launch 移到工作线程后，与原 HTTP 线程
同时调用会修改进程状态的 proc.running。按次取消不读取进程状态，始终直接处理。

serverinfo 声明 `RHLaunchCancel=1`。新客户端在每次实际 launch/resume 发送
64 个小写十六进制字符 `rhremoteLaunchToken`（256 位随机值），仅通过已配对 TLS，
随后以同一请求凭据调用 HTTPS `/rhremote/launch/cancel`。
该参数在服务器普通查询日志中删去值。接口不注册到明文 HTTP，也不修改应用、
活动串流或显示配置；取消未知的有效凭据会建立有上限的取消记录，阻止随后迟到的准备。
响应 `RHLaunchState` 为 cancelled / handed_off / finished / expired；handed_off
表示 RTSP 已认领，客户端必须继续本次原生串流的正常关闭，不能改用全局 cancel。

准备和发布之间检查按次状态，发布前再次检查原配对授权；禁用／移除证书立即撤销其待启动请求。
排队满或关闭返回 503，非法或重复参数 400，其他证书的凭据 403，预先取消的启动请求 410。
新增四组 TLS 绑定／队列测试和两组真实证书／生产取消处理回归。

Windows / Android 请求生命周期已接入下述本地候选，实际新服务端 HTTPS 及立即重连
回归尚未完成。不能把以上源码或单独的注册表回归视为整个问题已解决。

服务端版本为 2ae02f8915cc75bb8fb2243c54902a138ae09beb，完整 CI 40034197133 已成功，
90 项引擎回归零失败（其中槽位 10 项、TLS／队列 4 项、证书授权 7 项）；独立回归与
ThreadSanitizer 通过。147 文件包验证后已部署 winserver / claw，配置、UUID、6 / 4 个
配对均保留，TLS 中继重连成功；本机待管理员升级。

## Windows 客户端接入

只从已固定证书验证的 HTTPS serverinfo 学习能力，作为临时设备属性传入实际连接，
不持久化到配置或 WebDAV。每次 launch/resume 使用系统随机源产生独立 256 位凭据，
建立独立 Qt 清理线程，持有原地址、端口和配对证书快照；正常旧主机不发送扩展。
退出监听、控制接口、窗口退出、阶段失败和正常停止均触发同一请求的幂等取消。
取消请求不随应用 aboutToQuit 中断，使用独立 NvHTTP 与三秒超时；退出前等待其结束，
失败记录为未确认、停止自动回退。普通 URL 日志和 Qt 网络错误隐藏请求凭据。

显示准备完成后不再重置已经发生的取消。阶段回调和协议开始前也检查取消状态，
只有新 Session 开始时才初始化本地停止标记。HTTPS 已配对连接在 encrypted 回调
检查实际叶证书，即使证书通过公共 CA 验证也不能替换原配对身份。

原生生命周期回归与完整 Windows 构建通过，最终 native SHA-256 为
`D8C9935D7D57579CC4F5442AE39032F0A4A363BB622AC75C6121FCEAFD6894F1`，
构建记录 `D:\RHRemoteTools\build-launch-client-stop-hooks-20261024.log`。
源准备重复运行的五个被适配文件哈希一致。

普通 Windows 客户端已使用该 native，Rust UI 保留原版本。对仍为 12e49a20、
不声明新能力的 winserver 再做轻量归属回归：准确落在 HTTP 内退出，约 103 ms
正常结束，立即重试仍 409，退出后 11 秒的第二次尝试收到实际画面。
这证明旧主机兼容及原问题仍存在，不能视为新的按次取消已经实机通过。
原 native 配置文件哈希不变，API 清理完成，未录音；测试文件为
`D:\RHRemoteTools\light-owner-reconnect-legacy-newnative-20261024.json`。

## Android 客户端接入

NvConnection 使用严格配对证书验证，只从该 HTTPS serverinfo 学习能力。
每个实际请求有独立 SecureRandom 凭据；取消早于请求登记也会触发独立 TLS 清理。
清理不使用已取消的 NvHTTP 及其 dispatcher，请求和调用均有三秒上限。
协议路径保留斜杠，不把 `/rhremote/launch/cancel` 编成一个带 `%2F` 的路径段。
停止等待同时覆盖 JNI 启动线程和本请求清理；未确认时保留占用，相同凭据在退避后
可继续确认，不拿客户端自行等待当作服务端释放的证明。恢复等待失败时休眠，避免忙循环。
请求 URL 和网络异常隐藏凭据，保留 TLS／证书错误的分类。旧主机不发送新凭据或接口。

完整 143 项 JVM 回归零失败，新增 7 项请求生命周期和 4 项真实双向 TLS 测试；
TLS 使用现场新生成的测试证书与实际 NvHTTP、NvConnection.stop，原生 ARM 方法除
静态加载／查询参数外不被伪装成已经执行。覆盖响应未知时停止、等待取消确认、
取消早于请求登记、TLS 拒绝后不能释放占用及同凭据重试、旧主机。
应用和测试 APK 构建通过；0.2.7 / code209 仍是未正式发布的候选，SHA-256 为
`D53C7BE0A887B19FB572399B93D27D40C8B71281AEE9DA19FFADC2F540E60770`。
完整日志为 `D:\RHRemoteTools\build-android-launch-cancellation-20261024.log`。
OPPO 未操作，Android 实际视频、网络切换与服务端联测仍未验收。

## 新服务端的实际原生退出回归

Windows 原生与新服务端已完成两台主机的同一 HTTP 内退出场景。测试用独立、低负载
归属进程触发真实退出监听，并保留普通 UI 和本地转发服务；不以脚本直接调用全局 cancel。
退出日志必须实际落在 http_launch / http_resume 内，且 protocol 尚未开始。
两台都通过配对 HTTPS 收到 `cancelled`，随后仅一次立即重试即记录实际 render_return。

| 指标 | winserver | claw |
|---|---:|---:|
| 原生正常退出 | 212.361 ms | 197.337 ms |
| 重试首次实际画面（从启动重试进程计） | 1828.331 ms | 754.782 ms |
| 从归属进程退出到重试收到画面 | 2105.021 ms | 984.080 ms |
| 原生结束记录 | 1 个完整 footer | 1 个完整 footer |

winserver 的重试进程在退出后约 276 ms 已开始，脚本没有等待 11 秒；原 native 配置哈希
保持，服务及配对不变。winserver 两个原有显示路径的大小、位置、刷新与旋转均核对未变。
这证明该槽位取消／原生退出路径已经修复，不是稳定首帧上限、帧率或全部界面取消的验收。

claw 首轮边界观测失败的原报告保留：实际已收到 458 次渲染，HTTP launch 在原生启动约
532 ms 时发生，观察器要求 800 ms 或长空闲，因此错过窗口，并非连接失败。
根据真实 HTTP 序列改为第三条 TLS 流触发，后验仍严格要求 HTTP 内退出、未启动协议，
才得到上表的有效结果，没有把先前不合格用例改写成通过。

文件为 `D:\RHRemoteTools\light-owner-cancel-fixed-lan-first-20261024.json`、
`light-owner-cancel-fixed-claw-thirdflow-20261024.json`；原始失败与裁定为
`light-owner-cancel-fixed-claw-first-20261024.json`、
`claw-first-pending-boundary-adjudication-20261024.json`。

## 尚需修复和验收

源检查发现 Windows Rust UI 的 stream.disconnect 在非 streaming 状态仍走 stop_owned，
会强制结束子进程；取消后的后台 LaunchOutcome 和窗口关闭也存在 OwnedChild::drop／
stop_owned 的强制结束路径。这会绕过已实现的原生清理。下一步应先请求控制接口，
在后台保持连接占用与 transport，等待真实清理，不阻塞 UI；必须覆盖尚未收到原生控制
端口、后台启动刚交接、API 取消、窗口关闭、失败清理，不能只修连接成功后的按钮。

此外 Network 当前在 UI 进程内持有 QUIC Client。上表保留普通 UI，只终止轻量归属进程；
不能据此推广到中继下真正的 UI 崩溃。需验证并解决 UI 退出后取消通道的存活，再做
HTTPS 中继的完整退出／立即重试。Android 当前只验收了 JVM 实际 TLS，未验收 OPPO。
正式更新仍为 Android 0.2.6、Windows 0.2.3，0.2.7 APK 暂未发布。
