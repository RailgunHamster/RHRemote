# Android 连接失败的上下文

本地 Android preview.85，versionCode 303。Windows 保持 preview.81。证据目录：`D:\RHRemoteTools\ui-connection-feedback85-review-20261029`。本轮未发布、未操作 OPPO。

## 问题与行为

preview.84 的实际失败链先后出现两次 RTSP SETUP 响应少一个字节，随后恢复选路失败。最终提示却仅为“所有路径不可达或 UUID 不符”，丢失原始阶段，也把尚未证实的身份问题写成可能原因。读取同次原始 attempts 后确认，末尾失败实际包含模拟器缺少 `librhremote_transport.so` 和 Tailscale IPv6 超时，没有 UUID 不符证据；两次 RTSP 响应缺字节的来源仍未确定。

现在按实际结果区分暂时不可达、组件不可用、证书／TLS 验证失败、设备标识不符、没有匹配的配置路径和本轮路径已尝试完。混合失败同时保留不同原因，不把所有失败统一归为网络问题。原始说明仍保留在 attempts，并增加 failureKind；只统计与当前路由匹配的排除项。

自动恢复期间保留最初失败，在后继 Game 的本次 Intent 中传递。最终失败页显示“自动恢复未完成”和“最初失败”，继续使用现有的返回／重试按钮。首帧到达即清除旧上下文，新的手动请求沿用现有清空错误的流程。上下文不写入连接配置、Activity Bundle 或 WebDAV。

界面继续按[Android UI 状态指导](https://developer.android.com/topic/architecture/ui-layer)组织错误信息、对应状态和重试动作。本轮是 RHRemote 的具体错误归属处理，不声称复制了 HelpWire 的协议或异常实现。

没有改变成功路径、顺序、超时、恢复次数、加密或 UUID／证书检查；原有 Unavailable 与不可自动重试异常的区分保留。

## 验证与图像

- 修改前的两个恢复用例和真实 HTTP UUID 用例共三个失败，记录在 `baseline-failures85.json`、`before85.log` 及原始 XML。分别证明根因被覆盖、后继 Intent 未保留上下文、UUID 不符仍显示通用说明。
- 最终完整 JVM 回归 **408 项通过**，无失败／错误／跳过，见 `units85.json`。包括实际恢复协调器的清理、上下文交接、收到帧后清除，以及真实 resolver 的组件异常、路径排除和错误身份拒绝；原有取消、重试预算及证书边界继续通过。
- 原生失败页矩阵为 1512×1008、1008×1512、540×1200、960×540，后两种字体 1.3；三类错误与浅深主题共 **24 个场景、24 次真实 PcView 重建**。文字不省略，末行可滚动到达，返回／重试按钮完整可见且至少 48 dp；点击返回后设备和查询保留，配置全量指纹相同。这里使用预设错误状态，不混称为真实串流失败；记录为 `native-pages85.json`。
- 最终 `native-wire03` 通过两种主题的真实模拟器 loopback HTTP 流程：实际设备按钮 → 正式 admission／resolver → 一次 serverinfo 请求 → 错误 UUID 被拒绝 → 具体失败页 → 返回原设备。新错误取代了旧错误，重试配对按钮可用。没有调用 Sunshine、发送 PIN、建立串流或注入系统输入。结果为 `connection-error-wire-result.json`，不是实际远程主机／配对持久化的验收。
- `connection-error85.html` 是 24 张自身 View.draw 图的单文件画廊；没有网络资源、OS 截图或 computer use。

早先 `native-wide01` 在全量配置比较处失败：新临时设备缺少本端默认配置，当前主题也尚未归入共享快照。补齐测试准备后重跑整套矩阵，保留失败日志。`native-wire01`、`native-wire02` 的前置拒绝均已发生，但测试先后错误地要求全局错误为空、全局带标题错误与页面正文完全相同；核对正式 admission 的前缀约定后修正断言，最终完整流程通过。没有为这些测试改动产品行为。

## 产物和剩余范围

ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.85.apk`，SHA-256 `76EED164589A10746A6439177DF092AC9604117D628BF07DDE87E123A25DA4E1`。签名验证通过，证书和两份原生视频／传输库均与 preview.84 相同，见 `package85.json`。打包保留 preview.84／85 和正式发布件，自动清理未使用的 preview.83 APK。

本轮仍只识别到 DISPLAY26 虚拟屏，未启动 Windows 窗口或回退到主屏。Windows 的实体呈现、OPPO、真实 WebDAV／更新安装、真实断网与外网恢复，以及 RTSP 缺字节来源均继续保留。整套 UI 目标尚未完成。
