param(
 [Parameter(Mandatory)][string]$WindowsDirectory,
 [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version='0.2.7',
 [ValidateRange(209,2100000000)][int]$AndroidVersionCode=209,
 [ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedSignerSha256='2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
& git -C $repo diff --quiet HEAD --
if($LASTEXITCODE-ne0){throw 'Build and stage a release from clean committed product sources'}
$commit=(& git -C $repo rev-parse HEAD).Trim()
if($commit-notmatch'^[a-f0-9]{40}$'){throw 'Source commit unavailable'}
$uiCargo=Get-Content -LiteralPath (Join-Path $repo 'client/crates/rhremote-ui/Cargo.toml') -Raw
if($uiCargo-notmatch('(?m)^version\s*=\s*"'+[regex]::Escape($Version)+'"\s*$')){throw 'Update the Windows UI version before preparing this release'}
$feed=Get-Content -LiteralPath (Join-Path $WindowsDirectory 'releases.win.json') -Raw|ConvertFrom-Json
if($feed.Assets.Count-ne1-or$feed.Assets[0].Version-ne$Version){throw 'Unexpected Windows release feed'}
$packageName=[string]$feed.Assets[0].FileName
if($packageName-notmatch'^[A-Za-z0-9._-]+\.nupkg$'-or$packageName.Contains('..')){throw 'Unsafe Windows package filename'}
$package=Get-Item -LiteralPath (Join-Path $WindowsDirectory $packageName)
if($package.Length-ne$feed.Assets[0].Size-or(Get-FileHash -LiteralPath $package.FullName -Algorithm SHA256).Hash-ine$feed.Assets[0].SHA256){throw 'Windows package checksum mismatch'}
$apk=Get-Item -LiteralPath (Join-Path $repo "dist/android/RHRemote-v$Version.apk")
$buildTools=Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/37.0.0'
$badgingLines=@(& (Join-Path $buildTools 'aapt.exe') dump badging $apk.FullName)
$badging=$badgingLines|Select-Object -First 1
$pattern="name='com.rhremote.client'.*versionCode='"+$AndroidVersionCode+"'.*versionName='"+[regex]::Escape($Version)+"'"
if($LASTEXITCODE-ne0-or$badging-notmatch$pattern){throw 'APK package/version metadata differs from candidate'}
if($badgingLines-contains'application-debuggable'){throw 'A stable release cannot contain a debuggable APK'}
$signers=@(& (Join-Path $buildTools 'apksigner.bat') verify --print-certs $apk.FullName)
if($LASTEXITCODE-ne0){throw 'APK signature verification failed'}
$digests=@($signers|ForEach-Object{if($_-match'certificate SHA-256 digest: ([0-9a-fA-F]{64})'){$matches[1].ToLowerInvariant()}}|Sort-Object -Unique)
if($digests.Count-ne1-or$digests[0]-ine$ExpectedSignerSha256){throw 'APK signer differs from the installed release signer'}
$notes=Join-Path $repo ('design/RELEASE-NOTES-v'+$Version+'.md')
if(-not(Test-Path -LiteralPath $notes)){throw 'Release notes are missing'}
$out=Join-Path $repo ('dist/release-candidates/'+$Version+'-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out|Out-Null
foreach($name in @('RHRemote-win-Setup.exe','RHRemote-win-Portable.zip','releases.win.json','RELEASES',$packageName)){Copy-Item -LiteralPath (Join-Path $WindowsDirectory $name) -Destination $out}
$apkName="RHRemote-Android-$Version-arm64-v8a.apk"
Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $out $apkName)
$apkHash=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$android=[ordered]@{schemaVersion=1;android=[ordered]@{version=$Version;versionCode=$AndroidVersionCode;packageName='com.rhremote.client';tag="v$Version";fileName=$apkName;size=$apk.Length;sha256=$apkHash;notes='See RELEASE-NOTES.md for changes.'}}
[IO.File]::WriteAllText((Join-Path $out 'releases.android.json'),($android|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
$metadata=[ordered]@{channel='stable';sourceCommit=$commit;windowsVersion=$Version;windowsBuildType='release';windowsUiRenderer='software';androidVersion=$Version;androidBuildType='release';androidDebuggable=$false;androidVersionCode=$AndroidVersionCode;androidPackage='com.rhremote.client';androidSignerSha256=$digests[0];androidApkSha256=$apkHash;oppoInstalled=$false;actualCellularVerified=$false}
[IO.File]::WriteAllText((Join-Path $out 'build.json'),($metadata|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$source=Join-Path $out "RHRemote-$Version-source.zip"
& git -C $repo archive --format=zip --output=$source $commit
if($LASTEXITCODE-ne0){throw 'Committed source archive failed'}
Copy-Item -LiteralPath $notes -Destination (Join-Path $out 'RELEASE-NOTES.md')
$checksums=@(Get-ChildItem -LiteralPath $out -File|Sort-Object Name|ForEach-Object{((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())+'  '+$_.Name})
[IO.File]::WriteAllLines((Join-Path $out 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
$artifacts=@(Get-ChildItem -LiteralPath $out -File|Sort-Object Name|ForEach-Object{[ordered]@{fileName=$_.Name;size=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
$plan=[ordered]@{version=$Version;tag="v$Version";preview=$false;commit=$commit;developmentBuild=$false;artifacts=$artifacts}
[IO.File]::WriteAllText((Join-Path $out 'publish-plan.json'),($plan|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{directory=$out;commit=$commit;tag=$plan.tag;artifacts=$artifacts.Count;apkSha256=$apkHash;signerSha256=$digests[0];windowsVersion=$Version;androidVersionCode=$AndroidVersionCode}
