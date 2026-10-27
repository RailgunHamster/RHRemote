# 屏幕接口 EOF 与视频表面隔离验证

本轮继续使用应用接口、Instrumentation 和应用自身 SurfaceView；没有 computer use、系统输入或系统截图。Android 产品保持本地 preview.37，Windows 保持 preview.47；OPPO 未操作，未公开发布。

## 实服务响应截断

使用 emulator-5580 的既有配对证书，通过正式 RhHost.request 访问 winserver。健康接口确认统一服务 PID 43628、Sunshine PID 27488；服务文件 SHA-256 为 `C64828237B0C3BA3A0FF92DEEC2CADABB27C79C2DDA09AC45C8EE2DD70E99443`，与已验证安装包一致。

异常栈位于 Android Http1xStream.FixedLengthSource.read：收到 Content-Length 后读取正文提前结束，而不是连接超时。增加原始 SSLSocket 与 HttpsURLConnection 两条对照，均使用原客户端身份和相同主机证书固定校验，不导出私钥，不降低信任要求。

`host-display-rpc05/host-display-probe.json` 的六组请求中，原始 TLS 三次声明约 1.4 KB 正文但实际收到 0 字节；HttpURLConnection 四次同样失败。随后正式自动选屏 POST 也返回 EOF。此前另有完整成功样本，所以不能将其表述为接口一直不可用，或只归因于 Android HTTP 库。测试默认仅查询；显式 prepareAuto 只在已有可捕获物理屏时，以该屏当前尺寸请求 auto，不新建、缩放或切换屏幕。

一次探针因测试代码将 HTTP 状态行的 null 键直接转换为 JSONObject 而失败，已修正；该次不是产品缺陷。最终探针逐项检查所有原始／HTTP 响应长度，避免只依据最后一次请求成功判为通过。

## 服务端关闭流程修复

正式响应发送函数独立到 http_response.rs。旧发送逻辑的真实 TLS 回归在 200 UTF-8 多记录正文和 409 错误正文两项均失败：客户端检测到缺少 TLS close_notify 的非正常结束。现在合并响应头和有界正文发送，排入 close_notify 后 flush。服务端 23 项测试通过；此前失败日志保留。Release 运行时已构建，SHA-256 为 `C98794802D4A957CC68FF2DBFBB450BDFFACFBBCE0CE8E55C3D0B1598540B3D3`。

这证明正常 TLS 结束通知的缺失已修正，**不单凭该测试认定实服务正文截断的全部根因已经解决**。仍需部署后的相同原始 TLS／HTTP／自动选屏请求对照。没有放宽证书／身份规则、增加自动重试或加长超时。

## 生成画面隔离验证

RhVideoSurfaceDeviceTest 不使用远程桌面内容：在应用自己的 SurfaceView 绘制灰色基线，使用 c2.android.avc.encoder 编码三帧生成灰色 YUV，再由 c2.android.avc.decoder 分别解码到 Image 和新 SurfaceView。

真实原生测试通过：Canvas 与视频各 23814 个取样点全部非黑；三帧缓冲解码的 2760 个亮度样本均值 Y=200，表面收到三次呈现回调。没有重启模拟器、注入输入、播放声音或改变产品解码器选择。应用偏好前后相同。

这排除了“当前无窗口模拟器完全不能显示任何解码视频”的假设，但不代表此前两条真实远程会话已经通过。仍需区分实际远程编码内容、会话格式及其呈现路径，保留原非黑画面断言和失败样本。

## 证据

目录：`D:\RHRemoteTools\ui-session-api-review-20261028`。

- host-probe-native01～05.log、host-display-rpc01～05：正式接口、原始长度及间歇失败。
- host-response-before.log／host-response-after.log：旧关闭流程两项失败，新实现 23 项通过。
- host-response-release-build.log：本地 Release 构建。
- video-surface-native01.log、video-surface-generated01：生成内容的真实编解码、两张自身 Surface 图与亮度记录。

当前真实远程黑屏仍未通过，完整 UI 目标保持未完成。部署及实服务复验结果应另行记录，不能用本地构建或生成画面替代。
