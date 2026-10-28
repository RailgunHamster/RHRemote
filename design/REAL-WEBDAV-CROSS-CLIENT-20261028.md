# 现有 WebDAV 服务与真实跨客户端同步（preview.57）

Windows 与 Android 已在现有 winserver WebDAV 上完成一次真实双端同步。实际服务会忽略普通 HTTP 条件写入头，原先的并发保护不可靠；两端现使用它支持的独占锁保护读取、合并及写入。本轮完成 HTTP 跨端验收，完整 UI 目标及剩余范围仍见 TODO。

## 账户与测试范围

用户原地址为 `http://winserver.taile71e4.ts.net:5244/dav`。读取已有 Windows 元数据与 Credential Manager 凭据，在内存中用于请求和隔离测试账户，没有要求用户重新提供密码。测试前分别读取原地址及 `http://192.168.0.104:5244/dav`，正常同步文件哈希一致。

实际双端传输使用同一服务的局域网地址下新建目录 `RHRemote-UI-cross-56a249bb4fd1`，自动同步关闭。Windows 使用三台日常设备的隔离配置副本，在实体 DISPLAY2 上运行不激活的 preview.57；Android 使用 headless `emulator-5580` 的 preview.57。两端使用临时独立客户端 ID，并复制各自已有参数。

Android 通过实际 View 按钮和共享动作执行，Windows 通过自身 Slint 控件 focus／Enter 回调和共享动作执行。没有替换 HTTP、ETag、合并或文件应用实现，没有系统截图、系统输入、computer use、串流或音频。OPPO、HTTPS 实际窗口及 Tailscale 串流不计入此轮。Sunshine、RHVideo、行情采集与显示拓扑未调整。

## 真实问题与修复

- 服务对错误 `If-Match` 和重复创建的 `If-None-Match: *` 均返回 201 并覆盖探针文件；此结论来自实际请求，不据响应头推断产品或版本。OPTIONS 声明 DAV class 2；独占锁有效，缺少／错误凭据写入被 423／412 拒绝，正确凭据写入及 UNLOCK 成功。
- 现在两端首次同步探测 OPTIONS，并在该账户实例内缓存能力。class 2 执行 LOCK → GET → 合并／PUT → UNLOCK，PUT 同时保留原 HTTP 条件头及加入 WebDAV `If` 条件。冲突预览前释放锁；选择后重新取锁、读取并核对原冲突快照。
- 非锁服务保留原条件写入路径；可选探测返回 403／404／405／501 兼容回退，其他传输及认证错误仍报错。禁止重定向，保留证书和主机名验证。
- 服务 Lock-Token 为尖括号包裹的数值。preview.56 将内部内容当绝对 URI 解析而失败；preview.57 改为限制长度、ASCII 和头语法的不透明 token，原样回送，不用于导航或进入公开状态／日志。参见 [RFC 4918 §6.5](https://www.rfc-editor.org/rfc/rfc4918.html#section-6.5) 与 [§10.4](https://www.rfc-editor.org/rfc/rfc4918.html#section-10.4)。这不表示数值形态满足 RFC 对生成 token 的全部要求。
- 新 URL 被 LOCK 创建为空文件时允许首次账户继续写入；已有同步基准的空内容仍报格式错误，已有有效内容不跳过。
- 423 显示另一台设备正在同步。客户端申请 60 秒锁，UNLOCK 限时 3 秒；释放失败不宣告完成。PUT 已确认时保留待应用／结果未确认并暂停自动同步，手动重试重新读取。实际锁期限由服务端响应决定，不承诺覆盖断电每个断点。
- Android LOCK／UNLOCK 使用项目已有 OkHttp，独立于串流主机证书策略，采用系统 TLS、禁止重定向和隐式连接重试；其余请求仍为原实现。Windows 使用原 ureq Agent。

## 已通过的真实流程

| 流程 | 核对结果 |
| --- | --- |
| 创建与往返 | Windows 创建；Android 拉取并发布自身配置；Windows 拉取 |
| 独立参数 | Windows 保持 1920×1080／音量 25，Android 保持 2520×1680／音量 65；互相参数作为复制来源保留 |
| 并发别名 | 同一基准改成不同别名，Windows 先发布；Android 显示本机／远端预览，实际按钮选择远端；Windows 拉取后别名一致，原主机名和 UUID 不变 |
| 预览无写入 | 冲突预览前后直接 GET 文件的哈希相同 |
| 顺序 | Android 调整并发布，Windows 拉取后的 UUID 顺序逐项一致 |
| 配置复制预览 | Android 查看 Windows 参数后取消，本机分辨率及音量不变 |
| 自身设备 | 同步后保留；专项复验连接按钮无可用入口，原始 API 明确拒绝 local device，未进入连接／串流 |
| 控件动作 | Windows 9 次业务按钮每次只派发一次预期动作，清理另有 2 次按钮；Android View.performClick 前检查完整可见范围 |

证据：`D:\RHRemoteTools\ui-real-sync-20261028`。

- `cross-client-result.json`、`native-cross.log`：最终通过，Android JUnit `OK (1 test)`；53.82 秒是整条测试耗时，不是连接或同步时延。
- `windows-*-phases.json`、`conflict-review-proof.json`、`android-native/result.json`：阶段、预览不写入、参数与恢复断言。
- `android-native/initial-synchronized.png`、`conflict-review.png`、`conflict-resolved.png`、`foreign-profile-preview.png` 和 `windows-sync-completed.png`：实际页面自身绘制图。已查看冲突、复制预览和完成页面。
- `windows-self-device.json`、`windows-self-device.png`：实际本机卡片和原始错误，窗口仍在 DISPLAY2、不激活。首个专项脚本未接收预期的 HTTP 错误而结束；修正脚本后通过，未改产品代码。

## 回归、产物与清理

Windows 当前 UI **159** 项通过（新增 7 项实际 TCP 协议回归）；Android 当前 nonRootDebug **361** 项通过（新增 6 项 Robolectric＋实际 TCP／OkHttp 回归）。覆盖数值 token、能力缓存、class 1、空创建、忙碌、PUT 失败、冲突释放及快照变化、无效头、UNLOCK 失败和冷实例未确认提示。原核心代码未变，上一轮 **123** 项通过。原 HTTPS 证书及条件写入回归保留在当前 Windows 测试中，不替代 class 2 HTTPS 真实服务。

日志为 `windows57-all-ui-tests.log`、`android57-full-build01.log`；当前 Android XML 归档为 `android57-unit-results.zip`。旧 release 测试不混入当前 361 项计数。

| 本地产物（未发布） | SHA-256 |
| --- | --- |
| Windows Portable `.57` | `f410ae94756e8ec1a1fa36f21e7dd29cbac9e78525d5651d8eb929bb43651b3b` |
| Windows Setup `.57` | `56bdea5bdced611a6bc185778168129e548c937e34ebe15b8577d20cc84293f6` |
| Android ARM64 `.57`／281 | `0156036eb3edc91706d4249e69c9c2af302e8605b0b801e3280672c1ce4471e9` |

Windows ZIP 的 UI 与实际测试程序及当前 Release 一致，原生引擎保持 `.55` 的 `6711cac6f5e5d4dc711b2655b72a227ef612f676c249c5e24b27cedb65f9fa01`。271 项／262 文件中无偏好、WebDAV 私有元数据、令牌、私钥或日志。Android APK 版本、ARM64 ABI、签名及原签名身份已核对。详见 `package57-verification.json`。

Windows 隔离账户经实际移除／确认按钮删除，Credential Manager 查询确认临时凭据不存在；Android 原账户文件、共享配置、全局及同步任务偏好、原备份目录恢复，测试加密账户删除。Windows 正常偏好 SHA 仍为 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`；Android 共享文件仍为 `fe08b5761c960bdd24fb980a58aac5ab4c58d8698bae1a3d2536270ae273a0d8`，布局保持 1512×1008／density 240／font 1.0。

客户端均结束后核对唯一测试目录内容，重新取锁及核对哈希，删除测试文件，再确认空目录并删除。正常文件始终只读，最终通过原 Tailscale 地址读取，SHA 仍为 `7c649603e2c57a7736169533f51d803f2d631108082be29cb13a0365f3424917`。见 `windows-cleanup.json`、`selfcheck-cleanup02.json`、`android-native/result.json`、`dav-cleanup.json`。

前期失败及恢复证据保留在 `ui-real-sync-20261028-attempt01`、`-attempt02`：第一次 Android 测试寻找“保存”而实际资料按钮为“完成”；第二次是数值 token 兼容问题。初次私有输入传递的 shell 引号失败也保留在第一份记录中。没有将这些失败计入最终通过数。

剩余实际 HTTPS 窗口、网络／文件错误、传输或应用时结束进程、自动同步实际计时、OPPO 及完整 UIA／硬件键盘验收继续保留。
