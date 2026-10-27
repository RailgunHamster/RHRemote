param(
 [int]$Port=48751,
 [Parameter(Mandatory=$true)][string]$DataDirectory,
 [Parameter(Mandatory=$true)][string]$DeviceUuid,
 [Parameter(Mandatory=$true)][string]$ReportPath,
 [string]$TokenFile='D:\RHRemoteTools\secrets\rhremote-control-token.txt'
)
$ErrorActionPreference='Stop'
$actionScript=Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1'
$uiScript=Join-Path $PSScriptRoot 'Invoke-RHRemoteUiTest.ps1'
$data=(Resolve-Path -LiteralPath $DataDirectory).ProviderPath.TrimEnd('\')
$normal=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'RHRemote')).TrimEnd('\')
if($data -eq $normal){throw 'Use an isolated preview data directory'}
$preferences=Join-Path $data 'preferences.json'
$before=(Get-FileHash -LiteralPath $preferences -Algorithm SHA256).Hash
$steps=[Collections.Generic.List[object]]::new()
$failure=$null
$webSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
function Assert-Ui([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Read-State{(& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $webSession -Mode state).state}
function Do-Action([string]$Action,[hashtable]$Arguments=@{}){& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $webSession -Action $Action -Arguments $Arguments | Out-Null}
function Call-Ui([string]$Operation,[string]$Control,[string]$Key,[switch]$Shift,[switch]$Repeat,[double]$Pixels=0,[string]$RequestId){
 $argsUi=@{Port=$Port;TokenFile=$TokenFile;WebSession=$webSession;Operation=$Operation;RequestId=$(if($RequestId){$RequestId}else{'keyboard-suite-'+[guid]::NewGuid().ToString('N')})}
 if($Control){$argsUi.Control=$Control};if($Key){$argsUi.Key=$Key}
 if($Shift){$argsUi.Shift=$true};if($Repeat){$argsUi.Repeat=$true}
 if($Operation -eq 'scroll'){$argsUi.Pixels=$Pixels}
 try{$record=& $uiScript @argsUi}catch{$steps.Add([pscustomobject]@{operation=$Operation;control=$Control;key=$Key;requestId=$argsUi.RequestId;failure=$_.Exception.Message});throw}
 Assert-Ui ($record.result.systemInputInjected -eq $false) 'Unexpected system input path'
 $steps.Add([pscustomobject]@{operation=$Operation;control=$Control;key=$Key;record=$record})
 Assert-Ui (@($record.result.focused).Count -le 1) 'Several controls retain focus markers simultaneously'
 $record.result
}
function Expect-FocusFailure([string]$Control,[string]$Pattern){
 $caught=$null
 try{Call-Ui focus -Control $Control | Out-Null}catch{$caught=$_.Exception.Message}
 Assert-Ui ($caught -and $caught -match $Pattern) ('Expected focus refusal: '+$Control)
 $steps.Add([pscustomobject]@{operation='refused-focus';control=$Control;reason=$caught})
}
function Open-Settings{
 $inspection=Call-Ui inspect
 $button=@($inspection.controls | Where-Object {$_.id -like ('action|device.settings.open|'+$DeviceUuid+'|*')})
 Assert-Ui ($button.Count -eq 1) 'Connection settings button must be unique'
 Call-Ui focus -Control $button[0].id | Out-Null
 $entered=Call-Ui key -Key Enter
 Assert-Ui ($entered.commandIds -contains 'device.settings.open') 'Settings button did not invoke its shared action'
 Assert-Ui ((Read-State).page -eq 'settings') 'Settings page did not open'
}
try{
 $initial=Read-State
 Assert-Ui (-not $initial.streaming -and -not $initial.connecting -and -not $initial.navigationPrompt -and -not $initial.settingsDirty -and -not $initial.syncDirty) 'Preview must start idle and without drafts'
 Do-Action 'devices.open'
 Do-Action 'device.select' @{uuid=$DeviceUuid}
 $deviceInspection=Call-Ui inspect
 $ids=@($deviceInspection.controls.id)
 Assert-Ui (@($ids | Sort-Object -Unique).Count -eq $ids.Count) 'Control IDs must be unique'
 Assert-Ui (@($deviceInspection.controls | Where-Object {$_.PSObject.Properties.Name -contains 'value' -or $_.PSObject.Properties.Name -contains 'text'}).Count -eq 0) 'Inspect must not expose input contents'
 Open-Settings
 Expect-FocusFailure 'action|settings.save||保存' '当前不可用'
 Call-Ui focus -Control 'choice|视频编码' | Out-Null
 $opened=Call-Ui key -Key Enter
 Assert-Ui ($opened.expanded -contains 'choice|视频编码') 'Codec dropdown did not open'
 $closed=Call-Ui key -Key Escape
 Assert-Ui (@($closed.expanded).Count -eq 0 -and @($closed.commandIds).Count -eq 0 -and (Read-State).page -eq 'settings') 'First Escape must only close the dropdown'
 $held=Call-Ui key -Key Escape -Repeat
 Assert-Ui (@($held.commandIds).Count -eq 0 -and (Read-State).page -eq 'settings') 'Held Escape must not navigate'
 $back=Call-Ui key -Key Escape
 Assert-Ui ($back.commandIds -contains 'navigation.back') 'Second Escape must use the shared back action'
 Assert-Ui ((Read-State).page -eq 'devices') 'Second Escape did not return to devices'
 Open-Settings
 Call-Ui focus -Control 'choice|视频编码' | Out-Null
 $next=Call-Ui key -Key Tab
 Assert-Ui (@($next.focused | Where-Object {$_ -like 'action|settings.set|hdr=*'}).Count -eq 1) 'Tab order after codec is incorrect'
 $previous=Call-Ui key -Key Tab -Shift
 Assert-Ui ($previous.focused -contains 'choice|视频编码') 'Shift+Tab did not restore codec focus'
 Call-Ui key -Key Down | Out-Null
 Assert-Ui ((Read-State).settingsDirty) 'Actual codec control did not change the draft'
 Do-Action 'devices.open'
 $modal=Call-Ui inspect
 Assert-Ui ($modal.modal -eq 'unsaved' -and $modal.focused -contains 'action|navigation.resolve|stay|继续编辑') 'Unsaved decision must initially focus Continue editing'
 Expect-FocusFailure 'input|画面宽度' '对话框限制'
 $discard=Call-Ui key -Key Tab
 Assert-Ui ($discard.focused -contains 'action|navigation.resolve|discard|放弃更改') 'Dialog Tab did not focus Discard'
 $stay=Call-Ui key -Key Tab -Shift
 Assert-Ui ($stay.focused -contains 'action|navigation.resolve|stay|继续编辑') 'Dialog Shift+Tab did not focus Continue editing'
 $resumed=Call-Ui key -Key Enter
 Assert-Ui ($resumed.modal -eq '' -and $resumed.focused -contains 'choice|视频编码') 'Continue editing did not restore the original control focus'
 Assert-Ui ((Read-State).settingsDirty) 'Continue editing discarded the draft'
 Do-Action 'devices.open'
 $cancelled=Call-Ui key -Key Escape
 Assert-Ui ($cancelled.focused -contains 'choice|视频编码' -and (Read-State).settingsDirty) 'Dialog Escape must preserve the draft and restore focus'
 Do-Action 'settings.cancel'
 Open-Settings
 $savedFps=[string](Read-State).draft.fps
 Do-Action 'settings.input' @{key='fps';value=$(if($savedFps -eq '24'){'25'}else{'24'})}
 Call-Ui focus -Control 'action|settings.save||保存' | Out-Null
 Do-Action 'settings.input' @{key='fps';value=$savedFps}
 $disabled=Call-Ui key -Key Enter
 Assert-Ui (-not (Read-State).settingsDirty -and @($disabled.commandIds).Count -eq 0) 'Disabled focused Save must not submit'
 Call-Ui focus -Control 'input|画面宽度' | Out-Null
 Do-Action 'settings.input' @{key='width';value='invalid'}
 Do-Action 'devices.open'
 Call-Ui focus -Control 'action|navigation.resolve|save|保存并继续' | Out-Null
 $invalid=Call-Ui key -Key Enter
 $invalidState=Read-State
 Assert-Ui ($invalid.commandIds -contains 'navigation.resolve' -and $invalidState.page -eq 'settings' -and $invalidState.settingsErrorField -eq 'width' -and $invalidState.settingsInputs.width -eq 'invalid') 'Save decision must use the actually focused choice and retain invalid input'
 Do-Action 'devices.open'
 Call-Ui focus -Control 'action|navigation.resolve|discard|放弃更改' | Out-Null
 Call-Ui key -Key Enter | Out-Null
 Assert-Ui ((Read-State).page -eq 'devices') 'Focused Discard must navigate without saving'
 Open-Settings
 $down=Call-Ui scroll -Control 'scroll|form' -Pixels -10000
 $bottom=($down.controls | Where-Object id -eq 'scroll|form').scroll
 Assert-Ui ($bottom.y -eq $bottom.minimumY) 'Scroll must clamp at the bottom'
 $up=Call-Ui scroll -Control 'scroll|form' -Pixels 10000
 Assert-Ui (($up.controls | Where-Object id -eq 'scroll|form').scroll.y -eq 0) 'Scroll must clamp at the top'
 Call-Ui focus -Control 'choice|视频编码' | Out-Null
 $request='keyboard-suite-'+[guid]::NewGuid().ToString('N')
 Call-Ui key -Key Enter -RequestId $request | Out-Null
 Call-Ui key -Key Escape | Out-Null
 Call-Ui key -Key Enter -RequestId $request | Out-Null
 $deduplicated=Call-Ui inspect
 Assert-Ui (@($deduplicated.expanded).Count -eq 0) 'Replaying a completed request must not execute it again'
 Do-Action 'settings.cancel'
 $success=$true
}catch{$failure=$_.Exception.Message;$success=$false}
finally{
 $after=(Get-FileHash -LiteralPath $preferences -Algorithm SHA256).Hash
 if($before -ne $after){$success=$false;$failure='Isolated preferences changed unexpectedly: '+$failure}
 $report=[ordered]@{success=$success;failure=$failure;port=$Port;deviceUuid=$DeviceUuid;preferencesBefore=$before;preferencesAfter=$after;allWindowsInactive=(@($steps | Where-Object {$_.record -and $_.record.result.windowActive -ne $false}).Count -eq 0);steps=$steps}
 $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ReportPath -Encoding utf8
 $webSession.Dispose()
}
if(-not $success){throw ('UI keyboard regression failed; see '+$ReportPath+': '+$failure)}
[pscustomobject]@{success=$true;steps=$steps.Count;preferencesUnchanged=($before -eq $after);allWindowsInactive=$report.allWindowsInactive;report=$ReportPath}
