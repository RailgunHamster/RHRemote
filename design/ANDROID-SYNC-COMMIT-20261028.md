# Android 同步完成顺序（preview.42）

原实现的顺序是：PUT 成功 → 保存新合并基准和成功时间 → 在主线程应用本机配置。后一步失败或进程在中间退出时，本机仍是旧值，基准却已经是远端新值。下一次三方合并会把本机旧值当成相对新基准的主动编辑，再写回远端。

使用正式 RhSync 合并、条件写入和文件存储代码，只将 HTTP 与解密替换为内存 stand-in，三项回归在 preview.41 上均失败：基准提前更新、本机应用失败仍推进成功时间，以及新实例重试时将“远端新名”改回“本机旧名”。没有启动网络监听或 WebDAV fixture。

## 修复

- PUT 之后仅在当前实例保存待应用结果和账户快照，不推进基准、成功时间。
- 工作线程依次应用配置、核对实际本机内容、持久保存主题、提交并回读同步完成记录。只有全部通过，才更新内存基准和成功时间。
- 结果被更改、账户被移除或本机内容不匹配时，旧结果不能提交。应用或完成记录失败时保留旧基准，显示具体收尾阶段失败，保留 pending 并暂停自动同步。
- 下一次手动同步读取新鲜远端和原合并基准，避免用本机旧数据覆盖已传输的新数据；不会在重开时自动重放旧结果或旧冲突选择。
- 本机应用与完成记录的文件 I/O 在工作线程执行；忙碌归属保持到主线程完成回调。同步页面的按钮区分“同步中”“正在应用”“正在保存”，状态文字说明本机应用和完成记录两个阶段，不增加用户确认步骤。

## 证据

目录：`D:\RHRemoteTools\ui-sync-commit-review-20261028`。

- `sync-commit-before02-unit.xml`：正式三项失败。第一次 harness 的内存连接继承了 HttpURLConnection 默认 GET 方法，计数不正确；已改成显式捕获请求类型，原始失败文件保留，不将该计数错误算成产品缺陷。
- `sync-commit-after02-unit.xml`：后续三个预期 IOException 被 Robolectric 的直接调用包装成 RuntimeException；调整测试桥接解包后通过，未为此修改产品异常处理。
- 最终 315 项单元测试、同步收尾类 9 项全部通过，包括两个冷实例重试边界、结果／账户变化、静默 AtomicFile 完成失败的回读检测、真实 worker 上的确认、主线程回调前仍保留忙碌，以及实际设置页在保存阶段禁用重复提交。
- `native42-files01.log` 为 JUnit `OK (1 test)`。无窗口 Android 模拟器上使用注入的待应用结果和独立测试元数据文件，实际执行 RhShared.apply、Android AtomicFile、未应用时拒绝确认、完成记录文件被非空目录占据时的真实写入失败，以及恢复文件后再次提交成功。没有执行 HTTP，也没有把该测试描述成真实服务器传输。
- `native42-files01/local-apply-result.json` 明确记录 `transferResultInjected=true`、`httpRequests=0`、原共享配置已恢复、真实 WebDAV 账户哈希不变。本机偏好和模拟器共享配置 SHA-256 前后一致；没有改显示布局、操作 OPPO、使用系统输入或系统截图。

## 范围与后续

上述修复覆盖 Android 的提交顺序和文件失败；内存 HTTP 对照不替代真实 WebDAV、真实网络中断和传输后实际结束进程的验收。原有 ETag、条件写入、证书／主机名检查及 URL 规则保留。

检查发现 Windows 仍有相同顺序：`client/crates/rhremote-ui/src/config_sync.rs` 在返回 shared 前推进 base／last_sync_ms，`client/crates/rhremote-core/src/global_settings.rs` 之后才 persist 本机偏好。该项已加入 P0 待修，本轮 Windows preview.47 未改，不能把 Android 修复视为两端通过。

本地 Android ARM64 Release 候选为 preview.42，versionCode 266，未公开发布。完整 UI 目标继续保留真实同步、Windows 对应修复和其余逐流程验收。
