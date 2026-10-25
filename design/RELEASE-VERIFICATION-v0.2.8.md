# Android 0.2.8 发布与 OPPO 验证

源码／标签：`e17f1b202966c00d2969df4bbfbf32e84d81acb4` / `v0.2.8`。Android code221，非 debuggable，原签名不变，APK SHA-256 `6fb72e5f96c5f5225b736e525fc66284788aec74888cab6d518630736bc7b19e`。Windows feed 保持 0.2.7，通过资产版本字段下载原 v0.2.7 安装包。

GitHub release 423425218、Gitee release 1240409 均公开；两站各八资产和两个 latest feed 均匿名下载校验通过。181 项 Android Release JVM、13 项 Rust 传输、16 项发布工具回归通过。该提交的八个 GitHub 工作流全部成功，包括 Windows／portable 服务端及两次独立中继工作流。

OPPO 从真正的公开 Gitee 更新源经手机热点下载 19,879,214 字节，无 USB 反向代理或测试 HTTP 工厂。产品下载动作返回 25 ms，47 次状态查询最大 74 ms，整体下载及校验观察用时 97.893 秒。产品确认大小、SHA-256、包名、versionCode 及签名后转为 downloaded；后续安装动作 37 ms 返回并打开 OPPO 安装界面。

## 固定安装 URI 的兼容问题

OPPO 当时安装 code220，公开 APK 为 code221；系统安装器仍报已有更高版本。重启安装器进程不改变结果，URI 读取权限已分别授予 com.android.packageinstaller 和 com.oplus.appdetail。只读检查设备自身安装器字节码，错误分支为 installedVerCode > apkVerCode，不能归因为对预览版 versionName 的字符串比较。

同签名 framework Instrumentation 在 Release 应用 UID 内读取实际缓存：包名匹配，incomingCode=221、installedCode=220，完整 SHA 与公开发布一致。将这同一份已下载 APK 复制到新的版本／哈希文件名，以新的 FileProvider URI 打开后通过版本检查，进入 OPPO 的正常人工安装流程。用户完成后系统实际读回 code221 / 0.2.8，Saved 原文和自动同步与安装前一致。

该对照把问题定位到固定名称／URI 的安装路径，符合安装器保留旧解析内容的现象；没有读取安装器私有缓存文件证明其具体缓存键。用户完成安装后旧 ready.json 被正常清理，因此计划中的再次 A/B 比较未执行，不能将其描述为完整交替对照。Android 0.2.9 将在产品中使用按 code／完整 SHA 固定的独立安装快照；此诊断打开新地址的结果不能冒充旧 0.2.8 产品按钮已自动采用新路径。

首次诊断运行因构建脚本相对路径错误遗漏 runner 类而崩溃，属于测试工具故障；补齐并核对实际测试 APK 类后才获得以上设备证明，不记作自然串流崩溃。

0.2.8 安装后独立公网中继视频及两组主副键／拖动／滚轮通过，记录 `1792937350454-5cad0d23-9ad`；关闭客户端 Tailscale，原热点、网络偏好、Saved 和同步恢复。该轮未注入中断，不替代先前 code219／220 的 15／30 秒实际断网验收。详见 OPPO-WAN-VALIDATION-20261025.md。
