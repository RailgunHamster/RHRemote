# RHRemote-Sunshine

`sunshine/` is maintained Sunshine source, imported with **Git subtree** from the official LizardByte repository. It is not an external build directory or a Sunshine submodule. `UPSTREAM.json` records the upstream commit, license and pinned third-party dependencies. Original copyrights, LICENSE and NOTICE remain in the subtree.

`crates/rhremote-host/` contains the unified Rust Windows service and `host/scripts/` contains its display workers. The service replaces the original Sunshine supervisor, owns the paired-client API and launches the Sunshine engine in the active console session. There is one SCM service, displayed as **RHRemote-Sunshine**. The old standalone RHRemoteHost service is removed after a successful migration.

## Upstream merges

The import commit is a clean official baseline. RHRemote startup fixes and branding are subsequent normal commits. To merge a newer official tag or commit:

```powershell
.\tools\update-sunshine.ps1 -Ref <official-tag-or-commit>
git submodule update --init --recursive -- server/sunshine/third-party/libdisplaydevice
```

The script requires a clean tracked working tree, verifies the official remote and performs a three-way `git subtree merge --squash`. Changes made by both projects remain ordinary Git merge conflicts. It never resets local edits, chooses an entire side automatically, or pushes a result. After resolving conflicts, commit the resolution, run `tools/sync-sunshine-dependencies.py --revision <merged-upstream-sha>`, review dependency metadata and rebuild both affected products before publishing. Keep RHRemote-only functionality in distinct modules where practical; changes to upstream capture/encoding code remain explicit reviewable commits.

Third-party modules retain the exact commits from Sunshine. Their paths are registered in the root `.gitmodules` so normal recursive checkout works in this monorepo. The sync tool updates registrations after upstream adds or removes modules. Moonlight remains a pinned client reference and is not automatically merged by this script.

## Builds and identity

The `RHRemote-Sunshine Windows` workflow builds `server/sunshine` from this repository, including its web UI and service supervisor. Local builds use `tools/build-server.ps1` with an existing MSYS2 UCRT64 toolchain. Client builds remain separate.

The UCRT64 packages are listed in `.github/workflows/server-windows.yml`: toolchain, CMake, Ninja, Boost, cppwinrt, curl-winssl, miniupnpc, nlohmann-json, onevpl, OpenSSL, Opus and MinHook, plus native Windows Node.js. With those installed:

```powershell
.\tools\build-server.ps1 -MsysRoot C:\msys64 -InitializeDependencies
```

The output is a new `dist/server-<timestamp>/` directory containing `sunshine.exe`, `host/RHRemoteHost.exe`, tools, display workers, assets, web UI, license, upstream metadata and a SHA256 manifest. Building does not install or restart a service. `tools/prepare-sunshine.py` remains a historical A/B diagnostic helper for a separate clean upstream checkout; it is not used to generate or overwrite the maintained server source.

`python tools/test-upstream-merge.py` runs isolated real-Git regressions: a nonconflicting official update retains RHRemote fixes, a conflicting update stops with both changes present, dirty tracked source is rejected, and an unexpected remote is rejected.

Display branding is **RHRemote-Sunshine**. Internal Sunshine protocol IDs, certificate files, configuration paths and the `SunshineService` service identity remain compatible for upgrades. Client display branding is **RHRemote-Moonlight**; its package ID, signing identity and user data are preserved.

## Install or replace the original service

In administrator PowerShell, run the installer from the extracted unified package:

```powershell
.\install-server.ps1 -SourceDirectory $PWD
```

The default installation remains `C:\Program Files\Sunshine`, including its existing `config` directory, certificates, peers and app list. `-SunshineConfig` supports a separate existing configuration directory. The installer checks every packaged file before stopping services, backs up the old installation under the protected `C:\ProgramData\RHRemote-Sunshine\backups` directory, and reconfigures `SunshineService` to run `host\RHRemoteHost.exe --sunshine-service`. The executable name is retained for compatibility with existing display workers; it does not create a second service. Existing startup/recovery policies and the Windows service-SAS policy are preserved.

Readiness requires the GameStream server to answer with the original UUID, the paired API listener to belong to the SCM service PID, and the Sunshine engine to be a child of that same PID. A failed check automatically restores the original service, binaries and companion. Successful installation deletes the old standalone RHRemoteHost service. Firewall access for streaming and the paired API is limited to the local subnet. Private configuration and certificates are excluded from packages and retained only in protected on-host backups.

For manual rollback, pass the backup printed by the installer:

```powershell
.\install-server.ps1 -RestoreBackup 'C:\ProgramData\RHRemote-Sunshine\backups\<backup>'
```

## Runtime and provenance

The Rust supervisor follows the console-token, job-object and graceful Ctrl-C behavior of the vendored upstream `tools/sunshinesvc.cpp` (GPL-3.0-only). Its implementation is in `crates/rhremote-host/src/supervisor.rs`; no extra patches to upstream Sunshine's service executable are required. A session change only restarts the engine when the active console session actually changes. An engine crash is retried while the paired API remains alive. Stopping the service shuts down the engine; an unexpected supervisor exit closes its owned job. Explicit application breakaway is retained so applications started remotely can outlive the stream, following [Windows job-object behavior](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects).

The authenticated `GET https://<host>:47995/v1/health` endpoint reports the product, unified mode, service PID, engine PID, active session and start count. It requires the same enabled Sunshine client certificate and host-certificate pin as screen and secure-attention operations. The source still supports legacy `--service` mode for rollback/compatibility; the unified package's default installer uses `--sunshine-service` exclusively.

The installer handles GameStream XML returned as raw bytes without a Content-Type header on Windows PowerShell 5.1. Diagnostics record service/engine lifecycle separately from per-connection first-frame timings; installation or service readiness time is not presented as connection latency.
