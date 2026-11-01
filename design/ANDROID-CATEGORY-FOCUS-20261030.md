# Android 分类切换的键盘焦点（preview.118）

分类切换现在保留键盘上下文：用 Enter 激活分类后，新分类按钮继续获得焦点，可以继续移动到其他分类或正文。原本没有焦点的触摸／接口激活不主动建立键盘焦点。

## 原因与修正

原实现切换分类时重建页头，旧按钮被移除后没有恢复对应焦点。preview.117 的真实 PcView 中，激活“音频”后，分类已经切换，但实际焦点回到“远程设备”。分类样式还用静态背景覆盖了原焦点状态，并关闭系统默认高亮，导致未选中分类的键盘焦点不可见；新增样式回归在旧实现明确失败。

`RhHome.categoryTabs` 只为原来已聚焦的按钮恢复焦点，在替代按钮首次布局后执行一次。恢复要求当前 Home、当前可见且被选中的按钮仍对应；如果焦点已经移到其他对象或页头已移除，则不恢复。没有延时循环或跨页面保存的焦点请求。

分类及分辨率预设共用状态背景：正常颜色和尺寸不变，聚焦／按下时增加蓝色边框；预设刷新也保留该状态。分类继续调用 `settings.tab {tab}`、`app.settings.tab {tab}`；未保存确认仍通过原 `navigation.resolve`，焦点回调不提交表单。

使用 Android 的[键盘导航](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/navigation)和 [View 焦点接口](https://developer.android.com/reference/android/view/View#requestFocusFromTouch())，没有增加新的遍历引擎。

## 最终验证

- 450 项 JVM 通过，0 失败、0 跳过。新增 7 项覆盖分类、搜索／草稿保留、确认框、无焦点激活、提前离开、其他控件的更新焦点及预设刷新样式。
- 最终 `native04-final`／`native05-font200`，正常与 200% 字号，均含浅深主题。合计 36 次正式 PcView 分类按钮 Enter 激活、16 项焦点归属检查通过。
- 核对实际替代按钮持有焦点、分类被选中、设备／搜索／未保存输入保留、持久配置不变。平台原生 `focusSearch` 前后方向有可用对象；它不等于物理 Tab 事件验收。
- 每轮恢复模拟器 15 个正常配置／身份文件、原备份与系统字号，进程已退出。没有连接、配对、同步或操作 OPPO，日常 Windows 保持 preview.101 且关闭。
- 36 张 `View.draw` 原图、SHA-256 与可切换的离线 `preview.html` 保留。

早期直接请求焦点／View 队列的版本未通过布局时序，最终改为首次布局的一次回调。JVM 测试也曾混用窗口与手动视口尺寸，已固定视口并重跑旧代码失败记录。焦点归属测试明确选择不同于系统自动回退的控件；选择已聚焦的回退对象本身不能证明发生了新的焦点转移。原生 `native02-final` 曾在原生确认回调尚未处理时点下层“取消”，被正常保护拦住；最终分开两次 UI 操作，确认弹窗关闭后再取消。这些失败保留，不计入最终通过。

证据目录为 `D:\RHRemoteTools\ui-category-focus118-review-20261030`；关键文件是 `final-unit.xml`、`unit-suite-counts-final.json`、两轮原生 `result.json`／`verification.json`、`render-manifest.json` 和 `final-verification.json`。

## 本地交付与边界

ARM64 本地候选为 `dist/android/RHRemote-v0.2.11-preview.118.apk`，版本代码 336，SHA-256 `C5C5680DD99747FF051236BAD8378F834936D28DAB281D4B96263584F6EE822E`。签名和原生库对照见最终核对，未公开发布。

这轮只完成分类的应用内键盘上下文及焦点提示，不代替物理键盘、真实输入法、OPPO、完整双端 UI 或远程会话验收。原 TODO 的剩余范围继续保留。
