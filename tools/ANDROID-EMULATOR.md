# 完整的 Android 模拟器测试包

专用 x86_64 模拟器使用以下构建入口：

```powershell
.\tools\build-android-emulator.ps1 -Version '0.2.11-preview.114' -VersionCode 332 -BuildTests
```

版本号和 versionCode 使用当前候选值。

脚本的目标检测与 Cargo 构建都在仓库目录执行，使用该目录的 `rust-toolchain.toml`。从其他目录以绝对路径调用脚本，也不会误用调用目录的默认工具链来判断目标是否已安装。首次需要安装对应 Rust 标准库，同样在仓库目录运行：

```powershell
rustup target add x86_64-linux-android
```

该入口使用 Android NDK 编译 x86_64 Rust 传输库，再准备 Moonlight／RHRemote 源码并构建 Debug APK。结束时检查 APK 同时包含 `libmoonlight-core.so`、`librhremote_transport.so`，两者必须是 ELF64、小端、EM_X86_64；存在其他 ABI 时拒绝该测试包。编译环境变量退出时还原。

APK 位于既有 Gradle `app/build/outputs/apk/nonRoot/debug/`，不会复制到 ARM64 的 `dist/android/`，也不安装或操作设备。构建输出给出实际路径和 SHA-256，并区分原始 Rust 库和 APK 内库的哈希；NDK 去除调试信息后，两者可以不同。正式 ARM64 包继续使用 [build-android.ps1](build-android.ps1)。

不要仅用 `prepare-android.ps1 -Abi x86_64` 加 Gradle 作为完整测试包的构建流程：prepare 负责准备源码，没有编译 Rust 传输库。历史 preview.114 x86_64 包因此缺少该库，实际连接跳过内置打洞和中继；GUI 通过不能证明这些路径可用。

`RhTransportLoadDeviceTest` 可在专用模拟器上验证实际库加载与 JNI 身份方法。它在自己的缓存目录生成、复用和删除临时密钥，核对正常独立连接身份没有变化，不打开网络连接、不向服务端授权。真实串流测试另行核对实际所选路由、原生帧和取消清理；`tailscale_relay` 配置标签加 `path: unknown` 不能当成已证明 DERP 中继。

模拟器 Debug APK、ARM64 Release APK和各次测试证据必须分别核对哈希。设备操作始终显式指定专用模拟器，不操作 OPPO。测试 UI 使用应用自身 View／共享动作和渲染接口。
