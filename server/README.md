# RHRemote-Sunshine

`sunshine/` is maintained Sunshine source, imported with **Git subtree** from the official LizardByte repository. It is not an external build directory or a Sunshine submodule. `UPSTREAM.json` records the upstream commit, license and pinned third-party dependencies. Original copyrights, LICENSE and NOTICE remain in the subtree.

`crates/rhremote-host/` and `host/scripts/` contain the Rust host extensions. Existing released clients can still use the separately installed companion during migration; moving the source does not replace a running Sunshine service or change pairing identity.

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

The output in `dist/server/` includes `sunshine.exe`, service supervisor, tools, assets, web UI, license and upstream metadata. Building does not install or restart a service. `tools/prepare-sunshine.py` remains a historical A/B diagnostic helper for a separate clean upstream checkout; it is not used to generate or overwrite the maintained server source.

`python tools/test-upstream-merge.py` runs isolated real-Git regressions: a nonconflicting official update retains RHRemote fixes, a conflicting update stops with both changes present, dirty tracked source is rejected, and an unexpected remote is rejected.

Display branding is **RHRemote-Sunshine**. Internal Sunshine protocol IDs, certificate files, configuration paths and the `SunshineService` service identity remain compatible for upgrades. Client display branding is **RHRemote-Moonlight**; its package ID, signing identity and user data are preserved.
