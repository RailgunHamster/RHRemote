param(
 [Parameter(Mandatory)][string]$WindowsDirectory,
 [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version='0.2.7',
 [ValidateRange(209,2100000000)][int]$AndroidVersionCode=209,
 [string]$ServerArchive='',
 [string]$ServerAssetsDirectory='',
 [ValidatePattern('^\d+\.\d+\.\d+$')][string]$ServerVersion='0.2.11',
 [string]$LinuxDirectory='',
 [DateTimeOffset]$PublishedAt=[DateTimeOffset]::UtcNow,
 [ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedSignerSha256='2f6464fe50d23ef17e4fdab8c1dcc7b6ec4171b890158738653c51854031dadc'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
& git -C $repo diff --quiet HEAD -- . ':!client/crates/rhremote-core/src/settings.json'
if($LASTEXITCODE-ne0){throw 'Build and stage a release from clean committed product sources'}
$commit=(& git -C $repo rev-parse HEAD).Trim()
if([version]$Version-ge[version]'0.2.23'-and-not$ServerAssetsDirectory){throw 'Every stable release must carry a signed server feed, signature and package; prepare ServerAssetsDirectory first'}
if($ServerAssetsDirectory){
 . (Join-Path $PSScriptRoot 'server-update-signing.ps1')
 $serverFeed=Test-RHRServerUpdateManifest -Manifest (Join-Path $ServerAssetsDirectory 'releases.server.win.json') -Signature (Join-Path $ServerAssetsDirectory 'releases.server.win.json.sig')
 if($serverFeed.releaseTag-cne"v$Version"){throw 'Server feed must target this release tag, including when carrying forward an unchanged server version'}
 $ServerVersion=$serverFeed.version;$ServerArchive=Join-Path $ServerAssetsDirectory $serverFeed.fileName
 if((Get-Item -LiteralPath $ServerArchive).Length-ne$serverFeed.size-or(Get-FileHash -LiteralPath $ServerArchive -Algorithm SHA256).Hash-ine$serverFeed.sha256){throw 'Server package differs from the signed feed'}
}
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
. (Join-Path $PSScriptRoot 'release-notes.ps1')
$historyPath=Join-Path $repo 'client/shared/release-history.json'
$history=[IO.File]::ReadAllText($historyPath)|ConvertFrom-Json
$historyCurrent=@($history.releases|Where-Object version -ceq $Version)
if($history.schemaVersion-ne1-or$historyCurrent.Count-ne1){throw 'Refresh and commit the public release history before building this version'}
if(-not$PSBoundParameters.ContainsKey('PublishedAt')){$PublishedAt=[DateTimeOffset]$historyCurrent[0].publishedAt}
$releaseNotes=New-RHRemoteReleaseNotes -Notes ([IO.File]::ReadAllText($notes)) -PublishedAt $PublishedAt
if($historyCurrent[0].notes-cne$releaseNotes.notes){throw 'Committed history notes differ from the current release notes'}
foreach($asset in $feed.Assets){$asset|Add-Member -NotePropertyName NotesMarkdown -NotePropertyValue $releaseNotes.notes -Force}
$feed|Add-Member -NotePropertyName PublishedAt -NotePropertyValue $releaseNotes.publishedAt -Force
$out=Join-Path $repo ('dist/release-candidates/'+$Version+'-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out|Out-Null
foreach($name in @('RHRemote-win-Setup.exe','RHRemote-win-Portable.zip','releases.win.json','RELEASES',$packageName)){Copy-Item -LiteralPath (Join-Path $WindowsDirectory $name) -Destination $out}
[IO.File]::WriteAllText((Join-Path $out 'releases.win.json'),($feed|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$apkName="RHRemote-Android-$Version-arm64-v8a.apk"
Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $out $apkName)
Copy-Item -LiteralPath $historyPath -Destination (Join-Path $out 'releases.history.json')
if($ServerArchive){
 $serverFile=Get-Item -LiteralPath $ServerArchive
 if($serverFile.PSIsContainer-or$serverFile.Name-ne("RHRemote-Sunshine-$ServerVersion-win-x64.zip")){throw 'Unexpected server archive identity'}
 Copy-Item -LiteralPath $serverFile.FullName -Destination (Join-Path $out $serverFile.Name)
 if($ServerAssetsDirectory){foreach($name in @('releases.server.win.json','releases.server.win.json.sig')){Copy-Item -LiteralPath (Join-Path $ServerAssetsDirectory $name) -Destination $out}}
}
if($LinuxDirectory){
 $linux=Get-Content -LiteralPath (Join-Path $LinuxDirectory 'packages.json') -Raw|ConvertFrom-Json
 if($linux.version-cne$Version-or$linux.platform-cne'ubuntu24.04-x11-amd64'-or@($linux.assets).Count-ne2){throw 'Unexpected Linux package identity'}
 foreach($kind in @('client','server')){
  $name="rhremote-${kind}_${Version}_ubuntu24.04_amd64.deb"
  $entry=@($linux.assets|Where-Object name -ceq $name)
  if($entry.Count-ne1-or$entry[0].sha256-notmatch'^[a-f0-9]{64}$'){throw 'Missing audited Linux package'}
  $file=Get-Item -LiteralPath (Join-Path $LinuxDirectory $name)
  if($file.Length-ne$entry[0].bytes-or(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash-ine$entry[0].sha256){throw 'Linux package checksum mismatch'}
  Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $out $name)
 }
 Copy-Item -LiteralPath (Join-Path $LinuxDirectory 'packages.json') -Destination (Join-Path $out 'packages.linux.json')
 Copy-Item -LiteralPath (Join-Path $repo 'docs/LINUX.md') -Destination (Join-Path $out 'LINUX.md')
}
$apkHash=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$android=[ordered]@{schemaVersion=1;android=[ordered]@{version=$Version;versionCode=$AndroidVersionCode;packageName='com.rhremote.client';tag="v$Version";fileName=$apkName;size=$apk.Length;sha256=$apkHash;notes=$releaseNotes.notes;publishedAt=$releaseNotes.publishedAt}}
[IO.File]::WriteAllText((Join-Path $out 'releases.android.json'),($android|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
$metadata=[ordered]@{publishedAt=$releaseNotes.publishedAt;channel='stable';sourceCommit=$commit;windowsVersion=$Version;windowsBuildType='release';windowsUiRenderer='software';androidVersion=$Version;androidBuildType='release';androidDebuggable=$false;androidVersionCode=$AndroidVersionCode;androidPackage='com.rhremote.client';androidSignerSha256=$digests[0];androidApkSha256=$apkHash;oppoInstalled=$false;actualCellularVerified=$false}
$metadata.serverVersion=if($ServerArchive){$ServerVersion}else{$null}
$metadata.linuxIncluded=[bool]$LinuxDirectory
if($LinuxDirectory){$metadata.linuxPlatform='ubuntu24.04-x11-amd64';$metadata.linuxBuildType='release';$metadata.linuxServerVersion=$Version;$metadata.linuxUpdateMethod='manual-deb'}
[IO.File]::WriteAllText((Join-Path $out 'build.json'),($metadata|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$source=Join-Path $out "RHRemote-$Version-source.zip"
& git -C $repo archive --format=zip --output=$source $commit
if($LASTEXITCODE-ne0){throw 'Committed source archive failed'}
[IO.File]::WriteAllText((Join-Path $out 'RELEASE-NOTES.md'),$releaseNotes.notes,[Text.UTF8Encoding]::new($false))
$checksums=@(Get-ChildItem -LiteralPath $out -File|Sort-Object Name|ForEach-Object{((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())+'  '+$_.Name})
[IO.File]::WriteAllLines((Join-Path $out 'SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
$artifacts=@(Get-ChildItem -LiteralPath $out -File|Sort-Object Name|ForEach-Object{[ordered]@{fileName=$_.Name;size=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
$plan=[ordered]@{version=$Version;tag="v$Version";preview=$false;commit=$commit;developmentBuild=$false;artifacts=$artifacts}
[IO.File]::WriteAllText((Join-Path $out 'publish-plan.json'),($plan|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{directory=$out;commit=$commit;tag=$plan.tag;artifacts=$artifacts.Count;apkSha256=$apkHash;signerSha256=$digests[0];windowsVersion=$Version;androidVersionCode=$AndroidVersionCode}
