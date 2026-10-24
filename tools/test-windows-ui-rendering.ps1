param(
 [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]{1,32}$')][string]$Tag,
 [ValidateRange(1,40)][int]$Cycles=6,
 [ValidateRange(1,60)][int]$IdleSeconds=10
)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
function Action($id,$values=@{}){& .\tools\Invoke-RHRemoteAction.ps1 -Action $id -Arguments $values}
function State{(& .\tools\Invoke-RHRemoteAction.ps1 -Mode state).state}
$initial=State
if($initial.streaming-or$initial.connecting-or$initial.statistics.nativeCleanupPending-or$initial.draft-or$initial.syncBusy){throw 'Finish current work before measuring the UI'}
$root=Join-Path D:\RHRemoteTools ('ui-render-'+$Tag+'-'+(Get-Date -Format yyyyMMdd))
if(Test-Path -LiteralPath $root){throw 'Immutable UI result already exists'}
$null=New-Item -ItemType Directory -Path $root
$exe='D:\git\RHRemote\dist\RHRemote-win-Portable\current\RHRemote.exe'
$ui=@(Get-Process RHRemote -ErrorAction SilentlyContinue|Where-Object Path -eq $exe)
if($ui.Count-ne1){throw 'Expected one idle ordinary UI'}
$ui=$ui[0];$null=$ui.Handle
$profile='C3448560-C539-6209-F595-88C1E8D2769B'
$selected=$initial.selected;$originalPage=$initial.page;$originalTab=$initial.settingsTab
$saved=$initial.saved|ConvertTo-Json -Depth 20 -Compress
$result=[ordered]@{result='RUNNING';source=(git rev-parse HEAD).Trim();uiPid=$ui.Id;uiSha256=(Get-FileHash $exe).Hash;tag=$Tag;cycles=$Cycles;oppoOperated=$false;remoteSessionStarted=$false;timingSemantics='API request to completed application rendering snapshot and PNG write, includes UI timer and polling; not physical screen presentation';snapshots=@();resources=@()}
function Resource($phase){
 $ui.Refresh()
 $result.resources+=@{phase=$phase;cpuSeconds=$ui.TotalProcessorTime.TotalSeconds;privateBytes=$ui.PrivateMemorySize64;workingSetBytes=$ui.WorkingSet64;handles=$ui.HandleCount;threads=$ui.Threads.Count;utc=[DateTime]::UtcNow.ToString('o')}
}
function Save{[IO.File]::WriteAllText((Join-Path $root result.json),($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))}
function Snapshot([string]$name,[bool]$keep){
 $previous=(State).preview.completedAtMs
 $clock=[Diagnostics.Stopwatch]::StartNew();$null=Action view.snapshot
 $until=(Get-Date).AddSeconds(10)
 do{$s=State;$preview=$s.preview;if($preview.completedAtMs-gt$previous){break};Start-Sleep -Milliseconds 15}while((Get-Date)-lt$until)
 if(-not$preview.ok-or$preview.completedAtMs-le$previous){throw 'UI snapshot did not finish'}
 $path=[IO.Path]::GetFullPath($preview.path)
 $expected=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'RHRemote\preview.png'))
 if($path-cne$expected){throw 'Snapshot path is not the ordinary application-owned preview'}
 $result.snapshots+=@{name=$name;elapsedMs=$clock.Elapsed.TotalMilliseconds;bytes=(Get-Item -LiteralPath $path).Length}
 if($keep){Copy-Item -LiteralPath $path -Destination (Join-Path $root ($name+'.png'))}
}
try{
 Resource start
 $before=$ui.TotalProcessorTime.TotalSeconds;$clock=[Diagnostics.Stopwatch]::StartNew()
 Start-Sleep -Seconds $IdleSeconds
 $ui.Refresh();$result.idle=@{wallSeconds=$clock.Elapsed.TotalSeconds;cpuSeconds=$ui.TotalProcessorTime.TotalSeconds-$before}
 Resource idle
 $before=$ui.TotalProcessorTime.TotalSeconds;$clock.Restart()
 for($cycle=0;$cycle-lt$Cycles;$cycle++){
  if((State).streaming-or(State).connecting){throw 'Another operation started during UI measurement'}
  $null=Action devices.open;Snapshot devices ($cycle-eq0)
  $null=Action device.settings.open @{uuid=$profile};Snapshot video ($cycle-eq0)
  $null=Action settings.tab @{tab='advanced'};Snapshot advanced ($cycle-eq0)
  $null=Action settings.cancel
  $null=Action app.settings.open;$null=Action app.settings.tab @{tab='about'};Snapshot about ($cycle-eq0)
  Resource ('cycle'+$cycle)
 }
 $ui.Refresh();$result.busy=@{wallSeconds=$clock.Elapsed.TotalSeconds;cpuSeconds=$ui.TotalProcessorTime.TotalSeconds-$before}
 $samples=@($result.snapshots.elapsedMs|Sort-Object)
 $result.snapshotLatency=@{count=$samples.Count;medianMs=$samples[[int][math]::Floor(($samples.Count-1)*0.5)];p95Ms=$samples[[int][math]::Floor(($samples.Count-1)*0.95)];maxMs=$samples[-1]}
 $result.modules=@($ui.Modules|Where-Object ModuleName -match '^(opengl32|nvoglv64|d3d11|dxgi|libEGL|libGLESv2)\.dll$'|Select-Object -ExpandProperty ModuleName)
 $result.uiRendering=(State).uiRendering
 $result.result='PASS'
}catch{$result.result='FAIL';$result.error=$_.Exception.Message;throw}
finally{
 try{
  $s=State
  if($s.draft){$null=Action settings.cancel}
  if($selected){$null=Action device.settings.open @{uuid=$selected};$null=Action settings.cancel}
  if($originalPage-eq'app-settings'){$null=Action app.settings.open;$null=Action app.settings.tab @{tab=$originalTab}}else{$null=Action devices.open}
  $result.preferencesUnchanged=(((State).saved|ConvertTo-Json -Depth 20 -Compress)-ceq$saved)
  Resource final
  if(-not$result.preferencesUnchanged){throw 'UI-only measurement changed saved preferences'}
 }catch{$result.restoreError=$_.Exception.Message}
 Save
}
if($result.restoreError){throw 'UI measurement did not restore state'}
[pscustomobject]$result|Select-Object result,tag,uiRendering,idle,busy,snapshotLatency,modules,preferencesUnchanged|ConvertTo-Json -Depth 4
