param([string]$Version='0.2.3',[switch]$ReleaseBuild,[string]$OutputDirectory='')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$stage=Join-Path $repo ('dist/velopack-stage-'+[guid]::NewGuid().ToString('N'))
$out=if($OutputDirectory){[IO.Path]::GetFullPath($OutputDirectory)}else{Join-Path $repo ('dist/velopack-releases-'+$Version)}
if(-not $out.StartsWith((Join-Path $repo 'dist')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Package output must remain under the workspace dist directory'}
if(Test-Path -LiteralPath $out){throw 'A version directory already exists; never replace immutable release artifacts'}
$vpk='D:\RHRemoteTools\Velopack\vpk.exe';if(-not(Test-Path -LiteralPath $vpk)){throw 'Install vpk 1.2.0 into D:\RHRemoteTools\Velopack first'}
Push-Location $repo
try{
 if($ReleaseBuild){& cargo build -p rhremote-ui --release --locked -j 4;if($LASTEXITCODE -ne 0){throw 'Release build failed'}}
 $configuration=if($ReleaseBuild){'release'}else{'debug'}
 New-Item -ItemType Directory -Force -Path (Join-Path $stage 'engine')|Out-Null
 Copy-Item -LiteralPath (Join-Path $repo "target/$configuration/RHRemote.exe") -Destination (Join-Path $stage 'RHRemote.exe')
 Copy-Item -LiteralPath (Join-Path $repo 'client/LICENSE') -Destination (Join-Path $stage 'LICENSE')
 # Explicit runtime allowlist. Pairing INIs, caches, logs and local snapshots are never packaged.
 $native=Join-Path $repo 'dist/windows'
 Get-ChildItem -LiteralPath $native -File|Where-Object {$_.Extension -eq '.dll' -or $_.Name -in @('RHRemote.exe','gamecontrollerdb.txt','LICENSE')}|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $stage 'engine')}
 foreach($directory in @('iconengines','imageformats','networkinformation','platforms','qml','tls','translations')){if(Test-Path -LiteralPath (Join-Path $native $directory)){Copy-Item -LiteralPath (Join-Path $native $directory) -Destination (Join-Path $stage 'engine') -Recurse}}
 foreach($dll in @('vcruntime140.dll','vcruntime140_1.dll','msvcp140.dll')){Copy-Item -LiteralPath (Join-Path $native $dll) -Destination $stage}
 $forbidden=@(Get-ChildItem -LiteralPath $stage -Recurse -File|Where-Object {$_.Extension -in @('.ini','.pem','.key','.log','.pfx','.p12') -or $_.Name -match 'token|credential|password|portable\.dat'})
 if($forbidden){throw 'Unexpected private or runtime configuration in package staging'}
 & $vpk pack --packId RHRemote --packVersion $Version --packDir $stage --mainExe RHRemote.exe --packAuthors RailgunHamster --packTitle RHRemote-Moonlight --icon (Join-Path $repo 'client/shared/icons/rhremote.ico') --channel win --runtime win-x64 --outputDir $out
 if($LASTEXITCODE -ne 0){throw 'Velopack packaging failed'}
 Get-ChildItem -LiteralPath $out -File|ForEach-Object {[pscustomobject]@{name=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}}
}finally{Pop-Location}
