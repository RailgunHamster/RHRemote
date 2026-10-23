param(
 [ValidatePattern('^\d+\.\d+\.\d+-preview\.[1-9]\d*$')][string]$Version='0.2.4-preview.1',
 [ValidateRange(204,2100000000)][int]$VersionCode=204,
 [ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedSignerSha256='2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
& git -C $repo diff --quiet HEAD --
if($LASTEXITCODE -ne 0){throw 'Commit product changes before preparing a preview'}
$commit=(& git -C $repo rev-parse HEAD).Trim()
if($commit -notmatch '^[a-f0-9]{40}$'){throw 'Source commit unavailable'}
$apk=Join-Path $repo ('dist/android/RHRemote-v'+$Version+'.apk')
$buildTools=Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/37.0.0'
$badging=(& (Join-Path $buildTools 'aapt.exe') dump badging $apk)|Select-Object -First 1
$pattern="name='com.rhremote.client'.*versionCode='"+$VersionCode+"'.*versionName='"+[regex]::Escape($Version)+"'"
if($LASTEXITCODE -ne 0 -or $badging -notmatch $pattern){throw 'Preview APK identity differs from the requested metadata'}
$signers=@(& (Join-Path $buildTools 'apksigner.bat') verify --print-certs $apk)
if($LASTEXITCODE -ne 0){throw 'Preview APK signature verification failed'}
$digests=@($signers|ForEach-Object {if($_ -match 'certificate SHA-256 digest: ([0-9a-fA-F]{64})'){$matches[1].ToLowerInvariant()}}|Sort-Object -Unique)
if($digests.Count -ne 1 -or $digests[0] -ine $ExpectedSignerSha256){throw 'Preview APK signer differs from the installed release signer'}
$notes=Join-Path $repo ('design/RELEASE-NOTES-v'+$Version+'.md')
if(-not(Test-Path -LiteralPath $notes)){throw 'Preview release notes are missing'}
$directory=Join-Path $repo ('dist/release-candidates/'+$Version+'-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory|Out-Null
$fileName='RHRemote-Android-'+$Version+'-arm64-v8a.apk'
Copy-Item -LiteralPath $apk -Destination (Join-Path $directory $fileName)
Copy-Item -LiteralPath $notes -Destination (Join-Path $directory 'RELEASE-NOTES.md')
$metadata=[ordered]@{channel='preview';version=$Version;versionCode=$VersionCode;packageName='com.rhremote.client';sourceCommit=$commit;upstreamAndroid='b48494cb96bff23d8886c4775cc4f39a1075495d';signerSha256=$digests[0];apkSha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant();oppoInstalled=$false;actualCellularVerified=$false;stableFeedsIncluded=$false}
[IO.File]::WriteAllText((Join-Path $directory 'build.json'),($metadata|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
$checksums=@(Get-ChildItem -LiteralPath $directory -File|Sort-Object Name|ForEach-Object {((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())+'  '+$_.Name})
[IO.File]::WriteAllLines((Join-Path $directory 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
$assets=@(Get-ChildItem -LiteralPath $directory -File|Sort-Object Name|ForEach-Object {[ordered]@{fileName=$_.Name;size=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
$plan=[ordered]@{version=$Version;tag=('v'+$Version);preview=$true;commit=$commit;artifacts=$assets}
[IO.File]::WriteAllText((Join-Path $directory 'publish-plan.json'),($plan|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{directory=$directory;commit=$commit;tag=$plan.tag;apkSha256=$metadata.apkSha256;signerSha256=$digests[0];assets=$assets.Count}
