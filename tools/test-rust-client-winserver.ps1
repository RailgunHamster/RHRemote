param([ValidateSet(1920,2520,3840)][int]$Width=1920,[ValidateSet(1080,1680,2160)][int]$Height=1080,[ValidateRange(0,60)][int]$SampleSeconds=0,[ValidateSet('HEVC','H.264')][string]$Codec='HEVC')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$result=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');kind='functional_integration';stability='INCONCLUSIVE';notes='Bounded input and connection test; not a frame pacing or long-duration stability acceptance.'}
function Action([string]$id,[hashtable]$values=@{}){& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Action $id -Arguments $values}
function State{(& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Mode state).state}
function HostProbe([string]$action,[string]$file,[hashtable]$extra=@{}){
 $parameters=@{Action=$action};foreach($key in $extra.Keys){$parameters[$key]=$extra[$key]}
 & (Join-Path $PSScriptRoot 'Invoke-RemoteScript.ps1') -HostName winserver -SftpDir /D:/RHRemoteTools/scripts -ScriptPath (Join-Path $PSScriptRoot 'tablet-motion-control.ps1') -Include (Join-Path $PSScriptRoot 'wts.ps1') -Parameters $parameters -OutFile (Join-Path $repo "results/clients-v2/$file") -TimeoutSec 30
 Get-Content -LiteralPath (Join-Path $repo "results/clients-v2/$file") -Raw|ConvertFrom-Json
}
$state=State
if($state.streaming){throw 'An existing session is active; leaving it untouched.'}
$hostDevice=@($state.devices|Where-Object {$_.name -eq 'winserver' -and $_.online -and $_.paired -and -not $_.isSelf})
if($hostDevice.Count -ne 1){throw 'Expected exactly one online paired winserver.'}
$uuid=$hostDevice[0].uuid
$result.action_count=@((& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Mode actions).actions).Count
$sourceStarted=$false
try{
 $null=Action 'statistics.configure' @{enabled=$true}
 $null=Action 'device.settings.open' @{uuid=$uuid}
 $null=Action 'settings.set' @{key='swapButtons';value=$true}
 $null=Action 'settings.set' @{key='resolution';value=@($Width,$Height)}
 $null=Action 'settings.set' @{key='fps';value=30}
 $null=Action 'settings.set' @{key='codec';value=$Codec}
 $null=Action 'settings.set' @{key='statistics';value=$false}
 $null=Action 'settings.save'
 $b64=[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'tablet-interaction-probe.ps1')))
 $null=HostProbe 'start' 'rust-probe-start.json' @{ScriptB64=$b64;Seconds='180'};$sourceStarted=$true
 $pre=HostProbe 'status' 'rust-probe-preflight.json'
 if(-not $pre.motion -or $pre.motion.ended -or $pre.motion.width -ne 2520 -or $pre.motion.height -ne 1680 -or $pre.motion.swap_buttons -ne 1){throw 'Owned source or button mapping preflight failed.'}
 $result.connect=Action 'device.connect' @{uuid=$uuid}
 $deadline=(Get-Date).AddSeconds(120)
 do{Start-Sleep -Milliseconds 500;$state=State}while(-not $state.streaming -and $state.statistics.phase -ne 'error' -and (Get-Date) -lt $deadline)
 if(-not $state.streaming){throw "Native stream failed: $($state.statistics.error)"}
 $deadline=(Get-Date).AddSeconds(35)
 do{Start-Sleep -Milliseconds 500;$state=State}while(-not $state.statistics.statisticsAtMs -and $state.statistics.phase -ne 'error' -and (Get-Date) -lt $deadline)
 if(-not $state.statistics.statisticsAtMs){throw 'Decoder statistics were not observed before input'}
 $result.beforeInput=$state.statistics
 $volume=[int]$state.volume
 $null=Action 'stream.audio.volume' @{volume=0};$null=Action 'stream.audio.toggle'
 $audio=State;if(-not $audio.audioEnabled -or $audio.volume -ne 0){throw 'Muted live audio controls failed'}
 $null=Action 'stream.audio.toggle';$null=Action 'stream.audio.volume' @{volume=$volume};$result.audio_controls=$true
 if($state.statistics.activeProfile.width -ne $Width -or $state.statistics.activeProfile.height -ne $Height){throw 'Native adapter did not receive this client profile'}
 $result.tap=Action 'input.gesture' @{gesture='tap';x=[int](650*$Width/2520);y=[int](550*$Height/1680)}
 $tap=HostProbe 'status' 'rust-input-tap.json'
 if($tap.motion.primary_down -ne 1 -or $tap.motion.primary_up -ne 1 -or $tap.motion.secondary_down -ne 0 -or $tap.motion.held -ne 0){throw 'Primary logical click was not balanced.'}
 $null=Action 'input.gesture' @{gesture='secondary';x=[int](1750*$Width/2520);y=[int](550*$Height/1680)}
 $null=Action 'input.gesture' @{gesture='drag';x=[int](650*$Width/2520);y=[int](980*$Height/1680);toX=[int](1100*$Width/2520);toY=[int](980*$Height/1680)}
 $null=Action 'input.gesture' @{gesture='scroll';delta=120}
 $inputState=HostProbe 'status' 'rust-input-complete.json'
 $result.input=$inputState.motion
 if($result.input.primary_down -ne 2 -or $result.input.primary_up -ne 2 -or $result.input.secondary_down -ne 1 -or $result.input.secondary_up -ne 1 -or $result.input.drag_moves -lt 1 -or $result.input.wheel_delta -eq 0 -or $result.input.held -ne 0){throw 'Input probe did not receive the expected released events.'}
 $deadline=(Get-Date).AddSeconds(8)
 do{Start-Sleep -Milliseconds 500;$state=State}while(-not $state.statistics.statisticsAtMs -and (Get-Date) -lt $deadline)
 $result.statistics=$state.statistics
 if($SampleSeconds -gt 0){
  $samples=@();$end=(Get-Date).AddSeconds($SampleSeconds)
  do{Start-Sleep -Seconds 3;$snapshot=State;if(-not $snapshot.streaming){throw 'Stream ended during bounded sampling'};$samples+=@{utc=[DateTime]::UtcNow.ToString('o');statistics=$snapshot.statistics.statistics;statisticsAtMs=$snapshot.statistics.statisticsAtMs}}while((Get-Date)-lt $end)
  $result.samples=$samples
 }
 $result.disconnect=Action 'stream.disconnect'
 $deadline=(Get-Date).AddSeconds(15)
 do{Start-Sleep -Milliseconds 300;$state=State}while($state.streaming -and (Get-Date) -lt $deadline)
 if($state.streaming){throw 'Stream did not disconnect.'}
 $result.result='PASS'
}catch{$result.result='FAIL';$result.error=$_.Exception.Message;throw}
finally{
 try{if((State).streaming){$null=Action 'stream.disconnect'}}catch{}
 if($sourceStarted){$result.cleanup=HostProbe 'stop' 'rust-probe-stop.json'}
 try{& (Join-Path $PSScriptRoot 'collect-statistics.ps1') -Platform windows *> (Join-Path $repo 'results/clients-v2/rust-statistics-analysis.log')}catch{$result.statistics_analysis_error=$_.Exception.Message}
 [IO.File]::WriteAllText((Join-Path $repo 'results/clients-v2/rust-functional-test.json'),($result|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
}
$result|ConvertTo-Json -Depth 8
