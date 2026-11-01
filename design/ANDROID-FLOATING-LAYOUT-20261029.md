# Android 控制面板与悬浮按键：模拟器原生绘制

后续 Android preview.102 已修正开关轨道的实际透明度，并完成最终 416 项回归、八组原生离屏图及 44 个实际像素核对，见[开关与像素验证](ANDROID-SWITCH-CONTRAST-20261029.md)。以下保留 preview.101 的首轮布局与覆盖安装记录。

专用 emulator-5580 从 preview.87 覆盖安装到 preview.101，versionCode 从 304 到 319。安装前核对签名相同，保留数据及旧 APK。没有操作 OPPO，没有运行 PcView / Game Activity，没有远程连接、源端输入或 WebDAV 请求。

证据目录：`D:\RHRemoteTools\ui-floating-native101-review-20261029`。最终 `instrument02.log` 为 OK (1 test)，`android-evidence02/result.json` 为 passed，包含八组实际 Android View 绘制场景。

## 场景和断言

测试直接实例化生产 RhStreamPanel、RhFloatingKeyboard，在模拟器 Android 运行时中测量、布局和 View.draw；根 View 没有附加到 Activity 或系统窗口。背景网格、屏幕列表和接收动作都是显式测试夹具，没有伪造实际串流成功。

- 浅深两主题，各检查 840×560、360×800、640×360；另检查两主题 360×800、130% 字号，共八组。
- 每组导出操作、屏幕、声音及悬浮按键四幅自身 PNG，共 32 幅。顶部开关与断开操作在三个分类都完整位于视口中，不重叠、不依赖滚动，控制面板整体不越界。
- 开关使用真实 Switch 回调，开启后控制面板收起，十个按键完整显示，保留 16 dp 边缘和至少 48 dp 高度。
- Tab 位于左侧偏上，方向键位于右下倒 T；按键之间不重叠，空白处的触摸到达下层测试画面。
- 实际调用十个按钮，逐个核对同一 stream.shortcut 接口及对应 shortcut 值；重新打开控制面板后按键隐藏，关闭开关后仍保持隐藏。
- 声音夹具明确更新音量模型，核对标签为 100 时实际 SeekBar 进度也是 100；屏幕页使用两台显式假显示器。

主题通过只读 Context 包装提供，任何尝试编辑 SharedPreferences 都会失败。测试本身也核对共享配置和全局偏好指纹，成功结果只在最终核对后写入。安装及两轮测试前后另外核对十四份设备、WebDAV、配对证书／私钥、唯一身份、数据库及偏好文件，全部字节相同；见 `configuration-verification02.json`。

第一轮原生检查的八组布局及按键断言也通过，但图片中的音量标签与滑块未初始化到同一夹具值，屏幕仍是初始加载文案。它们不作为完整页面状态的证据；第二轮补齐显式模型后重新导出，第一轮图像保留。

## 范围

这是在模拟器 Android 框架中运行生产 View 的离屏布局与回调验证，不是附着窗口、系统栏／缺口、真实 SurfaceView 合成或 OPPO 触控验收。动作接收端是测试夹具，不能代替远端收到按键的证明。此前实际会话的对应范围见 [初次实现与远端回执](FLOATING-KEYBOARD-20261029.md)、[模拟器会话转向](ANDROID-SESSION-ROTATION-20261029.md)。

Windows 日常 preview.101 在本轮没有启动或更改，ARM64 正式 APK 也未被离屏测试覆盖。后续根据图像继续调整开关辨识度，结果另补，不把当前旧图当成后续生产改动的通过证据。
