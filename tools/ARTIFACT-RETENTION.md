# 构建产物保留规则

用户于 2026-10-29 授权清理；产品源码、Git 历史、正式发布件和正在使用的构建不作为清理对象。

`clean-build-artifacts.ps1` 默认仅生成计划；加 `-Apply` 执行。配置见 `artifact-retention.json`。

- target 总量超过 40 GiB 时，清理 debug 的 incremental、deps、build、.fingerprint、examples。保留 debug 根目录当前输出及整个 target/release；活动 Rust 构建或 debug 进程存在时跳过，使用 Cargo 缓存锁防止并发清理。
- server-* 保留最新两个及明确保护的已部署包；候选目录、Android 和 Velopack 预览包保留最新两个可用版本。发布标签对应的正式产物保留；打包 staging 只保留最新两个。
- results/runs 只删除已有 conclusion.json、无 keep-raw／.keep 标记的 raw 下大于等于 16 MiB 的文件；结论、分析及小文件保留。尚需调查的原始文件应放置保留标记。
- 删除前验证绝对路径、拒绝重解析点和受 Git 跟踪的文件，检查活动构建、进程路径，并使用 Windows Restart Manager 检查 dist 和原始文件的占用。检测失败或存在使用者时跳过，不终止应用或服务。

Windows、Android、Host 和服务包的成功构建／打包末尾调用同一工具，临时保护刚产出的目录。每日 04:00 的线程定时清理作为兜底。每次执行在 `D:\RHRemoteTools\artifact-cleanup` 生成独立报告；删除文件逻辑大小与磁盘实际可用空间变化分别记录，二者可能因硬链接和其他进程写盘而不同。

手动查看计划：`powershell -File tools\clean-build-artifacts.ps1`。执行：`powershell -File tools\clean-build-artifacts.ps1 -Apply`。不调用 cargo clean，也不操作其他工作区或公开发布站点。
