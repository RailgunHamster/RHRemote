# Android 确认框与列表窗口

本地 Android `0.2.11-preview.84`，versionCode 302。Windows 保持 preview.81。证据目录为 `D:\RHRemoteTools\ui-dialogs84-review-20261029`，未发布，未操作 OPPO。

沿用选定的浅色／深色配色，把原生确认框改为有边缘留白的圆角窗口，主操作填色，次操作描边，移除使用危险色。按钮至少 48 dp 高，间距 8 dp。保留原生按钮角色、监听器、返回取消和长列表滚动，不另建一套确认行为。窄窗口仍由原生 ButtonBarLayout 自动堆叠，主操作位于上方；宽窗口保留原生排列。

参照 [Android 原生对话框](https://developer.android.com/develop/ui/views/components/dialogs)与 [Material 对话框规则](https://m1.material.io/components/dialogs.html)。原有三个未保存选择仍完整保留；没有为了套用两按钮示例删掉业务选项。

## 实际视觉问题

第一轮尺寸和回调检查通过，但自身窗口像素仍显示灰色按钮底栏。读取真实原生层级后，发现按钮上层的 ScrollView 获得焦点，且默认焦点高亮仍开启；仅给按钮换背景不足以消除该层覆盖。最终关闭动作区域祖先的默认高亮，保留按钮自身的聚焦边框与原生列表行状态。窗口背景也明确使用当前主题的浮动表面色。

保留修改前、第一次修改与最终像素，未把中间版本算作视觉通过。`comparison84.html` 包含浅深主题及宽／窄窗口的前后对照，另有竖屏和矮窗口。图片由本应用自身 View.draw 和对话框 Window 的 PixelCopy 导出，不包含系统截图或系统输入操作。

## 验证

- 完整 Android JVM 回归 403 项通过。第一次完整回归的旧外观断言失败，原日志保留；断言改为核对主题、主操作背景及实际文字对比度后完整重跑通过，继续保留“继续编辑不丢草稿”的检查。
- 最终原生矩阵为四种窗口（1512×1008、1008×1512、540×1200、960×540），后两种字体 1.3；浅深主题与保存、移除、关闭、列表共 32 个场景通过。另有 8 个列表末行像素样本，实际滚动至第 24 项并触发一次原生回调。按钮完整可见、不重叠、单行，最小实测文字对比度 4.908。
- `native-final84.json` 记录最后一轮 32 个场景、8 个末行样本及配置恢复；全部自身 Window PixelCopy 返回成功。早先 `native-matrix84.json` 对应前一轮视觉矩阵，不覆盖旧证据。
- 实际表单归属／页面重建流程通过，耗时 11.935 秒。临时共享文件、偏好及原始备份目录恢复，模拟器恢复 1512×1008、240 dpi、字体 1.0。

## 连接失败仍保留

本轮另一次实际会话流程未通过，不能据此声称会话菜单和恢复已完成验证。首帧前两次加密 RTSP SETUP 失败，诊断分别记录 expected=149 / received=148、expected=155 / received=154，均 interrupted=0；后续恢复才报所有启用路径不可达或 UUID 不符。完整链见 `session84-failure-chain.json`，原始字节诊断见 `rtsp-byte-errors84.log`。

只读核对时，Windows 和模拟器均能取得 winserver 的 serverinfo，UUID 正确，主机报告 BUSY；当前会话归属没有证据，因此未清理未知会话或重启服务。少一个字节的来源尚未确定，没有放宽长度校验、禁用加密或修改网络实现。该失败不归因于本轮外观修改，也不标记为已解决。

## 安装包

ARM64 Release：`dist/android/RHRemote-v0.2.11-preview.84.apk`，SHA-256 `1A80214434FAA41921CA351EAB50390D827EEA378A1C88F59C049BDE4938D114`。签名验证通过，与上一版证书相同；两份原生视频／传输库与 preview.83 字节相同。悬浮键盘和四向滚动保留，实际输入证据见[悬浮键盘](FLOATING-KEYBOARD-20261029.md)及[方向与滚动](ANDROID-SESSION-ROTATION-20261029.md)。

打包后的自动保留规则删除了未使用的 preview.82 APK（19,971,630 逻辑字节），保留 preview.83、preview.84、正式发布件和在用产物。没有替换日常 Windows 客户端、安装 OPPO 或发布到更新源。
