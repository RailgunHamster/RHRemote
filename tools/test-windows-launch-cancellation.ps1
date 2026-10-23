param([string]$QtDirectory='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$compiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
$out=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-launch-cancellation-tests-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out|Out-Null
$batch=@"
@echo off
call "$compiler" >nul
if errorlevel 1 exit /b 1
cd /d "$out"
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /I"$repo" /I"$QtDirectory\include" /I"$QtDirectory\include\QtCore" /Fe:test-launch-cancellation.exe "$repo\tools\test-windows-launch-cancellation.cpp" /link /LIBPATH:"$QtDirectory\lib" Qt6Core.lib
if errorlevel 1 exit /b 1
set "PATH=$QtDirectory\bin;%PATH%"
test-launch-cancellation.exe
exit /b %errorlevel%
"@
$path=Join-Path $out 'test.cmd';[IO.File]::WriteAllText($path,$batch,[Text.Encoding]::ASCII)
& cmd /d /c $path
if($LASTEXITCODE-ne0){throw 'Native launch cancellation lifecycle regressions failed'}
