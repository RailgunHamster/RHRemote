param([string]$SourceDir='D:\RHRemoteTools\moonlight-qt-v6.1-source')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$common=Join-Path $SourceDir 'moonlight-common-c/moonlight-common-c'
$output=Join-Path ([IO.Path]::GetTempPath()) ('rh-frame-clock-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output|Out-Null
$vcvars='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
$commands=@"
@echo off
call "$vcvars"
if errorlevel 1 exit /b 1
cd /d "$output"
cl /nologo /std:c11 /W3 /WX /MD /DNDEBUG /D_CRT_SECURE_NO_WARNINGS /I"$common/src" /I"$common/enet/include" /I"$common/reedsolomon" /I"$SourceDir/libs/windows/include" "$repo/client/windows/tests/frame-clock-queue.c" "$common/src/RhFrameClock.c" "$common/src/RtpVideoQueue.c" "$common/reedsolomon/rs.c" /Fe:frame-clock-test.exe
if errorlevel 1 exit /b 1
frame-clock-test.exe
exit /b %errorlevel%
"@
$batch=Join-Path $output 'build-test.cmd'
[IO.File]::WriteAllText($batch,$commands,[Text.Encoding]::ASCII)
& cmd /d /c $batch
if($LASTEXITCODE-ne0){throw ('Actual native queue clock test failed; evidence retained at '+$output)}
