# WebDAV 连接错误与响应丢失的界面回归

Android 本地候选 `0.2.11-preview.76` / versionCode 294，Windows 仍为 preview.74。证据目录：`D:\RHRemoteTools\ui-sync-response76-review-20261029`。OPPO 未操作，未发布。

## 实际问题和修改

preview.75 在实际 TCP 连接被重置后直接显示 `Connection reset`，并把底层英文与“远端写入结果未确认”拼在一起。`wire-before.log`、`wire-before76-light/uncertain.json` 及自身渲染图记录真实 PcView 的失败；本地回归五例中三例明确失败，分别是连接重置、拒绝连接、提前结束。

现在分别显示连接中断、连接失败、地址不可达或连接提前结束的中文说明，不回显异常内附带的端点。原有证书／TLS、超时、DNS、HTTP 认证、并发锁和本机存储提示保留；锁释放失败不被底层通用 socket 错误覆盖。只改变提示转换，没有改变超时、传输、重试、写锁或合并规则。

## 通过的本地验证

- 完整 Android 单元回归 393 项通过，包含五个错误转换与诊断优先级用例。
- `RhSyncWireDeviceTest` 在现有专用 emulator-5580 运行三种尺寸 × 浅深主题，共六个真实 PcView 场景全部通过。客户端通过正式 HTTP 实现，测试端是应用进程内的内存 HTTP 端点，仅使用固定的虚构测试账户。
- 初次同步完成后，测试端接受第二次 PUT 的完整内容、更新自身文档与 ETag，再通过真实 TCP RST 丢弃这一响应。客户端显示明确的待确认提示，旧完成时间保留；独立 RhSync 实例从文件读到 pendingTransfer。自动请求返回 sync_interrupted，不产生第三次 PUT。
- 实际 Activity 重建后提示仍在；点击实际“立即同步”按钮，第三次 PUT 完成后清除待确认和自动暂停。六例均核对控件完整可见并导出自身 View.draw 图，没有系统截图、系统输入或实际结束进程。
- 每例先隔离既有同步备份，结束后恢复共享文件、原加密账户、全量偏好和备份。最终共享文件 SHA-256 为 `1be1929793f0ef8e4387292269d0c16a583fcd9f4fdb12b5501086873d59bf9a`，账户为 `44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a`，均与开始时一致。恢复 1512×1008、density 240、font_scale 1.0。

本地 TCP 端点不支持 DAV class 2，不能证明 winserver 的真实锁／释放行为、服务器实际提交后的故障恢复、跨端同步或进程终止。实际自动间隔计时也未在这六例中运行。它是独立的界面回归，不替代下述真实服务验收。

## 真实服务测试

当时已编译 `RhSyncResponseDeviceTest`：计划在 winserver 现有 DAV 下新建唯一目录，通过仅监听应用本地的转发器核对真实服务器已保存 PUT 后再丢弃响应，核对锁、待确认、重建和手动恢复。首次准备命令被自动审批审查以“blocked by policy”拒绝，未给出详细原因；该次没有执行。

用户随后明确允许按 `real-service-review-plan.json` 测试。preview.87 在同一隔离范围完成真实服务提交后的 TCP 响应丢失、待确认、Activity 重建和原生按钮手动恢复；3 次 PUT／LOCK／UNLOCK，正常文档与本机配置未变，测试目录在独占锁下清理并显式释放锁。完整证据、早期夹具失败及剩余边界见[既有服务复验](REAL-WEBDAV-RESPONSE-20261029.md)。完整 UI 目标保持未完成。

## 本地交付

`dist/android/RHRemote-v0.2.11-preview.76.apk` 是 ARM64 Release，versionCode 294，SHA-256 为 `88bc84d9a29432fc6ed3a3f4e61073764ba44e96f0a5d6a35f9956092d48eab7`。签名验证通过，保持原有 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。模拟器实际操作使用同版 x86_64 Debug，不算 ARM64 实机安装验证。

`verification76.json` 记录当时的单元／原生结果、恢复核对及产物哈希。断流前后图和完成页在各 `wire-after76-*` 目录中；后续既有服务的结果见 `real-service-approved06`。未发布，也未改变 Windows 正在使用的客户端或 Sunshine 服务。
