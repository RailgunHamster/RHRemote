# 主题切换、连接结果与配置预览的上下文

本地版本：Windows 0.2.11-preview.43、Android 0.2.11-preview.33（versionCode 257）。未发布、未操作 OPPO，未修改 Sunshine 或业务服务。完整 UI 目标仍未完成。

## 原问题与最终行为

Android 原先只保存设备／设置等页面的导航标识，连接失败后重建 Activity 会回到设备列表。现在仅在同一进程的内存中保留已结束的连接结果：原设备、错误、操作类型、尝试次数及是否收到过画面。恢复页面不恢复请求、PIN、证书、套接字或运行中的连接；内存丢失时回到原设备，显示“连接已结束”，等待用户操作。目标不匹配或重复交付的旧内存状态不能重新打开结果页。

配置复制预览原先在 Android 重建时重新读取来源，可能把用户正在查看的 42 Mbps 换成后来更新的 55 Mbps。现在同一份只读快照一直保留到用户明确关闭／重新打开预览；复制到草稿采用实际展示的值，保存前不改变当前客户端的配置。来源已移除时返回原设置分类，显示“来源配置不可用”；该提示在下一次实际 Activity 重建后也保留。错误和配置快照不进入 Bundle，导航字段仍为九个。

Windows 旧版在切换主题前执行了普通离开表单的清理，导致干净的设备资料／连接设置也退回列表。现在干净表单保持原页、分类与数据；有未保存更改时仍需保存／放弃／继续编辑。成功保存或放弃后恢复原页面与分类再切换主题，保存失败保持错误输入及原主题。配置预览保持原快照；来源不可用时返回原分类。确认过程只暂存导航标识和只读预览，不复制未保存的表单值或 WebDAV 凭据。

## Android 验证

证据目录：`D:\RHRemoteTools\ui-result-context-review-20261027`。

- 修复前 `connection-result-before.xml` 明确复现预期 `connection`、实际 `devices`；`copy-snapshot-before.xml` 复现展示的码率随来源变化。修复后最终 271 项单元测试通过，无跳过。
- 原生测试使用无窗口 `emulator-5580` 的真实 `PcView`、按钮和共享动作分发器。连接／配对测试只连接测试自身的 loopback HTTP 监听器，验证实际超时、失败、重试、取消清理和返回。结果页在两次实际主题重建后保留错误、设备、搜索与尝试次数；重试仍属于原设备，取消和明确返回均回到原设备。
- 配置复制测试覆盖实际查看、取消、复制到草稿、明确保存及放弃；另在预览期间修改来源，验证主题重建后仍展示原快照、应用采用原值、重新打开才读取新值。移除来源后保持原分类及可见提示，不自动保存配置。
- 横屏 1512×1008、竖屏 1008×1512、窄屏 540×1200／字体 1.3 各通过四项原生流程，共 12 项、21 次 Activity 重建、45 张应用自身渲染 PNG。底部按钮均按完整可见范围检查；窄屏内容允许正常滚动。连接配置按规范化文档比较，结束后恢复原值和主题；模拟器恢复原尺寸及字体。
- 中间失败保留：`native-connection-before-fixture-action.log` 中的共享测试设备没有持久化配对证书，重建后不能继续假定其设备卡片仍显示“连接”。测试改用卡片实际报告的 `canConnect`，未把授权复制进恢复状态。`native-copy-before-notice-retention.log` 则发现提示在第二次重建时消失，现已有代码及回归修复。
- 最终 Android 运行日志：`native-landscape-notice-{connection,copy}.log`、`native-{portrait,compact-largefont}-{connection,copy}.log`；状态和图片在三个 `android-*` 目录。这里只证明模拟器的 UI 和 loopback 错误路径，不是实际 Sunshine 配对、视频首帧或外网验收。

## Windows 验证与尚缺证据

- preview.42 打包程序通过自身接口复现设备资料页切换主题后退回列表，证据为 `windows-info-theme-before.json`。测试保持窗口不激活；隔离偏好文件已恢复相同 SHA-256。
- `windows-appearance-before.log` 的两个新增回归先失败。修复后的完整核心 112 项、UI 123 项串行测试通过，记录为 `windows43-serial-suite.log`；覆盖干净表单、保存／放弃／继续编辑、无效保存、预览快照和来源移除。
- 一次默认并行测试在未改动的 `transport_owner::tests::path_parent_and_creation_boundaries_exclude_other_processes` 中出现 `0x80070005`。单项隔离运行及全套串行均通过。该测试会枚举同一测试进程的子进程，存在与其他并行清理测试交叉的可能，但尚未证明根因；保留 `windows-core-ui43-final.log`、`windows43-process-boundary-isolated.log`，未放宽生产进程权限检查。
- preview.43 的 Release、Portable、Setup 和 nupkg 已构建；内置原生引擎 SHA-256 仍为 `6B7ED0380956D960EAA17180DFC2093E85E00C7F9D7F94AF2C98E691C6880304`。编译和单元测试不代替实际控件／焦点复验。
- 准备启动新包时，QueryDisplayConfig 只返回主屏 DISPLAY1／XG32UQ 和虚拟屏 DISPLAY26；实体 DISPLAY2／C3273IUY 已不活动。启动在显示器检查处停止，没有打开新窗口，没有移到主屏，也没有关闭旧 preview.42；旧窗口已由系统最小化且未激活。preview.43 实际运行复验等待实体副屏恢复，不能计为通过。

## 产物与约束

Android：`dist/android/RHRemote-v0.2.11-preview.33.apk`，SHA-256 `7F798EE2CA71410D3F43EBD30C42C776EBFFDF2AD818AC243D77C032F923196A`。

Windows：`dist/velopack-releases-0.2.11-preview.43`。Portable SHA-256 `8140F5AE274C47B47B900771B105006459F7E4530E26F4EE6B440E11808D7404`；Setup `3FD6C15793031E84ED8DFB674931B6329FF3D4B2B35BC88D15374DC59CA5FFCD`；nupkg `1BF0A67A2F4F94B4FDE9EF5C312EC6E3B83E34721893D327A75AB091EA016455`。

全过程使用应用接口、原生应用内测试及自身渲染，没有 computer use、系统输入、Android 控制端口转发或控制令牌读取。Windows 只允许实体副屏、不激活；正常设备列表、顺序、独立配置和凭据保持不变。未完成项继续按 UI-FLOW-SPEC.md 与 TODO.md 验收。
