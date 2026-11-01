# 本机与 claw 的 Sunshine 引擎更新

2026-10-31 用户批准两台更新，沿用 `D:\RHRemoteTools\server-pairing-update-20261030\engine-patch\deployment-plan.json`。该计划在 10-30 准备时未获重启授权；现在的授权记录为 authorization-20261031.json，单独固定原计划摘要，不改写当时的准备状态。

范围仅为 SunshineService 下的 sunshine.exe、zlib1.dll 及包元数据。保留现有 RHRemoteHost、脚本、网页、配置和配对；不更新 winserver，不操作 OPPO，不重启业务服务或整机。此前被安全软件阻止的新辅助服务文件仍排除，没有恢复、重试或修改安全设置。

两台重新只读预检均确认原 UUID、旧引擎／DLL 摘要、配对数量及空闲状态符合计划。执行脚本在真正停止前再检查空闲，创建仅管理员／SYSTEM 可读的备份，失败尝试回滚引擎、DLL 和包元数据；不覆盖并发配置更改。

## 当前结果

claw 已更新并恢复 Running，独立只读核对记录为 `post-claw-20261031.json`：

| 项目 | 核对值 |
| --- | --- |
| 主机 | WIN-2HTCCO8RAKV |
| 原 UUID | C653B40F-832A-8905-799A-E107A93759EA |
| 引擎 commit | 3b358b6857826d4c7865886aa6b746cb5e25c33e |
| 引擎 SHA-256 | D6F714241D3BB5077F1194584E61A197A82F6F6FF68AC375B5200AD69AFB85B8 |
| 辅助服务 SHA-256（未改） | E4DF66CD21D24BDC80E4E703C47DD5F1FF6D8844898579EADEE69192900D012D |
| 授权文件 SHA-256（未改） | E7468DADE70473A112E362DED223CE7134647FC557C129CC51D4031CA83BC092 |
| 原配对数量 | 4 |

更新脚本还核对全部 JSON／conf 配置字节没有改变。回滚备份为 claw 上 `C:\RHRemoteTools\server-pairing-update-20261030\engine-patch\rollback-20261031-000556-ca1ebcc4`。没有触发回滚。

本机原工具会话 **28906** 已结束，用户确认的 UAC 在 01:13 启动管理员进程 **65116**。管理员脚本随后退出，`local-authorized-outcome-20261031.json` 明确记录 `success:false`、`The host is not confirmed idle; no service was stopped`。这是终态失败，不能再把它描述成等待 UAC，也不重复启动原请求。

01:15 独立只读核对 `local-post-idle-rejection-20261031.json` 确认主机为 `SUNSHINE_SERVER_BUSY`、`currentgame=881448767`。SunshineService 仍 Running，旧引擎 SHA 为 `8F2DAE5C7909DDDCDF4699E11CD7DF785C4D43C81C027EFEFF9F26906597EB42`，UUID 不变。没有停止服务、替换文件或触发回滚。保留现有会话；后续必须在新的空闲核对通过后安排本机更新，不擅自中断未知归属的串流。

此前普通权限下的完整只读核对读取已安装的辅助服务文件时返回拒绝访问，已保留错误，没有重试该文件或更改权限／安全设置。后来管理员更新脚本实际遇到的停止原因是主机不空闲，不能将两件事混为一谈。
这份记录证明部署和保留检查，不代替更新后的真实同一身份重复配对、取消与恢复验收；这些工作继续保留在 [TODO](../TODO.md)。客户端 UI 141 的测试发生在本次服务授权之前，二者分别记录，见[客户端启动与检查](WINDOWS-STARTUP-CHECKS-20261030.md)。

## 后续空闲核对与更新请求

03:02 的 serverinfo 仍为 BUSY／currentgame=881448767，但它只能证明应用仍在运行，不能单独证明还有活跃串流。完整连接日志的最后一条是 01:16:56 CLIENT DISCONNECTED；随后重新运行原方案的完整只读预检，`local-fresh-preflight-20261031-0307.json` 确认 idle=true、原 UUID、旧引擎／DLL 和配对数量符合要求。没有据 BUSY 状态强制取消应用或关闭客户端。

因此沿用已经授权的原计划发起新一次管理员更新请求，单独保存 `Run-LocalAuthorized-Retry-20261031-0310.ps1` 和 `local-update-retry-20261031-0310.json`，只改变本次日志／结果文件名，不覆盖 01:13 的终态记录。真正停止前原脚本仍会再次检查空闲、校验原辅助服务和创建受限备份。该次 UAC 被接受，但启动前出现新的活跃串流；下面记录其终态及后续等待。

03:03 对 claw 的独立 serverinfo／安装文件只读核对再次确认原 UUID、FREE／currentgame=0、新引擎 SHA 与 commit，以及原授权文件摘要和 4 个配对。没有重新读取已安装辅助服务文件、重启服务或写入授权状态。证据为 `claw-fresh-review-20261031-0304.json`。

03:05:31 新客户端连接早于管理员进程 03:05:35 启动；因此 `local-authorized-outcome-20261031-0310.json` 同样在停止服务前拒绝执行。该连接 03:06:10 已断开；03:06 独立核对仍是原引擎和 Running 服务，完整预检重新通过。没有重启或替换文件，也未杀死该连接。

为减少 UAC 期间新连接带来的反复，准备了独立的 `Run-LocalAuthorized-Retry-20261031-0312.ps1`：原引擎更新脚本与批准的部署计划均未修改，只增加最多 90 秒的只读预检等待，连续空闲至少 5 秒才调用原更新；出现其他错误立即终止，实际停止前原检查仍保留。本次请求工具会话为 87218，输出文件为 `local-authorized-outcome-20261031-0312.json`。截至本记录尚在等待本次 UAC，不能复用已结束的管理员进程或将先前的“已确认”当成新进程已获系统权限。

05:38 再次对同一工具会话 87218 核对，仍为运行中且没有新输出；请求进程 183484 与系统 consent 进程仍存在，管理员运行信息和本轮 outcome 文件均未生成。独立只读核对确认 SunshineService 为 Running、引擎仍为旧摘要 `8F2DAE5C7909DDDCDF4699E11CD7DF785C4D43C81C027EFEFF9F26906597EB42`。这是待完成的系统确认，不是部署成功或已启动管理员脚本；未重新发起请求。记录位于 `D:\RHRemoteTools\ui-update-intent148-review-20261031\local-service-status148.json`。等待期间已完成两端更新页的本地候选与验证，见[148 记录](UPDATE-ACTION-INTENT-20261031.md)。

07:16 核对原请求：183484／124188 仍存在，runtime／outcome 仍未生成，SunshineService 为 Running。本次普通权限进程快照无法取得引擎路径，因此不将缺少新文件摘要当作已验证版本；没有重复启动、停止服务或重试读取原辅助服务文件。证据为 `D:\RHRemoteTools\ui-diagnostic149-review-20261031\local-service-status149.json`。等待期间完成 Android 诊断取消、结果页与实际平台对照，见[149 记录](ANDROID-DIAGNOSTIC-CANCELLATION-20261031.md)。

10:06 核对原工具会话 87218 仍未结束，183484／124188 仍存在，本轮 runtime／outcome 仍未生成，SunshineService 为 Running，PID 91848。没有新发起管理员请求、停止服务或读已安装辅助文件；本轮未确认已安装引擎摘要，不能将用户先前确认或服务 Running 当作更新成功。证据为 `D:\RHRemoteTools\ui-android-inspection153-review-20261031\local-service-status153.json`。Android 153 的隔离回环与界面测试没有使用本机串流，也不代替该部署验收。

## 10:38 后的新结果

用户要求新增本机配对后，已通过服务器 PIN 接口完成，并从 clients/list 核对启用状态。87218 随后取得管理员权限，PID 57876，于 10:38:32 在停止前以“Paired clients changed after review”退出；原预检要求 1 个客户端，实际已经有 2 个。该请求已终止，不能再描述为等待 UAC。

在原授权范围内仅刷新本机 expectedClientCount 为 2；引擎／DLL、原 UUID、辅助服务、配置与回滚要求均不变。独立方案在 `D:\RHRemoteTools\ui-status155-review-20261031\local-engine-review\deployment-plan.json`，原方案及其授权摘要不改。普通权限只读预检通过；派生方案 SHA-256 为 `EB10085A6AD46548280412FC7BCD4AD7FC400A1481EDDE0A17344A018CD25DCD`，新增配对保留。

用户确认本次 UAC 后，管理员进程 28220 于 10:45:05 启动。日志显示另一条串流在 10:45:01 开始、10:45:02 CONNECTED，随后 90 秒没有取得连续空闲。10:46:36 的本次 outcome 为 success:false、No stable idle period was available，进程已结束，服务没有停止。已告知用户可恢复使用，不再重复请求 UAC；后续须先确认该会话结束，再安排原更新。不能擅自取消未知客户端来通过预检。

证据为上述独立目录的 preflight-155.json、elevated-runtime155.json 和 local-authorized-outcome-20261031-0312.json。原目录同名 outcome 属于 10:38 的配对数量变化，两份分别保留。本次没有重读此前拒绝访问的已安装辅助服务文件、重试被安全软件阻止的新辅助服务包或修改安全设置。
