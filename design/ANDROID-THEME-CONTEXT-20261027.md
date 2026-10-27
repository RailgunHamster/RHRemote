# Android 主题切换的页面恢复

范围：Android 0.2.11-preview.31（versionCode 255）；Windows 保持 preview.42。仅本地构建和无窗口模拟器验收，未操作 OPPO，未发布。整体 UI 目标仍未完成。

## 原因与改动

原来的导航快照只记录“设备”或“应用设置”。切换主题时，设备资料和连接设置会先执行普通离开逻辑，随后重建 Activity，因而返回设备列表；正在添加设备时还会清空地址。

现在保留设备资料、连接设置及其分类、只读配置复制预览的来源、添加设备页，以及原设备和搜索条件。未保存表单仍先经过保存／放弃／继续编辑的确认；保存失败不切换主题。恢复复制来源不可用时，回到原设置分类，不执行复制或连接。

添加地址和错误提示仅通过 Android 的 non-configuration instance 在同一进程的 Activity 重建间保留。Bundle 只包含九个导航标识字段，不保存表单内容和 WebDAV 凭据。回填输入框时抑制编辑回调，避免将程序恢复误判为用户输入而清空错误提示。主题切换会取消仍在查找的添加请求，晚到的响应不能保存设备或跳转页面。

## 验证

- 原导航恢复回归先失败：预期 `settings`，实际 `devices`。新增的添加页恢复回归又复现错误提示从原文本变为空；两份失败 XML 保留。
- 最终 259 项 Android 单元测试通过，无跳过。覆盖保存失败、继续编辑、放弃后恢复分类、复制来源失效、查找取消及晚到响应不提交。
- 真实 `PcView` 在无窗口 `emulator-5580` 中运行。横屏 1512×1008、竖屏 1008×1512、窄屏 540×1200（字体 1.3）各通过两项原生测试，共 27 次实际 Activity 重建。
- 测试调用应用共享动作接口和自己的 View 控件；截图由 Activity／确认框自己的 decor View 渲染，未使用 computer use、系统输入或整屏截图。每种尺寸保存浅深色应用设置、确认框、连接设置、设备资料和添加页，共 30 张本轮截图。底部操作按钮均完整可见；窄屏设置内容通过滚动访问。
- 只读取已有模拟器设备，不配对、不建立串流、不修改连接参数；校验连接配置保持不变，并恢复原主题。结束后模拟器恢复 1512×1008、density 240、字体 1.0。
- Windows 预览只读核对：DISPLAY2，(-3776,64)，未激活。日常偏好文件 SHA-256 为 `030B64999823FEBB5FA55271883B454A05867F4AAA57264739BB06236F7F8092`，与此前一致。

证据目录：`D:\RHRemoteTools\ui-theme-context-review-20261027`。主要文件：`navigation-before-unit.xml`、`add-error-before-unit.xml`、`android-full-fixed-build.log`、`unit-summary.json`、三份 `native-*.log`、三个 `*-theme-only` 目录及 `windows-passive-state.json`。

本地 ARM64 Release APK：`dist/android/RHRemote-v0.2.11-preview.31.apk`，SHA-256 `4DB9291D9C8DCF53FA56AF5447897D8B08AEA627A9D0AA5DC263EC35F89B6C4E`。

## 仍未完成

本轮不代表连接／恢复页或活动串流的主题、诊断开关、Android 实机、更新安装和网络同步验收通过。配置复制预览的主题恢复有单元测试，尚未在实际 Activity 重建中覆盖。安装包的正式升级与配置保留仍在 TODO。
