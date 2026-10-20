$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$secret='D:\RHRemoteTools\secrets\rhremote-android-control-token.txt'
function Action([string]$id,[hashtable]$values=@{}){& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port 48754 -TokenFile $secret -Action $id -Arguments $values}
function State{(& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port 48754 -TokenFile $secret -Mode state).state}
function Probe([string]$action,[string]$file,[hashtable]$extra=@{}){$parameters=@{Action=$action};foreach($key in $extra.Keys){$parameters[$key]=$extra[$key]};$path=Join-Path $repo "results/clients-v2/$file";& (Join-Path $PSScriptRoot 'Invoke-RemoteScript.ps1') -HostName winserver -SftpDir /D:/RHRemoteTools/scripts -ScriptPath (Join-Path $PSScriptRoot 'tablet-motion-control.ps1') -Include (Join-Path $PSScriptRoot 'wts.ps1') -Parameters $parameters -OutFile $path -TimeoutSec 30;Get-Content -LiteralPath $path -Raw|ConvertFrom-Json}
$result=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');kind='android_api_functional_integration';stability='INCONCLUSIVE'}
$s=State;if($s.streaming -or $s.connecting){throw 'An existing Android stream is active'}
$hostDevice=@($s.devices|Where-Object {$_.name -eq 'winserver' -and $_.online -and $_.paired});if($hostDevice.Count -ne 1){throw 'Expected one paired online winserver'}
$uuid=$hostDevice[0].uuid;$source=$false
try{
 $null=Action 'statistics.configure' @{enabled=$true}
 $null=Action 'device.settings.open' @{uuid=$uuid};foreach($entry in @(@{key='resolution';value=@(2520,1680)},@{key='fps';value=30},@{key='swapButtons';value=$true},@{key='statistics';value=$true},@{key='trackpad';value=$false})){$null=Action 'settings.set' $entry};$null=Action 'settings.save'
 $b64=[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'tablet-interaction-probe.ps1')));$null=Probe start 'android-api-probe-start.json' @{ScriptB64=$b64;Seconds='120'};$source=$true
 $null=Action 'device.connect' @{uuid=$uuid};$deadline=(Get-Date).AddSeconds(120);do{Start-Sleep -Milliseconds 400;$s=State}while(-not $s.streaming -and (Get-Date)-lt $deadline);if(-not $s.streaming){throw 'Android connection did not become active'}
 $deadline=(Get-Date).AddSeconds(35);do{Start-Sleep -Milliseconds 500;$s=State}while(-not $s.stream.statistics -and (Get-Date)-lt $deadline);if(-not $s.stream.statistics){throw 'Decoder statistics were not observed before input'}
 $w=[double]$s.stream.surface.width;$h=[double]$s.stream.surface.height;if($w -le 0 -or $h -le 0){throw 'Invalid decoded surface dimensions'}
 $volume=[int]$s.stream.volume;$null=Action 'stream.audio.volume' @{volume=0};$null=Action 'stream.audio.toggle';$audio=State;if(-not $audio.stream.audioEnabled -or $audio.stream.volume -ne 0){throw 'Muted live audio controls failed'};$null=Action 'stream.audio.toggle';$null=Action 'stream.audio.volume' @{volume=$volume};$result.audio_controls=$true
 $null=Action 'input.gesture' @{gesture='tap';x=650*$w/2520;y=550*$h/1680};Start-Sleep -Milliseconds 200
 $null=Action 'input.gesture' @{gesture='secondary';x=1750*$w/2520;y=550*$h/1680};Start-Sleep -Milliseconds 200
 $null=Action 'input.gesture' @{gesture='drag';x=650*$w/2520;y=980*$h/1680;toX=1100*$w/2520;toY=980*$h/1680};Start-Sleep -Milliseconds 350
 $null=Action 'input.gesture' @{gesture='scroll';delta=120}
 $probe=Probe status 'android-api-input-complete.json';$result.input=$probe.motion
 if($probe.motion.primary_down -ne 2 -or $probe.motion.primary_up -ne 2 -or $probe.motion.secondary_down -ne 1 -or $probe.motion.secondary_up -ne 1 -or $probe.motion.held -ne 0 -or $probe.motion.wheel_delta -eq 0 -or $probe.motion.drag_moves -lt 1){throw 'Android input was not correctly delivered and released'}
 foreach($id in @('stream.controls.toggle','stream.mode.toggle','stream.pan.toggle')){$before=State;$null=Action $id;$middle=State;$null=Action $id;$after=State;$field=switch($id){'stream.controls.toggle'{'controls'}'stream.mode.toggle'{'trackpad'}'stream.pan.toggle'{'pan'}};if($before.stream.$field -eq $middle.stream.$field -or $before.stream.$field -ne $after.stream.$field){throw "Control toggle did not round-trip: $id"}}
 $null=Action 'input.gesture' @{gesture='pinch';factor=1.25};$null=Action 'stream.zoom.reset'
 $deadline=(Get-Date).AddSeconds(35);do{Start-Sleep -Milliseconds 500;$s=State}while(-not $s.stream.statistics -and (Get-Date)-lt $deadline);if(-not $s.stream.statistics){throw 'Decoder statistics were not observed'};$result.statistics=$s.stream.statistics;$result.surface=$s.stream.surface
 $null=Action 'stream.disconnect';$deadline=(Get-Date).AddSeconds(15);do{Start-Sleep -Milliseconds 200;$s=State}while(($s.streaming -or $s.connecting) -and (Get-Date)-lt $deadline);if($s.streaming -or $s.connecting){throw 'Android did not disconnect'}
 $result.result='PASS'
}catch{$result.result='FAIL';$result.error=$_.Exception.Message;throw}
finally{
 try{if((State).streaming){$null=Action 'stream.disconnect'}}catch{}
 if($source){$result.cleanup=Probe stop 'android-api-probe-stop.json'}
 try{& (Join-Path $PSScriptRoot 'collect-statistics.ps1') -Platform android *> (Join-Path $repo 'results/clients-v2/android-statistics-analysis.log')}catch{$result.statistics_analysis_error=$_.Exception.Message}
 try{$null=Action 'device.settings.open' @{uuid=$uuid};$null=Action 'settings.set' @{key='statistics';value=$false};$null=Action 'settings.save'}catch{}
 [IO.File]::WriteAllText((Join-Path $repo 'results/clients-v2/android-api-functional-test.json'),($result|ConvertTo-Json -Depth 12))
}
$result|ConvertTo-Json -Depth 10
