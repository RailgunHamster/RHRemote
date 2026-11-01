# Android 表单完成与 Enter（preview.119）

添加电脑和设备名称统一使用当前编辑器的提交规则：软键盘“完成”或一对有效 Enter 才提交；旧页面、禁用控件及后台页面的完成事件不处理后来打开的表单。按钮仍通过原共享动作执行。

## 问题与修正

preview.118 的两个 `OnEditorActionListener` 没有检查具体 View 的归属；添加页还接受所有动作编号。旧 InputConnection 因此可以提交新的添加表单，或保存另一台设备的名称草稿。“下一项”也会进入添加校验。原 7 项 JVM 中 5 项失败；真实 PcView 的旧添加连接也复现误提交，输入和文件前后对照保留。

`RhEditorSubmit` 处理同一套规则，`RhHome` 校验当前 Home、前台生命周期、页面、当前编辑器及可用状态，然后继续调用 `device.add.submit` 或 `device.info.save`：

- `IME_ACTION_DONE` 提交当前有焦点的编辑器。
- “下一项／上一项”交给 Android 的原生焦点导航，其余不匹配的软键盘动作不回退成提交。
- Enter 和数字键盘 Enter 的按下只建立配对，匹配设备、键码和 downTime 的非取消释放才提交一次；重复按下不重复提交。
- 焦点离开、View 移除、替换编辑器或 Home 暂停均清掉旧按键状态；返回后需要新按键。
- 未保存确认仍由原有确认动作处理。失效完成事件不保存、不换页，也不改变当前表单的错误与草稿。

[Android 的回调文档](https://developer.android.com/reference/android/widget/TextView.OnEditorActionListener)说明，Android 14 起单行编辑器的 Enter 也可能带配置的动作编号；不能只按动作编号立即保存。[TextView 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java)还显示未处理动作的 Enter 回退，以及按下／释放两侧的回调。因此保留原生输入框，只在现有回调中明确区分提交和导航。

## 最终验证

458 项 JVM 通过，0 失败、0 跳过；新增 8 项覆盖旧添加／旧名称连接、不同动作、禁用、Unicode 当前提交、Enter 配对、取消释放及暂停后的状态清除。初版测试没有调用 Home 的 resumed，前台保护正确拦住了正向提交；补齐真实生命周期再重跑旧代码，仍得到原 5 项失败，没有移除产品保护来迎合测试。

早期候选还暴露暂停后保留已按下 Enter 的问题，独立基线 `pause-guard-baseline.xml` 失败；最终增加暂停清理，重新运行全部回归及原生流程。早期 `native01`／`native02-font200` 没有此补充，不作为最终证据。

最终专用模拟器（API 35）的 `native03-final`／`native04-font200` 各含浅深主题、16 个完整表单流程，共 32 个通过：

| 路径 | 核对 |
| --- | --- |
| 旧添加输入连接 | 新表单与错误不变；当前“完成”仍能显示地址校验错误 |
| 非完成动作 | Next／Previous／Search／Go／Send／IME_NULL 不误提交 |
| 禁用、后台、空地址 | 不越过当前控件的可用状态 |
| Enter 的重复与释放 | 按下不提交，正确释放提交一次，孤立释放忽略 |
| 旧设备名称连接 | 不保存新设备草稿；当前连接正常保存 |
| Unicode 组合输入 | 组合提交后正确保存，设备选择及搜索保留 |
| 取消、焦点离开、暂停 | 旧释放不保存；表单与文件保持原值 |
| 数字键盘 Enter | 当前新按键可正常完成名称保存 |

使用真实 PcView、InputConnection 和 TextView 的应用内事件，不注入系统输入；暂停是 Home 生命周期的受控调用。未操作 OPPO，不代表物理键盘、真实输入法候选界面或系统切应用验收。添加用无效地址验证提交入口，在创建网络查找操作之前停止；没有把它当作完整设备查找、配对或串流验收。

两轮恢复模拟器 15 个配置／身份文件、原备份和字号，测试进程退出。日常 Windows 的程序与配置保持原哈希。8 张自身绘制图与记录保留在 `D:\RHRemoteTools\ui-editor-submit119-review-20261030`；关键文件为 `final-unit.xml`、`unit-suite-counts-final.json`、两轮最终原生的 `result.json`／`verification.json` 和 `final-verification.json`。

## 构建与范围

本轮修正模拟器构建脚本在仓库外检查错误 Rust 工具链的问题。原调用目录只检测到 Windows 目标，仓库工具链已安装 Android x86_64；现在检测和实际 Cargo 构建都在仓库执行。从仓库外完整构建已通过，没有另外安装目标来掩盖目录错误。

ARM64 本地候选为 `dist/android/RHRemote-v0.2.11-preview.119.apk`，版本代码 337，SHA-256 `13CC507DAB77E821DDA42A583AD734872120BA48BAAE1E7CD4E3D0B35833B3F4`。原签名和原生库对照通过，见最终核对；未公开发布或替换日常 Windows。整套 UI、OPPO、物理输入和真实连接的剩余 TODO 保留。TODO 仅移除 4 段已通过记录，全部未完成勾选项逐字保留，详见 `todo-status-cleanup.json`。
