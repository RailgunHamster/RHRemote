# 同步冲突按字段对照

2026-10-31，本地候选 preview.157。参考 [VS Code Settings Sync](https://code.visualstudio.com/docs/configure/settings-sync) 先查看本机与远端差异、再选择保留一端的流程；没有增加逐字段合并算法。

## 问题与当前行为

原先连接配置冲突只显示尺寸、帧率及手动码率。只改变声音、编码或自动码率时，两边摘要可能完全相同；设备只改备用地址时也看不出区别。用户缺少作出保留选择所需的信息。

两端现按参数分行、本机／远端分列显示真正变化的字段。比较依据是经过原有配置验证的原始值，不用显示文字判断相同与否；空别名与字面名称“未设置”可区分，备用地址及顺序也会列出。同步审阅包含其他平台的连接参数，主机左右键交换仍属于设备资料。

`conflictItems[].fields` 为 `{key,label,local,remote}`。此前的 `local`／`remote` 文本继续供接口兼容使用，主题等简短冲突仍可用文本。字段有稳定控件标识，未变化的数据不会反复重建 Windows 表格模型。保留操作仍按原冲突项选择一端，上传继续使用已有的 ETag 条件保护。

配置复制与同步审阅共用参数名称和值的格式。自动模式下保留的数值标为“手动码率”和“手动值，未启用”，避免被误解为本次实际码率。自动码率仍只有连接时预算，运行中的自适应未完成。

## 当前证据

证据目录：`D:\RHRemoteTools\ui-review157-20261031`，只读图集 `gallery157.html`。

- `windows-workspace03.log`：Release workspace 506 项通过，原有 5 项忽略不变。覆盖逐个客户端设置的差异、备用地址、占位名称、缺少节点、复制预览，以及真实 Slint 表格的宽窄／浅深主题、换行、滚动与不变模型的身份；自身软件渲染 16 张在 `windows-table01/`，没有物理 Windows 窗口或真实连接验收。
- `android-release02.log`、`android-release-unit-table/`：ARM Release 构建和 564 项 JVM／Robolectric 回归通过。包括每个客户端字段、其他平台设置、空节点、别名占位文字及复制预览。
- `android-native-review02.log`、`android-native-review02/result.json`：专用 `emulator-5580` 上的隔离包 `.uicheck157`，1 个仪器测试内执行 16 个场景。浅深主题 × 360／840 dp × 100%／200% 字号 × 保留本机／远端。每场景使用实际 RhHome 控件、RhSync 与本地回环 DAV，验证选择前保持 1 次基线 PUT，选择后共 2 次 PUT，持久化值与所选一端相同。共 192 个值控件的换行、高度、列宽、无省略及不重叠检查，80 张自身 View.draw 图含滚动到码率、顺序及复制数值的画面。
- 仪器测试使用专用自身 Activity，不初始化后台设备发现、配对或串流；未使用系统输入或系统截图。这些结果不替代既有真实 WebDAV、物理输入、跨网络与 OPPO 验收。
- `emulator-cleanup157.json`：隔离应用及仪器包均已移除，正常模拟器客户端仍为 preview.129／347。`protected157.json` 中 8 个日常客户端、配置、认证与基线 APK 哈希均与 155 基线相同，仅核对哈希。
- ARM 候选 `dist/android/RHRemote-v0.2.11-preview.157.apk`，versionCode 375，SHA-256 `39AC8FE21E6C32DFE8427ECCD4C3E39F7C460505EF276C41D1F3D0583872D923`。当前仅本地包；未升级日常 Windows、未发布或操作 OPPO。

## 保留的中间证据与未完成范围

`windows-workspace01.log` 记录初次控件标识缺少模型 key 的编译失败，已补齐映射；02 是平面文字版的通过记录及 `windows-review01/` 图，03 才是最终表格版。Android release01 和 `android-before-table.apk` 为表格前候选；native01 是首次 16 场景及 48 张图，native02 补充实际滚到数值处的 80 张图。没有改标历史结果。

总目标继续按 UI-FLOW-SPEC 和 TODO 推进。真实服务端及多线路联动、未完成的调度／操作归属边界、实体窗口、OPPO、公开更新源和最终交付继续保留。此记录只证明上述同步审阅与复制显示范围通过。
