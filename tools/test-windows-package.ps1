param([Parameter(Mandatory)][string]$Directory)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$zip=Join-Path $Directory 'RHRemote-win-Portable.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($zip)
try{foreach($entry in $archive.Entries){if($entry.FullName -match '(?i)\.(ini|pem|key|log|pfx|p12)$' -or $entry.FullName -match '(?i)token|credential|password'){throw 'Private configuration found in portable archive'}}}finally{$archive.Dispose()}
$root=Join-Path 'D:\RHRemoteTools\package-smoke' ([guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Force -Path $root|Out-Null
$unpack=Join-Path $root 'app';[IO.Compression.ZipFile]::ExtractToDirectory([IO.Path]::GetFullPath($zip),$unpack)
$values=@{RHREMOTE_CONTROL_PORT='48755';RHREMOTE_CONTROL_TOKEN=[IO.File]::ReadAllText('D:\RHRemoteTools\secrets\rhremote-control-token.txt').Trim();RHREMOTE_DATA_DIR=(Join-Path $root 'data');RHREMOTE_UI_POSITION='-3750,80';RHREMOTE_NATIVE_ENGINE=$null}
$before=@{};$process=$null
function State{(& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port 48755 -Mode state).state}
try{
 foreach($name in $values.Keys){$before[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$values[$name],'Process')}
 $process=Start-Process -FilePath (Join-Path $unpack 'RHRemote.exe') -WorkingDirectory $unpack -PassThru -RedirectStandardError (Join-Path $root 'stderr.log') -RedirectStandardOutput (Join-Path $root 'stdout.log')
 $deadline=(Get-Date).AddSeconds(20);do{Start-Sleep -Milliseconds 250;try{$state=State}catch{$state=$null}}while(-not $state -and (Get-Date)-lt $deadline)
 if(-not $state -or -not $state.updates.managed){throw 'Packaged app did not detect its Velopack manifest'}
 $null=& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port 48755 -Action 'device.add.submit' -Arguments @{address='192.168.0.104'}
 $state=State;$device=@($state.devices|Where-Object name -eq 'winserver');if($device.Count -ne 1 -or -not $device[0].online -or $device[0].paired){throw 'Packaged native adapter did not probe a fresh unpaired winserver'}
 $result=[ordered]@{result='PASS';private_configuration_excluded=$true;velopack_managed=$state.updates.managed;fresh_host_probe=$true;directory=$root}
 [IO.File]::WriteAllText((Join-Path $repo 'results/clients-v2/windows-package-smoke.json'),($result|ConvertTo-Json));$result|ConvertTo-Json
}finally{
 try{$null=& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port 48755 -Action 'window.close'}catch{}
 if($process -and -not $process.WaitForExit(10000)){Stop-Process -Id $process.Id -Force}
 foreach($name in $values.Keys){[Environment]::SetEnvironmentVariable($name,$before[$name],'Process')}
}
