param([string]$QtDirectory='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$out=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-keyboard-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out|Out-Null
$data=[Security.SecurityElement]::Escape((Join-Path $repo 'client/shared/keyboard.json').Replace('\','/'))
[IO.File]::WriteAllText((Join-Path $out 'keyboard.qrc'),('<RCC><qresource prefix="/res"><file alias="rhremote-keyboard.json">'+$data+'</file></qresource></RCC>'),[Text.UTF8Encoding]::new($false))
$compiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
$batch=@"
@echo off
call "$compiler" >nul
if errorlevel 1 exit /b 1
cd /d "$out"
"$QtDirectory\bin\rcc.exe" keyboard.qrc -o keyboard-qrc.cpp
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /I"$repo" /I"$QtDirectory\include" /I"$QtDirectory\include\QtCore" /Fe:keyboard-test.exe "$repo\tools\test-windows-keyboard.cpp" keyboard-qrc.cpp /link /LIBPATH:"$QtDirectory\lib" Qt6Core.lib
if errorlevel 1 exit /b 1
set "PATH=$QtDirectory\bin;%PATH%"
keyboard-test.exe
exit /b %errorlevel%
"@
$command=Join-Path $out 'test.cmd';[IO.File]::WriteAllText($command,$batch,[Text.Encoding]::ASCII)
& cmd /d /c $command
if($LASTEXITCODE-ne0){throw ('Native keyboard check failed; evidence retained: '+$out)}
