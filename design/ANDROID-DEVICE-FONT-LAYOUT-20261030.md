# Android 设备工作区的大字号与宽窄布局

本轮保持设备列表、概览、连接设置和连接入口的现有路径，修正大字号下文字裁切及窄屏参数省略。生产改动仅在 `RhDeviceBrowser.java`；没有变更连接、同步、配对或串流实现。Windows 日常版仍为 preview.101 并保持关闭；没有操作 OPPO。

## 复现与修正

Android preview.110 的原生组件在 360×800、640×360、840×560、1008×672、浅深主题、100%／200% 字号共 16 组布局中，大字号共有 **68 处文字内容高度超过控件可用高度**。实际测量例子：按钮内容需要 39 px，固定 48 px 控件扣除上下内边距后只剩 32 px；在线标签需要 35 px，原固定高度为 28 px。窄屏的分辨率和码率也出现省略号，见修改前 `browser111-baseline02-evidence` 原生 View.draw 图及 `result.json`。

现在标题及按钮保留最小高度，实际文字需要更多空间时自然撑高。状态标签也保留最小高度而不强制固定高度。分辨率等信息仍优先采用原横排；用实际字体绘制宽度核对每列，放不下时该组纵向排列，避免靠缩小字号解决。

第一候选还给“概览”标签增加了多余的最小宽度，正常字号图与原版不同。最终撤销该宽度，仅保留自适应高度。相关 `browser111-candidate03`、初次 Release 包及对照记录保留，没有交付初次包。

## 最终验证

证据根目录：`D:\RHRemoteTools\ui-device-browser111-review-20261030`。

| 验证 | 证据与范围 |
| --- | --- |
| 原生布局 | `browser111-final04`：16 组布局、文字垂直裁切 0、参数值省略 0；按钮最小点击高度保持 48 dp。 |
| 宽窄切换 | 每组改变尺寸并恢复，期间刷新设备数据，共 32 次布局转换；选择保持原设备，16 组恢复图与转换前逐字节相同。 |
| 正常字号对照 | 浅深两主题与四种尺寸的 8 张正常字号 PNG，最终图与修改前 preview.110 逐字节相同；见 `browser111-final04-image-comparison.json`。 |
| JVM 回归 | 最终生产代码 426 项通过、0 失败／错误／跳过；见 `build111-spacing-final-tests.log` 与 `unit-counts111-final.json`。 |
| 配置保留 | 原生验证前后独立 SHA-256 核对 14 份文件，均不变。测试 Context 禁止 SharedPreferences.edit；没有启动 Activity 窗口、主机操作、WebDAV 请求或系统输入。 |

这是模拟器上的原生组件绘制和布局验证，包含 Android 实际字体测量；不是附着到真实窗口的导航／会话验收，也不代替 OPPO。第一轮 `baseline01` 因测试 Activity 没有 Window 时调用 setTheme 而失败，随后改为仅使用带主题的配置 Context；该轮不是产品崩溃证据。有效旧版复现为 `baseline02`。

APK 的最终身份与签名、原生库和正常配置保留核对记录在同目录 `final-audit111.json`，**23 项全部通过**。最终 ARM64 Release 为 `dist/android/RHRemote-v0.2.11-preview.111.apk`，versionCode **329**，非 debuggable，SHA-256 **DE62B93572D18CAE67629B5F254F267D2EA07B80CFE0AA14ADC9967BC9326E19**。签名与上一版一致，两个原生库与 preview.110 相同；模拟器所装主 APK／测试 APK 的实际文件哈希也与本轮保留的最终测试文件一致。

已查看最终窄屏浅色和宽屏深色的原生绘制图；系统字号仍为 1.0，模拟器主应用退出，日常 Windows 程序和三份配置、winserver 配对身份文件保持原哈希。候选未公开发布、未向 OPPO 安装。整套 UI 及其余真实业务路径仍以 TODO 为准。
