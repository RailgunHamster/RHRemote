param(
 [ValidateSet('main','toolbar','picker')][string]$Window='main',
 [Parameter(Mandatory=$true)][string]$DestinationPath,
 [ValidateRange(1,30)][int]$TimeoutSeconds=10,
 [int]$Port=48751,
 [string]$TokenFile='D:\RHRemoteTools\secrets\rhremote-control-token.txt',
 [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession
)
$ErrorActionPreference='Stop'
$actionScript=Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1'
$destination=[IO.Path]::GetFullPath($DestinationPath)
if(Test-Path -LiteralPath $destination){throw 'Destination exists; use a new evidence filename'}
if(-not (Test-Path -LiteralPath (Split-Path $destination) -PathType Container)){throw 'Destination folder does not exist'}
$ownsSession=-not $WebSession
if($ownsSession){$WebSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()}
$pending=$null
try{
 $requestId='snapshot-'+[guid]::NewGuid().ToString('N')
 $pending=$destination+'.'+$requestId+'.pending.png'
 $action=switch($Window){'main'{'view.snapshot'};'toolbar'{'view.toolbar.snapshot'};'picker'{'view.picker.snapshot'}}
 $record=(& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $WebSession -Action $action -Arguments @{requestId=$requestId}).result
 $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
 while($record.status -in @('queued','running')){
  if([DateTime]::UtcNow -ge $deadline){throw ('Snapshot remains pending; query preview.id '+$requestId+' instead of resubmitting')}
  Start-Sleep -Milliseconds 80
  $record=(& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $WebSession -Mode state).state.preview
  if($record.id -ne $requestId){throw 'Another snapshot replaced the requested result'}
 }
 if($record.id -ne $requestId -or $record.window -ne $Window -or $record.status -ne 'complete' -or -not $record.ok){throw ('Snapshot did not complete: '+$record.error)}
 if(-not (Test-Path -LiteralPath $record.path -PathType Leaf)){throw 'Completed snapshot file is missing'}
 Copy-Item -LiteralPath $record.path -Destination $pending
 $png=[IO.File]::ReadAllBytes($pending)
 if($png.Length -lt 24 -or ($png[0..7] -join ',') -ne '137,80,78,71,13,10,26,10'){throw 'Snapshot file is not a PNG'}
 $width=[Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($png,16))
 $height=[Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($png,20))
 if($width -ne $record.width -or $height -ne $record.height){throw 'PNG dimensions do not match this request'}
 $after=(& $actionScript -Port $Port -TokenFile $TokenFile -WebSession $WebSession -Mode state).state.preview
 if($after.id -ne $requestId -or $after.status -ne 'complete' -or $after.completedAtMs -ne $record.completedAtMs){throw 'Snapshot result changed while exporting'}
 [IO.File]::Move($pending,$destination)
 [pscustomobject]@{id=$requestId;window=$Window;status='complete';path=$destination;width=$width;height=$height;sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash;completedAtMs=$record.completedAtMs;page=$record.page;settingsTab=$record.settingsTab;profileTab=$record.profileTab;controlsTab=$record.controlsTab;menu=$record.menu}
}finally{
 if($pending -and (Test-Path -LiteralPath $pending -PathType Leaf)){
  if([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($pending)) -ne [IO.Path]::GetDirectoryName($destination)){throw 'Pending export escaped destination folder'}
  Remove-Item -LiteralPath $pending
 }
 if($ownsSession){$WebSession.Dispose()}
}
