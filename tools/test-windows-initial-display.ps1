param([string]$QtDirectory='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$compiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
$out=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-initial-display-tests-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out|Out-Null
$batch=@"
@echo off
call "$compiler" >nul
if errorlevel 1 exit /b 1
cd /d "$out"
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /I"$repo" /I"$QtDirectory\include" /I"$QtDirectory\include\QtCore" /Fe:test-initial-display.exe "$repo\tools\test-windows-initial-display.cpp" /link /LIBPATH:"$QtDirectory\lib" Qt6Core.lib
if errorlevel 1 exit /b 1
set "PATH=$QtDirectory\bin;%PATH%"
test-initial-display.exe
exit /b %errorlevel%
"@
$path=Join-Path $out 'test.cmd';[IO.File]::WriteAllText($path,$batch,[Text.Encoding]::ASCII)
& cmd /d /c $path
if($LASTEXITCODE-ne0){throw 'Windows initial source and fresh resume identity regressions failed'}
'Windows initial display validation, acknowledgement and fresh resume identity regressions passed'
