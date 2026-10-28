param(
 [Parameter(Mandatory=$true)][int]$Port,
 [Parameter(Mandatory=$true)][string]$OutputDirectory,
 [Parameter(Mandatory=$true)][string]$DataDirectory,
 [Parameter(Mandatory=$true)][string]$Version
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$data=(Resolve-Path -LiteralPath $DataDirectory).ProviderPath.TrimEnd('\')
$daily=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'RHRemote')).TrimEnd('\')
if($data -eq $daily){throw 'Visual export requires an isolated preview'}
if(Test-Path -LiteralPath $OutputDirectory){throw 'Output directory already exists'}
$out=New-Item -ItemType Directory -Path $OutputDirectory
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$images=[Collections.Generic.List[object]]::new()
function State {(& "$repo\tools\Invoke-RHRemoteAction.ps1" -Port $Port -Mode state -WebSession $session).state}
function Act([string]$id,[hashtable]$values=@{}){& "$repo\tools\Invoke-RHRemoteAction.ps1" -Port $Port -Action $id -Arguments $values -WebSession $session | Out-Null}
function Capture([string]$name){$result=& "$repo\tools\Export-RHRemoteView.ps1" -Port $Port -DestinationPath (Join-Path $out.FullName ($name+'.png')) -WebSession $session;$images.Add($result)}
$initial=State
$inspection=(& "$repo\tools\Invoke-RHRemoteUiTest.ps1" -Port $Port -Operation inspect).result
if($inspection.windowActive -ne $false){throw 'Preview must remain inactive'}
if($initial.streaming -or $initial.connecting -or $initial.settingsDirty -or $initial.syncDirty -or $initial.sync.automatic){throw 'Preview has active or automatic work; no visual export started'}
$before=(Get-FileHash -LiteralPath (Join-Path $data 'preferences.json') -Algorithm SHA256).Hash
$dailyBefore=(Get-FileHash -LiteralPath (Join-Path $daily 'preferences.json') -Algorithm SHA256).Hash
try {
 foreach($dark in @($false,$true)){
  $theme=if($dark){'dark'}else{'light'}
  Act devices.open
  if([bool](State).saved.dark -ne $dark){Act appearance.toggle}
  Act device.select @{uuid=$initial.selected};Capture "devices-$theme"
  Act devices.filter @{query='no-such-device'};Capture "search-empty-$theme";Act devices.filter @{query=''}
  Act device.settings.open @{uuid=$initial.selected}
  foreach($tab in @('video','audio','input','network','advanced')){Act settings.tab @{tab=$tab};Capture "profile-$tab-$theme"}
  Act settings.cancel
  foreach($tab in @('updates','sync','appearance','about')){Act app.settings.tab @{tab=$tab};Capture "application-$tab-$theme"}
  Act devices.open
  Act device.info.open @{uuid=$initial.selected};Capture "device-info-$theme";Act device.info.cancel
  Act device.add.open;Capture "device-add-$theme";Act device.add.cancel
  Act device.settings.open @{uuid=$initial.selected};Act settings.input @{key='fps';value='25'};Act devices.open
  if((State).navigationPrompt){Capture "unsaved-$theme";Act navigation.resolve @{choice='discard'}}else{Act settings.cancel}
 }
} finally {
 if((State).settingsDirty){Act settings.cancel}
 if((State).syncDirty){Act sync.cancel}
 if([bool](State).saved.dark -ne [bool]$initial.saved.dark){Act appearance.toggle}
 Act devices.open;Act device.select @{uuid=$initial.selected}
 $after=(Get-FileHash -LiteralPath (Join-Path $data 'preferences.json') -Algorithm SHA256).Hash
 $dailyAfter=(Get-FileHash -LiteralPath (Join-Path $daily 'preferences.json') -Algorithm SHA256).Hash
 [ordered]@{version=$Version;visualOnly=$true;functionalVerification=$false;remoteSessionStarted=$false;syncStarted=$false;updateStarted=$false;systemInputInjected=$false;osScreenshots=$false;images=$images;preferencesBefore=$before;preferencesAfter=$after;dailyBefore=$dailyBefore;dailyAfter=$dailyAfter} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $out.FullName 'catalog.json') -Encoding utf8
 $session.Dispose()
 if($before -ne $after -or $dailyBefore -ne $dailyAfter){throw 'Preferences changed during visual export'}
}
[pscustomobject]@{images=$images.Count;directory=$out.FullName;visualOnly=$true}
