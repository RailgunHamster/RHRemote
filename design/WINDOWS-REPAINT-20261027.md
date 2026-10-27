# Windows 主界面切回后透明

2026-10-27，用户报告设备／设置窗口从其他窗口切回后有时透明，缩放一次恢复。排查限定主 UI；视频仍由独立原生进程运行。

## 代码与上游依据

当前版本使用 Slint 1.18.1 / winit / software，softbuffer 0.4.8 在 Windows 通过 GDI `BitBlt` 提交脏区。Slint 按内容变化计算脏区；Windows 在恢复、DPI 等变化后可能已丢弃原有窗口像素，但缓冲仍报告可复用。如果系统请求补画时界面内容未变，Slint 得到空脏区，就不提交像素。缩放或主题变化使内容重新变脏，因此暂时恢复。

上游 [问题 #13537](https://github.com/slint-ui/slint/issues/13537) 与 [已合并修复 #13568](https://github.com/slint-ui/slint/pull/13568) 覆盖这一缺口。后者在绘制之前读取 Windows `GetUpdateRect`，按窗口实际缩放换算为逻辑区域，交给渲染器的脏区跟踪。应用自己发起且没有系统失效区域的绘制仍使用原来的增量路径；没有增加定时重绘或持续刷新。

本项目纳入发布的 `i-slint-backend-winit` 1.18.1 crate，通过根 Cargo 的 `[patch.crates-io]` 使用，仅将上述 PR 的源码差异回移到 `winitwindowadapter.rs`。原始版权、许可证及 Cargo 元数据保留；不修改全局 Cargo 缓存。后续升级必须核对官方版本是否已包含该改动，再移除本地覆盖。

- PR diff SHA-256：`03FD29D7A89A1A37A96B9878B9C153A703AB74C680295D47125EFB1596D3048C`。
- 本地补丁及验证目录：`D:\RHRemoteTools\ui-repaint-review-20261027`。
- Windows 预览版本：`0.2.11-preview.25`。

## 实际窗口检查与边界

未修复 preview.24 的真实窗口仍有完整 UI Automation 控件树，但窗口捕获透出了后面的内容，与用户描述相符；不把应用内部 `view.snapshot` 当作桌面合成正常的证据。

修复版 preview.25 的真实设备页、设置页均能显示，原生最小化后通过窗口激活恢复，未缩放即显示完整内容；真实 winserver 串流显示桌面后，切回主窗口也能显示连接页。窗口控制与断开均用实际窗口操作复验。此为短时检查，长时间遮挡、多轮切换及不同 DPI 的物理双屏往返仍需继续验证，不能据此声称所有透明现象已根治。

测试预览仅在显式测试模式且提供独立数据目录时，在标题和侧栏标明“测试预览／独立配置”。测试窗口只添加 winserver；用户日常设备配置未被替换。

完整 UI 目标仍按 [逐项规范](UI-FLOW-SPEC.md) 验收。
