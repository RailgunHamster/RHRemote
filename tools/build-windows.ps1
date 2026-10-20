param([string]$SourceDir='D:\RHRemoteTools\moonlight-qt-v6.1-source',[string]$QtDir='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'prepare-windows.ps1') -SourceDir $SourceDir
$repo=Split-Path $PSScriptRoot -Parent;$build=Join-Path $SourceDir 'build/rhremote'
$vcvars='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
if(-not(Test-Path -LiteralPath $vcvars)){throw 'MSVC environment not found.'}
$cmdFile='D:\RHRemoteTools\build-rhremote-windows.cmd'
$commands=@"
@echo off
call "$vcvars"
if errorlevel 1 exit /b 1
cd /d "$build"
"$QtDir\bin\qmake.exe" "$SourceDir\moonlight-qt.pro" "CONFIG+=release" "QMAKE_CXXFLAGS+=/MP4"
if errorlevel 1 exit /b 1
nmake /nologo
exit /b %errorlevel%
"@
[IO.File]::WriteAllText($cmdFile,$commands,[Text.Encoding]::ASCII)
& cmd /d /c $cmdFile
if($LASTEXITCODE -ne 0){throw 'Windows build failed.'}
$out=Join-Path $repo 'dist/windows';New-Item -ItemType Directory -Force -Path $out|Out-Null
$runtime='D:\RHRemoteTools\Moonlight-RHRemote-6.1.0'
& robocopy $runtime $out /E /NFL /NDL /NJH /NJS /XF *.exe *.ini *.pem *.key *.log portable.dat /XD 'Moonlight Game Streaming Project' 'cache' 'boxart' 'logs'|Out-Null
if($LASTEXITCODE -ge 8){throw 'Could not copy the existing Qt runtime.'}
Copy-Item -LiteralPath (Join-Path $build 'app/release/RHRemote.exe') -Destination (Join-Path $out 'RHRemote.exe') -Force
[IO.File]::WriteAllText((Join-Path $out 'portable.dat'),'')
Copy-Item -LiteralPath (Join-Path $repo 'client/LICENSE') -Destination (Join-Path $out 'LICENSE') -Force
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $out 'RHRemote.exe')|Select-Object Hash,Path
exit 0
