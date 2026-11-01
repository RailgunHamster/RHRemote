# Android 设备状态与检查入口

更新：preview.152 已将主动“检查连接”接入独立任务及逐设备线路策略，并加入 HTTP／HTTPS 与证书分类；[最新证据与剩余范围](ANDROID-DEVICE-PROTOCOL-20261031.md)。下文保留 146 的历史实现和原始证据。

2026-10-31，本地候选 preview.146／364。沿用已选 HelpWire D 的设备列表与详情工作区，将设备状态、主操作及明确的配对入口放在同一详情页；不增加单独的连接设置返回层级。Windows 145 的原生分类见[另一份记录](WINDOWS-DEVICE-CHECK-RESULTS-20261031.md)，不能将两端的验证范围混用。

## 可见状态与操作

| 当前记录 | 配对状态 | 主操作 | 其他入口 |
| --- | --- | --- | --- |
| 没有本次配对结果，也没有本机证书 | 未确认 | 检查连接 | 明确的配对按钮 |
| 有本机证书，当前结果为空 | 已保存配对 | 连接 | 连接设置 |
| 当前 PAIRED 且有本机证书 | 已配对 | 连接 | 连接设置 |
| 当前未配对，或有 PAIRED 声明但本机没有证书 | 未配对 | 配对 | 连接设置 |

可达状态独立显示为待检查、在线或离线。没有实际操作归属时，不把 UNKNOWN 称为正在检查。保存过配对的设备即使当前离线，也允许显式连接，继续由原有 admission 校验线路、UUID 和证书。添加本机证书时，即使设备名称、在线状态和原始 pairState 都没变化，详情按钮也会更新。

检查不会自动提交 PIN。未知设备保留明确配对入口，避免只保存 `rhr:` 中继地址、不能被普通发现轮询确认的设备陷入无法首次配对的状态；该入口仍走现有可取消的完整连接准备。没有因为暂时未知新增一条一概禁止远程连接／配对的 API 规则。

这里的 PAIRED 来自已有 ComputerDetails，不新增 HTTPS 来源证明。当前 Android `verified` 字段名称沿用原接口；不能据此声称已经移植 Windows 145 的 HTTP／HTTPS／TLS 分类。真实来源和错误分类仍在 TODO。

## 刷新与锁

主按钮发送 `devices.refresh(uuid)`，只请求指定已保存设备；列表工具栏省略 uuid，检查整个列表。未知或空 uuid 在同一个 RhHome dispatcher 拒绝。实际完成仍由发现服务的后续结果通知，界面只反馈“已请求检查连接”，不把排队成功说成网络检查成功。

PcView 快速取得 binder 和目标列表，将 invalidateStateForComputer 放到 RhDiscoveryRefresh 后台队列。队列按服务实例与 UUID 去重，完成只释放自身令牌；失败不会遗留其余目标的 pending 状态，不保留 Activity／View 引用。队列有界，已有连接／配对时不从刷新重新开启发现。

只移动到后台还不够：原 binder 在等待某台设备的 networkLock 时持有整个 pollingTuples 列表锁，读取另一台设备同样会被阻塞。现在只在列表锁内取出目标引用，释放列表锁后等待该目标的网络锁。不会在网络锁内反向获取列表锁，不会重新插入已移除的旧 tuple。旧目标被同 UUID 的新对象替换时，迟到的失效操作不改新对象。

当前队列仍是普通发现失效请求，不是按完整逐设备路由策略执行的原生探测。不会为背景发现打开媒体中继；完整线路检查、分类、逐台等待与取消的统一设计继续保留。

## 原生预览及发现的布局问题

[最终图集](D:/RHRemoteTools/ui-android-check146-review-20261031/gallery146.html) 使用专用 emulator-5580 的独立 `com.rhremote.client.uicheck146` 包，相同生产 Java View、x86_64 Debug，FixtureActivity 不附着系统窗口。没有系统截图、系统输入注入或远端输入；按钮接收端记录动作和 UUID。

最终目录为 `native-view-final02`：浅深两主题、360×800／1008×672、100%／200% 字号、八种状态，共 **64** 张图与 **88** 次按钮分发。检查文字高度、指标省略、至少 48dp 的按钮、按钮位于父容器留白内，以及状态标签只有一行、文字布局不超出内容区。

先前两处图均保留，没有改标为最终：固定 64dp 的状态标签在大字号下将待检查挤成两行；改成 setSingleLine 后，Android 给它分配 1,048,576 像素的滚动布局，而实际内容区只有 48 像素，居中文字在未附着窗口的原生画布中不可见。最终使用随文字扩展的宽度及 setMaxLines(1)，不启用横向滚动。两份原生红测试分别确认 2 行和超宽布局，最终图已人工复查。

![大字号竖屏，未确认与明确配对](D:/RHRemoteTools/ui-android-check146-review-20261031/native-view-final02/dark-360x800-font200-state0.png)

![横屏，离线但已保存配对](D:/RHRemoteTools/ui-android-check146-review-20261031/native-view-final02/light-1008x672-font100-state5.png)

## 证据与范围

证据根目录为 `D:\RHRemoteTools\ui-android-check146-review-20261031`。

- `red-flow.log`：原实现三项失败，分别确认未知被当作未配对、指定目标刷新仍调用全列表、离线未知没有检查入口。
- `red-list-lock.log`：实际 ComputerManagerBinder 与自己的 PollingTuple 网络锁，读另一台设备超时。修复后的检查还覆盖主线程响应、同 UUID 替换、服务隔离、去重、失败释放和重试；没有真实网络请求。
- `native-badge-red.log`、`native-badge-width-red.log`：实际 Android View 的标签换行和超宽布局失败。
- `native-view-final02.log`、`native-view-verification146-final.json`：最终 64 个布局场景及 88 次动作边界检查。原 `native-view-01` 和 `native-view-final` 都是中间图，不作为最终效果。
- 最终 Release **492** 项通过，0 失败／错误／忽略；正式包为 ARM64、非 debuggable，版本 **0.2.11-preview.146 / 364**，SHA-256 为 `074AC0CE1F594F2F529B062B8A89E6E188F949BB40D351DBCA2A83A3D3B1FA56`，签名与 129 一致。构建／测试、包摘要、选定源码摘要见 `build-release-verified-final.log`、`final-release-test-counts.json`、`release-package146.json`、`source-manifest146.json`。这不代表 APK 已覆盖安装到 OPPO。

日常二进制、配置、主机配对及旧 APK 等八项摘要在本轮结束核对均未改变。签名核对脚本最初未匹配 SDK 37 的 `V2 Signer` 输出；改为读取证书 SHA-256 后确认一致，没有换证书或重签。

隔离包已在取回证据后移除；模拟器原主包仍是 129／347。没有改模拟器全局分辨率或字号，没有操作 OPPO、重启业务服务、替换日常 Windows 客户端或公开发布。

仍未完成：按设备的完整路由策略检查、Android 的 HTTP／HTTPS 来源与失败分类、其余动态页面的按压归属与物理输入，以及实际 HTTPS／配对、外网、OPPO 与完整交付。当前组件图、实际 binder 锁和 JVM 回归不替代这些范围，完整 UI 目标继续保持未完成。

设备页按压期间状态或目标改变的控件范围，随后已在 147 的实际附着窗口通过，见[输入归属记录](ANDROID-DEVICE-ACTION-INTENT-20261031.md)；本记录的 146 图片与包摘要保留原来源。
