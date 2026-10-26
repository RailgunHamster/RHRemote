# 0.2.11-preview.4（本地预览）

Windows 与 Android 的连接设置和 WebDAV 设置改为完整表单流程：分类切换保留输入，固定保存 / 取消栏，离开未保存页面时选择保存后继续、放弃更改或继续编辑。数值输入不再在每次按键时强制转换；保存失败保留内容，并显示相应分类和字段错误。

Android 修改单个选项时更新原控件，避免整页重建造成焦点和滚动位置丢失。同步页保存栏与连接设置对齐；未修改时保存按钮禁用并使用灰色外观；同步期间禁用编辑，避免输入被后台完成操作覆盖。Windows 的输入框与加减按钮不再重叠，表单限制宽度，按钮和分类补充键盘操作及焦点边框。

设备别名和复制其他客户端配置放在高级分类。应用设置保持“更新 → 配置同步 → 外观 → 关于”。新增控制动作 `navigation.back`、`navigation.resolve`、`settings.input`、`settings.step`、`sync.edit`、`sync.save`、`sync.cancel`；界面与控制 API 使用相同提交路径。WebDAV 草稿密码不导出到状态 API。

预览包：Windows Velopack 安装包 / Portable / 完整更新包；Android ARM64 Release APK，versionCode 229，沿用现有签名。preview.3 是中间校验构建，未覆盖它的文件；preview.4 修复了该构建中安卓同步保存栏的对齐和禁用外观。

本轮没有修改串流核心和 Sunshine 服务，也没有操作 OPPO。完整 UI 流程尚未全部完成，详见 [UI-FLOW-SPEC.md](UI-FLOW-SPEC.md) 的逐流程验收要求；Windows 无障碍焦点和添加 / 连接 / 会话页仍有后续工作。本预览未发布到 GitHub / Gitee 更新源。
