param([Parameter(Mandatory)][string]$Archive,[Parameter(Mandatory)][ValidatePattern('^v\d+\.\d+\.\d+$')][string]$ReleaseTag,[Parameter(Mandatory)][string]$OutputDirectory,[DateTimeOffset]$PublishedAt=[DateTimeOffset]::UtcNow)
$ErrorActionPreference='Stop'
$file=Get-Item -LiteralPath $Archive
if($file.Length-le0-or$file.Length-gt512MB){throw 'Server archive exceeds its download budget'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($file.FullName)
try{
 $entry=$zip.GetEntry('server-package.json');if(-not$entry-or$entry.Length-gt1MB){throw 'Missing or oversized server package metadata'}
 $reader=[IO.StreamReader]::new($entry.Open());try{$package=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
}finally{$zip.Dispose()}
if($package.schema-ne'rhremote.server-package/v1'-or$package.product-ne'RHRemote-Sunshine'-or$package.serviceMode-ne'unified'-or$package.version-notmatch'^\d+\.\d+\.\d+$'-or$package.commit-notmatch'^[a-f0-9]{40}$'-or$file.Name-cne("RHRemote-Sunshine-"+$package.version+'-win-x64.zip')){throw 'Unexpected server archive identity'}
$out=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $out){throw 'Use a new immutable feed directory'}
$null=New-Item -ItemType Directory -Path $out
Copy-Item -LiteralPath $file.FullName -Destination $out
$feed=[ordered]@{schema='rhremote.server-update/v1';product='RHRemote-Sunshine';platform='windows-x64';keyId='release-rsa3072-20261109';version=$package.version;releaseTag=$ReleaseTag;fileName=$file.Name;size=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant();sourceCommit=$package.commit;updaterProtocol=1;publishedAt=$PublishedAt.ToUniversalTime().ToString('o')}
[IO.File]::WriteAllText((Join-Path $out 'releases.server.win.json'),($feed|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{directory=$out;version=$package.version;releaseTag=$ReleaseTag;requiresSignature=$true}
