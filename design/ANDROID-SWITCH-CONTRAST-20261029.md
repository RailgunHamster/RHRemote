# Android 会话开关：实色轨道与原生像素核对

Android 本地 preview.102（versionCode 320）调整悬浮键盘和滚动模式两个开关；Windows 日常保持 preview.101。本轮不操作 OPPO、不发起远程连接，不改变原有开关动作或按键布局。

## 图像发现与改动

preview.101 的开关轨道很淡；暗色开启状态的轨道和滑块又使用相同颜色。先调整颜色与间距后，原生图像仍显示关闭状态过淡：系统轨道资源自身的透明度没有被 tint 消除。该中间候选没有发布，旧 APK 保留在证据目录。

最终保留原生 Switch 的键盘、触控和无障碍行为，使用 44×24 dp 的实色圆角轨道、白色滑块以及 8 dp 的文字间距。蓝色表示开启，灰色表示关闭；两处开关共用一个样式函数。48 dp 的控件触摸高度和顶部固定位置保持。

## 验证与范围

证据目录：`D:\RHRemoteTools\ui-floating-native102-review-20261029`。

- 最终实现完整 Android JVM 回归 **416** 项通过。覆盖主题颜色区分、面板与焦点顺序、开关／共享动作、旧控件退出边界等原有回归。
- `instrument03.log` 为 OK (1 test)，`android-evidence-final/result.json` 为 passed。模拟器 Android 框架中运行生产 View，浅深主题各覆盖三个横竖视口，另测两主题 130% 字号，共八组，导出 **40** 幅自身 PNG。
- 每幅展开面板图都必须实际检查到可见开关，避免采样全部跳过仍通过。检查实际绘制后的轨道像素与预期实色相同，并计算滑块／轨道的实际像素对比。
- 共 **44** 个实际采样，开启 14 个、关闭 30 个，最低对比 **3.056987859**。明细为 `pixel-checks.json`，汇总为 `pixel-verification.json`。这不是对整个应用或系统显示的无障碍合规声明。
- 原有八组布局、十个悬浮键的正确接口回调、空白穿透、开关和面板互斥均通过。十四份设备、WebDAV、配对、身份、数据库及偏好文件在覆盖安装和测试前后逐字节相同，见 `configuration-final-verification.json`。

测试根 View 没有附加到 Activity 或系统窗口；主题由只读 Context 提供，动作接收端是夹具。上述证据不替代系统栏／缺口、实际 SurfaceView 合成、远端收到输入或 OPPO 实机验收。模拟器现为同签名 debug preview.102；没有把 x86 模拟器结果算作 ARM64 性能通过。

## 本地安装包

ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.102.apk`，SHA-256 `4FB811923E27FA13B642F6A32F8D3C3FA500D8517CBA39D067AE8EA86804C0F0`。versionCode 320，签名验证通过，与 preview.101 一致。发布包使用的 RhStreamPanel.java 与仓库源 SHA 相同；`release-verification.json` 保存对应摘要和单元测试计数。

APK 已生成，尚未安装到 OPPO，也没有公开发布。中间仅 tint 的未发布候选另存为 `candidate-before-opaque-track.apk`，没有修改正式发布件。构建后的授权保留检查未发现待清理文件，未删除运行中的文件或停止服务。
