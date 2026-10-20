# Include wts.ps1. Input ScriptB64. Runs only our bounded synthetic probe, hidden.
$ErrorActionPreference='Stop';$root='C:\RHRemoteTools\qsv-probe';$taskName='RHRemote-Qsv-Startup-Probe'
if(Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue){throw 'A QSV probe is already registered.'}
$console=@(Get-WtsSessions|Where-Object {$_.active_console -and $_.state -eq 'Active'})
if($console.Count -ne 1 -or -not $console[0].user){throw 'Expected one existing active console.'}
New-Item -ItemType Directory -Force -Path $root|Out-Null
$script=Join-Path $root 'probe.ps1';$result=Join-Path $root 'result.txt'
Remove-Item -LiteralPath $result -ErrorAction SilentlyContinue
$code=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($ScriptB64))
$code="& {`n"+$code+"`n} | Set-Content -LiteralPath '$result' -Encoding UTF8"
[IO.File]::WriteAllText($script,$code,[Text.UTF8Encoding]::new($true))
$action=New-ScheduledTaskAction -Execute powershell.exe -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+$script+'"')
$principal=New-ScheduledTaskPrincipal -UserId $console[0].user -LogonType Interactive -RunLevel Highest
$settings=New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Seconds 70) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
try{
 Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings|Out-Null
 Start-ScheduledTask -TaskName $taskName;$deadline=(Get-Date).AddSeconds(75)
 do{Start-Sleep -Milliseconds 500;$state=(Get-ScheduledTask -TaskName $taskName).State}while(((-not(Test-Path -LiteralPath $result)) -or $state -eq 'Running') -and (Get-Date)-lt $deadline)
 if($state -eq 'Running'){Stop-ScheduledTask -TaskName $taskName;throw 'Synthetic probe exceeded its hard limit.'}
 if(-not(Test-Path -LiteralPath $result)){throw 'Synthetic probe did not produce a result.'}
 Get-Content -LiteralPath $result | Where-Object {$_ -like 'B64:*'}
}finally{Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue;Remove-Item -LiteralPath $script,$result -ErrorAction SilentlyContinue}
