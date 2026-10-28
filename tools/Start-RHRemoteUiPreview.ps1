param(
 [Parameter(Mandatory=$true)][string]$PackageDirectory,
 [Parameter(Mandatory=$true)][string]$DataDirectory,
 [Parameter(Mandatory=$true)][string]$RuntimeFile,
 [string]$Monitor='\\.\DISPLAY2',
 [switch]$AllowPrimaryDisplay,
 [int]$Port=48751,
 [string]$TokenFile='D:\RHRemoteTools\secrets\rhremote-control-token.txt'
)
$ErrorActionPreference='Stop'
$package=(Resolve-Path -LiteralPath $PackageDirectory).ProviderPath
$data=(Resolve-Path -LiteralPath $DataDirectory).ProviderPath.TrimEnd('\')
$normal=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'RHRemote')).TrimEnd('\')
if($data -eq $normal -or -not (Test-Path (Join-Path $data 'preferences.json'))){throw 'An existing isolated preferences directory is required'}
$exe=Join-Path $package 'RHRemote.exe';$engine=Join-Path $package 'engine\RHRemote.exe'
if(-not (Test-Path $exe) -or -not (Test-Path $engine)){throw 'Packaged UI and engine are required'}
if(Test-Path -LiteralPath $RuntimeFile){throw 'Runtime manifest already exists; inspect its process before starting'}
if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq $Port).Count){throw 'Control port already has a listener'}
. (Join-Path $PSScriptRoot 'displayconfig.ps1')
$targets=@([RhDisplayConfig]::Query() | Where-Object GdiDeviceName -eq $Monitor)
if($targets.Count -ne 1 -or -not $targets[0].MonitorName){throw 'The named physical display is not active; no main-screen fallback'}
$target=$targets[0]
$Monitor=$target.GdiDeviceName
$primary=$target.PositionX -eq 0 -and $target.PositionY -eq 0
if($primary -and -not $AllowPrimaryDisplay){throw 'Preview must use the secondary display unless the user explicitly authorizes AllowPrimaryDisplay'}
$origin=([string]($target.PositionX+64))+','+([string]($target.PositionY+64))
$envNames=@('RHREMOTE_TEST_MODE','RHREMOTE_DATA_DIR','RHREMOTE_CONTROL_PORT','RHREMOTE_CONTROL_TOKEN','RHREMOTE_NATIVE_ENGINE','RHREMOTE_BENCHMARK_NO_INPUT','RHREMOTE_TEST_MONITOR','RHREMOTE_TEST_WINDOW_ORIGIN')
$prior=@{};foreach($name in $envNames){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 $env:RHREMOTE_TEST_MODE='1';$env:RHREMOTE_DATA_DIR=$data;$env:RHREMOTE_CONTROL_PORT=[string]$Port
 $env:RHREMOTE_CONTROL_TOKEN=[IO.File]::ReadAllText($TokenFile).Trim();$env:RHREMOTE_NATIVE_ENGINE=$engine
 $env:RHREMOTE_BENCHMARK_NO_INPUT='1';$env:RHREMOTE_TEST_MONITOR=$Monitor;$env:RHREMOTE_TEST_WINDOW_ORIGIN=$origin
 $started=Start-Process -FilePath $exe -WorkingDirectory $package -WindowStyle Hidden -PassThru -RedirectStandardOutput ($RuntimeFile+'.stdout.log') -RedirectStandardError ($RuntimeFile+'.stderr.log')
}finally{foreach($name in $envNames){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}}
$record=[ordered]@{pid=$started.Id;exe=$exe;engine=$engine;data=$data;port=$Port;monitor=$Monitor;monitorName=$target.MonitorName;monitorBounds=@{x=$target.PositionX;y=$target.PositionY;width=$target.SourceWidth;height=$target.SourceHeight};origin=$origin;primaryDisplay=$primary;primaryDisplayExplicitlyAllowed=[bool]$AllowPrimaryDisplay;passivePreview=$true;physicalInputDisabled=$true;sourcePreferences=(Join-Path $normal 'preferences.json');sourceSha256=(Get-FileHash (Join-Path $data 'preferences.json') -Algorithm SHA256).Hash}
$record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $RuntimeFile -Encoding utf8
$deadline=[DateTime]::UtcNow.AddSeconds(20)
do{
 try{& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port $Port -TokenFile $TokenFile -Mode state | Out-Null;break}catch{
  if($started.HasExited -or [DateTime]::UtcNow -ge $deadline){throw 'Preview did not start; inspect the recorded process and logs'}
  Start-Sleep -Milliseconds 100
 }
}while($true)
$view=(& (Join-Path $PSScriptRoot 'Invoke-RHRemoteUiTest.ps1') -Port $Port -TokenFile $TokenFile -Operation inspect).result
if($view.monitor -ne $Monitor -or $view.windowActive -ne $false){
 & (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Port $Port -TokenFile $TokenFile -Action 'window.close' | Out-Null
 throw 'Preview did not remain inactive on the requested monitor; closed it without retrying'
}
[pscustomobject]@{pid=$started.Id;monitor=$view.monitor;monitorName=$target.MonitorName;position=$view.physicalPosition;windowActive=$view.windowActive;runtimeFile=$RuntimeFile}
