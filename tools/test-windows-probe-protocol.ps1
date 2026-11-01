param(
 [string]$Engine='D:\git\RHRemote\dist\windows\RHRemote.exe',
 [string]$QtDirectory='D:\RHRemoteTools\Qt\6.7.2\msvc2019_64',
 [Parameter(Mandatory=$true)][string]$OutputDirectory,
 [string]$Cases='http-no-pin'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$taskRoot=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $taskRoot){throw 'Use a fresh protocol evidence directory'}
if(-not[IO.Path]::IsPathRooted($OutputDirectory)){throw 'Use an absolute protocol evidence directory'}
if(-not(Test-Path -LiteralPath $Engine)){throw 'Native client unavailable'}
New-Item -ItemType Directory -Path $taskRoot|Out-Null
$taskAccount=[Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $taskRoot /inheritance:r /grant:r ($taskAccount+':(OI)(CI)F') 'SYSTEM:(OI)(CI)F'|Out-Null
if($LASTEXITCODE-ne0){throw 'Could not restrict the fresh test directory'}
$taskPlan=[ordered]@{schema='rhremote.native-protocol-fixture/v1';engine=[IO.Path]::GetFullPath($Engine);engineSha256=(Get-FileHash -LiteralPath $Engine -Algorithm SHA256).Hash;output=$taskRoot;host='127.77.0.2';cases=$Cases;maximumProcessSeconds=12;identity='newly generated test client and server keys only';allowedEndpoints=@('/serverinfo','/applist','/v1/network');noOperations=@('no real host contact','no PIN or pairing','no streaming, audio or input','no service restart','no normal client or configuration change','no system window or screenshot');termination='only exact Popen child handles created by this run';qtPlatform='offscreen';platformPluginDirectory=(Join-Path $QtDirectory 'plugins\platforms')}
$taskPlan|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $taskRoot 'plan.json')
$taskCompiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
$taskBatch=@"
@echo off
call "$taskCompiler" >nul
if errorlevel 1 exit /b 1
cd /d "$taskRoot"
cl /nologo /std:c++17 /Zc:__cplusplus /permissive- /EHsc /utf-8 /MD /I"$QtDirectory\include" /I"$QtDirectory\include\QtCore" /I"$QtDirectory\include\QtNetwork" /Fe:seed-probe-identity.exe "$repo\tools\test-windows-probe-identity.cpp" /link /LIBPATH:"$QtDirectory\lib" Qt6Core.lib Qt6Network.lib
exit /b %errorlevel%
"@
$taskBatchPath=Join-Path $taskRoot 'build-identity.cmd'
[IO.File]::WriteAllText($taskBatchPath,$taskBatch,[Text.Encoding]::ASCII)
& cmd /d /c $taskBatchPath *> (Join-Path $taskRoot 'build-identity.log')
if($LASTEXITCODE-ne0){throw 'Protocol fixture identity helper failed to compile'}
& python (Join-Path $PSScriptRoot 'test-windows-probe-protocol.py') --plan (Join-Path $taskRoot 'plan.json') --qt-bin (Join-Path $QtDirectory 'bin')
if($LASTEXITCODE-ne0){throw 'Native protocol fixture failed; evidence retained'}
