# Windows 当前页面与完整导航复验

本轮 Windows 本地候选为 `0.2.11-preview.159`，Android 候选仍为 preview.158。改动集中在网络设置的可读性及诊断详情收起后的结果定位；完整 UI 目标继续按 [D1–G2 验收表](UI-FLOW-SPEC.md#完整流程与完成证据)推进。

## 用户可见变化

首选地址和备用地址现在分别有常驻标签，填入值后仍能辨认用途。备用地址标签注明逗号分隔及随 WebDAV 同步；输入框的动作、无障碍标识、校验和草稿归属沿用原实现。Android 的地址输入原本已有独立标签，本轮没有修改 Android。

网络表单去掉重复的“设备地址”标题及已保存地址摘要，直接展示说明和可编辑字段。完整地址分类摘要继续保留在设备概览。窄窗口的诊断操作仍可到达，正文滚动时分类栏和保存栏固定。

收起诊断详情后，结果提示保持可见。原逻辑只与更新后的按钮参数比较，而刚完成的点击仍保留收起前的参数；本轮保留前一次参数供显示定位使用，并要求同一诊断及草稿。原来的过期动作拒绝规则没有放宽：该参数只决定是否滚动显示结果，不执行旧命令。切到输入框、主动滚到其他位置或换到其他任务时，不拉回旧结果。

## 当前来源与检查范围

证据目录：`D:\RHRemoteTools\ui-workspace159-review-20261031`。

完整 Release 工作区在 `windows-workspace159-02.log` 通过 **508** 项、0 失败，另 **5** 项保留显式条件忽略。UI 测试可执行文件与构建时来源摘要记于 `workspace-test-identity159.json` 和 `selected-source-manifest159-03.json`；补齐本文及交互索引后，当前 22 份选定来源记于 `selected-source-manifest159-04.json`，核对两份之间只有文档变化。这是选定来源清单，不宣称冻结整个工作树。

显式日常设备流程使用同一完整工作区产生的测试可执行文件，`workspace-flow159-02.log` 通过 1 项整体流程测试。它从日常 preferences 的公开 Saved 结构建立隔离副本，保留 RailgunHamster、winserver、Claw 的顺序、别名、备用地址及各客户端参数，不复制 WebDAV 登录或原生配对身份。源文件原始字节在每组结束后核对不变。

| 范围 | 当前证据 |
| --- | --- |
| 窗口与主题 | 720×560、1180×780，各含浅深色，共 4 组 |
| 设备工作区 | 3 台设备逐一选择、搜索、刷新、编辑及返回，共 12 个逐设备流程、132 个检查点 |
| 草稿 | 换设备时继续编辑／保存后切换；离开时继续编辑／放弃；其他设备及其他客户端参数不变 |
| 连接页 | 实际连接、重试和返回按钮；平台边界返回受控错误，不代表真实主机连接 |
| 应用设置 | 更新只有一个主流程按钮；HTTP WebDAV 地址编辑及取消；外观／关于／更新切换及返回 |
| 全页预览 | 每组 21 张，共 84 张正式 Slint 组件自身渲染图，包含五类连接设置、四类应用设置、资料、添加及未保存提示 |
| 诊断控件 | 5 项既有回归通过；浅深主题、宽窄窗口及应用内鼠标／键盘路径，共 84 张诊断状态图 |

诊断的独立回归为 `windows-diagnostic159-03.log`，图及结构化结果位于 `diagnostic159-03-images`。原有结果可见性、取消清理、替换任务的旧按钮保护、编辑焦点及主动滚动位置断言均保留，没有修改测试断言来接受裁切。

## 可见预览

离线预览：`D:\RHRemoteTools\ui-workspace159-review-20261031\preview159-final.html`。可切换页面、主题和窗口大小；点击图片查看原尺寸。84 张图片的来源和 SHA-256 在 `gallery-manifest159-final.json`。

![窄窗口网络设置](D:/RHRemoteTools/ui-workspace159-review-20261031/workspace-flow159-02/compact-light/profile-network.png)

![收起诊断详情后的结果](D:/RHRemoteTools/ui-workspace159-review-20261031/diagnostic159-03-images/flow-diagnostic-720-false-false-collapsed.png)

## 本地候选包

优化 Release 构建产生于 `dist/velopack-candidates/0.2.11-preview.159`，没有安装或公开发布。

| 产物 | 字节 | SHA-256 |
| --- | ---: | --- |
| RHRemote-win-Setup.exe | 53,059,361 | `AE94E422149C49BCCE9C27F228E802DB8713ED9A717B481AB9949365F4570851` |
| RHRemote-win-Portable.zip | 48,522,729 | `FED7BBAB2665C7D4BE7186DCF51770E67046FFBF137555ECB1B787F55FEDCA9C` |
| RHRemote-0.2.11-preview.159-full.nupkg | 48,560,417 | `942C65F10B374342293A0A9336A536371187E54BF81B892AB26ED7E904FD2246` |

包内 UI 与本轮 production 可执行文件、包内引擎与准备目录逐字节摘要比较；更新元数据的版本和 SHA-256 另行核对。测试可执行文件与 production 包分别记录，不把软件后端控件测试称为安装包实体运行。构建结束按现有保留策略调用统一产物清理工具。

## 保留的失败与范围边界

最初直接增加标签后，`windows-workspace159-01.log` 中有 5 个诊断控件用例失败：窄窗口首屏的诊断按钮被推下。去掉重复摘要后，`windows-diagnostic159-02.log` 仍复现收起详情后的提示裁切，提示上边缘为 171、视口上边缘为 200；`diagnostic159-02-render.log` 和对应图保留该失败。最后修正同次收起操作的显示归属后，5 项独立回归及完整工作区均通过。

早先的 `workspace-flow159-01`、`preview159.html` 和 preview.158 包是同轮开始时的基线，不是 159 的最终结果。`selected-source-manifest159-final.json` 的名称早于最后修正，当前来源应以 `selected-source-manifest159-04.json` 为准，构建输入仍可追溯到 03；不重写旧清单或旧图片。

当前 QueryDisplayConfig 只识别无实体名称的 DISPLAY26 虚拟屏，因此本轮没有启动可见 Windows 预览，也没有回退到主屏或虚拟屏。上述测试使用正式控件和 Controller 的软件后端；在线、配对、本机设备及连接失败属于明确的测试边界，不代替真实主机、实体窗口、物理输入、长期重绘、跨屏 DPI 或远程首帧验收。

本轮没有操作 OPPO、修改正常 Android 安装、重启业务服务、升级日常 Windows 或公开发布。最终包、保护文件及预览完整性另由本轮 `final-audit159.json` 核对。
