# Android Release 界面验证与候选安装包

2026-11-01，Android 本地候选 `0.2.11-preview.189 / 407`，Windows 保持 188。本轮将已有 Android 界面补到正常 ARM64 Release 包，并验证隔离 Release 的主要页面；没有改动生产 Java 界面布局或连接参数。完整 UI 目标继续进行。

证据目录：`D:\RHRemoteTools\ui-android-release189-review-20261101`。

## 交付与保护

- 正常安装包：`dist/android/RHRemote-v0.2.11-preview.189.apk`，包名 `com.rhremote.client`，ARM64，非 Debug，版本码 407。SHA-256 为 `9001BB3EC5B862679F4DFA747B51B2B0D611095405CE1C4860299C8B45D90959`。
- 签名与已保留的正常 129 安装包相同；检查了 ABI、ELF 架构、版本、签名及运行配置文件名。正常包的 manifest 和 DEX 均没有 `RhUiFixtureActivity`。
- 测试完成后恢复正常 ARM64 构建参数，用最终准备脚本重新生成 APK，结果与上述候选逐字节相同；未覆盖已冻结的 dist 文件。记录为 `normal-rebuild189.json`。
- 本轮没有安装到 OPPO 或替换正常模拟器客户端。专用模拟器正常 129／347 未启动，三个配置文件、八个受保护文件及显示／密度／字号／旋转设置不变。隔离主包和测试包均已移除。
- `build-android.ps1` 增加同版本产物保护：在构建前拒绝已有文件，提交产物使用不覆盖的文件复制。现有 189 的拒绝路径已核对，其哈希和修改时间不变；不再允许默认参数覆盖旧正式 APK。

## 已通过的检查

| 范围 | 本轮证据 |
| --- | --- |
| 正常 Release 完整 JVM | 71 个套件、641 项，失败／错误／忽略均为 0；XML 已冻结 |
| 隔离 Release 主要页面 | 设备页、五类连接设置、四类应用设置和操作面板，两主题共 22 张实际 View.draw 图 |
| 标题、导航与草稿 | 12 组，检查固定标题／导航、底部进入设置、继续编辑／放弃／保存、切换设备及同设备刷新滚动位置 |
| 设置正文与保存栏 | 96 个页面检查点全部可用；8 组真实 EditText 编辑／保存及保持同一草稿的尺寸变化通过 |
| 完整错误与取消 | 36 组，全文可读、保存／取消可见、同错误保留位置、新错误归零，真实取消保留已存数据或复制草稿 |
| Windows 当前控件补图 | 用冻结的 188 测试程序单独导出 76 张软件控件图；对应一个既有控件测试通过，没有重新构建或安装 Windows |
| 双端当前汇总 | Windows 188 的 80 张配置副本页面、76 张受控组件及 Android 189 的 22 张页面，共 178 张，见 `current-gallery189-02/gallery.html` 和 `overview.png` |

四个原生运行分别为 `release189-catalog-03`、`release189-header-03`、`release189-viewport-03`、`release189-errors-03`，均返回 `OK (1 test)`。这些运行使用同一冻结的隔离主包与观察器，主包的 installed APK 哈希在每次运行前均核对。

Windows 图中的设备配置来自日常副本，在线、配对、自身身份和错误是受控状态；Android 使用公开生成的测试设备。汇总不表示当前主机在线状态，也不代表完整像素一致性或全部真实业务通过。第一版汇总缺少 Windows 操作栏概览而被工具拒绝；补充当前冻结程序的实际控件图后，才生成最终 `current-gallery189-02`。

## Release 测试隔离与失败记录

`prepare-android.ps1` 增加显式 `rhremote.uiTestBuildType=release`。它要求 `.uicheck数字` 的隔离包后缀，才向 Release 源集加入已有测试 Activity 和 `rhremote-ui-test.pro`。正常构建默认不启用这个入口。

隔离主包仍是非 Debug、经过 R8 的 Release；它额外保留 tracing 与 Kotlin 的测试运行器支持接口。这些接口由另一个测试 APK 调用，目标 APK 的 R8 看不到这些调用，因此正常压缩会移除其中一些成员。隔离包的支持规则与测试 Activity 使它不同于正常安装包，不能把它等同于正常 ARM64 的完整实际运行验收。

保留了以下失败及对应输入：

1. 初版 Release 观察器 R8 缺少 Error Prone 注解类型。补依赖后，2.18.0 又引用 Android 不具备的 `javax.lang.model.element.Modifier`；最终使用缓存中的 2.47.0，保留 R8 的缺失类检查。[上游 Android 兼容问题及修复](https://github.com/google/error-prone/issues/2122)
2. 首次原生启动在 `AndroidJUnitRunner.onCreate` 抛出 `Trace.beginSection` 缺失，随后系统报告启动 ANR；目标 R8 的 `usage.txt` 记录该方法被移除。加入隔离 tracing 规则后，又暴露测试运行器 Kotlin 闭包所需的 `kotlin.jvm.internal.Lambda` 缺失。最终隔离支持规则修正后，四个原生套件全部通过。

前两轮还未进入 RhHome 页面；这两次不能作为正常安装包的启动或远程连接故障样本，也没有以扩大启动等待掩盖失败。各轮 APK、构建日志、两个原生失败日志及第一个进程 18782 的日志均保留。

## 保留的验收边界

本轮没有操作 OPPO、真实 Sunshine、WebDAV、配对、远程输入／声音、Windows 实体窗口或业务服务，没有公开发布、提交或推送。隔离页面内的点击、键盘及尺寸变化不等同于物理触控、系统输入法、横竖屏或真实网络恢复。正常 APK 的实际安装／升级、Windows 实体窗口、其余会话控制及真实业务验收仍保留；181 的灰色／after 帧差异也没有在本轮认定为已定位或解决。
