param(
 [ValidateSet('inspect','focus','key','scroll','activate','result')][string]$Operation='inspect',
 [ValidateSet('main','toolbar','picker')][string]$Window='main',
 [string]$Control,
 [string]$ActionId,
 [string]$Argument='',
 [bool]$ExpectedPending,
 [ValidateSet('Tab','Enter','Escape','Space','Up','Down','Left','Right','Home','End')][string]$Key,
 [switch]$Shift,
 [switch]$Repeat,
 [switch]$ActivateWindow,
 [double]$Pixels=0,
 [string]$RequestId,
 [string]$Id,
 [ValidateRange(1,30)][int]$TimeoutSeconds=10,
 [int]$Port=48751,
 [string]$TokenFile='D:\RHRemoteTools\secrets\rhremote-control-token.txt',
 [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession
)
$ErrorActionPreference='Stop'
if($PSBoundParameters.ContainsKey('ExpectedPending') -and ($Operation -ne 'activate' -or $Window -ne 'toolbar')){throw 'ExpectedPending requires activate on the toolbar'}
$actionScript=Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1'
$ownsSession=-not $WebSession
if($ownsSession){$WebSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()}
try{
if ($Operation -eq 'result') {
 if (-not $Id) { throw 'Id is required for result' }
 (& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $WebSession -Action 'view.ui.result' -Arguments @{id=$Id}).result
 return
}
$arguments=@{window=$Window;requestId=$(if($RequestId){$RequestId}else{'ui-'+[guid]::NewGuid().ToString('N')})}
if($ActivateWindow){if($Operation -ne 'focus' -or $Window -ne 'main'){throw 'ActivateWindow is restricted to an explicit main-window focus request'};$arguments.activateWindow=$true}
switch ($Operation) {
 'activate' { if (-not $ActionId) { throw 'ActionId is required for activate' }; $arguments.actionId=$ActionId; $arguments.argument=$Argument; if($PSBoundParameters.ContainsKey('ExpectedPending')){$arguments.expectedPending=$ExpectedPending} }
 'focus' { if (-not $Control) { throw 'Control is required for focus' }; $arguments.control=$Control }
 'key' { if (-not $Key) { throw 'Key is required' }; $arguments.key=$Key; $arguments.shift=[bool]$Shift; $arguments.repeat=[bool]$Repeat }
 'scroll' { if (-not $Control) { throw 'Control is required for scroll' }; $arguments.control=$Control; $arguments.pixels=$Pixels }
}
$record=(& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $WebSession -Action ('view.ui.'+$Operation) -Arguments $arguments).result
$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
while ($record.status -in @('queued','running')) {
 if ([DateTime]::UtcNow -ge $deadline) { throw ('UI request is still pending; query this Id rather than resubmitting: '+$record.id) }
 Start-Sleep -Milliseconds 80
 $record=(& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $WebSession -Action 'view.ui.result' -Arguments @{id=$record.id}).result
}
if ($record.status -eq 'failed') { throw ('UI request '+$record.id+': '+$record.result.error) }
if ($record.status -ne 'complete') { throw ('Unexpected UI request status: '+$record.status) }
$record
}finally{if($ownsSession){$WebSession.Dispose()}}
