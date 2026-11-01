# 更新和发布

客户端默认从公开的 [CNB 仓库](https://cnb.cool/railgunhamster/RHRemote/-/releases)读取更新；请求失败自动尝试 GitHub。Windows 与 Android 使用相同顺序。产品中不提供家庭服务器或 Gitee 更新源。

- 最新信息：`https://cnb.cool/railgunhamster/RHRemote/-/releases/latest/download/releases.win.json`、`releases.android.json`。
- 安装包固定到元数据对应的版本，例如 `.../-/releases/download/v0.2.12/RHRemote-0.2.12-full.nupkg`；下载后仍校验大小、SHA-256，Android 同时校验包名、版本和签名。
- 更新页面保留单个主按钮：检查更新 → 下载并安装 → 重启并安装（Windows）／系统安装确认（Android）。
- 客户端匿名访问；发布令牌不写入应用、更新信息、Git 或命令行。

在保存加密 CNB 令牌的 Windows 发布机使用 PowerShell 7：

```powershell
./tools/publish-cnb.ps1 -CandidateDirectory <已审核发布目录> -Commit <精确提交> -BundlePath <公开源码bundle> -ValidateOnly
./tools/publish-cnb.ps1 -CandidateDirectory <已审核发布目录> -Commit <精确提交> -BundlePath <公开源码bundle>
```

默认读取当前用户 `LocalAppData/RHFiles-Publishing/cnb-token.xml` 的 DPAPI 凭据。可通过 `-TokenFile` 指定另一份当前用户的加密文件。首次发布会创建公开的 `railgunhamster/RHRemote` 仓库。先上传并匿名校验不可变附件，全部通过后才提升为最新版本，再核对两个平台的 latest 信息。已有同名附件不会覆盖。GitHub 备用仍使用 `publish-release.ps1 -Provider github`；旧 Gitee 发布脚本仅保留历史兼容，不再用于新版本的更新发布。

测试：`python tools/test_cnb_release.py`；Rust `updates::tests`；Android `RhUpdateInstallTest`、`RhUpdatesTest`。
