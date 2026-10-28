# Windows 正确配对码误报与失败后的重试（preview.60）

本轮找到并修复了 Windows 原生配对代码中的二进制密钥截断。旧包在输入正确配对码时也会报“来自电脑的密码不匹配”；新包已通过相应真实样本、错误配对码拒绝、实际重试按钮、客户端重启保留配对及后续真实出画面。Android 保持 preview.57，未操作 OPPO，未公开发布。

## 原因

固定 Moonlight Qt v6.1 基底的 NvPairingManager::pair 原代码为：

```cpp
QByteArray aesKey = QCryptographicHash::hash(saltedPin, hashAlgo).constData();
aesKey.truncate(16);
```

哈希是二进制数据。constData() 得到 char 指针后再构造 QByteArray，会选择默认按零结尾字符串确定长度的重载。[Qt 构造函数文档](https://doc.qt.io/qt-6/qbytearray.html)说明了这种行为；实际 Qt 6.7.2／MSVC 回归也直接复现了它。遇到零字节，数组提前结束，后面的 truncate(16) 不会把短数组补成 16 字节。

原 AES-128 调用仍按 16 字节取密钥，因此会使用错误的密钥内容。服务器挑战校验随后失败，返回 PIN_WRONG，再被原翻译显示成“密码不匹配”。这条分支校验四位配对码及协议挑战，不是 Sunshine 管理网页的用户名或登录密码。

## 修正

- `RhPairing::deriveKey` 直接对二进制 QByteArray 结果取 left(16)，SHA-1／SHA-256 两种原有协议分支均保留全部前 16 字节，包括零字节。
- `tools/patch-pairing.py` 将正式 NvPairingManager 的同一密钥入口接到该函数。仍从固定上游基底生成，有明确锚点和重复应用检查；后续更换上游版本时可核对并移除此单独修正。
- RHRemote 的原生配对子进程在 PIN_WRONG 枚举分支直接给出“配对码校验未通过。请重新配对，并在主机输入新显示的配对码。”没有用本地化错误文字做字符串匹配，也没有跳过 PIN、签名、身份或证书检查。其他上游 Qt 界面保留原翻译。
- 正式“重试配对”按钮继续创建新请求及配对码；失败期间等待清理，成功后先提交并核对证书才返回完成，沿用此前的持久化修复。
- Android 的现有 PairingManager 使用 Arrays.copyOf(hash, 16)，没有该处 C 字符串转换；此结论来自源码检查，不等于本轮 Android 实机验收。

## 复现与真实验证

证据根目录：`D:\RHRemoteTools\ui-pairing59-review-20261028`。目录名称是本轮开始时的编号，最终正向包为 preview.60。

| 检查 | 结果及证据 |
| --- | --- |
| 固定向量 | `key-before.log`：四个独立 hashlib 公共向量在旧实现中只得到 0／7／0／9 字节，而不是 16。`key-after.log` 和正式 native60-build.log 中四项均通过；覆盖 SHA-1／SHA-256 及开头／内部零字节，正式构建加入该检查 |
| 旧包真实误报 | preview.58／旧原生 SHA `6711cac6f5e5d4dc711b2655b72a227ef612f676c249c5e24b27cedb65f9fa01`。观察实际生成的随机 nonce，第 7 次选择到第 6 字节为零的密钥条件，界面、原生参数及主机实际提交的 PIN 在内存中核对一致，仍报原 PIN 错误。`old-result.json`、`old-pin-proof.json`、`old-pair-result.png`；原 8 项授权和状态文件哈希未变 |
| 协议分支 | `server-protocol.json`：实际 appversion 为 7.1.431.-1，正式代码选择 SHA-256。初次解析把 HTTP 字节数组当 XML 对象，失败记录另存；按 UTF-8 解码后的正式结果才用于核对 |
| 修复后零字节样本 | preview.60 第 65 次观察到首个零字节索引为 8 的实际随机样本，正确配对码正常完成。`fixed-zero-result.json`、`fixed-zero-pin-proof.json`。没有固定随机数、替换 HTTP 响应或改动服务端算法 |
| 真正错误配对码 | 仅移除本轮临时授权后，从实际配对按钮进入，故意向主机提交不同的四位码；拒绝成功、正常授权不变，实际错误页显示明确提示和“重试配对”。`wrong-pin-phases.json`、`wrong-pin-result.png` |
| 实际重试 | 经实际“重试配对”控件重新发起，主机输入当前正确码后成功，原设备与搜索条件保留。`retry-correct-phases.json`、`fixed-result.json` |
| 重启保留 | 真正关闭并重开独立客户端后，原生刷新仍确认该测试身份已配对。`runtime-fixed-reopen.json`、`fixed-result.json` |
| 后续真实串流 | 同一最终包再次配对后，通过实际连接按钮取得首帧，原生统计有 18 个 decode_output／render_return，末尾 lost_events=0，随后实际断开。`smoke-result.json`、`smoke-connect-phases.json`、`smoke-toolbar-placement.json`；25 Mbps、1080p30、H.264、静音且不注入远程输入 |

所有 UI 操作使用共享动作或实际 Slint 按钮接口，图像来自应用自身绘制；窗口仍在物理 DISPLAY2 且不激活，没有 computer use 或 OS 截图。已查看真实错误配对码的最终错误页，提示与底栏按钮完整。

样本筛选仅在内存中计算当前公开 nonce 和当前配对码的哈希，记录零字节索引与一致性布尔值；实际 PIN、哈希或私钥不输出。未采用的请求经实际取消按钮及服务端签名确认清理。首轮只挑前 8 字节含零的条件，96 次未抽到；原失败及完整恢复存于 `fixed-sampling-attempt01`。随后按完整缺陷条件挑选前 15 字节含零的样本，没有降低配对校验或强制随机输入。随机抽样次数不代表产品重试次数或成功率。

最早 `.57` 那次误报缺少 nonce，不能逐样本回溯。本轮证明并修复的是能产生同类误报的确定性代码缺陷，不据此宣称所有配对失败都由这一原因造成。

## 构建、清理与交付范围

`native60-build.log`：原生正式构建、真实 RTP／FEC 队列与帧时钟、配对提交／新进程回读／原子替换失败恢复，以及新增四项密钥向量均通过。Rust 代码逻辑未改，候选 preview.59 的核心 123／UI 164 项全部通过；最终只更新版本及上述原生提示，preview.60 Release 构建和真实完整流程通过，不把旧测试计为又跑了一次。

preview.59 是内部候选，最终提示改为先重新配对、再输入新显示的码，使用新编号 preview.60，未覆盖既有包。首次 .60 打包因 Cargo.lock 版本未更新被 --locked 拒绝，更新本地包版本记录后重新构建成功，原失败日志保留。

每轮只操作独立测试身份。`old-cleanup.json`、`fixed-cleanup.json`、`smoke-cleanup.json` 均记录恢复完成；最终 `smoke-host-cleanup.json` 恢复原 **8** 项及原始 SHA `177acc4890b4b3b5449745a36288d26f2f0613bf114c5e2edf3562f51163134a`。正常与隔离偏好仍为 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`；Sunshine 服务、正常登录凭据、其他授权与业务服务未修改。

唯一串流原始统计约 35 KB，覆盖首帧及完整退出，仍用于验证新原生组件，保留完整记录。轮询观察的首帧约 2.8 秒不是物理呈现或性能提升证明，本轮不改变编码、捕获、网络或帧率策略。

本地 Windows 包：`dist/velopack-releases-0.2.11-preview.60`。便携 SHA `601082edfad804f2dc36c4fd19f47e58c7cc836681e09b480c1c2482f661e792`；新原生 SHA `65442e80437ffa5268f6d4227c026dbce4d233fb365f809de3798f2f51e85e7b`。ZIP、nupkg、实际测试 UI 及当前 Release 已逐项对照，271 项／262 文件，无私有偏好、凭据、密钥或日志。完整清单见 `verification60.json`。

此项未替代本机／claw、旧协议 SHA-1 实机、Android／OPPO、跨网络、其他自然错误、UIA、安装升级或长期稳定性验收。整体 UI 目标仍未完成，Windows 包仅本地提供，Android 继续使用 preview.57。
