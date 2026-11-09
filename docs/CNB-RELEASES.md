# 更新和发布

客户端默认从公开的 [CNB 仓库](https://cnb.cool/railgunhamster/RHRemote/-/releases)读取更新；请求失败自动尝试 GitHub。Windows、Android 从 0.2.13 起可在“设置 → 更新 → 首选更新源”中选择 GitHub，失败时尝试 CNB。选择保存在本机，不参与 WebDAV 同步；更新过程中禁止切换。产品中不提供家庭服务器或 Gitee 更新源。

## 发布渠道与共享目录

用户决定（2026-11-02）：后续版本只维护 CNB 和 GitHub，默认 CNB、GitHub 备用。不再向共享目录复制每个正式版或预览版，也不恢复家庭服务器、Gitee 更新源。

`\\server-home\public\Software\RHRemote` 仅作为首次安装入口，保留一套支持上述在线更新的 Windows 安装包和 Android APK，以及使用说明、校验文件。当前保留正式版 0.2.16；它不需要随每次发布更新。安装后通过客户端“设置 → 更新”升级。只有该安装入口失效、签名或更新协议兼容性确实需要迁移，或用户另行要求时，才替换这一套安装包；不保留共享目录中的历史版本和预览目录。Ubuntu 安装包从 CNB / GitHub 发布页获取。

共享目录不是客户端更新源，后续发布无需访问该目录。清理其冗余副本不删除 CNB / GitHub 附件、本地正式发布件、源码或标签。

## 更新接口与发布操作

同一操作也可通过动作接口调用：`updates.source.set`，参数 `{"source":"github"}` 或 `{"source":"cnb"}`；`updates.state` 返回 `preferredSource`。切换后点击“检查更新”；已经校验的安装包继续保留。

- 最新信息：`https://cnb.cool/railgunhamster/RHRemote/-/releases/latest/download/releases.win.json`、`releases.android.json`。
- 安装包固定到元数据对应的版本，例如 `.../-/releases/download/v0.2.12/RHRemote-0.2.12-full.nupkg`；下载后仍校验大小、SHA-256，Android 同时校验包名、版本和签名。
- 更新页面保留单个主按钮：检查更新 → 下载并安装 → 重启并安装（Windows）／系统安装确认（Android）。
- 客户端匿名访问；发布令牌不写入应用、更新信息、Git 或命令行。

在保存加密 CNB 令牌的 Windows 发布机使用 PowerShell 7：

每个新版本构建前，先运行 `tools/update-release-history.ps1 -Version <版本>`，将当前正式版的说明和时间加入 `client/shared/release-history.json` 并提交，再构建客户端和准备发布目录。候选目录会包含 `releases.history.json`；客户端首次展开历史区时从首选更新源读取，失败尝试备用源，并保留包内记录供离线查看。准备脚本核对当前版本说明和发布时间，避免历史数据遗漏或与安装包说明不一致。历史区和各版本正文默认折叠，相关按钮使用 `updates.history.*` 动作；Markdown 链接使用 `updates.notes.open`。

```powershell
./tools/publish-cnb.ps1 -CandidateDirectory <已审核发布目录> -Commit <精确提交> -BundlePath <公开源码bundle> -ValidateOnly
./tools/publish-cnb.ps1 -CandidateDirectory <已审核发布目录> -Commit <精确提交> -BundlePath <公开源码bundle>
```

默认读取当前用户 `LocalAppData/RHFiles-Publishing/cnb-token.xml` 的 DPAPI 凭据。可通过 `-TokenFile` 指定另一份当前用户的加密文件。首次发布会创建公开的 `railgunhamster/RHRemote` 仓库。先上传并匿名校验不可变附件，全部通过后才提升为最新版本，再核对两个平台的 latest 信息。已有同名附件不会覆盖。GitHub 备用仍使用 `publish-release.ps1 -Provider github`；旧 Gitee 发布脚本仅保留历史兼容，不再用于新版本的更新发布。

测试：`python tools/test_cnb_release.py`；Rust `updates::tests`；Android `RhUpdateInstallTest`、`RhUpdatesTest`。

## Windows 服务端自动更新（0.2.23 起）

服务端在 Sunshine 网页首页提供更新面板，管理接口为 `GET/POST /api/rhremote/updates`、`POST /api/rhremote/updates/action`（`{"action":"check"}` 或 `{"action":"install"}`），沿用管理员认证和 CSRF 校验。默认 CNB、GitHub 备用，检查间隔支持 5/15/30/60 分钟。启用后等待空闲安装；设置保存在 `%ProgramData%/RHRemoteHost/updates/settings.json`，不参与客户端 WebDAV 同步。

发布前使用 `assemble-server.ps1 -Version <服务端版本>` 生成含版本的服务端包，再用 `new-server-update-feed.ps1 -Archive <ZIP> -ReleaseTag v<本次发布版本> -OutputDirectory <新目录>` 生成清单。发布账户在 winserver 运行 `Protect-RHRServerUpdateManifest`，私钥是 DPAPI 加密的 `%LOCALAPPDATA%/RHRemote-Publishing/server-update-rsa3072.xml`，不得复制到源码、客户端或其他电脑。将签名后的资源目录传给 `prepare-release-candidate.ps1 -ServerAssetsDirectory <目录>`。每次发布都必须携带 `releases.server.win.json`、`.sig` 和清单指定的 ZIP；源提交、文件大小和 SHA-256 均须对应实际服务端包。

客户端单独发版时，可携带上一个服务端包，但需将清单 `releaseTag` 指向本次标签并重新签名。服务端版本保持不变，因此已更新的机器不会重复安装。这样后续清理旧发布也不会破坏更新下载。发布和匿名下载校验脚本会同时校验服务端信息。

首次部署使用 `install-server.ps1 -SourceDirectory <已核验新包> -EnableAutoUpdate`，需要管理员权限；此后签名正式版本由 SYSTEM 更新进程安装，无需再次手工处理每台电脑。自动更新仅替换包内公共文件，保留配置、配对及业务服务。发生回滚的同一版本不会自动反复安装，发布更高版本后再自动尝试。
