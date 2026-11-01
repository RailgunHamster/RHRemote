# Android 连接诊断：取消、结果与传输对照

本地候选 preview.149（367）。诊断结果摘要放在按钮上方，完整记录通过“查看详情”展开；收起详情后回到摘要。诊断期间可取消，切到其他页面后仍有标明设备的任务条；切换设备不会把前一台任务显示成后一台正在诊断。

任务只持有应用 Context。开始时固定设备、配置和完整地址列表，包括原本没有备用地址的情况；完成不覆盖当前草稿、焦点或其他页面提示。旧按钮固定任务编号、设置快照与操作意图，不能影响后续任务。详情开关也固定设备、任务和展开状态，并随本次页面重建保留。

诊断与连接准备、恢复、串流互斥，直到请求和诊断通道清理完成。取消先立即设置标记，再由后台线程关闭 HTTP／HTTPS、TCP、UDP 请求；UI 不等待网络。清理失败保留归属并提供“重试清理”，只重试清理。更新安装同样等待诊断清理。

## 接口

Android `device.network.diagnose` 在既有参数之外可传 `expectedIntent`、`expectedOperationId`，实际按钮携带这两个值。`device.network.cancel {operationId, expectedAction?}` 使用 `diagnostics.cancelAction` 的 `cancel`／`cleanup`；同一任务刚进入清理时原取消仍有效。

`device.network.details.toggle {uuid, operationId, expectedExpanded?}` 只操作当前网络页对应的已完成／失败任务，不发起请求。`diagnosticDetailsExpanded` 返回本次页面展开状态。新增动作在共享目录标明 Android 专用，Windows 沿用 preview.148。

`diagnostics` 增加 `requestIntent`、`cancelRequested`、`canCancel`、`cancelAction`；阶段为 idle、running、cancelling、cleaning、cleanup-error、completed、error、cancelled。任务和结果不进入 WebDAV。

## 撤回的回退与平台证据

Windows/JVM 的正文取消测试中，JDK `HttpURLConnection.disconnect()` 等待 `MeteredStream` 读取锁；读取／取消线程栈已保留。临时改用应用层 OkHttp 5 虽使 JVM 测试通过，却在实际 Android 模拟器造成退步：三轮旧版冷检查为 13.7～17.8 ms，临时实现为 128.8～141.9 ms，热请求也较慢。该实现未交付，已撤回。

不可变 preview.148 APK 的原生 Android 测试证明，系统 HTTP 在正文读取中可以约 1.6～3.0 ms 取消，close／keep-alive 两种响应均在服务端确认 EOF。因此保留原有平台连接，只增加请求生命周期及取消检查。两个新增 JVM 正文取消测试移到真正的 Android 仪器测试；JVM 失败、原测试源及回退数据均归档。原有 WAN 超时和响应头等待取消检查保留，没有放宽时限。

最终 native07 包的三轮交替对照：旧版冷检查 11.3／13.3／13.8 ms，本次 13.8／11.2／12.2 ms；各轮热检查中位数约 2.8～3.2 ms。每个新进程含 1 次冷请求、8 次预热、40 次计时请求，并核对实际版本、PID 和 49 次服务端请求。恢复后的其他轮次也保留。这是本机模拟器 loopback 组件对照，不是实机或远程首帧保证。

## 验证与范围

证据目录：`D:\RHRemoteTools\ui-diagnostic149-review-20261031`。`latency-comparison-final` 是被撤回实现，`latency-comparison-restored` 和 `latency-comparison-final07` 是恢复后测量；`platform-http148.json` 属不可变旧 APK。

最终 `diagnostic-final03` 的实际 RhHome 控件通过 64 组：360／1008 dp、正常／两倍字体、明暗主题、触控／Enter，覆盖取消与立即重试、按住的旧取消按钮、新设置拒绝旧诊断意图及关于页取消。详情开关没有增加网络请求，共享配置未改，任务归属已释放；80 张图来自应用自身 `View.draw(Canvas)`，没有系统截图或系统输入注入。HTTP、固定证书 HTTPS 正文读取中的取消还各覆盖两种 Connection 头，均须在 2 秒内结束并确认服务端 EOF。

JVM UDP 夹具另修正 Windows 的独立 TCP／UDP 端口分配：两者各绑定自有临时端口，在票据阶段映射至 UDP；仍使用生产允许的 47995 票据，不修改生产校验、不放宽取消期限。初次夹具失败、错误票据尝试与最终通过结果分别保留。

最终编译、Release 包和保护文件核对以目录中的 `final-audit149.json` 为准。正常 Windows preview.101、模拟器正常 Android preview.129 均不升级，不操作 OPPO，不公开发布。本次没有新增 Windows 诊断功能。

最终检查：Android Release 527 项、Rust Core 156 项、UI 接口目录 1 项全部通过；Debug 针对快照和 UDP 的 11 项通过。原生还通过成功／身份失败两条实际 PcView 重建流程，各重建两次、各只有一次 serverinfo 请求。APK 为 ARM64、非调试、原签名，摘要 `3A9B0E418BACE5ED2A034396A6AF6738FE8C55DCB51D3037C85C0F3F5AF767EF`；正常配置和包的 8 项摘要均未改变。测试包 .uicheck148／149 已移除，模拟器全局字体仍为 1.0。对比图索引为证据目录中的 `gallery149.html`。

仍需 Android 实机、真实外网／物理断网、长期运行与其余诊断失败主题边界。loopback 通过不证明视频 UDP、首帧或真实服务器重复配对通过。
