# Windows 原生设备检查：协议对照与认证提示

本轮为本地 Windows preview.151 候选版。范围是实际原生引擎的设备检查，不包含三台真实主机的配对、串流或外网验收。

## 已复现的问题

preview.150 原生引擎通过保存的测试证书完成 HTTPS 后，收到 HTTP 状态 401，报告却是 `unavailable`。Rust 因而无法显示“认证失败”和“重新配对”。返回 HTTP 200、XML 内 `status_code=401` 的情况则已能正确识别。

原因是两条异常路径不同：XML 401 产生 `GfeHttpResponseException`，进入已有 HTTP 回退；HTTP 401 产生 Qt 的 `AuthenticationRequiredError`，直接抛出 `QtNetworkReplyException`。原来的观察器只覆盖前一条路径，后一条由析构报告成未确认。

修复只记录已有请求的结果：在 `encrypted` 回调确认对端等于本机保存的固定证书后，遇到实际 HTTP 401，记录 `pinnedHttpsAuthRejected`。异常原样继续抛出，不增加回退、重试或网络请求。错误正文没有 UUID 时，`actualUuid` 保持空值，不把目标 UUID 填成已收到的事实。

Rust 仅在本次编号、目标、HTTPS 来源、已保存身份和上述固定证书证据一致时接受该认证拒绝；旧报告默认没有新字段。无证书的 HTTP 401、TLS 失败和证书变化均不适用此特例。原生报告和界面分类不会替用户删除身份文件或绕过证书验证。

## 验证方法与证据位置

`D:\RHRemoteTools\ui-protocol151-review-20261031` 保存计划、基线、修复后结果和最终审核。测试工具是 `tools/test-windows-probe-protocol.ps1`、同名 Python 文件和 `test-windows-probe-identity.cpp`。

工具新建受限目录、生成专用客户端及服务端证书，在 `127.77.0.2` 上独占绑定随机偏移端口，启动实际 `RHRemote.exe list`。HTTPS 服务要求测试客户端证书。只允许 serverinfo、原生轮询可能请求的 applist 和辅助 network 元数据；不发送 PIN，不启动串流，不控制桌面，不联系真实主机。Qt 使用 offscreen 平台。

原生子进程采用现有检查的五秒预算；取消或预算到期仅终止本次持有的子进程句柄。慢元数据样本检查“配对报告先发布”，取消样本还确认辅助连接收到 EOF。测试成功后删除专用 INI 和生成的私钥，保留公开证书和脱敏协议记录。

基线 `baseline-matrix01/result.json` 的 11 项中，只有 HTTPS HTTP 401 分类失败；`ui-protocol151-red-replay01.log` 对旧报告的 Rust 重放也在该项失败。修复后 `fixed-matrix01/result.json` 的 13 项原生检查通过：

| 实际场景 | 报告结果 |
| --- | --- |
| HTTP、没有保存证书 | 未配对 |
| 固定证书 HTTPS、已配对／未配对 | 已配对／需要重新配对 |
| HTTPS XML 401／HTTP 401 | 认证失败 |
| 保存证书与对端不同 | 证书不符 |
| TLS 端口不是 TLS | 安全连接失败 |
| HTTPS 返回不同 UUID | 设备标识不符 |
| TLS 响应超时 | 状态未确认，由现有进程预算转为检查超时 |
| 附加元数据迟迟不返回 | 已配对结果先发布 |
| 已发布配对后取消元数据 | 保留配对事实，本次连接结束 |
| HTTP 401、没有保存证书 | 状态未确认，不推断为认证拒绝 |
| 变化后的证书尝试返回 401 | 证书不符，不被 401 掩盖 |

基线服务创建曾触发 Python HTTPServer 的反向 DNS 等待；修复后的测试夹具直接使用已知回环地址。该等待属于测试准备，不是客户端耗时。记录中的 `firstReportNs` 从创建原生子进程前开始，`elapsedUs` 是原生 serverinfo 事务，两者都不包含测试服务创建时间，也不代表串流首帧时间。

## Rust 与界面检查

工作区回归 497 项通过、5 项跳过；随后显式启用其中新增的原生报告重放，13 份实际报告通过 Rust 的编号、UUID 和状态证据校验。实际 Slint 主按钮还通过浅深两主题、720／1180 两种窗口宽度的 32 个状态检查及应用软件渲染，HTTPS 认证拒绝显示“认证失败”并只提供“重新配对”；证书不符、TLS 失败和 UUID 不符保持各自的按钮逻辑。图像中的设备状态是已校验协议结果对应的界面夹具，不是 winserver 的当前状态。

首次优化构建的全量控件测试因缺少 Slint `ElementHandle` 所需元数据而有 74 项失败，原日志保留在 `ui-protocol151-workspace01.log`。既有 `build.rs` 已支持 `SLINT_EMIT_DEBUG_INFO=1`；启用后重跑的最终日志是 `ui-protocol151-workspace-final02.log`。测试没有修改断言或产品行为来消除该失败；候选安装包仍关闭该测试元数据。控件证据和正常发布构建的边界分别记录，不能将控件检查称为实体窗口验收。

## 仍需完成

真实三台主机及跨网络、完整同步与原生检查联动、物理窗口与最终用户升级仍保留在 TODO。回环 mTLS 证据不能替代真实服务器、OPPO 或首帧验收。本轮没有发布或替换日常客户端。
