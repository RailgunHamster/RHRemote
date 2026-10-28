# 设备设置导航（本地 preview.51）

用户指出原连接设置是孤立页面，不能直接切换设备，返回又放在右上角。本轮将设备及其连接设置放回同一个工作区。

## 最终交互

- Windows 始终保留设备列表；选中设备的右侧用“概览 / 连接设置”就地切换，删除连接设置右上的返回按钮。
- Android 宽屏保留设备列表，右侧显示同样的概览／设置入口。窄屏使用左上“‹ 概览”和设备下拉框，不要求先退出设置再换设备。
- 切换设备继续查看同一个分类，例如 winserver 音频 → claw 音频。每台设备分别加载自己的草稿和已保存参数。
- 未保存时仍提供保存并继续、放弃更改、继续编辑；非法内容保存失败时保留原设备和输入。窄屏选择框在等待决定时显示当前设备，避免标题和正文错配。
- Windows 设备行及概览页签不覆盖正文的返回焦点，选择继续编辑后保留原输入焦点。新布局按扣除设备列表后的实际正文宽度排列，窄窗口的预设按钮分行、标签与控件纵向排列。

按钮继续使用现有 `device.settings.open`、`device.select`、`navigation.resolve`，没有另建一套 UI 行为。

## 本地证据

根目录：`D:\RHRemoteTools\ui-device-navigation-20261028`。

| 内容 | 文件／目录 | 边界 |
| --- | --- | --- |
| Windows 实际 Slint 布局 | `windows-layout03`，168 张 | 软件后端，明确的控件场景，无 OS 窗口 |
| Android 横屏 | `android51-wide01`，50 张 | 1512×1008／240 dpi／1.0 字体，应用 Canvas |
| Android 竖屏 | `android51-portrait01`，50 张 | 1008×1512／240 dpi／1.0 字体 |
| Android 窄屏 | `android51-narrow01`，50 张 | 540×960／240 dpi／1.3 字体 |
| 四幅重点预览 | `navigation-preview.html` | 离线单文件，直接打开，不启动 HTTP 服务 |
| Windows 控件与草稿 | `windows-navigation-tests02.log` | 真正 Controller / MainView 和实际 Slint 按钮、设备行；宽窄、浅深、三种决定、非法输入及独立持久化 |
| Windows 完整回归 | `windows-ui-tests51c.log` | 148 项通过；`windows-device-row-focus51.log` 补充设备行返回输入框焦点通过 |

Android 的新回归使用实际 View 点击列表行、选择 Spinner、原生布局与正式草稿逻辑；保存分别核对两台设备，旧的返回按钮断言同步改为新的概览入口。旧会话键盘回归更新了新增滚动模式开关的位置，并继续验证禁用控件会被跳过。Windows 的跨端 API 检查从 Android 实际注册代码读入仅触控端支持的动作，避免将 Android 已有滚动模式错误要求为 Windows 动作。

模拟器结束时恢复 1512×1008／240 dpi／1.0 字体。未操作 OPPO、未使用 computer use／系统输入／OS 截图；无连接、服务、显示拓扑或声音配置变更。本轮本地包不发布到 GitHub／Gitee，完整 UI 目标其余流程仍见 TODO。

## 构建及配置核对

- Windows 148 项、Android 348 项完整回归通过；新加的设备行返回输入焦点再单独通过。Android 日志 `android-navigation-tests04.log`，Windows 重点焦点日志 `windows-device-row-focus51.log`。
- Windows 本地安装／便携包：`dist/velopack-releases-0.2.11-preview.51`；便携 ZIP SHA-256 `3fb16bb43f9aaf6023f5bfa774ea5e96b4654cb410304116f9a1583d6c556d68`，UI `d80e1559972394b78468bf5eaf602319c09f7f79b052834db6bb7700ed15eab0`。包内 271 项，无配置、证书、密钥或日志；原生引擎哈希仍为 `6b7ed0380956d960eaa17180dfc2093e85e00c7f9d7f94af2c98e691c6880304`。
- Android 本地 ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.51.apk`，versionCode 275，SHA-256 `339ce703ef2fa56133ea7042611a915b36e2f4d942c69952414ff336ab03b74a`；签名 SHA-256 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc` 与既有包一致。
- 日常 Windows 偏好哈希 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`；模拟器共享文件哈希 `fe08b5761c960bdd24fb980a58aac5ab4c58d8698bae1a3d2536270ae273a0d8`，均保持原值。完整元数据见 `verification51.json`。
- 当前用户在讨论界面，未再次弹出／激活 Windows 预览窗口。最终提供离线布局与本地包；不将构建、软件绘图或模拟器回归写成 OPPO／真实远程会话验收。
