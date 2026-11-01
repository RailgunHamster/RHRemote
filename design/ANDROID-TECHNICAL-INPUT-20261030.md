# Android 地址、搜索与用户名输入

preview.113 按字段用途配置键盘信息：连接地址、备用地址、中继和 WebDAV URL 使用 URI 类型；设备搜索使用筛选类型；用户名保留普通文字组合输入。技术字段不启用自动纠错／自动大写，并请求输入法不据此更新个性化数据。沿用原输入、保存、取消和校验动作，没有添加文字重写或地址替换规则。

用途与标志参照 Android 官方 [InputType](https://developer.android.com/reference/android/text/InputType#TYPE_TEXT_VARIATION_URI) 和 [EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)。URI／筛选字段在实际 TextView 中已禁用框架拼写建议；没有设置 `TYPE_TEXT_FLAG_NO_SUGGESTIONS`，为中文组合与候选保留输入法空间。用户名继续允许候选，设备别名及密码保持原输入用途。个性化数据标志属于对输入法的请求，本轮未检验 OPPO 输入法是否遵守它。

新增内部 `RhTextInput` 统一这些配置，保留字体和现有选区。添加设备原有的完成键继续提交，保留其动作标志。各字段仍通过原共享动作更新草稿。

## 旧版复现与原生验证

证据根目录：`D:\RHRemoteTools\ui-technical-input113-review-20261030`。

| 用例 | 结果与范围 |
| --- | --- |
| `technical112-baseline02` | 已核对实际安装文件是先前验证的 preview.112。浅深两种主题、9 个字段／对照共 18 份 EditorInfo；其中 14 份用途／标志不满足新规则。地址和搜索均为普通文本，框架拼写建议开启；用户名缺少不更新个性化数据的请求。旧版原文编辑、保存和取消流程也走完，未观察到输入被改写。 |
| `technical113-final03` | 正式 PcView、两主题相同 18 份信息均通过。主机 IPv6、备用地址、HTTPS 中继、HTTP WebDAV 编码路径、含中文与大小写的用户名保存正确；中文／emoji 别名、组合文本、局部替换及光标位置保留。连接参数和同步表单的取消均保留已保存值；添加页完成键的非法地址提交留在原页。 |
| `nav113-font200-final04` | 真正系统 fontScale 2，40 个页面检查点、6 次窗口横竖变化通过；完整可见控件文字裁切 0，草稿／确认／设备切换等流程继续通过。 |
| JVM | 最终生产源码 426 项通过、0 失败／错误／跳过，见 `build113-debug-tests.log`、`unit-counts113.json`。 |
| 最终核对 | `final-audit113.json` 的 47 项通过，另从原始 EditorInfo 数值独立核对输入类型、大小写／纠错标志及个性化数据请求；实际安装文件与最终测试 APK 的 SHA-256 相同。 |

用例直接调用应用内实际 EditText 的 InputConnection，包含 setComposingText、finishComposingText、局部选区 commitText 和完成键；没有系统输入注入或系统截图。它证明控件接受和保存所提交的文字，未替代 OPPO／实体键盘逐键输入与候选界面验收。

第一轮 `technical112-baseline01` 的测试选择器未处理空 ContentDescription，未进入字段流程；该失败及恢复记录保留，修正仅在测试类。有效旧版复现为 baseline02，没有把测试选择器异常归为产品问题。

## 数据与安装包

测试使用专用虚构设备和虚构同步账户，只保存本机配置，不启动串流、WebDAV 传输或更新下载；PcView 可继续正常发现已保存设备。没有读取正常 WebDAV 密码。测试进程退出后，独立核对原 14 份配置／身份／数据库文件及原同步备份恢复；系统字号回到 1.0，显示覆盖保留 1512×1008／240 dpi。日常 Windows preview.101 和三份配置、winserver 配对身份文件保持原哈希。

最终 ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.113.apk`，versionCode **331**，非 debuggable，SHA-256 **C37C4160A17C57AF8AFD2DC11B5E9C7C384413A43E5D33F65A5C5E9634A1C08A**。签名与既有版本一致，两个原生库与 preview.112 相同，准备目录中的三个生产源文件与仓库一致。Release 未公开发布、未向 OPPO 安装；原生验证使用同版 x86_64 Debug。实际输入法候选／键盘布局和完整 UI 其余范围继续保留在 TODO。
