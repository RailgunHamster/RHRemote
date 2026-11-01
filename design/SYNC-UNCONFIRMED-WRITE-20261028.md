# 同步写入结果未确认与状态说明

后续：Android 五个服务器提交／本机保存边界已使用既有 DAV 服务完成真实进程退出、冷启动与手动恢复，见[2026-10-30 验证](SYNC-COMMIT-PROCESS-EXIT-20261030.md)。下文保留 preview.68 当时的验证范围。

Windows 后续已在测试可执行文件中，通过正式 ConfigSync／Controller、实际文件和既有 DAV 服务验证五个对应进程中断点，见[Windows 后续记录](WINDOWS-SYNC-PROCESS-EXIT-20261030.md)；设备恢复使用替身、控件使用软件后端，不替代正常客户端的物理运行验证。

本地最终版本：Windows / Android `0.2.11-preview.68`，Android versionCode `286`。未发布，未覆盖日常 Windows 客户端；OPPO 本轮未操作。

## 原因与行为

原实现只在 PUT 返回成功后进入“等待本机应用”。如果服务器接受写入后连接超时，Windows 没有可供冷启动读取的传输记录；Android 的任务日志会在普通网络错误结束时被清除。自动同步可能继续执行，而本机合并基准仍停在上一次完成时。

两端现在在发送 PUT 前原子保存并回读本机 `pendingTransfer` 状态。状态未能保存时不发送配置。超时、传输异常、5xx 或未跟随的重定向保留待确认状态；明确拒绝本次请求的指定 4xx 可以撤销本次标记，但不能清除此前未确认的写入。手动重试重新读取服务器文件和强 ETag，继续使用原来的三方合并基准；冲突预览不写入远端。

Windows 收到成功响应后将标记转换为既有 `pendingApply`；Android 保留传输标记至本机应用和完成记录都确认。只有核对实际落盘配置并保存完成状态后，才更新合并基准及上次同步时间、解除自动同步暂停。标记只存本机，不随 WebDAV 共享。

同步卡片的状态说明按“进行中 → 存在冲突 → 结果待确认 → 失败 → 上次完成”显示。修复了底部提示结果未确认、顶部却仍写“上次同步已完成”的矛盾。Android 正常完成时保留日期，并明确标为“上次完成”。编辑、取消编辑或重建页面不会消除待确认状态。

本改动覆盖进程退出和重新打开；未据此宣称断电持久性。

## 验证

证据目录：`D:\RHRemoteTools\ui-sync-uncertain67-review-20261028`。目录名保留最初轮次，最终包及中断复验为 preview.68。

| 验证 | 结果与边界 |
| --- | --- |
| 修复前回归 | `windows-before.log` / `android-before.log` 分别复现冷启动未暂停、丢失 PUT 响应后继续允许自动同步。 |
| Windows 最终回归 | `windows-final68.log`：核心 124、UI 177 项通过；1 项既有公网更新测试按默认标记忽略，本轮未重跑该更新测试。 |
| Android 最终回归 | `android-final68-fixed.log` / `android-unit68.json`：369 项通过。覆盖写入已应用但响应丢失、没有任务日志时读取持久标记、明确拒绝不能清除旧未知结果、任务记录写入失败时不发送 PUT、冲突处理与手动恢复。HTTP 故障边界使用内存传输替身。 |
| 真实 HTTP 跨端 | `cross-client-result.json` / `android-native/result.json`：preview.67 两端连接既有 WebDAV 服务的新隔离目录。独立参数、别名冲突、预览不上传、设备顺序、自连接禁用及外端配置复制取消通过。9 次 Windows 业务控件派发与 Android 原生 View；preview.68 仅在此后补充状态说明。 |
| Windows 真实进程终止 | `interrupt-attempt04/`：preview.68 副屏隔离进程 195596 在 `pendingTransfer=true` 时终止，重开为 192372。旧基准及同步时间保留；自动请求返回 `sync_interrupted`；实际按钮手动重试后修改保留，两端文件一致。终止时服务器仍为旧文件，本次不证明“服务器已提交后才被终止”。 |
| 遗留写锁 | 上述中断后服务返回 HTTP 423，PROPFIND 未报告可释放的锁。11 次测试控制器发起的显式重试保持待确认屏障，约 59.6 秒后现有 `Second-60` 锁到期并恢复。未缩短锁、不夺取其他客户端的锁，也未增加产品自动重试循环。 |
| Android 原生冷启动与页面 | `android-death68/verified.json` / `android-death68-verify.log`：专用模拟器真实终止并重开应用（5133 → 5331），自动请求跳过、取消编辑保留提示、主题切换产生实际 Activity 重建，浅深色 View.draw 图及提示可见性通过。这次在已排队但尚未执行的任务阶段终止，网络请求为 0，不能算 Android 真实传输中断验收。 |

早期 `interrupt` 轮次未捕获到极短标记窗口；attempt02 捕获后立即重试遇到遗留锁，均未计为完整通过。attempt03 验证恢复后发现顶部仍显示旧成功说明，补改 preview.68，并以 attempt04 完整重跑。失败记录与原图保留。

Windows 图由应用渲染器导出；Android 图由自身 View.draw 生成。未使用系统截图、系统输入注入或 computer use。Windows 保持 `DISPLAY2`、窗口不激活。用户授权重启的 emulator-5580 已按原 AVD 正常恢复且保留数据，本轮未再次重启模拟器。

## 还原与产物

`dav-cleanup.json` 确认仅删除本轮两份隔离文件及空目录，删除受独占写锁保护。日常 DAV 文件 SHA-256 仍为 `7c649603e2c57a7736169533f51d803f2d631108082be29cb13a0365f3424917`。

日常 Windows preferences SHA-256 保持 `030b64999823febb5fa55271883b454a05867f4aaa57264739bb06236f7f8092`；模拟器 shared 配置保持 `fe08b5761c960bdd24fb980a58aac5ab4c58d8698bae1a3d2536270ae273a0d8`。隔离 Windows 凭据已移除，Android 测试加密账户、配置、主题和任务日志均还原。Sunshine、显示布局、声音及画质设置未改。

| 最终产物 | SHA-256 |
| --- | --- |
| `dist/velopack-releases-0.2.11-preview.68/RHRemote-win-Portable.zip` | `2c25f8b3168bfd58ec2d21f3c0d7d8528d6c2d8b3d2f2c41824682c9adb2cf52` |
| `dist/velopack-releases-0.2.11-preview.68/RHRemote-win-Setup.exe` | `6ddb93c3166e5e9a240a6d5d45ba170fa5229ca979d8714707c121b283bce614` |
| `dist/android/RHRemote-v0.2.11-preview.68.apk`（ARM64 / 286） | `474d81e4cad8febf99c45359f5594b007ebdb35e4cb1544c2c7738eefbd5bce0` |

APK 签名校验通过，证书保持 `2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc`。原生 Windows 引擎保持此前已验收的构建（SHA-256 `65442e80437ffa5268f6d4227c026dbce4d233fb365f809de3798f2f51e85e7b`）。

真实 HTTPS 窗口、服务器确已提交后的进程终止、本机应用阶段终止、Android 真实传输中断、实际自动间隔计时和 OPPO 等仍在 TODO，未以单测或模拟器排队中断代替。
