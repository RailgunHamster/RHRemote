# Android 原生窗口与系统键盘对照

本轮在专用 emulator-5580 的实际应用窗口中，补验 preview.128 的系统 IME 结果确认和控制按钮点击区域。浅深主题、100%／200% 字号共八个组件场景通过。使用实际 StreamView、WindowInsets 和系统输入法，没有创建 Game 或连接主机；完整串流仍有出帧后原生 `-1` 中断，不能将组件通过记作完整会话通过。

证据目录：`D:\RHRemoteTools\ui-platform-window128-review-20261030`。测试源码为 [RhPlatformWindowDeviceTest.java](../client/android/overlay/app/src/androidTest/java/com/limelight/rhremote/RhPlatformWindowDeviceTest.java)。本轮产品代码与此前 preview.128 的七文件清单一致，新增的是原生窗口测试；此前完整 JVM 回归仍为 479 项，不累加成新的 JVM 数量。

## 同一测试包的旧版／新版对照

最终测试包为 `ui-platform-tests128-02.apk`，SHA-256：`48144A58EDE87C2A869275A3AB31D8476C6E82B13D008C089829F4A5F45E0A63`。包中读取实际安装版本，避免把测试包编译时的版本常量误当作被测应用版本。

| 样本 | 被测版本 | 结果 |
| --- | --- | --- |
| `baseline126-font1-02` | preview.126 | 两个测试均失败：系统已显示键盘仍报告“键盘未打开”；警告清除后控制按钮从 x=682、宽 147 变成 x=690、宽 132。 |
| `current128-font1-03` | preview.128 | 两个测试通过，浅深主题共四个组件场景。控制按钮展开、警告清除与收起后均为 x=682、y=48、147×72。 |
| `current128-font2-04` | preview.128 | 两个测试通过，浅深主题共四个组件场景。200% 字号下三个状态均为 x=664、y=48、183×72。 |

最初测试包的 `baseline126-font1-01` 已保存：键盘失败可复现，但按钮检查在布局完成前读取了旧尺寸而误通过。最终测试改为等待根容器与按钮不再请求布局，且请求尺寸等于实际尺寸；同一最终测试包重跑旧版后明确失败，再跑新版通过。没有放宽产品断言，也没有将首次按钮检查写成有效反例。

## 系统 IME 的证据与限制

实际窗口包含生产使用的 StreamView，`onCheckIsTextEditor=false`，焦点和附着状态成立。显示阶段的系统 IME inset 为 509 px，确认依据是实际窗口可见性及非零键盘高度。

测试使用生产 `RhSoftKeyboard.WindowBackend`，仅包装其 `settled()`，受控延迟完成信号；可见状态与显示／隐藏请求仍交给系统。旧版在期限到达时已观察到键盘可见，却返回错误并发出反向隐藏请求。新版在已经观察到目标状态时正确确认，保留未知／错误状态的失败和取消逻辑。

| 字号／主题 | 普通显示完成 | 受控延迟完成 |
| --- | ---: | ---: |
| 100%／浅色 | 562 ms | 2040 ms |
| 100%／深色 | 672 ms | 2019 ms |
| 200%／浅色 | 541 ms | 2030 ms |
| 200%／深色 | 633 ms | 2017 ms |

四组均通过普通开关、受控延迟开关及立即取消后的防重开；取消后再等 700 ms，没有旧显示回调或键盘重开。受控延迟约两秒是该分支的测试条件，不能用作真实动画耗时或性能前后对比。

这些检查没有远端文本输入、物理键盘或正在解码的 Game 负载。此前真实 Game 中出现的误判及修正说明见[状态确认记录](ANDROID-IME-CONFIRMATION-20261030.md)。preview.128 的完整 Game 轮仍未走到 IME，后续系统栏／转向、菜单、退出和恢复仍待补齐。

## ARM64 候选与恢复核对

生成了独立 ARM64 Release 候选 `dist/android/RHRemote-v0.2.11-preview.128.apk`，versionCode 346，19,990,146 字节，SHA-256：`22127C5A8D546E3C1B1F2849A7309306CAB7150102D5CCAD981C207A2E0FB9D7`。签名校验通过，沿用 preview.122 的签名证书；非 debuggable，包含 arm64-v8a 的 Moonlight 与 Rust 传输库，两份 `.so` 均与 preview.122 逐字节一致。详情保存在 `arm128-artifact.json`、`arm128-signature.txt` 和 `arm128-native-libraries.json`。这是待验收候选，尚未公开发布或安装到 OPPO。

每轮恢复十五份配置／身份文件、同步备份和字号，核对原尺寸及密度，关闭自有窗口。最终再次核对十五份文件与 preview.126 之前的清单一致；模拟器主应用仍为 x86_64 Debug preview.128，进程关闭。日常 Windows 的 UI／原生程序和三份配置，以及 winserver 原八份授权文件均未变化，见 `final-verification.json`。

未操作 OPPO，未发起主机串流、发送远端输入或播放声音；没有系统输入注入、OS 截图或服务重启。主机现存连接归属尚未确认，保持原状。构建后执行既有清理工具，按策略删除旧 preview.121 APK，保留当前／上一 ARM 候选及受保护产物。
