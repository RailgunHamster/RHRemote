param(
 [int]$Port=48751,
 [Parameter(Mandatory=$true)][string]$DeviceUuid,
 [Parameter(Mandatory=$true)][string]$ReportPath,
 [string]$TokenFile='D:\RHRemoteTools\secrets\rhremote-control-token.txt'
)
$ErrorActionPreference='Stop'
$actionScript=Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1'
$uiScript=Join-Path $PSScriptRoot 'Invoke-RHRemoteUiTest.ps1'
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$pages=[Collections.Generic.List[object]]::new()
$failure=$null
function Read-State{(& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $session -Mode state).state}
function Do-Action([string]$Action,[hashtable]$Arguments=@{}){& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $session -Action $Action -Arguments $Arguments | Out-Null}
function Widget([string]$Operation,[string]$Control,[string]$Key,[switch]$Shift){
 $parameters=@{Port=$Port;TokenFile=$TokenFile;WebSession=$session;Operation=$Operation}
 if($Control){$parameters.Control=$Control};if($Key){$parameters.Key=$Key};if($Shift){$parameters.Shift=$true}
 (& $uiScript @parameters).result
}
function Audit-Page([string]$Name){
 $inspection=Widget inspect
 $expected=@($inspection.controls | Where-Object {$_.enabled -and -not $_.blockedByModal -and $_.kind -in @('button','navigation','device','input','checkbox','combobox','spinbox')} | Select-Object -ExpandProperty id)
 if(@($expected | Sort-Object -Unique).Count -ne $expected.Count){throw ('Duplicate control IDs on '+$Name)}
 foreach($backward in @($false,$true)){
  $seen=[Collections.Generic.HashSet[string]]::new()
  $sequence=[Collections.Generic.List[object]]::new()
  Widget focus -Control 'page' | Out-Null
  $first=$null
  for($i=0;$i -lt $expected.Count*3+10;$i++){
   $result=Widget key -Key Tab -Shift:$backward
   $focused=@($result.focused)
   $sequence.Add([pscustomobject]@{focused=$focused;windowActive=$result.windowActive;systemInputInjected=$result.systemInputInjected})
   if($focused.Count -gt 1){throw ('Multiple focus markers on '+$Name)}
   if($result.systemInputInjected -ne $false){throw 'Unexpected system input path'}
   if($focused.Count -eq 1){
    if($null -eq $first){$first=[string]$focused[0]}
    elseif($focused[0] -eq $first){break}
    $seen.Add([string]$focused[0]) | Out-Null
   }
  }
  $missing=@($expected | Where-Object {-not $seen.Contains($_)})
  $unexpected=@($seen | Where-Object {$_ -notin $expected})
  $inverse=$true
  if($backward -and $missing.Count -eq 0 -and $unexpected.Count -eq 0 -and $expected.Count -gt 0){
   $forward=@($pages[$pages.Count-1].sequence | ForEach-Object {$_.focused} | Select-Object -Unique)
   $reverse=@($sequence | ForEach-Object {$_.focused} | Select-Object -Unique)
   $start=[Array]::IndexOf($forward,$reverse[0])
   $reversed=@(for($j=0;$j -lt $forward.Count;$j++){$forward[($start-$j+$forward.Count)%$forward.Count]})
   $inverse=(($reversed -join "`0") -eq ($reverse -join "`0"))
  }
  $record=[pscustomobject]@{page=$Name;direction=$(if($backward){'backward'}else{'forward'});expected=$expected;sequence=$sequence;missing=$missing;unexpected=$unexpected;reverseOrderConsistent=$inverse;passed=($missing.Count -eq 0 -and $unexpected.Count -eq 0 -and $inverse)}
  $pages.Add($record)
  Write-Verbose ($Name+' '+$record.direction+': '+$seen.Count+'/'+$expected.Count)
 }
}
try{
 $initial=Read-State
 if($initial.streaming -or $initial.connecting -or $initial.settingsDirty -or $initial.syncDirty -or $initial.navigationPrompt -or $initial.syncClearPrompt){throw 'Preview must be idle and without unsaved input'}
 # This fails before any mutation unless the app explicitly enabled its isolated test interface.
 Widget inspect | Out-Null
 if(@($initial.devices | Where-Object uuid -eq $DeviceUuid).Count -ne 1){throw 'Target device is missing or duplicated'}
 Do-Action 'devices.open';Do-Action 'device.select' @{uuid=$DeviceUuid}
 Audit-Page 'devices'
 Do-Action 'device.settings.open' @{uuid=$DeviceUuid}
 foreach($tab in @('video','audio','input','network','advanced')){Do-Action 'settings.tab' @{tab=$tab};Audit-Page ('profile-'+$tab)}
 Do-Action 'settings.cancel'
 foreach($tab in @('updates','sync','appearance','about')){Do-Action 'app.settings.tab' @{tab=$tab};Audit-Page ('application-'+$tab)}
 Do-Action 'devices.open'
 Do-Action 'device.info.open' @{uuid=$DeviceUuid};Audit-Page 'device-info';Do-Action 'device.info.cancel'
 Do-Action 'device.add.open';Audit-Page 'device-add';Do-Action 'device.add.cancel'
 Do-Action 'app.settings.tab' @{tab=$initial.settingsTab};Do-Action 'devices.open'
 Do-Action 'device.select' @{uuid=$initial.selected};Do-Action 'devices.filter' @{query=$initial.deviceQuery}
 if(@($pages | Where-Object {-not $_.passed}).Count){throw 'Tab order audit has unreachable or unexpected controls'}
 $success=$true
}catch{$success=$false;$failure=$_.Exception.Message}
finally{
 $report=[ordered]@{success=$success;failure=$failure;pages=$pages;allWindowsInactive=(@($pages | ForEach-Object {$_.sequence} | Where-Object {$_.windowActive -ne $false}).Count -eq 0)}
 $report | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $ReportPath -Encoding utf8
 $session.Dispose()
}
if(-not $success){throw ('Tab order audit failed; see '+$ReportPath+': '+$failure)}
[pscustomobject]@{success=$true;pages=@($pages.page | Sort-Object -Unique).Count;traversals=$pages.Count;controls=(@($pages | Where-Object direction -eq 'forward' | ForEach-Object {@($_.expected).Count}) | Measure-Object -Sum).Sum;allWindowsInactive=$report.allWindowsInactive;report=$ReportPath}
