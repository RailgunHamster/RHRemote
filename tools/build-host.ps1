param([string]$Toolchain='1.92.0',[switch]$Release)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    $arguments=@('build','-p','rhremote-host','--locked','-j4');$configuration='debug'
    if($Release){$arguments+='--release';$configuration='release'}
    & cargo "+$Toolchain" @arguments;if($LASTEXITCODE -ne 0){throw 'RHRemoteHost build failed.'}
    $out=Join-Path $repo 'dist/host';New-Item -ItemType Directory -Force -Path $out|Out-Null
    Copy-Item -LiteralPath (Join-Path $repo "target/$configuration/RHRemoteHost.exe") -Destination $out -Force
    Copy-Item -LiteralPath (Join-Path $repo 'apps/host/scripts') -Destination $out -Recurse -Force
    foreach($name in @('install-host.ps1','uninstall-host.ps1')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $out -Force}
    Get-FileHash -LiteralPath (Join-Path $out 'RHRemoteHost.exe') -Algorithm SHA256|Select-Object Path,Hash
}finally{Pop-Location}
