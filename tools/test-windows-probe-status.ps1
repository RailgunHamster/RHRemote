param([string]$QtDirectory='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$compiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
if(-not(Test-Path -LiteralPath $compiler)-or-not(Test-Path -LiteralPath (Join-Path $QtDirectory 'lib/Qt6Network.lib'))){throw 'MSVC and the Qt 6 Network development runtime are required'}
$out=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-probe-status-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out|Out-Null
$batch=@"
@echo off
call "$compiler" >nul
if errorlevel 1 exit /b 1
cd /d "$out"
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /MD /I"$repo" /I"$QtDirectory\include" /I"$QtDirectory\include\QtCore" /I"$QtDirectory\include\QtNetwork" /Fe:test-probe-status.exe "$repo\tools\test-windows-probe-status.cpp" /link /LIBPATH:"$QtDirectory\lib" Qt6Core.lib Qt6Network.lib
if errorlevel 1 exit /b 1
set "PATH=$QtDirectory\bin;%PATH%"
test-probe-status.exe
exit /b %errorlevel%
"@
$path=Join-Path $out 'test.cmd'
[IO.File]::WriteAllText($path,$batch,[Text.Encoding]::ASCII)
& cmd /d /c $path
if($LASTEXITCODE-ne0){throw 'Native probe status regressions failed'}
