# 双端设备概览的切换位置

2026-10-31，记录 164。Windows 补齐与 [Android 163](ANDROID-DEVICE-OVERVIEW-SCROLL-20261031.md) 一致的规则：切换到另一设备回到概览顶部；后台刷新当前设备时保留滚动位置。实现仅为详情 ScrollView 增加设备 UUID 绑定及变化时的归零，不改连接参数、路由或原生串流引擎。

## Windows 实际控件对照

使用真实 Controller、MainView、Slint 控件及既有控制接口，在软件测试后端绘制窗口。设备行通过窗口内 PointerPressed／PointerReleased 验证只发出一条 device.select；另一组直接使用同一共享动作。窗口尺寸为 720×560、1180×780，各有浅深主题，共八组。

旧 UI 的八组均复现：看完 winserver 的详情底部，再选 claw 后，连接入口仍在上方屏幕外。例如窄窗口从 -261 px 变成 -246 px，宽窗口从 -73 px 变成 -58 px，均未回到顶部。最终八组均为 0 px。

模拟后台设备列表刷新时，当前设备的滚动位置在八组中均保持；通过设备行选择后，四组键盘焦点仍属于 claw 行。两台设备的 Profile 前后完全一致。软件后端的焦点报告不替代 Windows UIA 或实体窗口验证。

第一轮因新测试中的负数比较语法失败，第二轮缺少 Release 控件查询所需的 Slint 调试元数据；两轮均未计作产品行为证据。补齐后保留相同业务断言，旧 UI 失败、新 UI 通过。原日志均保留。

## 验证与对照图

- Release 工作区全量 509 项通过、零失败、5 项原有忽略；独立控件对照另通过一次。
- Android 沿用记录 163 的十六组原生 View 对照，没有在本轮重新操作模拟器或 OPPO。
- 双端二十四组、四十八张前后图统一在 `D:\RHRemoteTools\ui-overview164-review-20261031\comparison164.html`，图片来源和 SHA-256 见 comparison-manifest164.json。图中使用独立测试配置。
- 原生引擎的 253 个打包文件与 Windows 159 便携包逐文件一致，便携包 UI 与本轮构建的 RHRemote.exe 哈希一致，未包含配对配置、密钥或运行日志。

## 本地候选

Windows 版本为 0.2.11-preview.164，Cargo 与 Velopack 元数据一致：

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| RHRemote-win-Setup.exe | 53095198 | 022AA5AEC99E8EFD1224F3D3C4DC60DA67016AC258DCF4495188461DCC6B8A06 |
| RHRemote-win-Portable.zip | 48558567 | 9382C4AB24BCD60E67F4232BE0E97631CF1E56681A69870F72C78DD7E5EA4D07 |
| RHRemote-0.2.11-preview.164-full.nupkg | 48596254 | 60AA47487866D7BB1E779FC3B917C59C92BD7ABFC6079420B6C31DCC2BE9DE88 |

目录为 dist/velopack-candidates/0.2.11-preview.164；包、更新索引及源码指纹另保存在证据目录。PE 未提供 FileVersion／ProductVersion 字段，版本核对使用 Cargo、界面编译版本及包元数据，没有将空字段描述成已验证版本。

没有运行 Setup、覆盖日常便携版或公开发布。八个受保护的日常文件哈希保持一致；未重启服务或打开 Windows 可见预览。首帧、真实网络、物理输入、跨屏 DPI 和实际安装升级继续按 [TODO](../TODO.md) 验收；记录 162 的间歇性按钮可见性失败仍未确定根因，不以本次详情位置修正代替调查。

证据位于 `D:\RHRemoteTools\ui-overview164-review-20261031`：baseline03、fixed01、windows-full164.log、windows-test-counts164.json、packages164.json、selected-source164.json、tested-executable164.json、对照清单及最终审计。整体 UI 目标仍未完成。
