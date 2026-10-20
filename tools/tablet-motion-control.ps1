# Include wts.ps1. Inputs Action, ScriptB64. Temporary task has a hard execution limit.
$ErrorActionPreference='Stop'
$root='C:\RHRemoteTools\tablet-display'
$taskName='RHRemote-Tablet-Motion'
switch($Action){
 'start'{
  if(-not $Seconds){$Seconds='90'}
  $duration=[int]$Seconds
  if($duration -lt 30 -or $duration -gt 180){throw 'Invalid bounded test duration.'}
  if(Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue){throw 'A motion test task already exists.'}
  $console=@(Get-WtsSessions|Where-Object {$_.active_console -and $_.state -eq 'Active'})
  if($console.Count -ne 1 -or -not $console[0].user){throw 'Expected an existing active console.'}
  $script=Join-Path $root 'tablet-motion.ps1'
  [IO.File]::WriteAllBytes($script,[Convert]::FromBase64String($ScriptB64))
  Remove-Item -LiteralPath (Join-Path $root 'motion-status.json') -ErrorAction SilentlyContinue
  $actionDef=New-ScheduledTaskAction -Execute powershell.exe -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+$script+'" -Seconds '+$duration)
  $principal=New-ScheduledTaskPrincipal -UserId $console[0].user -LogonType Interactive -RunLevel Highest
  $settings=New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Seconds ($duration+30)) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
  Register-ScheduledTask -TaskName $taskName -Action $actionDef -Principal $principal -Settings $settings|Out-Null
  Start-ScheduledTask -TaskName $taskName
 }
 'stop'{New-Item -ItemType File -Path (Join-Path $root 'stop-motion') -Force|Out-Null;Start-Sleep -Seconds 1}
 'status'{}
 default{throw 'Unknown motion control action.'}
}
$task=Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
$r=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');state="$($task.State)";last_result=$(if($task){(Get-ScheduledTaskInfo -TaskName $taskName).LastTaskResult});motion=$(if(Test-Path -LiteralPath (Join-Path $root 'motion-status.json')){Get-Content -LiteralPath (Join-Path $root 'motion-status.json') -Raw|ConvertFrom-Json})}
if($task -and $task.State -ne 'Running' -and $Action -ne 'start'){Unregister-ScheduledTask -TaskName $taskName -Confirm:$false;$r.task_removed=$true}
'B64:'+ [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($r|ConvertTo-Json -Depth 5 -Compress)))
