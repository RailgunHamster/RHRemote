param([Parameter(Mandatory)][string]$SunshineDirectory,[string]$HostDirectory='',[string]$OutputDirectory='',[string]$EngineCommit='')
$ErrorActionPreference='Stop';$repo=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'server-package.ps1')
if(-not$HostDirectory){$HostDirectory=Join-Path $repo 'dist/host'}
if(-not$OutputDirectory){$OutputDirectory=Join-Path $repo 'dist/server-unified'}
$out=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $out){throw 'Use a new output directory for each immutable server package'}
foreach($file in @('sunshine.exe','assets/web/index.html')){if(-not(Test-Path -LiteralPath (Join-Path $SunshineDirectory $file))){throw ('Incomplete Sunshine build: '+$file)}}
foreach($file in @('RHRemoteHost.exe','scripts/display.ps1','scripts/RhDisplayConfig.dll')){if(-not(Test-Path -LiteralPath (Join-Path $HostDirectory $file))){throw ('Incomplete Rust service build: '+$file)}}
New-Item -ItemType Directory -Path $out|Out-Null
Get-ChildItem -LiteralPath $SunshineDirectory -Force|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $out -Recurse -Force}
$hostOut=Join-Path $out 'host';New-Item -ItemType Directory -Path $hostOut -Force|Out-Null
Copy-Item -LiteralPath (Join-Path $HostDirectory 'RHRemoteHost.exe') -Destination $hostOut -Force
Copy-Item -LiteralPath (Join-Path $HostDirectory 'scripts') -Destination $hostOut -Recurse -Force
foreach($name in @('install-server.ps1','server-package.ps1')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $out}
# Existing Sunshine setup UI and service scripts must use the unified installer.
$shim="@echo off`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File `"%~dp0..\install-server.ps1`" -SourceDirectory `"%~dp0..`"`r`nexit /b %ERRORLEVEL%`r`n"
[IO.File]::WriteAllText((Join-Path $out 'scripts/install-service.bat'),$shim,[Text.Encoding]::ASCII)
$commit=(& git -C $repo rev-parse HEAD).Trim()
$metadata=New-RHRPackageManifest $out $commit $EngineCommit
$null=Test-RHRPackage $out
[pscustomobject]@{directory=$out;product=$metadata.product;files=$metadata.files.Count;commit=$commit;manifestSha256=(Get-FileHash -LiteralPath (Join-Path $out 'server-package.json')).Hash}
