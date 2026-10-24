param([ValidateSet('lan','relay')][string]$Route='lan',[ValidatePattern('^[A-Za-z0-9_-]{1,24}$')][string]$Tag='first',[ValidateRange(1,100)][int]$Cycles=12)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Add-Type @'
using System;using System.Runtime.InteropServices;
public static class RhUiResourceProbe {[DllImport("user32.dll")]public static extern uint GetGuiResources(IntPtr process,uint flags);}
'@
function Action($id,$values=@{}){& .\tools\Invoke-RHRemoteAction.ps1 -Action $id -Arguments $values}
function State{(& .\tools\Invoke-RHRemoteAction.ps1 -Mode state).state}
$s=State
if($s.streaming-or$s.connecting-or$s.statistics.nativeCleanupPending-or$s.draft-or$s.syncBusy){throw 'Existing client work is active'}
if($s.uiRendering.renderer-ne'software'){throw 'Expected the software UI candidate'}
$uuid='C3448560-C539-6209-F595-88C1E8D2769B'
$original=$s.saved.profiles.$uuid;$sync=$s.sync;$selected=$s.selected;$stats=[bool](Action statistics.state).result.enabled
$output='D:\RHRemoteTools\software-toolbar-'+$Route+'-'+$Tag+'-'+(Get-Date -Format yyyyMMdd)
if(Test-Path -LiteralPath $output){throw 'Immutable toolbar evidence exists'}
$null=New-Item -ItemType Directory -Path $output
$exe='D:\git\RHRemote\dist\RHRemote-win-Portable\current\RHRemote.exe'
$ui=@(Get-Process RHRemote -ErrorAction SilentlyContinue|Where-Object Path -eq $exe)
if($ui.Count-ne1){throw 'Expected one idle ordinary UI'}
$ui=$ui[0];$null=$ui.Handle
$changes=@{width=1920;height=1080;fps=30;codec='H.264';displayMode='auto';audioEnabled=$false;packetSizeAuto=$false;packetSize=1024;bitrateMbps=5;remoteBitrateMbps=5;routeOrder=@($Route);gamepadEnabled=$false;decoder='hardware'}
$result=[ordered]@{result='RUNNING';route=$Route;cycles=$Cycles;oppoOperated=$false;audioEnabled=$false;uiSha256=(Get-FileHash $exe).Hash;uiRendering=$s.uiRendering;resources=@();toggles=@();snapshots=@()}
$changed=$false
function Save{[IO.File]::WriteAllText((Join-Path $output result.json),($result|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))}
function Resource($phase){$ui.Refresh();$result.resources+=@{phase=$phase;cpuSeconds=$ui.TotalProcessorTime.TotalSeconds;privateBytes=$ui.PrivateMemorySize64;handles=$ui.HandleCount;gdiObjects=[RhUiResourceProbe]::GetGuiResources($ui.Handle,0);userObjects=[RhUiResourceProbe]::GetGuiResources($ui.Handle,1)}}
function WaitToolbar([bool]$expanded){
 $until=(Get-Date).AddSeconds(5)
 do{$s=State;if($s.toolbar.visible-and$s.toolbar.expanded-eq$expanded-and$s.toolbar.shape.applied-and$s.toolbar.shape.key.expanded-eq$expanded){return $s};Start-Sleep -Milliseconds 25}while((Get-Date)-lt$until)
 throw 'Control toolbar did not apply its actual window hit region'
}
function Snapshot($kind,$name){
 $previous=(State).preview.completedAtMs;$null=Action ('view.'+$kind+'.snapshot');$until=(Get-Date).AddSeconds(10)
 do{$p=(State).preview;if($p.completedAtMs-gt$previous){break};Start-Sleep -Milliseconds 25}while((Get-Date)-lt$until)
 if(-not$p.ok-or$p.completedAtMs-le$previous){throw 'Overlay snapshot did not complete'}
 $path=[IO.Path]::GetFullPath($p.path);$expected=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA ('RHRemote\'+$kind+'-preview.png')))
 if($path-cne$expected){throw 'Unexpected overlay snapshot path'}
 Copy-Item -LiteralPath $path -Destination (Join-Path $output ($name+'.png'))
 $result.snapshots+=@{kind=$kind;name=$name;bytes=(Get-Item -LiteralPath $path).Length}
}
function StopStream{
 $s=State;if($s.streaming-or$s.connecting-or$s.statistics.nativeCleanupPending){$null=Action stream.disconnect}
 $until=(Get-Date).AddSeconds(22)
 do{$s=State;if(-not($s.streaming-or$s.connecting-or$s.statistics.nativeCleanupPending)){return};Start-Sleep -Milliseconds 50}while((Get-Date)-lt$until)
 throw 'Original UI test stream cleanup remains pending'
}
Save
try{
 if($sync.url){$null=Action sync.configure @{url=$sync.url;username=$sync.username;password='';automatic=$false;intervalMinutes=$sync.intervalMinutes}}
 $null=Action statistics.configure @{enabled=$true}
 $null=Action device.settings.open @{uuid=$uuid};foreach($e in $changes.GetEnumerator()){$null=Action settings.set @{key=$e.Key;value=$e.Value}};$null=Action settings.save;$changed=$true
 $clock=[Diagnostics.Stopwatch]::StartNew();$null=Action device.connect @{uuid=$uuid};$until=(Get-Date).AddSeconds(25)
 do{$s=State;if($s.streaming-and$s.statistics.firstFrame){break};Start-Sleep -Milliseconds 50}while((Get-Date)-lt$until)
 if(-not($s.streaming-and$s.statistics.firstFrame)){throw 'Native video did not reach its first frame'}
 if($s.network.route-ne$Route){throw 'Media used a different route'}
 $result.firstFrameObservedMs=$clock.Elapsed.TotalMilliseconds;$result.trace=(Action statistics.state).result.latest;$native=Get-Process -Id $s.statistics.streamProcessId;$null=$native.Handle
 $result.nativeModules=@($native.Modules|Where-Object ModuleName -match '^(d3d11|dxgi|nvoglv64)\.dll$'|Select-Object -ExpandProperty ModuleName)
 if(-not$s.controls){$null=Action stream.controls.toggle}
 $s=WaitToolbar $true;$result.expanded=$s.toolbar;Snapshot toolbar expanded
 Resource warm
 for($i=0;$i-lt$Cycles;$i++){
  $s=WaitToolbar $true;$open=$s.toolbar.toggle
  $null=Action stream.menu.open @{menu='shortcuts'}
  $until=(Get-Date).AddSeconds(5)
  do{$s=State;if($s.toolbar.picker.visible){break};Start-Sleep -Milliseconds 25}while((Get-Date)-lt$until)
  if(-not$s.toolbar.picker.visible){throw 'Shortcut picker did not become visible'}
  if($i-eq0){$result.picker=$s.toolbar.picker;Snapshot picker shortcuts}
  $null=Action stream.menu.close
  $null=Action stream.controls.toggle;$s=WaitToolbar $false;$closed=$s.toolbar.toggle
  if([math]::Abs($open.x-$closed.x)-gt1-or[math]::Abs($open.y-$closed.y)-gt1){throw 'Collapse moved the toggle to another screen coordinate'}
  if($i-eq0){$result.collapsed=$s.toolbar;Snapshot toolbar collapsed}
  $result.toggles+=@{cycle=$i;toggleStable=$true;windowRegionConfirmed=[bool]$s.toolbar.shape.applied}
  $null=Action stream.controls.toggle;$null=WaitToolbar $true
  Resource ('cycle'+$i)
 }
 $s=State;if(-not($s.streaming-and$s.statistics.firstFrame)){throw 'Video stopped during UI-only operations'}
 $result.uiModules=@($ui.Modules|Where-Object ModuleName -match '^(opengl32|nvoglv64|d3d11|dxgi)\.dll$'|Select-Object -ExpandProperty ModuleName)
 if($result.uiModules.Count){throw 'Default software UI loaded a former GPU presentation module'}
 if($result.nativeModules-notcontains'd3d11.dll'){throw 'Hardware video renderer was not observed in the separate native process'}
 StopStream
 $rows=@(Get-Content (Join-Path $result.trace native.jsonl)|ForEach-Object{$_|ConvertFrom-Json})
 $result.renderCount=@($rows|Where-Object event -eq render_return).Count
 $result.nativeFooterCount=@($rows|Where-Object event -eq trace_end).Count
 if($result.renderCount-lt2-or$result.nativeFooterCount-ne1){throw 'The actual native trace is incomplete'}
 Resource final
 $result.result='PASS'
}catch{$result.result='FAIL';$result.error=$_.Exception.Message;Save;throw}
finally{
 try{
  StopStream
  if((State).draft){$null=Action settings.cancel}
  if($changed){$null=Action device.settings.open @{uuid=$uuid};foreach($key in $changes.Keys){$null=Action settings.set @{key=$key;value=$original.$key}};$null=Action settings.save}
  if($selected){$null=Action device.settings.open @{uuid=$selected};$null=Action settings.cancel}
  $cs=(State).sync;if($sync.url){$null=Action sync.configure @{url=$cs.url;username=$cs.username;password='';automatic=[bool]$sync.automatic;intervalMinutes=$sync.intervalMinutes}}
  $null=Action statistics.configure @{enabled=$stats}
  $s=State;$result.profileRestored=($s.saved.profiles.$uuid|ConvertTo-Json -Depth 10 -Compress)-ceq($original|ConvertTo-Json -Depth 10 -Compress);$result.syncRestored=$s.sync.automatic-eq$sync.automatic
  if(-not$result.profileRestored-or-not$result.syncRestored){throw 'User settings were not restored'}
 }catch{$result.restoreError=$_.Exception.Message}
 Save
}
if($result.restoreError){throw 'Toolbar test restoration incomplete'}
[pscustomobject]$result|Select-Object result,route,firstFrameObservedMs,renderCount,nativeModules,uiModules,profileRestored,syncRestored|ConvertTo-Json -Depth 4
