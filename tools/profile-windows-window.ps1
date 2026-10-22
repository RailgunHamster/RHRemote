param(
 [string]$SourceDir='D:\RHRemoteTools\moonlight-qt-v6.1-source',
 [string]$QtDirectory='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64',
 [string]$RuntimeDirectory='D:\git\RHRemote\dist\windows'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$compiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
$out=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-window-profile-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out|Out-Null
$batch=@"
@echo off
call "$compiler" >nul
if errorlevel 1 exit /b 1
cd /d "$out"
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /I"$repo" /I"$QtDirectory\include" /I"$QtDirectory\include\QtCore" /I"$SourceDir\libs\windows\include\x64" /Fe:window-profile.exe "$repo\tools\profile-windows-window.cpp" /link /LIBPATH:"$QtDirectory\lib" /LIBPATH:"$SourceDir\libs\windows\lib\x64" Qt6Core.lib SDL2.lib user32.lib
if errorlevel 1 exit /b 1
set "PATH=$RuntimeDirectory;$QtDirectory\bin;%PATH%"
window-profile.exe
exit /b %errorlevel%
"@
$path=Join-Path $out 'profile.cmd';[IO.File]::WriteAllText($path,$batch,[Text.Encoding]::ASCII)
& cmd /d /c $path
if($LASTEXITCODE-ne0){throw 'Hidden-window startup diagnostic failed'}
