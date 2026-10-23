param(
 [string]$Uuid='C3448560-C539-6209-F595-88C1E8D2769B',
 [string]$Output='D:\RHRemoteTools\windows-media-fallback-functional-v2-20261023.json'
)
# Run against an already paired Windows development client with
# RHREMOTE_TEST_MODE=1 and a temporary LAN -> relay profile. Never changes profiles.
$ErrorActionPreference='Stop'
$invoke=Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1'
function Action($id,$values=@{}) { & $invoke -Action $id -Arguments $values }
function State { (& $invoke -Mode state).state }
function Trace { (Action statistics.state).result.latest }
function Save { $script:results | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Output -Encoding UTF8 }
function Wait-Cleanup {
 $until=(Get-Date).AddSeconds(18)
 do { $s=State; if(-not $s.statistics.nativeCleanupPending){return}; Start-Sleep -Milliseconds 200 } while((Get-Date)-lt $until)
 throw 'Previous native session did not finish cleanup'
}
function Stop-Stream {
 Wait-Cleanup
 $s=State;$streamPid=$s.statistics.streamProcessId
 if($s.streaming -or $s.connecting){$null=Action stream.disconnect}
 if($streamPid){$p=Get-Process -Id $streamPid -ErrorAction SilentlyContinue;if($p -and -not $p.WaitForExit(12000)){throw 'Owned stream process did not exit'}}
 Wait-Cleanup
 $until=(Get-Date).AddSeconds(3)
 do{$s=State;if(-not $s.streaming -and -not $s.connecting){return};Start-Sleep -Milliseconds 100}while((Get-Date)-lt $until)
 throw 'Client has not published the disconnected state'
}
function Wait-Frame($exclude='') {
 $until=(Get-Date).AddSeconds(40)
 do {
  Start-Sleep -Milliseconds 200
  $s=State;$trace=Trace
  if($s.streaming -and $s.statistics.firstFrame -and $trace -ne $exclude -and (Test-Path -LiteralPath (Join-Path $trace 'native.jsonl')) -and
     (Select-String -LiteralPath (Join-Path $trace 'native.jsonl') -SimpleMatch '"event":"render_return"' -Quiet)){return $s}
  if(-not $s.connecting -and $s.statistics.phase -eq 'error' -and
     -not $s.statistics.routeRetryEligible -and -not $s.statistics.retryEligible){throw ('Connection failed: '+$s.status)}
 } while((Get-Date)-lt $until)
 throw 'No rendered frame before test deadline'
}
function Assert-TraceEnd($trace) {
 $file=Join-Path $trace 'native.jsonl'
 if(-not(Test-Path -LiteralPath $file) -or -not(Select-String -LiteralPath $file -SimpleMatch '"event":"trace_end"' -Quiet)){throw ('Trace was not finalized: '+$trace)}
}
$results=@()
Stop-Stream
try {
 $clock=[Diagnostics.Stopwatch]::StartNew()
 $null=Action device.connect @{uuid=$Uuid;testInitialFailure='network'}
 $accepted=$clock.ElapsedMilliseconds
 $s=Wait-Frame
 $trace=Trace
 $results+=@{case='initial-network-fallback';acceptedMs=$accepted;nativeAttempt=$s.statistics.nativeAttempt;failed=$s.statistics.routeFailures;route=$s.network.route;trace=$trace}
 Save
 if($s.statistics.nativeAttempt -ne 2 -or $s.statistics.routeFallbackAttempts -ne 1 -or $s.network.route -ne 'relay'){throw 'Initial media failure did not fall back from LAN to relay'}
 Stop-Stream
 Assert-TraceEnd $trace
 foreach($kind in @('identity','decoder')) {
  $clock.Restart();$null=Action device.connect @{uuid=$Uuid;testInitialFailure=$kind};$accepted=$clock.ElapsedMilliseconds
  $until=(Get-Date).AddSeconds(20)
  do{Start-Sleep -Milliseconds 200;$s=State}while(($s.connecting -or $s.statistics.phase -ne 'error') -and (Get-Date)-lt $until)
  $trace=Trace
  $results+=@{case=$kind;acceptedMs=$accepted;streaming=$s.streaming;firstFrame=$s.statistics.firstFrame;nativeAttempt=$s.statistics.nativeAttempt;fallbacks=$s.statistics.routeFallbackAttempts;fatal=$s.statistics.routeFatal;trace=$trace}
  Save
  if($s.streaming -or $s.connecting -or $s.statistics.nativeAttempt -ne 1 -or $s.statistics.routeFallbackAttempts -ne 0 -or $s.statistics.routeFatal -ne $true){throw ($kind+' error was retried or stayed active')}
  Wait-Cleanup
  Assert-TraceEnd $trace
 }
 $null=Action device.connect @{uuid=$Uuid}
 $null=Wait-Frame;$firstTrace=Trace
 $clock.Restart();$null=Action stream.test.interrupt
 $s=Wait-Frame $firstTrace
 $trace=Trace
 $results+=@{case='rendered-session-recovery';recovered=$true;elapsedMs=$clock.ElapsedMilliseconds;attempt=$s.statistics.nativeAttempt;route=$s.network.route;trace=$trace;previousTrace=$firstTrace}
 Save
 Assert-TraceEnd $firstTrace
 Stop-Stream
 Assert-TraceEnd $trace
 $clock.Restart();$null=Action device.connect @{uuid=$Uuid};$accepted=$clock.ElapsedMilliseconds
 $clock.Restart();$null=Action stream.disconnect;$cancelMs=$clock.ElapsedMilliseconds
 $until=(Get-Date).AddSeconds(6)
 do{Start-Sleep -Milliseconds 200;$s=State}while(($s.streaming -or $s.connecting) -and (Get-Date)-lt $until)
 $results+=@{case='pending-connection-cancel';acceptedMs=$accepted;cancelMs=$cancelMs;streaming=$s.streaming;connecting=$s.connecting}
 Save
 if($s.streaming -or $s.connecting -or $cancelMs -gt 1500){throw 'Cancellation was not responsive'}
} finally { Stop-Stream }
$results | ConvertTo-Json -Depth 6
