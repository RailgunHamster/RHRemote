param(
 [Parameter(Mandatory=$true)][string]$RuntimeFile,
 [Parameter(Mandatory=$true)][string]$DeviceUuid,
 [Parameter(Mandatory=$true)][string]$ReportPath,
 [int]$Port=48751,
 [switch]$AllowWindowActivation,
 [string]$TokenFile='D:\RHRemoteTools\secrets\rhremote-control-token.txt'
)
$ErrorActionPreference='Stop'
if(-not $AllowWindowActivation){throw 'UIA focus audit is disabled by default because it activates the preview. Use the read-only UIA query while the user is working.'}
if(Test-Path -LiteralPath $ReportPath){throw 'Evidence already exists; inspect before repeating'}
$runtime=Get-Content -LiteralPath $RuntimeFile -Raw | ConvertFrom-Json
if($Port -ne $runtime.port){throw 'Port does not match owned runtime'}
$listener=@(Get-NetTCPConnection -State Listen -LocalPort $Port | Where-Object {$_.LocalAddress -eq '127.0.0.1'})
if($listener.Count -ne 1 -or $listener[0].OwningProcess -ne $runtime.pid){throw 'API listener does not belong to the preview'}
$api=Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1'
$widget=Join-Path $PSScriptRoot 'Invoke-RHRemoteUiTest.ps1'
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$contexts=[Collections.Generic.List[object]]::new();$errorMessage=$null;$success=$false
$prefs=Join-Path $runtime.data 'preferences.json'
$before=(Get-FileHash -LiteralPath $prefs -Algorithm SHA256).Hash
function State{(& $api -Port $Port -TokenFile $TokenFile -WebSession $session -Mode state).state}
function Act([string]$Id,[hashtable]$Arguments=@{}){& $api -Port $Port -TokenFile $TokenFile -WebSession $session -Action $Id -Arguments $Arguments | Out-Null}
function Widget([string]$Operation,[string]$Control,[switch]$Activate){
 $p=@{Port=$Port;TokenFile=$TokenFile;WebSession=$session;Operation=$Operation}
 if($Control){$p.Control=$Control};if($Activate){$p.ActivateWindow=$true}
 (& $widget @p).result
}
function Focus-Owner{
 $nodes=$root.FindAll([Windows.Automation.TreeScope]::Descendants,$focusCondition)
 $leaves=@(for($i=0;$i -lt $nodes.Count;$i++){
  $node=$nodes[$i];$leaf=$node.Current
  if($leaf.ProcessId -ne $runtime.pid){throw 'Foreign accessibility node'}
  $ownerId='';$ownerName='';$ownerEnabled=$false;$ids=[Collections.Generic.List[string]]::new()
  for($level=0;$node -and $level -lt 16;$level++){
   $p=$node.Current
   if($p.ProcessId -ne $runtime.pid){throw 'Accessibility walk escaped the owned root'}
   if($p.AutomationId){$ids.Add($p.AutomationId)}
   if(-not $ownerId -and $p.AutomationId -match '^(action|nav|device|input|choice|check|spin|picker)\|'){
    $ownerId=$p.AutomationId;$ownerName=if($p.IsPassword){'<password>'}else{$p.Name};$ownerEnabled=$p.IsEnabled
   }
   if($node.Equals($root)){break}
   $node=[Windows.Automation.TreeWalker]::RawViewWalker.GetParent($node)
  }
  [pscustomobject]@{name=$(if($leaf.IsPassword){'<password>'}else{$leaf.Name});type=$leaf.ControlType.ProgrammaticName;focused=$leaf.HasKeyboardFocus;offscreen=$leaf.IsOffscreen;ownerId=$ownerId;ownerName=$ownerName;ownerEnabled=$ownerEnabled;ancestorIds=@($ids)}
 })
 $leaves
}
function Audit([string]$Name){
 $inspection=Widget inspect
 $controls=@($inspection.controls | Where-Object {$_.enabled -and -not $_.blockedByModal -and $_.kind -in @('button','navigation','device','input','checkbox','combobox','spinbox')})
 $records=[Collections.Generic.List[object]]::new()
 foreach($control in $controls){
  $attempts=[Collections.Generic.List[object]]::new();$matched=$false;$inconclusive=$false
  for($attempt=1;$attempt -le 3;$attempt++){
   try{$result=Widget focus -Control $control.id}catch{
    $inconclusive=$_.Exception.Message -match '系统未激活测试窗口'
    $attempts.Add([pscustomobject]@{number=$attempt;windowStayedActive=$false;apiError=$_.Exception.Message;matched=$false})
    if($inconclusive){continue}else{break}
   }
   if($result.windowActive -ne $true){throw 'Window lost activation; audit stops without reactivating it'}
   if($result.systemInputInjected -ne $false -or @($result.focused).Count -ne 1 -or $result.focused[0] -ne $control.id){throw 'Invalid application focus result'}
   $timer=[Diagnostics.Stopwatch]::StartNew();$leaves=@();$active=$true
   do{
    $active=[RhRemoteUiaForeground]::GetForegroundWindow() -eq $handle
    if(-not $active){break}
    try{$leaves=@(Focus-Owner)}catch [Windows.Automation.ElementNotAvailableException]{$leaves=@()}
    $active=$active -and ([RhRemoteUiaForeground]::GetForegroundWindow() -eq $handle)
    $matched=$active -and $leaves.Count -eq 1 -and $leaves[0].focused -and $leaves[0].ownerId -eq $control.id -and $leaves[0].ownerEnabled -and -not [string]::IsNullOrWhiteSpace($leaves[0].ownerName)
    if($matched -or -not $active -or $timer.Elapsed.TotalSeconds -ge 2){break}
    Start-Sleep -Milliseconds 20
   }while($true)
   $attempts.Add([pscustomobject]@{number=$attempt;windowStayedActive=$active;uiaFocused=$leaves;matched=$matched;elapsedMs=$timer.ElapsedMilliseconds})
   if($matched -or $active){break}
   $inconclusive=$true
  }
  $records.Add([pscustomobject]@{control=$control.id;label=$control.label;matched=$matched;inconclusive=(-not $matched -and $inconclusive);attempts=$attempts})
 }
 $contexts.Add([pscustomobject]@{page=$Name;controls=$controls.Count;records=$records;matched=@($records | Where-Object matched).Count;inconclusive=@($records | Where-Object inconclusive).Count;passed=(@($records | Where-Object {-not $_.matched}).Count -eq 0)})
 Write-Output ($Name+': '+@($records | Where-Object matched).Count+'/'+$controls.Count)
}
try{
 $initial=State
 if($initial.streaming -or $initial.connecting -or $initial.settingsDirty -or $initial.syncDirty -or $initial.navigationPrompt -or $initial.syncClearPrompt){throw 'Preview must start idle and without drafts'}
 Widget inspect | Out-Null
 $preflight=$ReportPath+'.preflight.json'
 & (Join-Path $PSScriptRoot 'Read-RHRemoteUiAutomation.ps1') -RuntimeFile $RuntimeFile -ReportPath $preflight -TokenFile $TokenFile | Out-Null
 $owned=Get-Content -LiteralPath $preflight -Raw | ConvertFrom-Json
 $handle=[IntPtr][long]$owned.windowHandle
 $root=[Windows.Automation.AutomationElement]::FromHandle($handle)
 $focusCondition=[Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::HasKeyboardFocusProperty,$true)
 Widget focus -Control page -Activate | Out-Null
 Act 'devices.open';Act 'device.select' @{uuid=$DeviceUuid}
 Audit devices
 Act 'device.settings.open' @{uuid=$DeviceUuid}
 foreach($tab in @('video','audio','input','network','advanced')){Act 'settings.tab' @{tab=$tab};Audit ('profile-'+$tab)}
 Act 'settings.cancel'
 foreach($tab in @('updates','sync','appearance','about')){Act 'app.settings.tab' @{tab=$tab};Audit ('application-'+$tab)}
 Act 'devices.open';Act 'device.info.open' @{uuid=$DeviceUuid};Audit device-info;Act 'device.info.cancel'
 Act 'device.add.open';Audit device-add;Act 'device.add.cancel'
 $success=(@($contexts | Where-Object {-not $_.passed}).Count -eq 0)
}catch{$errorMessage=$_.Exception.Message}
finally{
 try{
  $s=State
  if($s.settingsDirty -or $s.syncDirty -or $s.infoDirty){throw 'Focus-only audit unexpectedly edited a draft'}
  if($s.page -eq 'settings'){Act 'settings.cancel'}
  if($s.page -eq 'device-info'){Act 'device.info.cancel'}
  if($s.page -eq 'add'){Act 'device.add.cancel'}
  Act 'app.settings.tab' @{tab=$initial.settingsTab};Act 'devices.open'
  Act 'device.select' @{uuid=$initial.selected};Act 'devices.filter' @{query=$initial.deviceQuery}
  Act 'window.minimize'
 }catch{$success=$false;if(-not $errorMessage){$errorMessage=$_.Exception.Message}}
 $after=(Get-FileHash -LiteralPath $prefs -Algorithm SHA256).Hash
 if($before -ne $after){$success=$false;if(-not $errorMessage){$errorMessage='Preferences changed'}}
 [ordered]@{passed=$success;error=$errorMessage;contexts=$contexts;controls=($contexts | Measure-Object controls -Sum).Sum;matched=($contexts | Measure-Object matched -Sum).Sum;inconclusive=($contexts | Measure-Object inconclusive -Sum).Sum;preferencesBefore=$before;preferencesAfter=$after;activatedOnlyOwnedWindow=$true;uiaReadOnly=$true;systemInputInjected=$false;remoteInputSent=$false} | ConvertTo-Json -Depth 22 | Set-Content -LiteralPath $ReportPath -Encoding utf8
 $session.Dispose()
}
if(-not $success){throw ('UIA audit incomplete; inspect '+$ReportPath+': '+$errorMessage)}
