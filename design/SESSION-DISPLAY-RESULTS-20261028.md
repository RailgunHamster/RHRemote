# 会话选屏、错误反馈与实际切换

本轮 Windows 使用已验收的本地 preview.68；Android 最终为 preview.69 / versionCode 287。未发布，未覆盖日常 Windows 客户端；OPPO 保持暂停。

视觉及菜单职责继续沿用已选方向。[HelpWire 官方会话菜单](https://kb.helpwire.app/operator-application/operator-menu/)将设备、屏幕和连接选项放在当前会话内。RHRemote 保留“操作 / 屏幕 / 声音”分类与固定展开、收起及断开入口，本轮核对选屏状态和实际执行结果。

## Android 修复

原流程在后台取得新屏幕后立即修改 selected，再在主线程发送切屏输入。发送失败时，界面仍给新屏幕打勾；复现回归明确得到 `expected old, was bad`。现在后台只准备候选结果，由仍存活的会话发送输入并提交选择。失败保留原屏幕，原列表和重试入口可用；会话销毁后到达的结果不提交、不发送键盘输入。

切屏时显示“正在切换屏幕”，列表刷新显示“正在读取屏幕”。切换期间的刷新按钮保持原名称并禁用；断开和分类入口仍可用。

进一步检查发现上游 Android 的 void JNI 键盘方法丢弃了 `LiSendKeyboardEvent2()` 的返回值。增加独立的 checked JNI 方法用于 RHRemote 切屏及快捷键，检查输入队列拒绝并沿用失败时释放修饰键的规则。失败的快捷键同时更新 lastShortcut，避免还显示前一次成功结果。原始上游方法保留。成功反馈的证明范围为输入队列接受；实际远端执行另以服务端证据核对。

此改动位于按钮控制路径。ARM64 包装函数反汇编为参数寄存器移动后直接尾调用 `LiSendKeyboardEvent2`，没有新增等待或修改视频解码、呈现路径。未据此宣称整机性能改善。

## 真实 Windows 选屏

证据目录：`D:\RHRemoteTools\ui-display69-review-20261028`。

preview.68 从日常三台设备配置创建独立副本，在 DISPLAY2 保持不激活；使用隔离配对身份连接 winserver。通过实际屏幕菜单行执行：

1. 起始物理屏 `DISPLAY1`，桌面 1920×1080。
2. 选择已经存在的虚拟屏 `DISPLAY25`，桌面 2520×1680。
3. 选择原物理屏，恢复 1920×1080。

`windows-screens-result.json` 记录 8 次实际业务控件派发。两次选屏均由当前原生进程 6644 接受；`host-existing-virtual-capture.log` 和 `host-physical-restored-capture.log` 分别记录对应的实际采集分辨率和偏移。发送后解码统计继续更新。

`windows-frame-evidence.json` 对应完整原生逐帧记录：205 次解码输出、199 次渲染提交，丢失事件为 0；虚拟屏切换完成后至下一次操作之间，以及物理屏恢复完成后，各仍有 5 次渲染提交。原始记录约 192 KiB，仍用于本轮证据，予以保留。这些数据不代替源像素匹配、物理呈现或持续 FPS 验收。

没有新建、调整显示器。`host-restore-proof.json` 对照前后两块屏幕的完整目录属性，确认拓扑未变，统一服务 PID 60240、Sunshine PID 30252 均未变化。仅移除本轮唯一授权后，八个原授权及文件 SHA-256 `177acc4890b4b3b5449745a36288d26f2f0613bf114c5e2edf3562f51163134a` 完全恢复。

首次 Windows 预览因我生成的测试配置把硬件 ID 填入 displayMode 而退出，未开始配对或连接；保留失败配置及日志。修正隔离配置为 auto 后才进行上述测试。启动期间客户端整理了客户端资料及配置映射；winserver 自有配置的字段均未改变，结束后隔离 seed 也已恢复。日常 preferences SHA-256 仍为 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`。

## Android 验证

| 证据 | 证明范围 |
| --- | --- |
| `android-before.log` | 原控制器回归复现选屏失败后 selected 被改成目标屏。 |
| `android-final-join.log` / `android-final-result.json` | 372 项 JVM 回归通过。实际控制器覆盖失败保留、立即重试、候选结果等待、销毁后晚到结果不发输入、队列拒绝、修饰键释放及快捷键旧成功状态更新。晚到结果测试确认准备线程退出后再处理其回调；网络及 JNI 故障用边界替身构造。 |
| `native-checked-input.log` | 3 项原生测试通过。真实 JNI 在没有输入通道时返回 -2，并由正式 Java 方法转为可见失败；另验证修饰键释放与安全注意序列不回退为普通键盘输入。未向远程桌面发送这些测试事件。 |
| `native-panel-landscape/` | 1512×1008、字体 1.0，浅深色原生 View 控件及自身绘制图。 |
| `native-panel-portrait/` | 1008×1512、字体 1.0，同上。 |
| `native-panel-narrow02/` | 540×1200、字体 1.3，同上；检查切屏等待、刷新禁用、失败后原屏幕勾选、长列表、不可用屏幕、错误详情和固定断开入口。 |
| `arm64-input-exports.txt` / `arm64-checked-input-disassembly.txt` | 正式 ARM64 包同时保留原 JNI 导出和新增 checked 导出，返回路径已检查。 |

原生面板是布局与控件参数验证，没有启动 Android 串流。窄屏首次复验被我并行启动的只读诊断中断，系统日志明确记录 `stop ... due to start instr`；未计为产品失败或通过。待两项命令均结束后，以新目录串行重跑窄屏并通过。模拟器分辨率、密度和字体比例恢复 1512×1008 / 240 / 1.0；shared 配置 SHA-256 保持 `fe08b5761c960bdd24fb980a58aac5ab4c58d8698bae1a3d2536270ae273a0d8`。

APK：`dist/android/RHRemote-v0.2.11-preview.69.apk`，ARM64、287，SHA-256 `51a36773e3034ebf8a2bf1f67d76d69cf3a2b5c653ac46dec3dda64158905cdc`；签名证书仍为 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。

## 剩余范围

Android 的真实串流选屏、窗口移屏、快捷键和安全注意序列实际送达、声音播放及触控缩放，仍保留对应 TODO。Windows 本轮只完成选屏，不把它扩展为其余会话动作通过。没有使用 computer use 或系统输入驱动客户端 UI；实际切屏经 RHRemote 正式输入通道发送。
