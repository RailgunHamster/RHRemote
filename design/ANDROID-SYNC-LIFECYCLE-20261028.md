# Android 同步状态、冲突与网络失败

本地 Android preview.34，versionCode 258。Windows 保持 preview.44；未发布，未操作 OPPO、Windows 客户端窗口、Sunshine 或业务服务。完整 UI 目标仍未完成。

## 实际问题

原同步任务、`RhSync` 的冲突快照和 `syncBusy` 都属于当前 `RhHome`。真实 GET 尚未返回时重建 Activity，新页面显示空闲，原请求却继续；失败后重建会丢失错误，冲突后重建会丢失待处理项。三个问题均在原包上独立失败，不是通过状态注入推断。

应用自身截图还显示“立即同步”和冲突选项位于长表单末尾，首屏只有输入框。原测试用 `performClick` 能调用屏幕以外的按钮，不能证明用户能在当前视口看到并操作。此轮因此同时修改页面顺序和原生测试的可见范围断言。

## 最终行为

- `RhSyncWork` 在应用进程内持有单一同步任务、已保存账户模型、结果和待处理冲突。网络线程不捕获 Activity 或 View；完成后在主线程应用配置并通知当前页面。旧页面解除订阅不会解除新页面的订阅。
- 页面重建保持同一次操作及忙碌状态，不能启动第二次同步。错误与冲突保留至用户重试、修改／保存设置或明确选择；重建不重新请求、不自动选择冲突结果。自动同步的尝试时间也由任务持有，避免重建绕过失败后的间隔。
- 同步入口、最近同步时间及冲突操作移到表单上方。冲突展示设备／客户端名称和两端的值摘要，不把 `devices.<uuid>` 直接展示给用户；选择只解决冲突项，其余变更按原规则合并。同步期间冲突按钮禁用，API 继续拒绝重复操作。
- 保存／取消仍只处理设置草稿，移除设置仍需原有明确确认；账户、密码加密、强 ETag、条件写入、远端变化后重新检查和不跟随重定向的规则保留。
- 登录、TLS／证书、超时、域名解析、重定向和条件写入失败各有对应提示。本轮网络校验与条件写入失败保留设备配置及最近成功时间；TLS 校验失败时不向未验证的对端发送 HTTP 认证信息。

任务不承诺在进程被系统杀死后继续执行。导航 Bundle 仍只存原有九个导航字段，没有加入请求、密码、配置快照或网络异常。页面／进程与任务的职责参照 [Android Activity 生命周期](https://developer.android.com/guide/components/activities/activity-lifecycle)；TLS 与主机名校验参照 [Android 网络安全文档](https://developer.android.com/privacy-and-security/security-ssl)。

## 验证

证据目录：`D:\RHRemoteTools\ui-sync-lifecycle-review-20261028`。

- `native-busy-before.log`：真实 GET 被测试服务暂缓，重建后 `syncBusy` 错误地变为 false。`native-result-before.log`：HTTP 401 错误丢失，以及非空冲突数组变为空数组的两个明确失败。
- `landscape-initial/sync-conflict-review.png`：修复任务归属后、调整布局前的真实页面，冲突选项仍埋在表单下方；只证明该阶段的问题，不计为最终布局。
- 最终 278 项 Android 单元测试通过，无失败、无跳过。新增覆盖单一任务、旧订阅解除、无页面时完成、提交快照、错误重试、执行器拒绝、可读冲突摘要及分类错误。结果见 `unit-summary-final.json` 和 `android34-delivery-unit-build.log`。
- 最终横屏 1512×1008、竖屏 1008×1512、540×1200／字体 1.3 各通过四项实际 `PcView` 原生流程，共 12 项、12 次真实 Activity 重建、42 张应用自身绘制 PNG 和 12 份逐流程 JSON。记录为 `native-final-*.log`、`android-landscape`、`android-portrait` 和 `android-compact-largefont`。
- 原生流程使用实际按钮与共享分发器。点击前核对按钮完整可见；忙碌重建后只有一次 GET 和一次 PUT，第二次同步被拒绝。401 重建后保留错误及同一已保存账户，手动重试成功。冲突重建后保留同一待处理集合，明确采用远端后才条件写入。
- HTTPS 使用模拟器内真实 `SSLServerSocket`。先以正确主机名连接未信任证书，拒绝；再在该独立 instrumentation 进程里仅信任本次临时证书，以不匹配的主机名连接，仍拒绝；两次均无 HTTP 请求或认证头到达服务端。正确证书与主机名组合完成 GET／PUT。随后验证带 `If-Match` 的 PUT 返回 412 时本机、远端与最近成功时间不变；带可达 `Location` 的 302 只产生一次 GET，没有跟随到目标或再次 PUT。
- 测试证书和密钥只在内存中创建，测试后恢复原 SSL factory；没有安装系统 CA、关闭主机名校验或修改 Release 的信任设置。该证据不是公网 CA 服务或用户真实 WebDAV 账户的验收。
- 模拟器配置恢复为原 1512×1008、字体 1.0；每项恢复原账户文件、共享配置及主题，并等待客户端完成回调后再恢复。所有截图来自自己的 decor View，没有 computer use、系统输入、控制端口转发或读取控制令牌。

## 尚未覆盖

Windows 的实际 HTTPS 网络与对应冲突展示、真实 WebDAV 服务的跨客户端同步及账户权限边界、进程被杀后的用户反馈、文件应用失败和更复杂冲突差异仍需独立验收。现有模拟器测试不替代 OPPO、真实远程会话、正式升级或整个 UI 目标。产物身份、签名和完整范围在 `verification-preview34.json` 中记录。

本地 ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.34.apk`，SHA-256 `90C89DA262A6FD3F9C020F918BF566FFC5C0805399B81761ED0A7CB92391F409`。包名 `com.rhremote.client`，versionCode 258；原签名 SHA-256 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc` 已核对，无 Debuggable 标记。模拟器验证使用 x86_64 Debug 包，ARM64 实机未运行。
