# 发布保留规则

CNB、GitHub 保留最近 3 个正式版本，以及尚未被正式版取代的最新 1 个预览版本。未发布草稿和非标准标签保持原状。源码 Git 标签及历史、本机正式发布归档、共享目录的首次安装包均不在此清理范围。

`tools/release-retention.json` 中的 `v0.2.15` 例外用于保留当前 Ubuntu 客户端和服务端。发布替代的 Linux 包后再移除此例外。

`tools/clean-release-history.ps1` 默认仅生成计划，显式 `-Apply` 才删除 Release 及其附件。执行前备份发布元数据，逐项复核发布标识、修改时间和附件指纹，删除后确认 404；同时核对匿名更新源和 Linux 包信息未改变。清理过程中若最新版本发生变化，停止后续删除。

两个发布脚本在成功发布后调用同一工具。发布机需要同时提供 `clean-release-history.ps1`、`release-retention.ps1`、`release-retention.json`。CNB 清理仍在持有原 DPAPI 凭据的机器运行；令牌不写入仓库、参数或日志。发布已经成功而清理失败时单独报告，保留清理记录供重试。
