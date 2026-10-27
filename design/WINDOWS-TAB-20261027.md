# Windows 页面与 Tab 顺序验收

## 设备流程

Windows preview.33 的打包程序通过应用接口完成 55 步实际控件流程：三台设备的单次选择、自身连接按钮禁用、已存 Tailscale 地址搜索、无匹配结果、刷新、连接设置五分类、返回保留搜索与选择、搜索状态下排序、首位禁用及应用设置四分类。排序恢复后，测试配置与日常配置的文件 SHA-256 均保持原值。测试没有发起配对或串流，也没有执行同步。

参照仍是 HelpWire 的[设备列表](https://kb.helpwire.app/client-management/client-list/)和[独立资料入口](https://kb.helpwire.app/client-management/edit-client/)。手动排序、多地址搜索及复杂参数草稿属于 RHRemote 的已有要求，不声称是 HelpWire 文档规定的行为。

## 发现的焦点问题

逐页遍历 preview.33 的所有已启用控件，12 个页面／分类中的 11 个完整可达；网络页漏掉“中继服务器”。接口显式聚焦可以到达该字段，等它已经滚入视图后 Tab 也可到达；从页面顶端依次遍历、字段尚在视图外时会被跳过。相同场景已用实际网络表单在控件测试中复现。

固定依赖 Slint 1.18.1 的 `ItemRc::is_visible_or_clipped_by_flickable` 先把输入框内部裁剪区与外层裁剪相交，再决定哪个祖先隐藏了控件。整行位于 Flickable 视图外时，内部裁剪的相交结果为空，代码过早将它判成普通裁剪，未能进入滚动揭示路径。

preview.34 保持 Slint 1.18.1 的其他实现和功能开关，只回移[已核对的上游算法](https://github.com/slint-ui/slint/blob/cffe9dc85733c901b39b9523c2e701e83ac57b29/internal/core/item_tree.rs)：区分祖先自身边界和累积裁剪。正常可见控件的快速路径不改；没有为测试强制跳到字段或另做焦点顺序。源码以单独的 Cargo override 保存，原发布 crate 的 SHA-256 与之前的锁文件一致，仅 `item_tree.rs` 有改动；来源和后续升级方式见 [补丁说明](../client/vendor/i-slint-core/RHREMOTE-PATCHES.md)。独立差异保存在 `patches/slint-1.18.1-nested-clip-tab.patch`。

## 回归与证据

- 网络表单回归在修复前失败、修复后通过。另一个实际 Slint 控件夹具验证正反向 Tab、自动滚入完整输入区域，并跳过真正被普通裁剪隐藏、不可见或禁用的输入框。早期夹具试图使用未编译进宏组件的调试元数据，失败在选择器；修正夹具后按真实 Flickable 偏移核对固定输入矩形，未改变产品代码来迁就夹具。
- 最终 Rust 核心 100／UI 115 项测试通过。完整打包程序的正反向页面遍历及配置核对保存在本轮验证清单中。
- `tools/Test-RHRemoteTabOrder.ps1` 从当前实际控件生成可用集合，分别走 Tab 和 Shift+Tab，检查可达集合、唯一焦点及两方向顺序互为逆序。它先检查隔离测试接口可用，不向普通实例发送导航操作；不发送 Enter、不修改字段或启动连接。

证据目录为 `D:\RHRemoteTools\ui-device-flow-review-20261027`，包含 preview.33 的明确漏项、修复前失败日志、依赖来源核对、preview.34 的运行记录、界面导出和 `verification-preview34.json`。不激活窗口，不发送系统输入；截图来自应用自身渲染器。

这里覆盖列出的闲置页面及控件。连接／恢复中的控件、复制预览、确认框、活动会话工具窗、Windows UIA 和其他布局边界仍按完整规范及各自证据验收；不能据此将 G1、G2 或完整 UI 目标全部标记完成。Android 本轮无改动，OPPO 未操作，安装包仅在本地。
