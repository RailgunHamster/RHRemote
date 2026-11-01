# 双端当前界面与 Android 完整导航复验

本轮使用 Windows preview.159、Android preview.158。没有修改产品代码、重建 APK 或替换日常安装；Android 使用已冻结的独立测试包，Windows 按当前源码重新导出状态组件。完整 UI 目标仍按 [D1–G2 验收表](UI-FLOW-SPEC.md#完整流程与完成证据)进行，不以本轮布局图代替真实连接或交付。

## 可直接查看的整页预览

`D:\RHRemoteTools\ui-workspace160-review-20261031\preview160.html` 提供双端当前页面对照，默认并排显示设备概览，可选择页面、主题、窗口、Android 字号及正文首屏／底部。点击图片查看原尺寸。

| 来源 | 图片 | 当前范围 |
| --- | ---: | --- |
| Windows 159 工作区 | 84 | 沿用上一轮三台日常设备副本的正式控件与 Controller 流程图，逐张核对原 SHA-256 |
| Windows 159 组件 | 272 | 本轮重新编译导出的正式 Slint 组件；包含会话面板、菜单、连接阶段、更新状态及表单布局示例 |
| Android 158 整页 | 290 | emulator-5580 的实际 PcView／原生 View；四组尺寸与字号，每组 56 张基本状态，另共 66 张正文底部 |
| 合计 | 646 | 66 个页面／状态分类；每张图标注来源和实际尺寸 |

Windows 的“large”例是 1920×1080 窗口布局，不标作 200% 字号；Android 的 200% 来自实际系统字号配置。组件及连接／会话状态示例使用明确夹具，没有发起真实远程会话、同步、更新或声音播放。

![Android 窄屏、200% 字号的控制面板](D:/RHRemoteTools/ui-workspace160-review-20261031/workspace160-visual-narrow2-01/panel-input-light.png)

![Android 当前宽屏控制面板](D:/RHRemoteTools/ui-workspace160-review-20261031/workspace160-visual-wide1-01/panel-input-dark.png)

## 实际导航与控件

Android 使用 `com.rhremote.client.uicheck158` 及其独立测试包，不操作正常 preview.129 或 OPPO。设备名称、别名、顺序、首选／备用地址及其他客户端参数来自 Windows 日常公开配置的副本；当前 Android 测试客户端另有明确的 1080p30、6 Mbps 等临时参数，不复制 WebDAV 登录或原生配对身份，也不写回日常配置。

两次完整导航均通过，普通／200% 字号各 **40 条步骤记录**，各观察到 **6 次实际 Android 方向配置变化**。覆盖宽屏切换设备时继续编辑、竖屏保留草稿、分类切换、保存后继续、放弃更改，以及资料／添加／应用设置的返回位置。选中设备、搜索条件、已保存配置和未保存输入按每一步核对。200% 字号另对完整进入可见区域的 TextView 测量文本高度，记录裁切为零；这不将正常滚动视口边缘的部分可见内容称为完整可见。

工作区原生事件在两档尺寸、两档字号下各通过 **18 条流程**，共 **72 条**，包含浅深两种主题。覆盖主导航 Enter 与焦点、隐藏搜索框的迟到输入、状态刷新／追加设备／排序后保留焦点、设备详情返回、搜索刷新，以及使用 Activity 正常分发的方向键浏览八行列表到底再逐行返回。不是逐行强制聚焦，也不使用系统输入注入。

以上是 **6 次功能测试调用**；四组整页导出另有 **4 次布局测试调用**，共 **10 次成功的原生测试调用**。导航及工作区还保留 112 张流程图，独立于 646 张整页／状态图集，不重复合计。两类控件测试使用生产 View 和应用内事件；设备可用性按夹具处理，没有真实串流、配对、WebDAV 或更新请求。

## 数据与环境恢复

八次使用 `RhSessionStateSnapshot` 的工作区／导出测试均核对原私有共享文档、偏好和原备份恢复，并确认自有 Activity 已退出。导航测试分别通过自己的原始字节恢复断言；全部结束后再次核对测试共享文档与准备的原文件摘要一致。

四组显示条件为 1512×1008、540×960，密度均为 240，字号 1.0／2.0。结束后实测恢复到原覆盖 1512×1008、密度 240、字号 1.0；自动旋转及用户旋转设置与开始时相同。两个独立测试包均已卸载，三份自有临时配置已移除。正常 Android 仍为 preview.129（347），其共享文档、全局偏好、设备偏好三份文件指纹不变；Windows 日常程序和此前保护文件也逐项核对。

## 来源、保留失败与验收边界

证据目录：`D:\RHRemoteTools\ui-workspace160-review-20261031`。

- `android-selected-source160.json`：13 份选定 Java 来源与准备目录逐字节摘要一致；测试包沿用 158 冻结 SHA-256。
- `windows-component-source160.json`、`windows-components160-01.log`：当前 159 的来源及 272 张组件导出。没有创建原生 Windows 窗口。
- `workspace160-nav1-02`、`workspace160-nav2-01`：两档字号完整导航的日志、结果及自身绘制图。
- `workspace160-focus-{wide1,wide2,narrow1,narrow2}-01`：72 条工作区原生事件流程与恢复记录。
- `workspace160-visual-{wide1,wide2,narrow1,narrow2}-01`：四组整页目录及正文到底检查。
- `gallery-manifest160.json`、`native-flow-images160.json`、`emulator-cleanup160.json`、`final-audit160.json`：图片摘要、恢复及最终核对。

第一次导航准备省略了夹具配置中必需的 codec，在打开界面前被现有配置校验拒绝；原失败保留在 `workspace160-nav1-01.log`。改为完整的公开客户端参数模板并设置明确的测试值后通过，没有修改产品校验或测试断言。第一次清理的 PowerShell 参数将两个包名合为一项，命令失败；只读检查确认两个包仍在，之后分别卸载成功，保留 `cleanup160-after-first-attempt-state.json` 和最终清理记录，没有重复卸载已不存在的包。

本轮 Windows 仍只枚举到无实体名称的 DISPLAY26 虚拟屏，未打开可见预览，也没有回退到主屏或虚拟屏。Windows 控件与 Android 模拟器的当前流程证据不能代替实体窗口、不同 DPI 双屏、物理键盘／输入法、OPPO、真实连接／首帧、外网恢复、系统安装和最终交付；这些要求继续保留在 TODO。上一轮 Windows 508 项及此前 Android 567 项回归各按其原记录使用，本轮没有把它们重算为新执行结果。
