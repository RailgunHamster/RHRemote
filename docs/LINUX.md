# Ubuntu 支持

当前目标是 Ubuntu 24.04 amd64 的 X11 会话，包含 RHRemote-Moonlight 客户端和 RHRemote-Sunshine 服务端。Wayland、Ubuntu 20.04 和 ARM 不在本轮二进制兼容范围内。

## 安装

下载对应的 `.deb` 与同目录的 `SHA256SUMS.txt`，核对校验值，然后安装需要的一端，也可以同时安装两端：

```sh
sha256sum --check SHA256SUMS.txt
sudo apt install ./rhremote-client_0.2.15_ubuntu24.04_amd64.deb
sudo apt install ./rhremote-server_0.2.15_ubuntu24.04_amd64.deb
```

客户端从应用菜单的 **RHRemote-Moonlight** 启动，或运行 `rhremote`。Ubuntu 软件包通过 `apt install ./新版.deb` 覆盖升级，保留用户配置；此安装方式没有接入 Velopack AppImage 自动替换。

## 服务端

在登录页选择 **Ubuntu on Xorg**，登录后运行 `rhremote-server open`。它启动当前用户的统一服务并打开 `https://localhost:47990/`。首次使用在网页设置管理账号，然后从客户端添加服务器地址并配对。

```sh
rhremote-server start
rhremote-server status
rhremote-server enable   # 当前桌面会话自动启动
rhremote-server stop
journalctl --user -u rhremote-sunshine -n 50
```

服务端配置使用 `~/.config/sunshine`，客户端使用 `${XDG_CONFIG_HOME:-$HOME/.config}/rhremote`。已有 Sunshine 配置不会被初始化模板覆盖。需要自定义服务端配置目录时，在启动前设置绝对路径 `RHREMOTE_SUNSHINE_CONFIG`。安装不会自动停止其他 Sunshine 实例；默认端口已经占用时，应先处理已有实例。

## 无桌面 Ubuntu Server 的独立桌面

可选的独立 Xorg 桌面使用 dummy 显示驱动，适用于没有其他图形会话的专用服务器。桌面版 Ubuntu 使用已有的 Xorg 会话；本轮不支持独立桌面与其他图形会话同时运行，检测到已有 X11 会话时会拒绝初始化或启动独立桌面服务。先安装依赖，再为实际使用远程服务的普通用户执行一次初始化；显示编号必须尚未使用：

```sh
sudo apt install xserver-xorg-video-dummy openbox xauth x11-xserver-utils acl
sudo rhremote-headless --user "$USER" --display 99
export DISPLAY=:99
export XAUTHORITY="$HOME/.config/rhremote-headless/Xauthority"
export XDG_SESSION_TYPE=x11
export RHREMOTE_HEADLESS=1
rhremote-server enable
```

独立桌面初始为 1920×1080。客户端可以选择较低的串流分辨率；本轮没有实现根据平板横竖屏自动创建任意 X11 显示模式。初始化脚本启用指定用户的 linger、独立 Xorg 和 Openbox 服务，X11 TCP 监听关闭，授权 cookie 保存在用户私有文件中。只配置它创建的独立桌面，不修改已有物理显示器布局。

## 悬浮按键

串流出画面后，打开顶部 **控制**，启用 **悬浮键盘**；面板收起后，方向键显示在右下，Tab / Shift+Tab 显示在左侧，其他按键分布在边缘。再次打开控制面板可以关闭。按键以约 65% 不透明度显示，按键之间的空白区域允许点击远程画面。Linux 的此功能使用 X11 输入区域和窗口所属进程校验。

## 当前验证记录

执行记录存放于专用测试目录 `D:\RHRemoteTools\linux-ubuntu-20261101`。仅将已实际通过的连接、按键、恢复与安装结果写入最终验收记录；编译成功不等同于串流或硬件加速通过。软件编解码虚拟机上的验证不替代 Intel / AMD / NVIDIA 实体 GPU 验证。
