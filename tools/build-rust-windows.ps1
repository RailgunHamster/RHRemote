param([switch]$SkipNative,[string]$Toolchain='')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if(-not $SkipNative){& (Join-Path $PSScriptRoot 'build-windows.ps1');if($LASTEXITCODE -ne 0){throw 'Native adapter build failed'}}
Push-Location $repo
try{
 $selector=@();if($Toolchain){$selector=@("+$Toolchain")}
 & cargo @selector test --workspace --locked -j 4
 if($LASTEXITCODE -ne 0){throw 'Rust tests failed'}
 & cargo @selector build --package rhremote-ui --locked -j 4
 if($LASTEXITCODE -ne 0){throw 'Rust client build failed'}
 $out=Join-Path $repo 'dist/windows-rust';New-Item -ItemType Directory -Force -Path $out|Out-Null
 Copy-Item -LiteralPath (Join-Path $repo 'target/debug/RHRemote.exe') -Destination (Join-Path $out 'RHRemote.exe') -Force
 Copy-Item -LiteralPath (Join-Path $repo 'client/LICENSE') -Destination (Join-Path $out 'LICENSE') -Force
 Get-FileHash -LiteralPath (Join-Path $out 'RHRemote.exe') -Algorithm SHA256|Select-Object Hash,Path
}finally{Pop-Location}
