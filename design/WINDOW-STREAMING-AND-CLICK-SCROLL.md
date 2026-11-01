# 选择窗口与点击滚动

Windows 使用 Rust / Slint，Android 使用 Java / 原生 View。窗口捕获整合在 RHRemote-Sunshine 引擎内，沿用现有统一服务。

## 操作与接口

设备页“选择窗口”调用 `device.windows.open {uuid}`；关闭调用 `device.windows.close`；选择可用项调用 `device.window.connect {uuid, source}`。窗口列表与连接共用已有配对、身份、草稿确认和准备资源管理。窗口源只保留于运行时，手动及自动恢复均保留原目标，不能隐式改为桌面。

Android 控制页开关调用 `stream.click-scroll.set {enabled}`。轻点主键点击，单指滑动滚轮，双指平行滑动主键拖动；缩放和按键交换沿用当前设置。停止、切换模式或取消手势会释放按下状态。接口手势与 View 手势使用同一控制器。

## 服务端

配对 HTTPS 接口 `GET /rhremote/windows` 返回支持能力及窗口列表，不接受未验证证书，不导出 HWND。源为 `rhrwindow:` 加 32 个小写十六进制字符，绑定请求证书；空闲租约 120 秒，活动捕获持有租约。连接必须收到相同窗口源的首屏确认，失效、错误或缺失的确认不能回退到桌面。

通过 [CreateForWindow](https://learn.microsoft.com/windows/win32/api/windows.graphics.capture.interop/nf-windows-graphics-capture-interop-igraphicscaptureiteminterop-createforwindow) 建立稳定的 GraphicsCaptureItem，结合 Closed 通知、PID 和私有窗口属性识别句柄复用。窗口源只使用 WGC，不回退桌面复制；尺寸变化先重建捕获再编码，独立附属窗口不自动加入画面。

鼠标位置从窗口内容坐标映射至当前虚拟桌面；拖动时冻结起始坐标平面，避免拖动窗口造成坐标反馈。松键不依赖窗口仍存在。首个真实点击负责激活，不注入假点击、不附加线程输入队列。窗口分支不调用显示器配置接口；已有用户自定义主机启动命令仍按原规则执行。

新增逻辑集中在 window_capture 与 rhremote_window_capture 模块，上游合并时检查 nvhttp、video、display、input 的接入点。

## 验证边界

原生协议、租约、坐标与客户端状态机测试覆盖正常、过期及错误路径；专用模拟器验证真实 View 点击、滚动、拖动、缩放和开关布局。实体副屏暂不可用，OPPO 测试仍暂停，因此真实窗口串流、硬件输入和带宽节省尚未验收。完整 UI 改版仍保持暂停，不能将本功能测试视为整体 UI 完成。
