# Android 后台发现与配对状态来源

2026-10-31，本地候选 **0.2.11-preview.154 / 372**。设备工作区继续沿用已选 HelpWire 方向；本轮修正列表和详情的状态事实，不新增连接步骤。参照结构仍为[设备列表](https://kb.helpwire.app/client-management/client-list/)与[连接选项](https://kb.helpwire.app/operator-application/connection-options/)，固定证书、HTTP／HTTPS 和 Sunshine 配对的规则是 RHRemote 自己的协议要求。

## 复现与结果

此前主动检查会区分固定证书 HTTPS 与 HTTP；后台发现仍使用旧的 getComputerDetails。TLS 证书变化后，旧路径允许回退到 HTTP 获取设备资料，随后将 HTTP PairStatus=1 与本机旧证书组合成“已验证配对”。这是状态误报，不能将 HTTP 回退取得资料当成认证成功。

`discovery-red03/observed-pin-change.json` 使用模拟器真实 ComputerManagerService.tryPollIp 和自有回环对端复现：`displayedPaired:true`、`rawPairingStatus:verified`，实际仅 1 次 HTTP 请求，TLS 握手被旧证书拒绝、没有成功的 HTTPS 请求。原失败和首次测试辅助类访问修正的编译失败均保留。

现在后台发现单独调用 getRhDiscoveryDetails，固定证书严格验证。证书变化或 HTTPS 401 仍可回退读取 HTTP 设备资料，以便显示目标并重新配对，但 UI 分别显示“证书不符”或“认证失败”，不会显示已配对。HTTP 的配对标志不能认证本机；正常固定证书 HTTPS 才能显示“已配对”。不会为检测另开串流或媒体中继。

每份运行时观察记录取得资料的 source／identitySource、HTTPS 验证事实、authenticationSource 和回退原因。HTTP 回退的 actualUuid 来自 HTTP，不伪称它经 HTTPS 认证；401 只有严格验证已成功的 HTTPS 才能标记为认证拒绝。仍由原轮询比较预期 UUID，其他设备的结果被拒绝。

观察绑定发起时的地址、线路策略、中继配置和固定证书，配置变化后的迟到结果显示需重新检查。数据随 ComputerDetails 的运行时副本传递，不进入 SQLite、WebDAV 或共享配置；新配对成功会丢弃旧发现失败，固定证书被替换时旧运行时观察也失效。

主动检查与后台发现共用进程内递增的 observationId，按结果生成顺序决定显示，避免后台派发和清理延迟改变先后。纳秒时间继续记录，但不用相同的时钟刻度判定顺序。旧主动检查仍在清理时产生新认证拒绝，以及旧后台结果晚于新主动检查到达，两种交错均有回归。显式身份不符／无效响应的阻止规则仍需主动检查解除，后台发现不悄悄清除它们。

## 证据与范围

目录：`D:\RHRemoteTools\ui-discovery154-review-20261031`。

- `release-build01.log`、`release-unit-final`：**555** 项 Release JVM 测试通过，0 失败／错误／跳过；包括配置与证书归属、后续认证拒绝、两种延迟交错、运行时复制与实际 SQLite 配对保存。
- `discovery-native02`：**8** 条真实 Android 后台轮询 HTTP／mTLS 路径：证书变化及变化后 401、两种 HTTPS 401、正常已配对／未配对、无本机证书 HTTP、错误 UUID。检查配置和固定证书保持，已知 HTTPS 端口条件下正常 HTTPS 为 1 次请求、HTTP 回退仅 1 次，没有新增验证请求。
- `discovery-view04`：将捕获的实际协议报告注入独立发现观察，浅深色、两档字号、360／1008 dp 下 **48** 项实际原生 View、按钮及布局检查。过期项只将隔离观察绑定到旧配置，未改磁盘或 WebDAV。图片来自 View.draw。
- `lifecycle-native02`：本轮包的 **24** 项完整 RhHome 检查等待、配置变更及宽设置页输入保留复验，32 张图。
- `active-protocol02`：主动检查的 **17** 条实际回环 HTTP／mTLS 及只读配对准备复验。
- ARM64、非 debuggable、原签名及两个原生库 ELF 核对；Release APK SHA-256：`35B24B417DEB0E9AF7384BB84FD74A3F081E38B6BFD33E02210D71E789784EDF`。

原中间失败没有覆盖：`focused-red-results02` 复现同一时钟刻度的交错仍误显示 paired；改为结果序号后，原场景和反向交错均通过。中间 `discovery-native01` 尚未包含最终结果序号，最终证据单独为 02，没有把旧包记录改标为新包。

`discovery-view02`／03 的过期场景误以为回环测试 UUID 已在共享列表中。回环服务使用动态端口，capture 按原规则没有写入该设备；因此改变地址的循环没有改变任何对象，两次仍显示已配对。失败日志及各五张图保留。04 明确创建绑定用的旧配置，并断言旧配置与当前 intent 不同，原过期状态断言继续保留。只重编译隔离测试 APK，Release 主 APK 没有为该测试错误改动。

本轮正常请求数的验证不等于 CPU、真实外网或首帧性能结论。实际协议使用独占回环对端和新生成的测试身份，没有向真实主机配对、串流或重启服务。

## 仍未完成

后台发现全部地址均失败时的 TLS／超时等细分仍需补齐；当前新结构只随成功取回的设备资料发布。真实多线路、系统 DNS、WebDAV 运行中改配置、OPPO、双端最终交付仍待验收。列表认证状态目前还沿用可达性颜色，认证失败与在线指示的颜色区分需要继续整理；主动检查失败与旧 PAIRED 标志冲突时的 paired 字段也需要独立补验，不能把这轮后台路径及主按钮正确当作所有字段和完整 UI 均已完成。

日常 Windows preview.101、正常模拟器 Android preview.129 与八份保护文件保持；Windows 本地候选仍为 preview.151。本轮没有公开发布或升级 OPPO，完整 UI 目标继续进行。
