param(
 [Parameter(Mandatory)][string]$WindowsBaselineDirectory,
 [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version='0.2.4',
 [ValidateRange(206,2100000000)][int]$VersionCode=206,
 [string]$Notes='See RELEASE-NOTES.md for changes.',
 [ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedSignerSha256='2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
& git -C $repo diff --quiet HEAD --
if($LASTEXITCODE -ne 0){throw 'Commit product changes before preparing a release'}
$commit=(& git -C $repo rev-parse HEAD).Trim()
if($commit -notmatch '^[a-f0-9]{40}$'){throw 'Source commit unavailable'}
$baseline=Get-Content -LiteralPath (Join-Path $WindowsBaselineDirectory 'publish-plan.json') -Raw|ConvertFrom-Json
if($baseline.preview -or $baseline.version -notmatch '^\d+\.\d+\.\d+$'){throw 'Expected a stable Windows baseline'}
foreach($name in @('releases.win.json','RELEASES')){
 $expected=@($baseline.artifacts|Where-Object fileName -eq $name)
 $path=Join-Path $WindowsBaselineDirectory $name
 if($expected.Count -ne 1 -or (Get-Item -LiteralPath $path).Length -ne $expected[0].size -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ine $expected[0].sha256){throw ('Windows baseline changed: '+$name)}
}
$winFeed=Get-Content -LiteralPath (Join-Path $WindowsBaselineDirectory 'releases.win.json') -Raw|ConvertFrom-Json
if($winFeed.Assets.Count -ne 1 -or $winFeed.Assets[0].Version -ne $baseline.version){throw 'Windows baseline feed identity differs'}
$apk=Join-Path $repo ('dist/android/RHRemote-v'+$Version+'.apk')
$buildTools=Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/37.0.0'
$badgingLines=@(& (Join-Path $buildTools 'aapt.exe') dump badging $apk)
$badging=$badgingLines|Select-Object -First 1
$pattern="name='com.rhremote.client'.*versionCode='"+$VersionCode+"'.*versionName='"+[regex]::Escape($Version)+"'"
if($LASTEXITCODE -ne 0 -or $badging -notmatch $pattern){throw 'APK identity differs from the requested release'}
if($badgingLines-contains'application-debuggable'){throw 'A stable release cannot contain a debuggable APK'}
$signers=@(& (Join-Path $buildTools 'apksigner.bat') verify --print-certs $apk)
if($LASTEXITCODE -ne 0){throw 'APK signature verification failed'}
$digests=@($signers|ForEach-Object {if($_ -match 'certificate SHA-256 digest: ([0-9a-fA-F]{64})'){$matches[1].ToLowerInvariant()}}|Sort-Object -Unique)
if($digests.Count -ne 1 -or $digests[0] -ine $ExpectedSignerSha256){throw 'APK signer differs from the installed release signer'}
$notes=Join-Path $repo ('design/RELEASE-NOTES-v'+$Version+'.md')
if(-not(Test-Path -LiteralPath $notes)){throw 'Release notes are missing'}
$directory=Join-Path $repo ('dist/release-candidates/'+$Version+'-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory|Out-Null
$fileName='RHRemote-Android-'+$Version+'-arm64-v8a.apk'
Copy-Item -LiteralPath $apk -Destination (Join-Path $directory $fileName)
Copy-Item -LiteralPath $notes -Destination (Join-Path $directory 'RELEASE-NOTES.md')
foreach($name in @('releases.win.json','RELEASES')){Copy-Item -LiteralPath (Join-Path $WindowsBaselineDirectory $name) -Destination (Join-Path $directory $name)}
$apkHash=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$feed=[ordered]@{schemaVersion=1;android=[ordered]@{version=$Version;versionCode=$VersionCode;packageName='com.rhremote.client';tag=('v'+$Version);fileName=$fileName;size=(Get-Item -LiteralPath $apk).Length;sha256=$apkHash;notes=$Notes}}
[IO.File]::WriteAllText((Join-Path $directory 'releases.android.json'),($feed|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$metadata=[ordered]@{channel='stable';version=$Version;versionCode=$VersionCode;packageName='com.rhremote.client';sourceCommit=$commit;upstreamAndroid='b48494cb96bff23d8886c4775cc4f39a1075495d';signerSha256=$digests[0];apkSha256=$apkHash;windowsVersion=$baseline.version;windowsFeedUnchanged=$true;oppoInstalled=$false;actualCellularVerified=$false}
[IO.File]::WriteAllText((Join-Path $directory 'build.json'),($metadata|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
$sourceArchive=Join-Path $directory ('RHRemote-'+$Version+'-source.zip')
& git -C $repo archive --format=zip --output=$sourceArchive $commit
if($LASTEXITCODE -ne 0){throw 'Committed source archive failed'}
$checksums=@(Get-ChildItem -LiteralPath $directory -File|Sort-Object Name|ForEach-Object {((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())+'  '+$_.Name})
[IO.File]::WriteAllLines((Join-Path $directory 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
$assets=@(Get-ChildItem -LiteralPath $directory -File|Sort-Object Name|ForEach-Object {[ordered]@{fileName=$_.Name;size=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
$plan=[ordered]@{version=$Version;tag=('v'+$Version);preview=$false;commit=$commit;artifacts=$assets}
[IO.File]::WriteAllText((Join-Path $directory 'publish-plan.json'),($plan|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{directory=$directory;commit=$commit;tag=$plan.tag;apkSha256=$apkHash;signerSha256=$digests[0];windowsVersion=$baseline.version;assets=$assets.Count}
