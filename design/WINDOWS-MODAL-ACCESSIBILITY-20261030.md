# Windows 确认框的可用状态与输入恢复

Windows 本地候选 preview.135 修正未保存确认／移除同步设置确认的辅助功能可用状态。日常 preview.101 未替换且保持关闭；Android 仍为 preview.129，未重新构建或操作 OPPO。本轮没有建立主机串流、重启服务或公开发布。

证据目录：`D:\RHRemoteTools\ui-accessibility-current134-review-20261030`。名称包含发现问题时的基线 134，最终验证与产物均明确标为 135，不混用版本。

## 现象与实现

preview.134 的未保存提示已经拦截鼠标背景区域和本地按键，但实际 Windows UIA 查询仍将后方 **38 个**控件报告为 enabled=true。确认框自己的三个按钮和这些后台控件同时显示可用。`unsaved-passive.json` 与 `unsaved-widgets.json` 保留发现现场；窗口未激活，因此该证据只证明可用状态，不证明系统焦点错误。

修正依据 [Windows UIA IsEnabled 定义](https://learn.microsoft.com/en-us/windows/win32/winauto/uiauto-automation-element-propids)：可用状态须反映控件能否被交互。应用使用 Slint 已有的 [accessible-enabled 属性](https://docs.slint.dev/latest/docs/slint/reference/common/#accessible-enabled) 表达该状态，没有修改上游框架或新增弹窗操作路径。

- 主窗口为当前确认类型设置局部 ModalContext，仅允许对应的既有 navigation.resolve 或 sync.clear.resolve 动作；正常页面时解除限制。
- 按钮、导航、设备行和输入／选择控件向辅助功能报告被当前确认阻挡；自定义按钮／导航／设备行的迟到回调也检查同一上下文。背景滚动暂停，确认框自身按钮保留既有可用条件。
- 保留原输入控件及其草稿。恢复内容焦点前先解除上下文限制，沿用原有焦点还原机制。
- 没有用禁用自定义 FocusScope 的方式实现，以保留此前修正的 FocusOut 交付；保存、移除和取消仍走原后台队列与请求 ID 校验。

本轮生产改动只有 app.slint 的交互／辅助功能绑定，以及 Windows 候选版本号；Core、原生串流引擎、服务端和 Android 产品代码未改。控件接口的可用状态同步使用相同条件，不另建一套弹窗。

## 回归与最终包

新增两项正式 Slint／Controller 集成回归，在旧实现均稳定失败于后台导航仍为可用。修正后两种主题均核对：背景不可用、当前决定可用、迟到背景默认动作不进入队列，以及取消确认后恢复原本可用／禁用状态。未保存场景还向原编辑器实际输入字符，确认草稿更新且存储未提交；同步场景确认原账户不被移除。

完整 Rust 回归：Core **136**、UI **252** 项通过，零失败，另 **4** 项既有专项用例忽略，见 `full-rust135.log`。

最终 Release Portable 在实体 `C3273IUY / DISPLAY2` 上完成浅深主题 × Enter／空格四组流程，**371 个检查点**通过，见 `native135-02/verification.json`。包括保存／放弃／继续编辑、取消／移除、正反向 Tab、长按过滤、输入焦点恢复及磁盘结果。应用内空格输入已在实际编辑器产生唯一 settings.input 动作，再恢复原草稿；没有注入系统键盘。

十六份实际只读 UIA 快照均来自本次进程的精确窗口句柄，确认框可用控件恰好为三个或两个，背景无误报可用项；窗口全程未激活。另导出 **16 张自身绘制图**，没有 OS 截图。人工查看的浅色未保存、深色移除确认布局完整；未声称逐张的所有视觉边界均通过。

[浅色确认框](D:/RHRemoteTools/ui-accessibility-current134-review-20261030/native135-02/light-Enter-unsaved-stay.png) · [深色移除确认](D:/RHRemoteTools/ui-accessibility-current134-review-20261030/native135-02/dark-Enter-sync-remove.png)

首次最终包尝试 `native135-01` 的 38 个已通过检查及失败记录保留：外部测试脚本要求按键接口输入字符 7，但该接口只接受列出的导航键，调用在脚本参数校验时停止。实际 UIA 已只有三个决定可用。后续用同一最终包、全新隔离数据和既有空格键输入检查完成原本的焦点恢复要求，没有放宽按键接口或修改产品来迁就脚本。软件回归继续使用数字字符。

## 还原、产物和剩余范围

最终核对四份冻结构建来源、候选包和自身图像。假 WebDAV 账户仅保存在隔离目录对应的本地凭据项，自动同步关闭，未执行同步；最后通过原按钮移除。原三台设备及其他客户端参数、主题与选择还原，独立窗口正常关闭，未强制结束进程。

日常客户端 UI／引擎、preferences、配对 INI 和 WebDAV 文件五份摘要不变；claw 四份及 winserver 八份配对授权文件摘要不变，Android ARM 候选不变。`final-verification135.json` 记录范围，完整 UI 目标明确为未完成。

候选：`dist/velopack-candidates/0.2.11-preview.135`。

| 产物 | SHA-256 |
| --- | --- |
| UI | `491A0E373325AEC25117BB49A31D6294C0C34A67D98954966E730E82EEE1819E` |
| Portable | `ADD8FB044344D193AFABCE29677EDC74CDAB0A7C526A50754D46CFA11CCF3619` |
| Setup | `C59D3E415D0000378E0923F08EEF2FD5CEA0A99AFEB05C7AD82096541B91FFE6` |
| nupkg | `4E9033E613111EC76E84B02EAB58E921091C5BD58118B78D139E54F6071FDB75` |

原生引擎仍为 `C789BFDF5619184428E764E4C6A785F0BD90F89130CE720BD2A906C49FC0D5EA`。

打包后统一清理工具移除超期 preview.133 和旧 staging，逻辑字节 **268,227,852**；实测空闲增加 **268,812,288** 字节，检查时 D 盘余量 **950,823,055,360** 字节。当前／上一候选、明确保护的 110 对照及已部署服务保留。

实际活动窗口中的 UIA 焦点一致性、物理键盘／输入法、长时遮挡与跨屏 DPI、真实连接／网络及最终安装交付仍按 [TODO](../TODO.md) 保留。本轮的非激活状态、实际控件和自身绘制不替代这些项目；本机／claw 服务更新确认也未据此获得。
