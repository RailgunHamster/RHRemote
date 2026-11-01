# Windows 确认与关闭的后台派发

Windows 当前本地候选为 preview.134，日常 preview.101 保持关闭且未替换；Android 仍为 preview.129。本轮没有操作 OPPO、建立主机串流、发起 WebDAV 网络请求或公开发布。

## 问题与原因

未保存提示和移除同步设置提示的按钮原本通过共享 Commands 回调进入工作队列，但原生键盘过滤器及应用按键接口调用的 modal 函数直接执行 Controller.dispatch。未保存确认还会同步保存，并继续执行先前的连接等动作，因此其平台调用可能占住界面线程。系统关闭回调也直接调用 window.close，后者可以等待原生引擎清理。

这是一条客户端事件回调中的阻塞路径，不等于全部首帧或连接耗时的原因。

证据目录：`D:\RHRemoteTools\ui-modal-queue134-review-20261030`。`baseline-modal-queue.log` 保存 preview.133 旧路径的明确失败：Enter 确认后，共享命令队列仍为零项；实际已经绕过按钮的派发入口。该用例使用正式 Slint 组件和 Controller，平台是内存替身，没有真实连接。

## 修正

- Enter、空格和 Escape 的确认统一发出原有 Commands 动作，复用按钮回调、当前请求 ID 捕获、后台队列和控制器校验。Tab／Shift+Tab 仍只改变当前焦点，重复确认和松开不重发。
- 原生窗口关闭回调只投递 window.close 并保持窗口，沿用控制器原有未保存提示、原生清理、失败保留窗口和最终退出规则。清理结果仍由原有状态／窗口动作决定。
- 应用按键接口不再手动重复记录一次确认动作，改由同一个 Commands 回调记录，避免一次按键报告两条命令。
- 旧请求不得确认新弹窗。相关回归现在明确排队、再执行并检查拒绝结果，不用“尚未执行”冒充隔离成功。

本轮没有更改 Core、协议、原生引擎、Slint 页面布局或上轮表单绑定。

## 验证

`full-rust-tests01.log`：Core **136**、UI **250** 项通过，零失败；另 **4** 项既有专项用例保持忽略。

新增两个回归用受控门闩暂停平台的 device.connect 或 engine.close。后台线程仍停在平台调用期间，原 UI 线程可读取状态并绘制正式 Slint 窗口；连接例还处理 Tab。记录明确核对平台线程不是 UI 线程，工作尚未结束，释放后检查保存／关闭结果。这验证同线程阻塞路径已移出事件回调，不是硬件连接性能测试。

最终 Portable 在实体 C3273IUY / DISPLAY2 完成浅深两主题 × Enter／空格共四组实际窗口流程，**315 个检查点**通过，见 `native-current01/verification.json`。包括：

- 三种未保存选择、默认安全焦点、正反向 Tab 和按键重复过滤。
- 实际保存后读取隔离 preferences.json 核对参数，随后还原原值。
- 在隔离目录创建不启用自动同步的本地假账户，验证移除的取消和确认，再清理账户；没有复制用户凭据或请求假地址。
- 每次控件操作核对窗口仍在副屏且未激活，无系统输入注入。每次确认只记录一次对应动作，再等待控制器及磁盘结果，不能用按键请求 complete 替代业务完成。

四组共导出 **16 张应用自身绘制图**，清单为 own-render-manifest.json。人工另核对浅色未保存提示和深色移除提示，文字与按钮完整可见；没有逐张声称全部布局边界通过。

系统物理键盘、真实窗口 X／Alt+F4、活动串流清理、真实网络长尾、UIA 和 OPPO 仍保留各自实测范围。软件门闩测试不替代这些范围，完整 UI 目标尚未完成。

## 产物与还原

本地候选：`dist/velopack-candidates/0.2.11-preview.134`。

| 产物 | SHA-256 |
| --- | --- |
| UI | `D1A8235A75916C0B63BAE9CDFCFB5202DCEA7EF600B82D9153A923B78D4B4379` |
| RHRemote-0.2.11-preview.134-full.nupkg | `E9910E0E821C6EA97FB94ABC3B6DFC3769E8F5786986690F073AC00769273221` |
| RHRemote-win-Portable.zip | `3CAFBAE3A045504FCAE14DD95B11277A439B93F32452110F3ECB8C3ED3E441C3` |
| RHRemote-win-Setup.exe | `720B150DD2756B2E621FBF449248363A9370EBFE17AA4F3FEAC041D7847EBAB6` |

原生引擎仍为 `C789BFDF5619184428E764E4C6A785F0BD90F89130CE720BD2A906C49FC0D5EA`。final-verification.json 核对六份冻结构建源文件、五份未改动的表单／布局来源、候选包和十六张自身绘制图；隔离偏好字节已还原，日常客户端五份文件和服务端配对状态未改变，测试进程已关闭。

打包使用统一工具清除超期 preview.132 与旧 staging：文件逻辑字节 **268,140,336**，清理前后实测可用空间增加 **268,722,176** 字节。当前／上一候选、明确保护的调试对照、已部署服务端及 target/release 保留，详见 cleanup-verification.json。
