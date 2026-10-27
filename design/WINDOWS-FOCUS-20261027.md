# Windows 禁用按钮残留焦点

## 复现与原因

preview.32 的应用内测试发现：聚焦“保存”，将草稿改回原值使按钮禁用，再聚焦宽度输入框；之后编辑内容重新启用保存，两个控件都报告焦点。保存的旧边框可能重新亮起，但键盘输入已进入输入框。旧版真实 Release 记录有四处多个焦点标记，不能把原先通过的业务动作检查当作焦点唯一性证明。

新增控件回归在修复前得到 `input|画面宽度` 和 `action|settings.save||保存` 两个标记。最初点到同名文本标签的失败单独保留，最终复现明确找到 `accessible-read-only` 存在的真实输入控件。

源码依据为固定依赖 Slint 1.18.1 的 `i-slint-core/items/input_items.rs`，`FocusScope::focus_event` 在匹配事件之前检查 `!self.enabled()` 并返回 `FocusIgnored`。因此禁用后的旧节点收不到清除 `has_focus` 的 FocusOut；随后重新启用，旧值仍为 true。这与确认框销毁后原输入焦点丢失是两个独立问题，后者已在 preview.32 修复。

## 应用层调整

preview.33 的自定义按钮、侧栏和会话菜单行保持 FocusScope 接收焦点通知；用 `focus-on-click`、`focus-on-tab-navigation` 限制禁用控件的点击与 Tab 进入。原有 TouchArea 可用状态、键盘动作判据、无障碍可用状态、接口验证及确认框限制全部保留。显式程序聚焦也先检查控件可用状态。

这样旧节点在禁用时仍可处理 FocusOut，而禁用动作不会提交。不修改 Slint 的全局依赖，也没有只在测试结果中隐藏旧标记。

## 验证范围

对应回归先失败、修改后通过。最终 Rust 核心 100／UI 113 项测试通过，包含禁用后的 Enter／空格、指针操作、侧栏锁定、会话菜单失效、确认框恢复和新表单初始焦点。

应用运行流程使用 `tools/Test-RHRemoteUiKeyboard.ps1`，在每一步额外要求最多一个焦点标记；完整运行和包哈希见 `D:\RHRemoteTools\ui-api-testing-review-20261027\verification-preview33.json`。

这只覆盖 RHRemote 自定义控件和已列出的应用内流程。Windows UIA 报告、标准库控件禁用的其余情况及全控件 Tab 顺序仍需核对；未恢复 computer use，未把应用内事件称为系统按键或物理输入验收。
