# 悬浮按键入口：控制面板顶部固定开关

Android 后续本地包为 preview.102，进一步修正了悬浮键盘和滚动模式开关的轨道与滑块辨识度，见[最终原生图与安装包](ANDROID-SWITCH-CONTRAST-20261029.md)。Windows 日常保持本文交付的 preview.101。

用户指出看不到之前要求的方向键和 Tab。Windows 日常 preview.93 实际已包含功能；此前回复“尚未安装”不准确，已更正。原开关只在“操作”页，用户切到屏幕或声音分类时无法直接找到。

preview.101 将 Windows 和 Android 的同一个悬浮键盘开关移到面板顶部，与设备名称及断开操作同排；三个分类都能直接看到，不依赖内容滚动。没有再建立第二个同名按钮。开启仍调用原有 `stream.keyboard.floating.set`，原有处理会收起面板，使半透明按键立即显示；关闭和失败重试仍使用原接口。

方向键保留右下倒 T，Tab / Shift+Tab 保留左侧偏上，Esc、Enter、退格和 Delete 的位置与键码不变。默认关闭规则、空白穿透、原生编码与服务端均未改。

## 验证

证据目录：`D:\RHRemoteTools\ui-floating-entry101-review-20261029`。

- Windows 完整 workspace 回归通过，包含 Core 135、UI 218 项，另有一项既有 UIA 用例忽略。新增实际 Slint 控件用例覆盖 320 / 355 / 560 逻辑宽度、浅深主题、三个分类和开关两状态，核对顶部完整可见、单次动作及忙碌拒绝。补充带 winserver 标题的同一布局用例通过；自身图在 `windows-visuals-final`。
- Android 完整 JVM 回归 **415** 项通过。原生 View 用例核对三个视口、三个分类的开关全尺寸可见且不在滚动容器中；实际 Switch 回调与禁用拒绝通过。新增禁用／隐藏回调检查，避免直接调用 performClick 时绕过控件可用状态。键盘回归明确核对新的顶部顺序：收起 → 悬浮键盘 → 断开；忙碌和禁用项跳过，收起后视频焦点恢复。
- 从最终 Windows Portable 解包启动独立副屏窗口，保持不激活，三个设备均保留，自身首幅页面导出成功。没有建立远程会话，之后正常退出；这一步不是新一轮远程输入验收。
- 最终 Windows Portable 与 nupkg 中的 UI 都是 `A178B66DB56F02A3BA63195F3BB9D732ED1A76B23A92C33EDC149F95E81875E6`，原生引擎均为既有 `6F1FEA50E30FCA9D5F02518E0516EA8F240E51C20D8F10A1E49649DC18107CB0`。

Windows 初次新增用例曾使用不支持快照的后端，改为软件测试后端；第二次曾把同标签的文本节点与按钮都计入唯一性，已限定按钮角色。这两轮不作为产品失败的基线证明。Android 旧布局在“无需滚动”断言失败；移动后的早期样本分别暴露直接调用禁用回调、旧 Tab 顺序断言和 JVM 物理视口未随尺寸更新的问题，均保留在证据目录，未改成跳过断言。

## 安装包和边界

- Windows：`dist/velopack-candidates/0.2.11-preview.101/RHRemote-win-Portable.zip`，SHA-256 `1741300E97F0AC082FB4D17E98BA336959D46E218BCC680A018A5CAFB3645885`。Setup 为 `4AB36A93E65215440AC64763BC87B0840209575EC620A5EC9FB250B36D6B350B`；nupkg 为 `353EBE5E87256B232856F5931D793B0E7842190A52DFA6D0802C09F6E271F801`。
- Android ARM64：`dist/android/RHRemote-v0.2.11-preview.101.apk`，versionCode **319**，SHA-256 `6675F48F447B6915A79BEDA7DB353D80C476EC9D5F245A3D3DC70CED3ED43564`，签名核验通过，与 preview.100 一致。
- Android 本轮最初结果是 JVM 原生 View 与布局回归；后续已在 emulator-5580 上运行八组生产 View 离屏绘制与回调检查，十四份配置和身份文件均保留，见 [模拟器原生绘制](ANDROID-FLOATING-LAYOUT-20261029.md)。它不代表附着系统窗口或 OPPO 安装通过。既有按键远端回执范围见 [功能记录](FLOATING-KEYBOARD-20261029.md)。旧初次未显示及 Windows 最小化恢复焦点问题继续保留。
- 本地升级准备方案为 `local-upgrade-101/plan.json`，最终配置备份只在用户保存并正常关闭后创建，安装器通过 Velopack 应用真实 nupkg，升级后保持关闭。实际结果以同目录 install-outcome 和最终核对为准；本记录不代替公开发布验收。

## 用户批准后的 Windows 原地升级

用户回复“已保存并关闭，升级”后执行上述安装器。Velopack 日志确认包应用成功，日常目录的 manifest 已为 preview.101，UI 与原生引擎 SHA 均与最终候选相同。最终核对 `local-upgrade-101/final-verification.json` 为 passed，日常目录运行进程为空，安装器未重新启动客户端。

preferences、原生配对 INI、webdav-local.json 均与用户关闭后建立的最终备份逐字节相同。备份保留在 `local-upgrade-101/final-backup-ffd0b198ea5c458d8ec4032d811b5eba`，原 preview.93 完整包也保留。没有操作 OPPO 或更改系统服务；Android APK 和公开更新源仍不是本机升级结果的一部分。
