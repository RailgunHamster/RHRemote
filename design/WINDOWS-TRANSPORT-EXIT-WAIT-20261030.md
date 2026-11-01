# Windows 退出期间的路径查询与清理等待

本地 Windows preview.110 回归实际出现：子进程正在退出，QueryFullProcessImageNameW 已返回拒绝访问，但原进程句柄尚未发出退出信号。原检查只等 10 ms，之后把该错误上交；清理器再等到总预算结束并报告失败，即使子进程随后已退出。

该问题来自本轮完整回归的实际失败，不是已证明的全部初次连接或控制界面卡顿根因。证据：`D:\RHRemoteTools\ui-sync-commit109-review-20261030\windows110-final-build-tests.log`，`exit_during_a_real_path_query_is_not_a_live_inspection_failure`。

现在把清理器原有截止时间传给子进程检查。只有路径查询失败时，才在剩余预算内等待**同一原进程句柄**；句柄真正发出退出信号后可以继续清理。正常路径查询没有新增等待，预算没有延长，权限拒绝也不会被当成退出。没有按 PID 重新寻找或终止进程。

Windows 完整 workspace 回归通过，包含 Core 135／UI 226，另有一项既有忽略。原真实路径退出用例保留；新增真实隐藏子进程的确定性检查，在查询错误发生后至少延迟 80 ms 才放行子进程退出，证明不再依赖 10 ms。仍运行且查询失败、已经退出无需查询、原生路径／父进程／创建时间边界、超时不终止子进程等检查均保留。

先前一轮回归还有旧的 HTTP 423 文案断言，更新为“文件被占用”后才暴露上述退出竞争。两轮失败日志均保留，没有通过重跑或删除断言跳过问题。最终日志为 `windows110-exit-grace-build-tests.log`。

本轮只有后台构建及真实测试进程检查，Windows 物理显示器未活动，没有启动可见预览或日常客户端。preview.110 仍需物理副屏下的实际串流及退出复验；日常 preview.101 保持关闭，不将单元回归当作该范围完成。

最终候选 `dist/velopack-candidates/0.2.11-preview.110` 已完成打包。完整 nupkg 与 Portable ZIP 内的 UI SHA-256 均为 **D36B9A8E1B6EC1B85359586A52892C5B5AD13E45B39D08191C22C786D626AF83**，引擎仍为 **C789BFDF5619184428E764E4C6A785F0BD90F89130CE720BD2A906C49FC0D5EA**。包内直接哈希及日常配置保留证据见本轮 `final-audit110.json`；未发布、未覆盖日常版。
