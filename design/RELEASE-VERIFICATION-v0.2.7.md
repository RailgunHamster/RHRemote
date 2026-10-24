# 0.2.7 发布及更新验证

2026-10-24，Windows / Android 均为 0.2.7，Android versionCode 209。
发布源码与标签 `v0.2.7` 精确指向
`dc501fea776078d70caef9c485060239a4dd3844`。
在独立干净工作树构建，候选已复制到主工作区 `dist/release-candidates/`，保留完整发布计划。

## 公开发布

- GitHub release 422447546：<https://github.com/RailgunHamster/RHRemote/releases/tag/v0.2.7>。
- Gitee release 1235402：<https://gitee.com/railgunhamster/RHRemote/releases/tag/v0.2.7>。
- 每站 11 个固定版本资产和两个 latest feed 均通过无凭据下载；资产大小和 SHA-256
  与同一个不可变候选一致。源码、安装包、更新包均公开，旧资产保留。
- 本地结果为 `D:\RHRemoteTools\v027-{github,gitee}-downloads-20261024.json`。

## 构建与发布包

Windows UI 为 Release，默认使用 Slint 软件绘制；视频继续在独立原生进程中处理。
103 项 Rust 核心、传输、UI 回归通过。15 项发布前置回归通过。
正式 portable 的隔离启动确认编译和安装版本均为 0.2.7，主 UI 为 software。
原生代码本次未变，包内哈希保持 `C2A90D11...E31E6`。

Android 使用 Release，143 项 JVM 测试零失败，Release APK 和测试 APK 均构建通过。
AGP Release 测试任务需要显式启用各构建类型的单元测试；第一次未配置该开关的构建失败
已保留，修复构建参数后完整重建成功，没有将失败构建当作通过。
APK 未启用 debuggable，R8 及资源压缩启用，包名 `com.rhremote.client`，ABI 为 arm64-v8a。
签名证书 SHA-256 与旧 APK 一致：
`2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。
这里沿用已有应用的签名身份，不能将 Release 构建等同于改用了新的生产签名密钥。
检查实际压缩 DEX 中四个 Rust JNI 方法及原生库导出一致，未用压缩前源码替代检查。

| 文件 | SHA-256 |
|---|---|
| Windows UI executable | 99ba7a97c5ac20b0734c39a5f9de21d0a0da1f3a7f394f3d57ab21ba35c85aaa |
| Windows full nupkg | 3d3ecfb13078d453260c161c624311c7c6c3040a4650f89dbcd42a4a53fd02ab |
| Windows portable | 78f243845a22ff45f4794d116e12d911a72bc15e6da1b1bbfaa19a49b0f1237f |
| Windows Setup | 82138495a215f9e7ff1c4535efae5aa9e5dea2bf13615735fe44fd7a586ed07f |
| Android APK | 007dd4fe4fba1d96d8146adf5e0fa8aa3bf7177806ceead6e0b766cffaa37505 |

## 实际 Windows 自动更新

隔离 canary 从原始 0.2.3 portable、空数据目录启动，建立专用设备别名及 720p30 / 11 Mbps / 静音配置。
使用既有更新动作接口发现公开 0.2.7、真正下载 nupkg、校验其哈希、交由 Velopack 应用并重启。
约 2382 ms 后新进程提供控制接口；编译和安装版本均为 0.2.7，客户端身份、别名和配置保留。
此过程未配对远端或启动媒体，独立进程最后正常关闭。

本机普通测试客户端随后使用同一公开更新流程从 0.2.3 升级到 0.2.7，约 4593 ms 后重启可用。
完整 Saved 状态保持一致，原生配对 INI 与 transport.key 哈希不变，未覆盖用户连接配置。
更新前备份保存在私有工具目录，未提交含凭据的配置。
新进程编译和安装版本均为 0.2.7，默认 software；原生哈希仍为上述 C2A90D11。
这些时间从应用已下载更新的动作开始，到重启 API 可用为止，不含下载、不代表视频首帧。

证据：

- `D:\RHRemoteTools\windows-upgrade-canary-v027-20261024\result.json`
- `D:\RHRemoteTools\main-upgrade-v027-20261024\result.json`
- `D:\RHRemoteTools\v027-final-windows-smoke-20261024.json`
- `D:\RHRemoteTools\build-v027-android-release-enabled-tests-20261024.log`
- `D:\RHRemoteTools\v027-release-apk-static-verification-20261024.json`

## 验收边界

OPPO 未操作或覆盖安装；实际系统安装器、蜂窝网络、网络切换、Android 媒体和输入仍待验收。
软件 UI 的短轮次实际媒体和菜单回归已通过，长期呈现稳定性仍待验证。
本次客户端发布没有替换本机主机服务，也没有改动 winserver / claw 的既有统一服务、配置和配对。
持续动态 30 FPS、60 FPS、4K 和首帧各阶段优化继续按 TODO 独立跟进。
