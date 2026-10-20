$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$adb='C:\Users\40608\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$phoneToken='D:\RHRemoteTools\secrets\rhremote-android-control-token.txt'
function Call([string]$side,[string]$id,[hashtable]$values=@{}){
 $parameters=@{Action=$id;Arguments=$values};if($side -eq 'android'){$parameters.Port=48754;$parameters.TokenFile=$phoneToken}
 & (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') @parameters
}
function State([string]$side){$parameters=@{Mode='state'};if($side -eq 'android'){$parameters.Port=48754;$parameters.TokenFile=$phoneToken};(& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') @parameters).state}
function Sync([string]$side,[string]$resolution=''){
 if($resolution){$null=Call $side 'sync.resolve' @{resolution=$resolution}}else{$null=Call $side 'sync.run'}
 $deadline=(Get-Date).AddSeconds(50)
 do{Start-Sleep -Milliseconds 200;$current=State $side}while($current.syncBusy -and (Get-Date)-lt $deadline)
 if($current.syncBusy){throw "$side sync timed out"}
 if(@($current.sync.conflicts).Count -eq 0 -and $current.status -ne '同步完成'){throw "$side sync failed: $($current.status)"}
 $current
}
function Set-Profile([string]$side,[string]$uuid,[hashtable]$values){$null=Call $side 'device.settings.open' @{uuid=$uuid};foreach($key in $values.Keys){$null=Call $side 'settings.set' @{key=$key;value=$values[$key]}};$null=Call $side 'settings.save'}
$before=@{windows=(State windows);android=(State android)}
foreach($side in $before.Keys){if($before[$side].streaming -or $before[$side].sync.hasPassword){throw 'Test requires idle clients with no configured WebDAV account'}}
$uuid=($before.windows.devices|Where-Object name -eq 'winserver').uuid
if(-not $uuid){throw 'winserver not discovered'}
$winId=$before.windows.saved.clientId;$padId=$before.android.saved.clientId
if(-not $winId -or -not $padId -or $winId -eq $padId){throw 'Independent installation IDs were not created'}
$root=Join-Path 'D:\RHRemoteTools\sync-fixture' ([guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Force -Path $root|Out-Null
$password=[guid]::NewGuid().ToString('N');[IO.File]::WriteAllText((Join-Path $root 'password.txt'),$password)
[IO.File]::WriteAllText((Join-Path $root 'before.json'),($before|ConvertTo-Json -Depth 25))
$checks=[ordered]@{};$process=$null
try{
 $process=Start-Process -FilePath 'C:\tools\Miniconda3\python.exe' -ArgumentList @((Join-Path $repo 'tools/sync-fixture-server.py'),'--directory',$root) -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $root 'stderr.log') -RedirectStandardOutput (Join-Path $root 'stdout.log')
 $deadline=(Get-Date).AddSeconds(10);do{Start-Sleep -Milliseconds 200;try{$health=Invoke-RestMethod 'http://127.0.0.1:48760/health' -TimeoutSec 1}catch{$health=$null}}while(-not $health.ok -and (Get-Date)-lt $deadline);if(-not $health.ok){throw 'Fixture did not start'}
 & $adb -s 192.168.0.103:5555 reverse tcp:48760 tcp:48760|Out-Null;if($LASTEXITCODE -ne 0){throw 'Fixture forwarding failed'}
 foreach($side in @('windows','android')){
  foreach($minutes in @(5,15,30,60)){
   $null=Call $side 'sync.configure' @{url='http://127.0.0.1:48760/';username='fixture';password=$password;automatic=$false;intervalMinutes=$minutes}
   if((State $side).sync.intervalMinutes -ne $minutes){throw "$side sync interval was not saved"}
  }
  $rejected=$false;try{$null=Call $side 'sync.configure' @{url='http://127.0.0.1:48760/';username='fixture';automatic=$false;intervalMinutes=10}}catch{$rejected=$true}
  if(-not $rejected -or (State $side).sync.intervalMinutes -ne 60){throw "$side invalid interval mutated settings"}
  $null=Call $side 'sync.configure' @{url='http://127.0.0.1:48760/';username='fixture';automatic=$false;intervalMinutes=$(if($side -eq 'windows'){15}else{30})}
 }
 $checks.httpAndSyncIntervals=$true
 Set-Profile windows $uuid @{resolution=@(1920,1080);fps=30;bitrateMbps=25;audioEnabled=$false;volume=25}
 Set-Profile android $uuid @{resolution=@(2520,1680);fps=30;bitrateMbps=25;audioEnabled=$false;volume=65}
 $null=Sync windows;$phone=Sync android;if(@($phone.sync.conflicts).Count -gt 0){$phone=Sync android remote};$win=Sync windows
 if($win.saved.profiles.$uuid.width -ne 1920 -or $phone.saved.profiles.$uuid.width -ne 2520){throw 'Initial sync overwrote a client resolution'};$checks.initialResolutionIsolation=$true
 if($win.saved.profiles.$uuid.volume -ne 25 -or $phone.saved.profiles.$uuid.volume -ne 65 -or $win.saved.clientProfiles.$padId.$uuid.volume -ne 65){throw 'Client audio preferences interfered'};$checks.clientAudioIsolation=$true
 if($win.sync.intervalMinutes -ne 15 -or $phone.sync.intervalMinutes -ne 30){throw 'Local sync intervals were overwritten by synchronization'};$checks.syncIntervalsRemainLocal=$true
 Set-Profile windows $uuid @{fps=60;bitrateMbps=15};Set-Profile android $uuid @{fps=30;bitrateMbps=40}
 $null=Sync windows;$phone=Sync android;$win=Sync windows
 if($win.saved.profiles.$uuid.width -ne 1920 -or $win.saved.profiles.$uuid.fps -ne 60 -or $win.saved.profiles.$uuid.bitrateMbps -ne 15 -or $phone.saved.profiles.$uuid.width -ne 2520 -or $phone.saved.profiles.$uuid.fps -ne 30 -or $phone.saved.profiles.$uuid.bitrateMbps -ne 40){throw 'Concurrent edits of different clients interfered'}
 if($win.saved.clientProfiles.$padId.$uuid.width -ne 2520 -or $phone.saved.clientProfiles.$winId.$uuid.width -ne 1920){throw 'Other client profiles were not retained as copy sources'};$checks.independentConcurrentProfiles=$true
 $null=Call android 'device.settings.open' @{uuid=$uuid};$null=Call android 'settings.copy_from' @{clientId=$winId};$draft=State android
 if($draft.draft.width -ne 1920 -or $draft.saved.profiles.$uuid.width -ne 2520){throw 'Copy did not remain in draft'}
 $null=Call android 'settings.cancel';if((State android).saved.profiles.$uuid.width -ne 2520){throw 'Cancel persisted a copied profile'};$checks.copyCancelDoesNotSave=$true
 $null=Call android 'device.settings.open' @{uuid=$uuid};$null=Call android 'settings.copy_from' @{clientId=$winId};$null=Call android 'settings.save'
 if((State android).saved.profiles.$uuid.width -ne 1920 -or (State windows).saved.profiles.$uuid.fps -ne 60){throw 'Explicit copy/save failed'};$checks.copySaveOnlyChangesCurrentClient=$true
 Set-Profile android $uuid @{resolution=@(2520,1680);fps=30;bitrateMbps=40};$null=Sync android;$null=Sync windows
 Set-Profile windows $uuid @{displayName='同步别名测试'};$null=Sync windows;$phone=Sync android
 $aliased=$phone.devices|Where-Object uuid -eq $uuid
 if($aliased.displayName -ne '同步别名测试' -or $aliased.name -ne 'winserver' -or $phone.saved.profiles.$uuid.width -ne 2520){throw 'Alias did not sync independently of host name and client tuning'};$checks.aliasSharedHostnamePreserved=$true
 Set-Profile android $uuid @{displayName=''};$null=Sync android;$win=Sync windows
 if(($win.devices|Where-Object uuid -eq $uuid).displayName -ne ''){throw 'Clearing alias did not sync'};$checks.aliasClear=$true
 Set-Profile windows $uuid @{displayName='Windows 并发别名'};Set-Profile android $uuid @{displayName='OPPO 并发别名'};$null=Sync windows
 $document=Get-FileHash -LiteralPath (Join-Path $root 'document.json');$conflict=Sync android
 if(@($conflict.sync.conflicts).Count -eq 0){throw 'Concurrent host edits were not reported'}
 if((Get-FileHash -LiteralPath (Join-Path $root 'document.json')).Hash -ne $document.Hash){throw 'Conflict unexpectedly overwrote remote'};$checks.conflictDoesNotUpload=$true
 $resolved=Sync android remote
 if(($resolved.devices|Where-Object uuid -eq $uuid).displayName -ne 'Windows 并发别名' -or $resolved.saved.profiles.$uuid.width -ne 2520 -or @($resolved.sync.conflicts).Count -ne 0){throw 'Explicit conflict resolution failed'};$checks.explicitResolutionKeepsOwnTuning=$true
 $win=Sync windows;$self=@($win.devices|Where-Object isSelf)
 if($self.Count -ne 1 -or -not @($resolved.devices|Where-Object uuid -eq $self[0].uuid).Count){throw 'Self device was lost in sync'}
 $refused=$false;try{$null=Call windows 'device.connect' @{uuid=$self[0].uuid}}catch{$refused=$true};if(-not $refused){throw 'Self connection was not blocked'};$checks.selfKeptAndBlocked=$true
 foreach($side in @('windows','android')){if((State $side|ConvertTo-Json -Depth 25).Contains($password)){throw 'Password leaked into API state'}};$checks.passwordNotExposed=$true
 [pscustomobject]$checks
}finally{
 foreach($side in @('windows','android')){
  try{foreach($device in $before[$side].devices){$p=$before[$side].saved.profiles.($device.uuid);if($null -eq $p){continue};$values=@{resolution=@([int]$p.width,[int]$p.height);fps=[int]$p.fps;bitrateMbps=[int]$p.bitrateMbps;codec=[string]$p.codec;swapButtons=[bool]$p.swapButtons;trackpad=[bool]$p.trackpad;statistics=[bool]$p.statistics;displayName=[string]$device.displayName};foreach($property in $p.PSObject.Properties){if($property.Name -notin @("width","height")){$values[$property.Name]=$property.Value}};Set-Profile $side $device.uuid $values};if([bool](State $side).saved.dark -ne [bool]$before[$side].saved.dark){$null=Call $side 'appearance.toggle'};$null=Call $side 'devices.open'}catch{Write-Warning "$side preference cleanup needs inspection"}
 }
 # Refresh cached foreign groups after restoring the real preferences as well.
 try{foreach($side in @('windows','android','windows')){$restored=Sync $side;if(@($restored.sync.conflicts).Count -gt 0){$null=Sync $side local}}}catch{Write-Warning 'Final fixture merge needs inspection'}
 foreach($side in @('windows','android')){try{$null=Call $side 'sync.clear'}catch{Write-Warning "$side test WebDAV credential cleanup needs inspection"}}
 & $adb -s 192.168.0.103:5555 reverse --remove tcp:48760|Out-Null
 [IO.File]::WriteAllText((Join-Path $root 'stop'),'')
 if($process -and -not $process.WaitForExit(5000)){Stop-Process -Id $process.Id -Force}
 Remove-Item -LiteralPath (Join-Path $root 'password.txt') -ErrorAction SilentlyContinue
 $report=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');schemaVersion=2;checks=$checks;fixture_directory=$root}
 [IO.File]::WriteAllText((Join-Path $repo 'results/clients-v2/cross-client-sync.json'),($report|ConvertTo-Json -Depth 10))
}
