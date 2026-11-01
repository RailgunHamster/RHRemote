# Windows 弹窗的空格确认

本地 preview.130 修复“保存更改／移除同步设置”弹窗中，按钮有焦点但空格无效的问题。普通 ActionButton 已处理空格；公共弹窗入口却先截住全部按键，仅处理 Enter、Tab 和 Escape，原生入口也把空格映射为 Other，因此按钮收不到该事件。

## 修改

`ui_keyboard::modal` 让 Space 与 Enter 走相同的确认动作及当前请求标识校验。仅在首次按下时执行；重复事件与松开不重复确认。原生事件入口复用 `native_key_name`，同时识别 NamedKey::Space 和字符空格，其他文字仍为 Other。Enter、Tab、Shift+Tab 和 Escape 处理保持原规则。

没有修改按钮位置、绘制后端、原生视频引擎、服务或网络协议。新增回归也检查旧弹窗的 Space 不能保存后继草稿或移除后继账户，与已有 Enter 归属检查并行。

## 旧版与新版

证据目录：`D:\RHRemoteTools\ui-modal-space130-review-20261030`。

- `baseline-space-tests.log`：旧实现两个新增流程测试均失败，Space 返回 None，导航／同步确认均未执行。
- `baseline-native03.json`：最终 Portable preview.125 在实体副屏不激活窗口中复现同一问题；实际 Space 的命令数为零，未保存弹窗原样保留，之后还原配置并正常关闭。
- `full-rust-tests02.log`：Core **136**、UI **239** 项通过；UI 另 **4** 项既有专项用例保持忽略。不是整个 workspace 或系统物理键盘验收计数。
- `native-current03/verification.json`：最终 Portable preview.130 浅深主题完成 **149** 个检查点，八张应用自身渲染图。每步核对 DISPLAY2、不激活窗口、没有系统输入注入。

实际窗口流程包含三个未保存选择：默认空格继续编辑，Tab 后空格放弃，Tab 两次后空格保存并继续；回读独立 preferences 确认保存，再恢复原 FPS。移除同步设置使用本轮新建的本地假账户，分别确认默认取消和 Tab 后移除，账户已清除。长按空格的 repeat 请求不确认弹窗，没有 WebDAV 网络传输或主机串流。

## 保留的测试失败

两次早期旧版窗口脚本把“取消”误当作需要未保存确认的导航，未复现目标；明确取消本就放弃草稿。最终改用真实侧栏“远程设备”，没有为脚本改变产品的取消语义。

第一次完整回归的同步新用例未先进入同步分类，夹具没有当前编辑上下文；补齐既有流程并断言配置成功后，完整回归通过。旧失败日志保留。

`native-current01` 已通过三个导航选择，随后错误假设保存还留在设置页而查找取消按钮失败。`native-current02` 又因返回页同时具有侧栏和页头返回，未指定唯一控件而被接口拒绝；补充核对确认假账户已清除，原配置恢复，窗口通过自身接口正常关闭，见 `native-current02-cleanup.json`。最终脚本使用实际当前页面和 inspect 的精确侧栏标识，完整浅深流程通过；没有放宽产品的唯一控件、禁用或确认规则。

## 产物与范围

候选目录：`dist/velopack-candidates/0.2.11-preview.130`。独立解包后测试，尚未发布或升级日常客户端。

| 产物 | SHA-256 |
| --- | --- |
| UI | `0C4F279CF28AA3C16C87CB963AACA60CB6DCA30075267AC4542A83BF29221B2F` |
| Portable | `27FDB62D73CF569D62627ECD5774D0B7B521A41AD79FB098E023E299FA6FA9F8` |
| Setup | `D272141AA6A77669D6C7EC1F2F63D7A018F5FFD10B4658F375F574013A1DDF39` |
| nupkg | `0547FB73EE03810D36A210E08B5B83DE8DE7F54AF94AC2E49C6B2F24FB48A720` |

原生引擎仍为 `C789BFDF5619184428E764E4C6A785F0BD90F89130CE720BD2A906C49FC0D5EA`。最终五份正常 Windows 程序／配置哈希、测试副本完整 preferences、winserver 原授权文件、源码和包清单均核对通过，见 `final-verification.json`。日常 preview.101 关闭，Android preview.129 与 OPPO 未操作；未重启服务、模拟器或 ADB。

本轮证明的是公共弹窗规则、原生键名映射和实际控件接口流程；没有发送系统键盘输入或完成 UIA 全量焦点、物理键盘、跨屏 DPI、真实串流及整套 UI 验收。

## 构建清理

既有清理工具按策略删除旧 preview.124 候选、旧打包暂存，以及 target/debug 的 incremental、deps、build、.fingerprint、examples。逻辑字节数 **24,438,418,871**；清理前后 D 盘可用空间实测增加 **21,762,043,904** 字节（20.27 GiB）。后续核对余量为 **981,815,406,592** 字节（914.39 GiB），可能继续受其他进程写入影响。保留 target/release、当前／上一版、日常版本、正式发布件和明确保护产物，未停止运行进程；详细报告见 `cleanup-verification.json`。
