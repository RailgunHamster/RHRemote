# Android 添加设备的准备、取消与重试

本地 Android `0.2.11-preview.39 / 263`，Windows 保持 preview.47。OPPO、Windows 桌面预览及服务端未操作，未发布。使用接口、应用内实际按钮及自身 decor Canvas，没有 computer use、系统输入或系统截图。

## 独立复现的初始化竞态

PcView 的连接服务在后台线程等待初始化，再发布 managerBinder；原字段没有 volatile，其他线程也读取它。添加页在字段仍为空时已可提交，旧 inspectRhComputer 立即抛出“连接管理器尚未准备好，请稍后重试”。

在真实模拟器应用中先等待正常绑定，再仅临时清空该 Activity 的 binder 引用；后台服务持续运行。旧 preview.38 的一次添加立即返回 editing 和上述错误，原生测试明确失败，没有开始网络查找。before 的状态、失败和自身绘制图保留。这证明该路径可触发，**不能据此确定 preview.38 那次没有错误细节的自然查找失败就是同一原因**。

## 提交后的行为

- 字段改为 volatile，保证跨线程发布可见；查找前在该次请求内等待本地组件就绪，最多 5 秒。
- 已就绪时立即返回，不创建等待周期；没有增加网络超时或自动重试 HTTP。
- 等待时 API 为 `add.phase=preparing`，按钮和正文显示准备状态；地址和主提交禁用，取消及离开仍可用。就绪后同一次提交继续 probing，无需再点击。
- 取消、页面离开或销毁仍使用已有请求取消和归属检查，迟到初始化不会开始新的查找或保存。
- 准备超时回到 editing，保留地址和错误，恢复“添加”。用户可明确重试。

全部 UI 和 HTTP 控制继续共用 `device.add.submit`／`device.add.cancel`，没有第二套动作实现。

## 验证

最终 297 项 Android 单元测试通过。新增真实请求对象的已就绪、延迟就绪、等待取消、超时和启动前取消；完整 Home／按钮流程检查准备提示、重复提交拦截、超时保留输入和显式重试。第一次全量回归有 12 个失败，原因是新增测试查找器误把“设备地址”标题当成输入框；恢复类型精确匹配后全部通过，未将其算作产品缺陷。

`RhAddReadinessDeviceTest` 在已有 winserver 记录上执行真实添加与持久化，分别通过：

1. 1512×1008、240 dpi、1.0 字体的浅色横屏。
2. 540×960、240 dpi、1.3 字体的深色窄屏。

每条均验证实际主按钮的一次提交，准备中地址禁用、API 重复提交被拒绝、组件就绪后自动继续并选中 winserver；实际取消按钮使等待结束，迟到就绪不保存；强制准备超时后保留地址和错误，实际“添加”重试成功。等待和超时的底部按钮完整可见，实际 APK 的 binder 字段反射确认为 volatile。每条五张自身图，共十张，已检查准备及窄屏错误布局。

测试只延迟 Activity 引用，不停服务，不修改配对、默认画质或服务器显示。没有建立媒体串流或播放声音。两轮结束恢复共享文件原哈希，显示尺寸／密度／字体亦恢复至横屏原值。日常 Windows 偏好和 winserver 授权文件哈希不变。

## 证据与范围

`D:\RHRemoteTools\ui-add-readiness-review-20261028`：

- `native-before01/`、`native-before01.log`：旧 APK 的明确错误和失败。
- `RhDeviceAddReadinessTest-final.xml`、`RhAddFlowTest-final.xml`、`unit-summary-final.json`：最终单元与 Home 流程。
- `native39-light01/`、`native39-dark-narrow01/`：真实控件、准备／超时／重试及配置恢复。
- `emulator-layout-before.json`、`emulator-layout-after.json`：模拟器恢复。
- `android39-release-build.log`、`apk39-badging.txt`、`apk39-signature.txt`：ARM64 Release、263、非 Debuggable、原签名。

最终 APK SHA-256：`93EBE3C7D841CD22EDB51DBD695B4B98A741B3B6D4014EDE0EE8D061E22213A7`。仅本地候选。该受控准备场景不代替自然启动失败的定位、OPPO 实机、真实系统返回／键盘、跨网络及完整 UI 验收。
