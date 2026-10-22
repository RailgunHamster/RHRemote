param([Parameter(Mandatory)][string]$WindowsDirectory,[string]$Version='0.2.3')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if($Version -ne '0.2.3'){throw 'Update application version metadata before preparing a different release'}
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
if($badging -notmatch "name='com.rhremote.client'.*versionCode='203'.*versionName='0.2.3'"){throw 'APK package/version metadata differs from candidate'}
$apkName="RHRemote-Android-$Version-arm64-v8a.apk"
Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $out $apkName)
$android=[ordered]@{schemaVersion=1;android=[ordered]@{version=$Version;versionCode=203;packageName='com.rhremote.client';tag="v$Version";fileName=$apkName;size=$apk.Length;sha256=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant();notes='Automatic LAN / Tailscale address selection, VPN packet sizing and paired network diagnostics.'}}
[IO.File]::WriteAllText((Join-Path $out 'releases.android.json'),($android|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))

# Archive the exact committed monorepo, including the maintained Sunshine tree.
# Local snapshots, build output and credentials are untracked and never scanned.
if((& git -C $repo status --porcelain --untracked-files=no)){throw 'Commit product sources before preparing a release'}
$source=Join-Path $out "RHRemote-$Version-source.zip"
& git -C $repo archive --format=zip --output=$source HEAD
if($LASTEXITCODE-ne0){throw 'Committed source archive failed'}
$relative=@(& git -C $repo ls-files)
Copy-Item -LiteralPath (Join-Path $repo ('design/RELEASE-NOTES-v'+$Version+'.md')) -Destination (Join-Path $out 'RELEASE-NOTES.md')
$checksums=@(Get-ChildItem -LiteralPath $out -File|Sort-Object Name|ForEach-Object {((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())+'  '+$_.Name})
[IO.File]::WriteAllLines((Join-Path $out 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
$artifacts=@(Get-ChildItem -LiteralPath $out -File|ForEach-Object {[ordered]@{fileName=$_.Name;size=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
$plan=[ordered]@{version=$Version;tag="v$Version";github='RailgunHamster/RHRemote';gitee='railgunhamster/RHRemote';visibility='public';published=$false;developmentBuild=$true;artifacts=$artifacts}
[IO.File]::WriteAllText((Join-Path $out 'publish-plan.json'),($plan|ConvertTo-Json -Depth 7))
[pscustomobject]@{directory=$out;artifacts=$artifacts.Count;sourceFiles=$relative.Count}
