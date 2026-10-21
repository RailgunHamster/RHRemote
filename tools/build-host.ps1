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
    $assemblyStage=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-display-assembly-'+[guid]::NewGuid().ToString('N'))
    & (Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'compile-displayconfig.ps1') -ScriptPath (Join-Path $out 'scripts/displayconfig.ps1') -OutputDirectory $assemblyStage
    if($LASTEXITCODE-ne0){throw 'Display configuration assembly compilation failed'}
    foreach($file in @('RhDisplayConfig.dll','RhDisplayConfig.assembly.json')){Copy-Item -LiteralPath (Join-Path $assemblyStage $file) -Destination (Join-Path $out 'scripts') -Force}
    foreach($name in @('install-host.ps1','uninstall-host.ps1')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $out -Force}
    Get-FileHash -LiteralPath (Join-Path $out 'RHRemoteHost.exe') -Algorithm SHA256|Select-Object Path,Hash
}finally{Pop-Location}
