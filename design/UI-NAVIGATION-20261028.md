# 返回与设备导航（本地 preview.52）

沿用用户选择的 HelpWire 视觉方向。本轮重新核对官方 [设备列表](https://kb.helpwire.app/client-management/client-list/) 与 [资料编辑](https://kb.helpwire.app/client-management/edit-client/)：先在设备列表选定对象，再进入该对象的资料分类。返回按钮统一左置是 RHRemote 对各表单的具体设计，不能伪称官方规定了相同按钮位置；复杂参数仍采用显式保存与离开保护。

## 本次变化

- 两端的设备资料、添加电脑和应用设置将返回按钮放在页头最左，标题排在其后；右上角不再承担这些页面的返回。沿用原有动作 ID、草稿和确认语义。
- Android 窄屏连接设置使用相同线条的左箭头图标和“概览”文字，旁边继续保留设备选择；Windows／Android 宽屏继续保留设备列表与就地概览／设置。
- Windows 返回按钮不覆盖正文输入框的返回焦点，避免在未保存确认中选择继续编辑后键盘输入落到导航按钮。
- 保存、完成、取消、添加和更新等操作继续放在固定底栏；返回与提交不混在同一组。

## 原生设备导航复验

`D:\RHRemoteTools\ui-device-navigation-20261028\android51-native-nav02/result.json` 记录已安装 preview.51 的正式 PcView、真实列表行和 Spinner 的操作。浅深两种主题下共 4 次真正横竖屏变化、8 张应用自身 Canvas 图，覆盖继续编辑、放弃、保存到另一台设备、非法输入阻止跳转、概览返回。保存结果核对两个设备的持久参数，模拟器共享文件在结束后恢复原始字节及哈希。该证据不涉及 OPPO 或远程串流。

首次原生复验发现的是测试夹具的主题不一致：只改本地主题偏好而没有同步夹具 JSON，返回列表正常捕获主题后，整份配置比较报出 dark 差异。夹具建立一致主题后完整流程通过；没有把该差异归因于产品，也没有删去整份配置核对。

## 当前验证范围

本轮根目录 `D:\RHRemoteTools\ui-navigation52-20261028`。Windows `windows-layout01` 包含 168 张实际 Slint 控件图；Android 三种尺寸的 `android52-wide01`、`android52-portrait01`、`android52-narrow01` 各导出 50 张原生 View 图，保持浅深色与明确布局夹具的标记。

Windows `windows-ui-tests52.log` 的 149 项通过。新流程通过实际返回按钮验证左边界、返回后的原设备、搜索及文件未改；既有输入焦点回归增加应用设置返回按钮。Android 使用 `RhDeviceNavigationDeviceTest` 补齐实际页头位置与返回验证，并检查横竖屏、分类、草稿、保存栏及设备独立参数。`android52-tests01.log` 记录 348 项回归通过；`android52-native-navigation01.log` 记录正式 PcView 原生流程通过（60.988 秒），6 次实际方向变化、12 个页头返回场景、20 张自身 Canvas 图，结果见 `android52-native-nav01/result.json`。横／竖、浅／深下的返回均保留设备和搜索，文件比较也通过。

所有布局与控件操作均使用应用自身渲染、共享动作或真实 View / Slint 回调，不使用 computer use、OS 截图或系统输入注入。没有操作 OPPO、弹出 Windows 预览或改动被控机服务。完整 UI 的更新、同步与真实会话剩余验收继续见 TODO。


## 本地交付与核对

- 布局检查覆盖 Windows 宽窄窗口，Android 1512×1008、1008×1512，以及 540×960／1.3 字体；界面检查通过后才跑本轮业务控件回归。离线重点预览为 `navigation-preview.html`，6 幅图，不启动 HTTP 服务。
- Windows `dist/velopack-releases-0.2.11-preview.52` 已生成 Setup、便携 ZIP 与完整更新包。便携 SHA-256 `81b0e725534d5b97d63f0df4afb82e627cdfb0c7ba71f96120cb015ff4488b1a`；包内 UI SHA-256 `8ed3f79ff823f7733f4ea42bbcf3123071142949753a9ec67ade509c4b17bcef` 与当前 release 相同；271 项无偏好、凭据或日志，原生引擎仍为 `6b7ed0380956d960eaa17180dfc2093e85e00c7f9d7f94af2c98e691c6880304`。
- Android `dist/android/RHRemote-v0.2.11-preview.52.apk` 是 ARM64 Release，versionCode 276，SHA-256 `14d3c9f5dc6891f5708cd0334bb9f9a35f1f9195d5ae809c0a8cf99929aa57f2`；签名仍为 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。模拟器上的原生控件验证使用同版 x86_64 Debug，不将其计为 ARM64 Release 安装／升级验收。
- 日常 Windows 偏好保持 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`，模拟器共享配置保持 `fe08b5761c960bdd24fb980a58aac5ab4c58d8698bae1a3d2536270ae273a0d8`；恢复 1512×1008／240 dpi／1.0 字体。元数据见 `verification52.json`。
- 本轮没有发布／部署，没有触碰 OPPO 和主屏。正式更新／安装保留、真实 WebDAV 跨端和其余远程会话及物理输入仍待完成；保持完整 UI 目标和 TODO。
