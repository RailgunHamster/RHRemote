# 同步页主操作与实际计时

本地候选 `0.2.11-preview.77`，Android versionCode 295，未发布。Windows 保持 Rust / Slint，Android 保持原生 View / Java。证据位于 `D:\RHRemoteTools\ui-sync-primary77-review-20261029`；修改前的实际计时位于 `D:\RHRemoteTools\ui-sync-timer76-review-20261029`。

## 操作层级

两端原先在编辑同步配置时，同时突出“立即同步”和“保存”。现在有未保存更改时，保存是主操作，立即同步使用普通按钮样式；保存或取消后，立即同步恢复主操作样式，保存与取消按原规则禁用。原有未保存提醒、共享动作 API、保存失败与同步事务没有改变。

Android 在原有 View 上更新按钮样式，只在主次状态变化时重新设置背景，不重建输入框、不改变焦点。Windows 通过现有 sync-dirty 属性绑定。

Windows 的 `render_visual_catalog` 新增 `sync-only` 导出选项。它只创建测试后端中的生产 Slint 组件，使用显式假数据，导出已保存／有修改、浅深色、宽／窄／大窗口共 24 张图；不创建 OS 窗口、不发起同步。原有完整目录导出保留。实际文件包括 `windows-sync-visuals/form-sync-wide-light.png` 和 `form-sync-draft-wide-light.png`。

Android 的 `interval-draft.png` 来自真实 PcView 的 View.draw，可同时看到顶部普通“立即同步”和底部主操作“保存”；未使用系统截图、系统输入或 computer use。

## 自动同步计时

原生回归使用模拟器 `emulator-5580` 内的独占 localhost HTTP 测试端点，使用虚构账户。先通过实际按钮完成一次手动同步，再经原生 Spinner 选择并保存 5、15、30、60、5 分钟，核对新建同步对象读取的持久值，最后通过原生复选框启用自动同步并保存。

等待过程不调用 sync.run、不修改同步时间戳或系统时钟。实际经过 30 秒后操作返回设备／设置／配置同步，90 秒后重建 Activity，继续等待同一调度器到期。自动传输暂时停在测试端点的 GET 响应前，以核对输入控件、保存／取消和同步按钮禁用、重复动作拒绝，以及传输中的 Activity 重建。随后释放响应，核对完成时间更新和当前页面恢复，关闭自动同步，再观察一个 35 秒检查窗口。

preview.76 的实际起点到自动启动为 **315,711 ms**，单调时钟经过 **315,407 ms**。本轮 Android 实现首次检查在 Activity 创建 15 秒后，之后每 30 秒检查一次，故配置 5 分钟并不保证恰好在第 300 秒启动。测试共发生一次手动和一次自动同步，没有重复请求；共享配置、加密账户文件、偏好和原备份均恢复。

preview.77 的相同完整计时复验通过：起点到自动启动为 **315,700 ms**，单调时钟经过 **315,358 ms**，完整场景用时 356.653 秒。两版各有一次手动同步和一次由调度器自动发起的同步，没有修改时间戳；页面离开／返回、到期前和传输中的 Activity 重建、忙碌禁用与重复拒绝均通过。新版另完成深色本地 TCP 响应丢失、待确认状态、页面重建及手动重试，文件／偏好与原备份均恢复。

## 回归与边界

- Windows Core 125 项、UI 181 项通过，另有一项既有 UIA 用例忽略；Android 393 项通过。
- 原生计时仅实际等待了 5 分钟；15／30／60 分钟已验证选择和持久化，未分别等待完整时长。35 秒的关闭后观察也不是完整 5 分钟的关闭对照。
- 本轮验证 Activity 重建，不等于进程终止、系统后台调度或 OPPO 实机验收。Windows 图只证明布局和主次显示，实体副屏未活动，没有当作桌面交互或 Windows 实际定时器证据。
- 本地端点不替代既有 WebDAV 服务验收。此前隔离方案首次准备被自动审批拒绝；用户后来明确确认，preview.87 已完成既有服务提交后 TCP 响应丢失及原生页面恢复，见[真实服务记录](REAL-WEBDAV-RESPONSE-20261029.md)。这一步不替代物理断网、进程终止或 OPPO 验收。
- OPPO、正常 Windows 客户端、配对、声音、业务服务和正常用户 WebDAV 配置不变。本轮不发布、不推送。

## 本地安装包

- Windows：`dist/velopack-releases-0.2.11-preview.77/RHRemote-win-Portable.zip`，SHA-256 `D831C1CA0528C44FFCFB1A1C18270D50225BB19DD4196EA17C74C741DEC7E064`。压缩包内 UI 和引擎已分别核对与构建产物相同。
- Android ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.77.apk`，SHA-256 `8320FEC32A956931FD2656A85750317DE2D7502129DA05EDD9CD7CD34623B303`，versionCode 295；签名验证通过，证书与上一版一致。
- 对照页：`D:\RHRemoteTools\ui-sync-primary77-review-20261029\preview.html`。仅导出自身界面，无系统截图或输入。
- 构建后的保留规则已移除旧 Android preview.75、Windows preview.70 及一个旧 staging 目录，本轮删除 287,104,101 个逻辑字节；正式发布件和正在使用的构建未删除。
