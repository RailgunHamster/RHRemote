param([Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,[DateTimeOffset]$PublishedAt=[DateTimeOffset]::UtcNow)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'release-notes.ps1')
$path=Join-Path $repo 'client/shared/release-history.json'
$feed=[IO.File]::ReadAllText($path)|ConvertFrom-Json
if($feed.schemaVersion-ne1){throw 'Unsupported history schema'}
$release=New-RHRemoteReleaseNotes -Notes ([IO.File]::ReadAllText((Join-Path $repo "design/RELEASE-NOTES-v$Version.md"))) -PublishedAt $PublishedAt
$items=@($feed.releases|Where-Object version -ne $Version)+@([pscustomobject]@{version=$Version;publishedAt=$release.publishedAt;notes=$release.notes})
$ordered=@($items|Sort-Object {[version]$_.version} -Descending)
if($ordered.Count-gt128){throw 'History exceeds the supported catalog size'}
$updated=[ordered]@{schemaVersion=1;releases=$ordered}
[IO.File]::WriteAllText($path,($updated|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{version=$Version;releases=$ordered.Count;publishedAt=$release.publishedAt}
