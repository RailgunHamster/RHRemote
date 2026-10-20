RHRemote v0.2.0 开发预览

- Windows：Rust + Slint 界面；Android：原生 View + Java；统一应用图标。
- 远程设备别名、每客户端独立连接配置；画面、音频、输入、高级四类设置。
- 音频开关和音量、主副键补偿、直接点击/触控板、缩放与滚动。
- HTTP / HTTPS WebDAV 同步；5、15、30、60 分钟自动同步；配对密钥和账号凭据留在本机。
- 设置页顺序：更新、配置同步、外观、关于。
- Windows Velopack 与 Android APK 更新入口，GitHub / Gitee 下载源。
- 测试模式逐帧记录和离线 Statistics 报告；时间轴以本次连接动作为 0，明确区分渲染提交返回与物理显示。

Windows 下载 Setup.exe 安装或解压 Portable.zip。Android 下载 arm64-v8a APK，由用户确认安装，可与 Moonlight 并存。

限制：当前为开发构建，Android 使用开发签名，Windows 尚无代码签名证书；首次连接的 Sunshine / QSV 初始化耗时尚未解决。短测连接成功不能代表稳定 30 / 60 FPS 或 4K 验收。请保留现有应用数据升级，不要先卸载。
