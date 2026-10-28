param([string]$QtDirectory='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$compiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
$out=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-pair-persistence-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out | Out-Null
$batch=@"
@echo off
call "$compiler" >nul
if errorlevel 1 exit /b 1
cd /d "$out"
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /I"$repo" /I"$QtDirectory\include" /I"$QtDirectory\include\QtCore" /I"$QtDirectory\include\QtNetwork" /Fe:pairing-test.exe "$repo\tools\test-windows-pairing-persistence.cpp" /link /LIBPATH:"$QtDirectory\lib" Qt6Core.lib Qt6Network.lib
if errorlevel 1 exit /b 1
set "PATH=$QtDirectory\bin;%PATH%"
pairing-test.exe "$repo\client\windows\tests\pairing-fixtures\paired-host.pem" "$repo\client\windows\tests\pairing-fixtures\different-host.pem"
exit /b %errorlevel%
"@
$path=Join-Path $out 'test.cmd'
[IO.File]::WriteAllText($path,$batch,[Text.Encoding]::ASCII)
& cmd /d /c $path
if($LASTEXITCODE -ne 0){throw ('Native pairing persistence regression failed; evidence retained at '+$out)}
