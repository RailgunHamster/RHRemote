param([Parameter(Mandatory)][string]$Directory,[switch]$CheckOnlineUpdates)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$zip=Join-Path $Directory 'RHRemote-win-Portable.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($zip)
try{foreach($entry in $archive.Entries){if($entry.FullName -match '(?i)\.(ini|pem|key|log|pfx|p12)$' -or $entry.FullName -match '(?i)token|credential|password'){throw 'Private configuration found in portable archive'}}}finally{$archive.Dispose()}
$root=Join-Path 'D:\RHRemoteTools\package-smoke' ([guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Force -Path $root|Out-Null
$unpack=Join-Path $root 'app';[IO.Compression.ZipFile]::ExtractToDirectory([IO.Path]::GetFullPath($zip),$unpack)
$application=if(Test-Path -LiteralPath (Join-Path $unpack 'current\RHRemote.exe')){Join-Path $unpack 'current'}else{$unpack}
if(-not(Test-Path -LiteralPath (Join-Path $application 'RHRemote.exe'))){throw 'Portable package is missing the application executable'}
$values=@{RHREMOTE_CONTROL_PORT='48755';RHREMOTE_CONTROL_TOKEN=[IO.File]::ReadAllText('D:\RHRemoteTools\secrets\rhremote-control-token.txt').Trim();RHREMOTE_DATA_DIR=(Join-Path $root 'data');RHREMOTE_UI_POSITION='-3750,80';RHREMOTE_NATIVE_ENGINE=$null}
$before=@{};$process=$null
function State{(& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port 48755 -Mode state).state}
try{
 foreach($name in $values.Keys){$before[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$values[$name],'Process')}
 $process=Start-Process -FilePath (Join-Path $application 'RHRemote.exe') -WorkingDirectory $application -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $root 'stderr.log') -RedirectStandardOutput (Join-Path $root 'stdout.log')
 $deadline=(Get-Date).AddSeconds(20);do{Start-Sleep -Milliseconds 250;try{$state=State}catch{$state=$null}}while(-not $state -and (Get-Date)-lt $deadline)
 if(-not $state -or -not $state.updates.managed){throw 'Packaged app did not detect its Velopack manifest'}
 $null=& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port 48755 -Action 'device.add.submit' -Arguments @{address='192.168.0.104'}
 $state=State;$device=@($state.devices|Where-Object name -eq 'winserver');if($device.Count -ne 1 -or -not $device[0].online -or $device[0].paired){throw 'Packaged native adapter did not probe a fresh unpaired winserver'}
 $result=[ordered]@{result='PASS';private_configuration_excluded=$true;velopack_managed=$state.updates.managed;fresh_host_probe=$true;directory=$root}
 if($CheckOnlineUpdates){
  $deadline=(Get-Date).AddSeconds(60);do{$state=State;if(-not $state.updatesBusy){break};Start-Sleep -Milliseconds 300}while((Get-Date)-lt $deadline)
  if($state.updatesBusy){throw 'Automatic update check did not finish'}
  $null=& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port 48755 -Action 'updates.check'
  $deadline=(Get-Date).AddSeconds(60);do{Start-Sleep -Milliseconds 300;$state=State}while($state.updatesBusy -and (Get-Date)-lt $deadline)
  if($state.updatesBusy -or $state.updates.status -ne '已是最新版本'){throw ('Live update check failed: '+$state.updates.status)}
  $result.anonymous_online_update_check=$state.updates.status
 }
 [IO.File]::WriteAllText((Join-Path $repo 'results/clients-v2/windows-package-smoke.json'),($result|ConvertTo-Json));$result|ConvertTo-Json
}finally{
 try{$null=& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port 48755 -Action 'window.close'}catch{}
 if($process -and -not $process.WaitForExit(10000)){Stop-Process -Id $process.Id -Force}
 foreach($name in $values.Keys){[Environment]::SetEnvironmentVariable($name,$before[$name],'Process')}
}
