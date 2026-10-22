param([string]$QtDirectory='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$compiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
if(-not(Test-Path $compiler)-or-not(Test-Path (Join-Path $QtDirectory 'lib/Qt6Core.lib'))){throw 'MSVC and the Qt 6 development runtime are required'}
$out=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-probe-tests-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out|Out-Null
$batch=@"
@echo off
call "$compiler" >nul
if errorlevel 1 exit /b 1
cd /d "$out"
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /I"$repo" /Fe:test-cache.exe "$repo\tools\test-windows-probe-cache.cpp"
if errorlevel 1 exit /b 1
test-cache.exe
if errorlevel 1 exit /b 1
cl /nologo /LD /Fe:test-rhremote-capture-api.dll "$repo\tools\test-windows-capture-api.cpp"
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /I"$repo" /I"$QtDirectory\include" /I"$QtDirectory\include\QtCore" /Fe:test-d3d.exe "$repo\tools\test-windows-d3d11-probe.cpp" /link /LIBPATH:"$QtDirectory\lib" Qt6Core.lib d3d11.lib dxgi.lib
if errorlevel 1 exit /b 1
set "PATH=$QtDirectory\bin;%PATH%"
test-d3d.exe
exit /b %errorlevel%
"@
$path=Join-Path $out 'test.cmd';[IO.File]::WriteAllText($path,$batch,[Text.Encoding]::ASCII)
& cmd /d /c $path
if($LASTEXITCODE-ne0){throw 'Windows native probe regressions failed'}
'Windows probe ownership, adapter isolation and capture compatibility regressions passed'
