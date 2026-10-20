$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$out=Join-Path $repo 'design/previews'
New-Item -ItemType Directory -Force -Path $out|Out-Null
function Action([string]$id,[hashtable]$values=@{}){& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Action $id -Arguments $values}
function State{(& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Mode state).state}
function Preview([string]$name){
 $previous=(State).preview.completedAtMs
 $null=Action 'view.snapshot'
 $deadline=(Get-Date).AddSeconds(20)
 do{Start-Sleep -Milliseconds 100;$p=(State).preview}while(( -not $p.completedAtMs -or $p.completedAtMs -le $previous) -and (Get-Date) -lt $deadline)
 if(-not $p.ok -or $p.completedAtMs -le $previous){throw 'UI preview did not complete'}
 $source=[IO.Path]::GetFullPath($p.path)
 if(-not $source.StartsWith('D:\RHRemoteTools\rust-client-state\',[StringComparison]::OrdinalIgnoreCase) -or -not $source.EndsWith('.png')){throw 'Preview returned an unexpected path'}
 Copy-Item -LiteralPath $source -Destination (Join-Path $out $name) -Force
}
$state=State
$d=@($state.devices|Where-Object name -eq 'winserver')
if($d.Count -ne 1){throw 'Expected one winserver device'}
$wasDark=[bool]$state.saved.dark
try{
 $null=Action 'device.settings.open' @{uuid=$d[0].uuid}
 foreach($tab in @('video','audio','input','advanced')){
  $null=Action 'settings.tab' @{tab=$tab}
  Preview ("windows-rust-settings-"+$tab+".png")
 }
 $null=Action 'settings.cancel'
 if(-not (State).saved.dark){$null=Action 'appearance.toggle'}
 Preview 'windows-rust-dark.png'
 $null=Action 'appearance.toggle'
 Preview 'windows-rust-light.png'
 $null=Action 'app.settings.open'
 $null=Action 'app.settings.tab' @{tab='sync'}
 Preview 'windows-rust-sync.png'
}finally{
 if([bool](State).saved.dark -ne $wasDark){$null=Action 'appearance.toggle'}
 $null=Action 'devices.open'
}
Get-ChildItem -LiteralPath $out -Filter 'windows-rust-*.png'|Select-Object Name,Length
