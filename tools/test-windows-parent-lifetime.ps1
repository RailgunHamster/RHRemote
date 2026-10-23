$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$root=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-parent-lifetime-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root|Out-Null
$compiler='C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
$batch=@"
@echo off
call "$compiler" >nul
if errorlevel 1 exit /b 1
cd /d "$root"
cl /nologo /std:c++17 /EHsc /utf-8 /I"$repo" /Fe:parent-lifetime-test.exe "$repo\tools\test-windows-parent-lifetime.cpp"
exit /b %errorlevel%
"@
$build=Join-Path $root build.cmd;[IO.File]::WriteAllText($build,$batch,[Text.Encoding]::ASCII)
& cmd /d /c $build;if($LASTEXITCODE-ne0){throw 'Parent watcher build failed'}
$exe=Join-Path $root parent-lifetime-test.exe
& $exe alive $PID;if($LASTEXITCODE-ne0){throw 'Alive parent, stop or invalid identity contract failed'}
$parent=$null;$watcher=$null
try{
 $parent=Start-Process -FilePath $exe -ArgumentList @('parent',('"'+$root+'"')) -WindowStyle Hidden -PassThru
 $ready=Join-Path $root watcher.pid;$until=(Get-Date).AddSeconds(5)
 do{Start-Sleep -Milliseconds 20}while(-not(Test-Path -LiteralPath $ready)-and(Get-Date)-lt$until)
 if(-not(Test-Path -LiteralPath $ready)){throw 'Watcher did not start'}
 $watcher=Get-Process -Id ([int][IO.File]::ReadAllText($ready)) -ErrorAction Stop
 $null=$watcher.Handle # Retain query access before this externally started process exits.
 Start-Sleep -Milliseconds 50
 if($watcher.HasExited-or(Test-Path -LiteralPath (Join-Path $root observed.txt))){throw 'Parent was considered dead while still alive'}
 $clock=[Diagnostics.Stopwatch]::StartNew();$parent.Kill();$parent.WaitForExit()
 if(-not$watcher.WaitForExit(3000)){throw 'Owner exit did not finish the watcher'}
 if($watcher.ExitCode-ne0-or[IO.File]::ReadAllText((Join-Path $root observed.txt))-ne'owner-exited'){throw 'Owner exit callback did not complete'}
 [pscustomobject]@{aliveAndCancel='PASS';invalidIdentities='PASS';originalParentExit='PASS';exitNotificationMs=$clock.ElapsedMilliseconds;output=$root}|ConvertTo-Json
}finally{
 foreach($process in @($parent,$watcher)){if($process-and-not$process.HasExited){$process.Kill();$process.WaitForExit()}}
}
# Keep this bounded fixture output as evidence; no deletion of user statistics.
