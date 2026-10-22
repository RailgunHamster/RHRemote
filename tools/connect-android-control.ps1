param([string]$Serial='192.168.0.103:5555',[int]$Port=48754,[switch]$RestartAfterInstrumentation)
$ErrorActionPreference='Stop'
$adb='C:\Users\40608\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$model=(& $adb -s $Serial shell getprop ro.product.model) -join ''
if($LASTEXITCODE -ne 0 -or $model.Trim() -ne 'OPD2515'){throw 'This test helper is scoped to the authorized OPPO Pad Mini'}
if($RestartAfterInstrumentation){& $adb -s $Serial shell am force-stop com.rhremote.client}
& $adb -s $Serial shell am start -n com.rhremote.client/com.limelight.PcView | Out-Null
if($LASTEXITCODE -ne 0){throw 'Could not start RHRemote on OPPO'}
Start-Sleep -Seconds 2
$token=(& $adb -s $Serial exec-out run-as com.rhremote.client cat files/rhremote-control-token.txt) -join ''
if($LASTEXITCODE -ne 0 -or $token -notmatch '^[a-f0-9]{64}$'){throw 'App-private control token unavailable; no credential content emitted'}
$path='D:\RHRemoteTools\secrets\rhremote-android-control-token.txt'
[IO.File]::WriteAllText($path,$token);$token=$null
& $adb -s $Serial forward "tcp:$Port" tcp:48752 | Out-Null
if($LASTEXITCODE -ne 0){throw 'ADB control forwarding failed'}
$state=& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Mode state -Port $Port -TokenFile $path
[pscustomobject]@{port=$Port;platform=$state.state.platform;page=$state.state.page;devices=$state.state.devices.Count;token_file=$path}
