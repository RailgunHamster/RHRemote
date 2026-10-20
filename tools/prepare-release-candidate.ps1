param([Parameter(Mandatory)][string]$WindowsDirectory,[string]$Version='0.2.1')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if($Version -ne '0.2.1'){throw 'Update application version metadata before preparing a different release'}
$out=Join-Path $repo ('dist/release-candidates/'+$Version+'-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out|Out-Null
$feed=Get-Content -LiteralPath (Join-Path $WindowsDirectory 'releases.win.json') -Raw|ConvertFrom-Json
if($feed.Assets.Count -ne 1 -or $feed.Assets[0].Version -ne $Version){throw 'Unexpected Windows release feed'}
foreach($name in @('RHRemote-win-Setup.exe','RHRemote-win-Portable.zip','releases.win.json','RELEASES',$feed.Assets[0].FileName)){Copy-Item -LiteralPath (Join-Path $WindowsDirectory $name) -Destination $out}
$package=Get-Item -LiteralPath (Join-Path $out $feed.Assets[0].FileName)
if($package.Length -ne $feed.Assets[0].Size -or (Get-FileHash -LiteralPath $package.FullName -Algorithm SHA256).Hash -ne $feed.Assets[0].SHA256){throw 'Windows package checksum mismatch'}
$apk=Get-Item -LiteralPath (Join-Path $repo "dist/android/RHRemote-v$Version.apk")
$aapt='C:\Users\40608\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt.exe'
$badging=(& $aapt dump badging $apk.FullName)|Select-Object -First 1
if($badging -notmatch "name='com.rhremote.client'.*versionCode='201'.*versionName='0.2.1'"){throw 'APK package/version metadata differs from candidate'}
$apkName="RHRemote-Android-$Version-arm64-v8a.apk"
Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $out $apkName)
$android=[ordered]@{schemaVersion=1;android=[ordered]@{version=$Version;versionCode=201;packageName='com.rhremote.client';tag="v$Version";fileName=$apkName;size=$apk.Length;sha256=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant();notes='Remote device ordering with WebDAV synchronization.'}}
[IO.File]::WriteAllText((Join-Path $out 'releases.android.json'),($android|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))

# Corresponding application sources only. Exclude every real device snapshot, credential, binary and cache.
$relative=@('.gitignore','Cargo.toml','Cargo.lock','rust-toolchain.toml','README.md','LICENSE','client/LICENSE','client/README.md','client/StableCadence.h','client/test-cadence.cpp','patches/moonlight-6.1.0-rhremote.patch','design/API.md','design/PRODUCT.md','design/IMPLEMENTATION-STATUS.md','design/STATISTICS.md','design/RELEASE-NOTES-v0.2.0.md','design/RELEASE-NOTES-v0.2.1.md')
foreach($folder in @('apps','crates')){$base=Join-Path $repo $folder;Get-ChildItem -LiteralPath $base -File -Recurse|ForEach-Object {$relative+=$_.FullName.Substring($repo.Length+1)}}
foreach($script in @('prepare-android.ps1','prepare-windows.ps1','build-android.ps1','build-windows.ps1','build-rust-windows.ps1','package-windows-velopack.ps1','prepare-release-candidate.ps1','build-icons.ps1','derive-icons.py','render-icons.cpp','patch-statistics.py','analyze-statistics.py','statistics-viewer.html','collect-statistics.ps1','diagnose-host-startup.ps1','diagnose-qsv-startup.ps1','run-console-probe.ps1','test-statistics.py','enable-host-audio.ps1','publish-release.ps1','run-release-publisher.ps1','verify-release-downloads.ps1','test-statistics-viewer.cjs','Invoke-RHRemoteAction.ps1','Invoke-RemoteScript.ps1','start-rust-client.ps1','connect-android-control.ps1','test-cross-client-sync.ps1','test-android-client-winserver.ps1','test-rust-client-winserver.ps1','test-windows-package.ps1','sync-fixture-server.py','export-rust-ui-previews.ps1','tablet-interaction-probe.ps1','tablet-motion-control.ps1','wts.ps1','displayconfig.ps1','build-cadence-test.cmd')){$relative+='tools/'+$script}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$source=Join-Path $out "RHRemote-$Version-source.zip";$archive=[IO.Compression.ZipFile]::Open($source,[IO.Compression.ZipArchiveMode]::Create)
try{foreach($entry in ($relative|Sort-Object -Unique)){$path=[IO.Path]::GetFullPath((Join-Path $repo $entry));if(-not $path.StartsWith($repo+'\',[StringComparison]::OrdinalIgnoreCase) -or $entry -match '(^|[\\/])(results|dist|target|\.git)([\\/]|$)' -or $entry -match '\.(ini|pem|key|pfx|p12|log|apk|exe|dll)$'){throw 'Invalid source archive entry'};[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$path,$entry.Replace('\','/'))|Out-Null}}finally{$archive.Dispose()}
Copy-Item -LiteralPath (Join-Path $repo ('design/RELEASE-NOTES-v'+$Version+'.md')) -Destination (Join-Path $out 'RELEASE-NOTES.md')
$checksums=@(Get-ChildItem -LiteralPath $out -File|Sort-Object Name|ForEach-Object {((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())+'  '+$_.Name})
[IO.File]::WriteAllLines((Join-Path $out 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
$artifacts=@(Get-ChildItem -LiteralPath $out -File|ForEach-Object {[ordered]@{fileName=$_.Name;size=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
$plan=[ordered]@{version=$Version;tag="v$Version";github='RailgunHamster/RHRemote';gitee='railgunhamster/RHRemote';visibility='public';published=$false;developmentBuild=$true;artifacts=$artifacts}
[IO.File]::WriteAllText((Join-Path $out 'publish-plan.json'),($plan|ConvertTo-Json -Depth 7))
[pscustomobject]@{directory=$out;artifacts=$artifacts.Count;sourceFiles=$relative.Count}
