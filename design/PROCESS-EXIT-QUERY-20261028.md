# Windows 退出期间的进程查询

本地 Windows preview.44。Android 保持 preview.33；未发布，未启动新客户端窗口，未操作 OPPO、配对配置或业务服务。完整 UI 目标未完成。

## 原因与影响

preview.43 的默认并行测试曾在 `transport_owner::tests::path_parent_and_creation_boundaries_exclude_other_processes` 返回 `0x80070005`。串行通过只能说明未触发竞争，不能说明权限边界正确或根因已解决。

失败发生在取得进程句柄之后。子进程退出时，`QueryFullProcessImageNameW` 可先返回错误 5（拒绝访问），此时同一原句柄的 `WaitForSingleObject(..., 0)` 仍返回 258；随后句柄才进入已退出状态。原代码只在查询前检查一次退出，直接把期间的路径查询错误当成仍在运行的检查失败。

十次原生调用测量均复现这一顺序；首次错误 5 与首次观察到句柄退出之间约 0.59～2.79 毫秒。`GetProcessTimes` 同期仍可成功。测量对象为自己创建的隐藏 `cmd.exe`，通过内置 `set /p` 等待标准输入，再自然退出；没有额外后代或业务进程参与。时间是本机实测，不是 Windows 的时限保证。

生产调用位于 `transport_worker.rs` 的 UI 管道 EOF 后清理：`owner.drain(Duration::from_secs(20))`。该阶段遇到检查错误原先会保留代理至整个清理预算结束，因而这个竞争可能让退出清理等待到 20 秒。本轮没有证明此前初次连接的 37 秒或其他首帧等待由此导致，也没有改变其预算。

## 修复

对保留的同一个进程句柄执行检查：已退出则不查询；查询成功后再次确认是否已退出；查询失败时名义最多等待 10 毫秒，只有该原句柄明确进入已退出状态才跳过。调度可能令墙钟等待略长于指定超时。仍在运行、等待超时或等待失败均保留原错误。

没有按错误码盲目忽略拒绝访问，没有增加进程权限，没有终止其他进程。父进程 PID、创建时间区间与原生程序路径检查保持不变，保留句柄继续防止 PID 复用混淆身份。原路径／父进程边界测试改为标准输入门控，避免依赖测试子进程恰好在一秒内不退出，并先断言正确身份可被选中。

微软文档分别说明了[路径查询所需访问权限](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-queryfullprocessimagenamew)、[等待原句柄的返回值与超时语义](https://learn.microsoft.com/en-us/windows/win32/api/synchapi/nf-synchapi-waitforsingleobject)，以及[进程退出后对象进入有信号状态、保留句柄维持对象有效](https://learn.microsoft.com/en-us/windows/win32/procthread/terminating-a-process)。错误 5 先于退出信号的具体顺序来自本轮测量。

## 验证与证据

证据目录：`D:\RHRemoteTools\ui-process-query-review-20261028`。

- `native-query-transition-before.json`：十次实际 Win32 调用的状态转移；`native-query-before.json` 保留完全退出后查询的对照。
- `real-race-regression-before.log`：新增真实退出回归在原实现明确失败，实际错误为 `0x80070005; signaled=false`。不是仅注入错误的模拟复现。
- `owner-tests-after.log`：修复后八项归属测试通过，真实竞争仍发生但正确归为已退出；另验证活进程的查询失败继续返回原错误，已退出进程不再执行路径查询。
- `windows44-full-parallel.log`：核心 112／UI 126 项默认并行通过；`windows44-ui-parallel-2.log`、`windows44-ui-parallel-3.log` 的 UI 126 项默认并行均通过。未使用串行测试代替竞争回归。
- `windows44-package.log` 与 `verification-process44.json` 保存构建结果及产物身份；发布、实体副屏实际控件、长时重绘和完整 UI 流程仍独立验收。

全程为后台构建、测试与 Win32 调用；没有 computer use、系统输入或客户端窗口激活。日常偏好配置不作修改，Android 不重复构建。
