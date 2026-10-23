# Android 串流恢复与窗口交接

## 已确认的缺口

0.2.5 的 RhRecovery 在调用 `startActivity` 前释放全局恢复占用。Activity 启动和控制器接管之间
存在一个时间窗，此时其他设备的连接可能取得同一个 Rust 代理。恢复选路还沿用不带取消范围的
RhNetwork 入口，用户取消不能直接通知当前 JNI 或 HTTP 请求。
另外，打洞实际得到中继、代理 UUID 检查失败或启动部分失败时，旧代理没有保证在尝试下一路径前关闭。

复查初次连接还发现另一处间隙：Game 获准进入 onCreate 后、控制器接管前取消时，
RhAdmission 可能在该 Game 的实际清理之前释放占用。单纯拒绝尚未创建的迟到 Intent 不足以覆盖此状态。

## 行为修复

- RhRecovery 使用按次编号和主机 UUID 标记后继 Intent，只允许一个 Game 领取。
  占用保持到真实控制器挂接，再与 stream 目标原子交接。取消、超时、重复或旧编号不能启动后继窗口。
- 初次连接同样保留已领取 Game 的清理占用。已经进入初始化的 Game 必须由它自己的 Stopper
  回报 native 与 HTTP 清理完成，才能关闭代理并允许另一连接；超时只报告错误，不当作已经清理。
- 后继窗口在接管前退出时，将清理确认交回原持有者，不能创建一个新恢复任务覆盖原占用。
  控制器发现连接已取消时正常结束窗口，不抛出未处理异常。
- 原 Game 的重复清理回调不能解除后继 Game 的占用。交接完成后移除对旧 Game / Home 的引用。
  旧窗口正常进入后台不会取消已经交出的后继会话。
- 初次连接和恢复共用 RhCancellation 的请求范围，恢复选路的 JNI 按次取消和 HTTP 断开均挂在同一范围内。
  UI 取消不等待 RhTunnel 的启动监视器；占用仍等工作线程真实退出和代理清理结束。
- 每条独立传输路径失败后在 finally 中关闭临时代理。关闭失败立即停止选路，不假定旧通道已经消失。
  被接受的实际路径保持运行。窗口接管失败且旧窗口已经退出时，用 Toast 显示原因。

## 验证与边界

2026-10-24 完整 128 项 JVM 测试零失败，ARM64 应用 APK / 测试 APK 构建通过；9 项发布前置测试通过。
在原 102 项测试上新增 26 项：

| 测试 | 新增数量 | 实际覆盖 |
| --- | ---: | --- |
| RhRecoveryOwnershipTest | 12 | 控制器接管之前保持占用、迟到和重复 Intent、后继实际 Game 拒绝生命周期、清理超时、重复回调、窗口启动失败、重试清理与旧 Activity 引用释放 |
| RhAdmissionGameHandoffTest | 4 | 初次 Game 已获准启动后的取消、后台退出、真实停止确认、控制器交接与旧 Intent 拒绝 |
| RhRouteCleanupTest | 6 | 打洞落到中继、UUID 不符、部分启动失败、关闭失败、取消和后续直连／中继之间的清理顺序 |
| RhRecoveryTransportTest | 4 | 默认恢复解析器、真实本地 HTTP 请求／正文、JNI 取消编号、取消期间仍持有占用、UUID 不符与成功路径的交接 |

传输测试仅将 ARM JNI 入口替换为测试夹具；HTTP 使用真实本地 ServerSocket，
复用真实恢复解析器、请求取消和代理状态。它们不证明 Android 真机硬件解码、实际中继吞吐量或蜂窝网络视频已经通过。
OPPO 仍按用户要求暂停自动操作，外网媒体、网络切换及长时间实际会话继续保留在 TODO。

构建日志：`D:\RHRemoteTools\build-android-recovery-ownership-tests-20261024.log`。
本次不改 Sunshine 主机、Tailscale 配置或用户码率及声音设置。

## 发布记录

0.2.6 / code208 的精确源码为 `5e902c9e1ee922a86a07b113790c94f84c8e275f`，标签 `v0.2.6`。
APK 为 26,422,935 字节，SHA-256 为
`f69cbc77f4bde580a6b065885aefc3f24989812d963fa671fcccddc79dcb4cfd`，签名与旧稳定版相同。
GitHub 正式 release 422114480 已发布，八资产和两个 latest feed 的无凭据下载及哈希校验全部通过。
Windows 0.2.3 的 feed 与 RELEASES 原样保留。

Gitee 发布尚未执行：用于读取其 DPAPI 令牌的 winserver 在 SSH 服务接受后、用户认证时重置连接。
通过 Tailscale 与局域网均重现，claw 转接也未成功；未降低主机密钥检查，未重启 sshd 或远程服务。
同机 Sunshine serverinfo HTTP 返回 200、UUID 与已知 winserver 一致，因此不把 SSH 故障写成远程串流服务离线。
Gitee 的源码与更新源仍保留先前状态，正式 APK 最新版本仍为 0.2.5。后续重试同一候选和同一精确提交，
不能覆盖已经发布的 GitHub 资产。

- 不可变候选：`D:\git\RHRemote\dist\release-candidates\0.2.6-42e0cdc5c3584f7d92edc7094d222c15`
- 构建与测试记录：`D:\RHRemoteTools\android-recovery-ownership-verified-20261024.json`
- 发布／匿名校验记录：`D:\RHRemoteTools\android-stable-v026-verification-20261024.json`
- SSH 诊断：`D:\RHRemoteTools\winserver-ssh-release-20261024.log`
