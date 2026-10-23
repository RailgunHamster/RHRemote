# Android 配对记录即时保存

同步导入的设备可能只有 RHHome 记录，没有原生 ComputerManager 的 tuple。原成功分支直接
访问 tuple，可能为空；已有设备则仅更新内存证书，直到以后轮询才写入 SQLite。

现在配对成功先写入本地数据库，再更新原生设备及 UI。数据库写入失败保留旧证书，返回失败。
普通轮询及迟到的设备发现不能恢复旧证书；删除后的普通轮询不会重建设备。临时代理地址不进入
持久化地址，transport-only 设备保留共享配置中的 rhr 身份。配对证书仍只存在本机，不随 WebDAV 同步。

旧实现的两个实际 PcView / SQLite 回归均失败，证据保留于
`D:\RHRemoteTools\android-pair-persistence-before-20261023.xml`。
修复后 11 项配对持久化回归通过，完整 60 项 JVM 测试零失败，APK 和测试 APK 构建成功。
测试覆盖重启后记录、重新配对、写入失败及异常、空证书、代理地址清理、迟到轮询和删除后的轮询。
测试只替换配对网络请求，使用实际 PcView 完成分支及 SQLite；不代替 OPPO 实机验收。

开发构建 `0.2.4-preview.3` / code206 尚未发布或安装。后续正式 Android 更新将一起交付已完成修复。
