param([ValidateSet('lan','relay')][string]$Route='relay',[ValidateSet('disconnect','window','crash')][string]$Mode='disconnect',[ValidateSet('http','streaming')][string]$Stage='http',[ValidatePattern('^[A-Za-z0-9_-]{1,20}$')][string]$Tag='first')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Add-Type @"
using System;using System.Collections.Generic;using System.Diagnostics;using System.Runtime.InteropServices;using System.Threading;
public static class RhUiCancelTcpBoundary {
 [DllImport("iphlpapi.dll")]static extern uint GetExtendedTcpTable(IntPtr table,ref int size,bool sorted,int family,int type,uint reserved);
 public static uint[] Ports(int pid,int remotePort){
  int size=0;GetExtendedTcpTable(IntPtr.Zero,ref size,false,2,5,0);if(size<4||size>1048576)throw new InvalidOperationException("Unexpected TCP table bound");
  IntPtr memory=Marshal.AllocHGlobal(size);try{
   if(GetExtendedTcpTable(memory,ref size,false,2,5,0)!=0)return new uint[0];int count=Marshal.ReadInt32(memory);if(count<0||count>(size-4)/24)throw new InvalidOperationException("Invalid TCP table");
   var ports=new List<uint>();for(int i=0;i<count;i++){IntPtr row=IntPtr.Add(memory,4+i*24);uint owner=(uint)Marshal.ReadInt32(row,20),remote=(uint)Marshal.ReadInt32(row,16);uint port=((remote&255)<<8)|((remote>>8)&255);
    if(owner==(uint)pid&&Marshal.ReadInt32(row)==5&&port==remotePort)ports.Add(unchecked((uint)Marshal.ReadInt32(row,8)));
   }return ports.ToArray();
  }finally{Marshal.FreeHGlobal(memory);}
 }
 public static object Wait(int pid,int remotePort,int limitMs,long processAgeMs){
  var clock=Stopwatch.StartNew();var seen=new HashSet<uint>();long lastActive=0;
  while(clock.ElapsedMilliseconds<limitMs){var ports=Ports(pid,remotePort);long idle=clock.ElapsedMilliseconds-lastActive;
   foreach(var port in ports){if(!seen.Contains(port)&&((seen.Count>0&&idle>=500)||(seen.Count==0&&processAgeMs+clock.ElapsedMilliseconds>=800)))return new {observedFlows=seen.Count+1,silentMs=idle,processAgeMs=processAgeMs+clock.ElapsedMilliseconds,localPortRaw=port};seen.Add(port);}
   if(ports.Length>0)lastActive=clock.ElapsedMilliseconds;Thread.Sleep(2);
  }throw new TimeoutException("No owned HTTPS flow after an idle launch-preparation gap");
 }
}
"@
function Action($id,$values=@{}){& .\tools\Invoke-RHRemoteAction.ps1 -Action $id -Arguments $values}
function State{(& .\tools\Invoke-RHRemoteAction.ps1 -Mode state).state}
function Save{[IO.File]::WriteAllText($output,($result|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))}
function Idle([int]$seconds=20){
 $until=(Get-Date).AddSeconds($seconds)
 do{$s=State;if(-not($s.streaming-or$s.connecting-or$s.statistics.nativeCleanupPending)){return $s};Start-Sleep -Milliseconds 50}while((Get-Date)-lt$until)
 throw 'Client cleanup remains pending'
}
$s=State
if($s.streaming-or$s.connecting-or$s.statistics.nativeCleanupPending-or$s.draft-or$s.syncBusy){throw 'Existing client work is active'}
$uuid='C3448560-C539-6209-F595-88C1E8D2769B'
$original=$s.saved.profiles.$uuid;$sync=$s.sync;$selected=$s.selected;$stats=[bool](Action statistics.state).result.enabled
$output='D:\RHRemoteTools\ui-worker-cancel-'+$Route+'-'+$Mode+'-'+$Tag+'-20261024.json'
if(Test-Path -LiteralPath $output){throw 'Immutable result already exists'}
$uiPath='D:\git\RHRemote\dist\RHRemote-win-Portable\current\RHRemote.exe'
$enginePath='D:\git\RHRemote\dist\RHRemote-win-Portable\current\engine\RHRemote.exe'
$ui=@(Get-Process RHRemote -ErrorAction SilentlyContinue|Where-Object Path -eq $uiPath)
if($ui.Count-ne1){throw 'Expected one task-owned ordinary UI'}
$ui=$ui[0];$null=$ui.Handle
$changes=@{width=1920;height=1080;fps=30;codec='H.264';displayMode='auto';audioEnabled=$false;packetSizeAuto=$false;packetSize=1024;bitrateMbps=5;remoteBitrateMbps=5;routeOrder=@($Route);gamepadEnabled=$false}
$result=[ordered]@{result='RUNNING';route=$Route;mode=$Mode;stage=$Stage;oppoOperated=$false;audioEnabled=$false;syntheticHttpBoundaryObservation=($Stage-eq'http');syntheticOwnerTermination=($Mode-eq'crash');uiPid=$ui.Id;uiSha256=(Get-FileHash $uiPath -Algorithm SHA256).Hash;nativeSha256=(Get-FileHash $enginePath -Algorithm SHA256).Hash;originalProfile=$original;originalStats=$stats;originalAutomaticSync=[bool]$sync.automatic}
$changed=$false;$native=$null;$closed=$false;Save
try{
 if($sync.url){$null=Action sync.configure @{url=$sync.url;username=$sync.username;password='';automatic=$false;intervalMinutes=$sync.intervalMinutes}}
 $null=Action statistics.configure @{enabled=$true}
 $null=Action device.settings.open @{uuid=$uuid};foreach($e in $changes.GetEnumerator()){$null=Action settings.set @{key=$e.Key;value=$e.Value}};$null=Action settings.save;$changed=$true
 $null=Action device.connect @{uuid=$uuid}
 $until=(Get-Date).AddSeconds(30)
 do{
  $children=@(Get-CimInstance Win32_Process -Filter "Name='RHRemote.exe'"|Where-Object {$_.ParentProcessId-eq$ui.Id-and$_.ExecutablePath-eq$enginePath})
  if(-not$children.Count){Start-Sleep -Milliseconds 10}
 }while(-not$children.Count-and(Get-Date)-lt$until)
 if($children.Count-ne1){throw 'Expected one native child of this UI'}
 $native=Get-Process -Id $children[0].ProcessId;$null=$native.Handle
 $before=State;$offset=[int]$before.network.policy.portOffset
 $result.actualRoute=$before.network.route;$result.trace=(Action statistics.state).result.latest;$result.nativePid=$native.Id;$result.portOffset=$offset
 if($result.actualRoute-ne$Route){throw 'Actual route differs from requested isolated route'}
 if($Mode-eq'crash'){
  $workers=@(Get-CimInstance Win32_Process -Filter "Name='RHRemote.exe'"|Where-Object {$_.ParentProcessId-eq$ui.Id-and$_.ExecutablePath-eq$uiPath-and$_.CommandLine-like'*--rhremote-transport-worker*'})
  if($workers.Count-ne1){throw 'Expected one separate owned transport worker'}
  $worker=Get-Process -Id $workers[0].ProcessId;$null=$worker.Handle;$result.workerPid=$worker.Id
 }
 if($Stage-eq'http'){
  $result.tcpBoundary=[RhUiCancelTcpBoundary]::Wait($native.Id,47984+$offset,30000,[long]([DateTime]::UtcNow-$native.StartTime.ToUniversalTime()).TotalMilliseconds)
 }else{
  $until=(Get-Date).AddSeconds(25)
  do{$playing=State;if(-not($playing.streaming-and$playing.statistics.firstFrame)){Start-Sleep -Milliseconds 50}}while(-not($playing.streaming-and$playing.statistics.firstFrame)-and(Get-Date)-lt$until)
  if(-not($playing.streaming-and$playing.statistics.firstFrame)){throw 'No actual stream established before owner termination'}
  $result.firstFrameBeforeTermination=$true
 }
 $clock=[Diagnostics.Stopwatch]::StartNew();$result.cancelRequestedUs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()*1000
 $command=if($Mode-eq'window'){'window.close'}else{'stream.disconnect'}
 if($Mode-eq'crash'){
 $ui.Kill();$ui.WaitForExit();$response=@{ok=$true};$closed=$true;$result.actionOrigin='synthetic forced UI termination'
}else{$response=Action $command};$result.actionResponseMs=$clock.Elapsed.TotalMilliseconds;$result.actionAccepted=[bool]$response.ok
 $result.uiExitedWhileNativeAlive=$false
 while(-not$native.HasExited){if($Mode-ne'crash'-and$ui.HasExited){$result.uiExitedWhileNativeAlive=$true};if($Mode-eq'crash'-and$worker.HasExited-and-not$native.HasExited){throw 'Transport worker exited before native cleanup'};if($clock.Elapsed.TotalSeconds-ge20){throw 'Owned native did not exit after cancellation'};Start-Sleep -Milliseconds 5}
 $result.nativeExitMs=$clock.Elapsed.TotalMilliseconds;$result.nativeExitCode=$native.ExitCode
 if($Mode-in@('window','crash')){
  if(-not$ui.WaitForExit(10000)){throw 'UI did not close after native cleanup'}
  $closed=$true;$result.uiExitMs=$clock.Elapsed.TotalMilliseconds
 }else{$null=Idle;$result.uiRemainsAlive=-not$ui.HasExited}
 $events=@(Get-Content (Join-Path $result.trace native.jsonl)|ForEach-Object{$_|ConvertFrom-Json})
 if($Mode-eq'crash'){$ownerEvent=@($events|Where-Object event -eq owner_process_exited);if($ownerEvent.Count-ne1){throw 'Native did not observe original UI exit'};$result.cancelRequestedUs=$ownerEvent[0].ts_us}
 $result.cancelDuringHttp=[bool]($events|Where-Object {$_.event-in@('http_launch','http_resume')-and$_.ts_us-le$result.cancelRequestedUs-and($_.ts_us+$_.dur_us)-ge$result.cancelRequestedUs})
 $result.protocolStarted=[bool]($events|Where-Object event -eq connection_begin)
 $result.nativeFooterCount=@($events|Where-Object event -eq trace_end).Count
 $result.normalCleanup=[bool]($events|Where-Object event -eq native_transport_closing)
 $result.cleanupOutcomes=@($events|Where-Object event -eq pending_launch_cleanup_result|ForEach-Object{$_.fields.outcome})
 if($Mode-eq'crash'){if(-not$worker.WaitForExit(22000)){throw 'Transport worker did not finish after native exit'};$result.workerExitMs=$clock.Elapsed.TotalMilliseconds;$result.workerExitCode=$worker.ExitCode;if($worker.ExitCode-ne0){throw 'Transport worker could not confirm bounded cleanup'}};$uiEvents=@(Get-Content (Join-Path $result.trace ui.jsonl)|ForEach-Object{$_|ConvertFrom-Json})
 $result.uiCleanupReports=@($uiEvents|Where-Object event -eq native_cleanup_end|ForEach-Object{$_.fields})
 $result.uiFooterCount=@($uiEvents|Where-Object event -eq trace_end).Count
 Save
 if($Stage-eq'http'-and(-not$result.cancelDuringHttp-or$result.protocolStarted)){throw 'Cancellation missed the required in-flight HTTP boundary'}
 if($Stage-eq'streaming'-and(-not$result.protocolStarted-or-not@($events|Where-Object event -eq render_return).Count)){throw 'Original session has no actual render evidence'}
 if($result.nativeExitCode-ne0-or$result.nativeFooterCount-ne1-or-not$result.normalCleanup){throw 'Native did not finish normal cleanup'}
 if($result.uiExitedWhileNativeAlive){throw 'UI exited while its original native child was still alive'}
 $expected=if($Stage-eq'http'){@('cancelled')}else{@('handed_off','finished')}
 if($result.cleanupOutcomes.Count-ne1-or$result.cleanupOutcomes[0]-notin$expected){throw 'Actual paired HTTPS request cleanup was not acknowledged'}
 $nativeReports=@($result.uiCleanupReports|Where-Object kind -eq native)
 if($Mode-ne'crash'-and($nativeReports.Count-ne1-or-not$nativeReports[0].confirmed-or$nativeReports[0].forced)){throw 'Rust UI did not confirm the actual native cleanup'}
 if($closed){$restarted=(& D:\RHRemoteTools\restart-active-client-release.ps1 -WithoutRenderTestCapture)|ConvertFrom-Json;$ui=Get-Process -Id $restarted.pid;$null=$ui.Handle;$closed=$false}
 $retry=[Diagnostics.Stopwatch]::StartNew();$null=Action device.connect @{uuid=$uuid};$until=(Get-Date).AddSeconds(25)
 do{Start-Sleep -Milliseconds 50;$rs=State}while($rs.connecting-and-not$rs.statistics.firstFrame-and(Get-Date)-lt$until)
 $result.retry=@{firstFrame=[bool]($rs.streaming-and$rs.statistics.firstFrame);elapsedMs=$retry.Elapsed.TotalMilliseconds;phase=$rs.statistics.phase;route=$rs.network.route;trace=(Action statistics.state).result.latest;error=$rs.status}
 $null=Action stream.disconnect;$null=Idle
 $frames=@(Get-Content (Join-Path $result.retry.trace native.jsonl)|ForEach-Object{$_|ConvertFrom-Json}|Where-Object event -eq render_return)
 $result.retry.renderCount=$frames.Count
 if(-not$result.retry.firstFrame-or-not$frames.Count){throw 'Immediate retry did not submit an actual frame'}
 $result.result='PASS'
}catch{$result.result='FAIL';$result.error=$_.Exception.Message;Save;throw}
finally{
 try{
  if($ui.HasExited){$null=& D:\RHRemoteTools\restart-active-client-release.ps1 -WithoutRenderTestCapture;$closed=$false}
  $s=State;if($s.streaming-or$s.connecting-or$s.statistics.nativeCleanupPending){$null=Action stream.disconnect;$null=Idle}
  if((State).draft){$null=Action settings.cancel}
  if($changed){$null=Action device.settings.open @{uuid=$uuid};foreach($key in $changes.Keys){$null=Action settings.set @{key=$key;value=$original.$key}};$null=Action settings.save}
  if($selected){$null=Action device.settings.open @{uuid=$selected};$null=Action settings.cancel}
  $cs=(State).sync;if($sync.url){$null=Action sync.configure @{url=$cs.url;username=$cs.username;password='';automatic=[bool]$sync.automatic;intervalMinutes=$sync.intervalMinutes}}
  $null=Action statistics.configure @{enabled=$stats}
  $after=State;$result.profileRestored=($after.saved.profiles.$uuid|ConvertTo-Json -Depth 9 -Compress)-ceq($original|ConvertTo-Json -Depth 9 -Compress);$result.syncRestored=$after.sync.automatic-eq$sync.automatic
  if(-not$result.profileRestored-or-not$result.syncRestored){throw 'Preference restoration differs'}
 }catch{$result.restoreError=$_.Exception.Message}
 Save
}
if($result.restoreError){throw 'Test restoration incomplete'}
[pscustomobject]$result|Select-Object result,route,mode,actionResponseMs,nativeExitMs,cancelDuringHttp,protocolStarted,cleanupOutcomes,retry,profileRestored,syncRestored|ConvertTo-Json -Depth 4
