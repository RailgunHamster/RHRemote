# Read-only timing evidence. Never emits host credentials or app command lines.
$ErrorActionPreference='Stop'
function Get-RHHostTimingEvidence {
 param(
  [Parameter(Mandatory)] [string] $LegacyLog,
  [Parameter(Mandatory)] [string] $WrapperLog,
  [string] $PreviousWrapperLog = '',
  [bool] $WrapperOwned = $false,
  [ValidateRange(1,8000)] [int] $Tail = 8000
 )
 # The engine truncates its own log at restart. Our service retains stdout across
 # engine lifetimes, so a restored test window must be collected from that log.
 # The supervisor rotates at service launch. A restoration restart can move
 # the just-measured engine lifetime into the previous file.
 if(-not$PreviousWrapperLog){$PreviousWrapperLog=Join-Path (Split-Path -Parent $WrapperLog) 'sunshine-service.previous.log'}
 $selectedPaths=@()
 if($WrapperOwned){
  foreach($candidate in @($PreviousWrapperLog,$WrapperLog)){
   if((Test-Path -LiteralPath $candidate -PathType Leaf)-and$selectedPaths-notcontains$candidate){$selectedPaths+=$candidate}
  }
 }
 $retained=$selectedPaths.Count-gt0
 if(-not$retained){
  if(-not(Test-Path -LiteralPath $LegacyLog -PathType Leaf)){throw 'No host timing log is available'}
  $selectedPaths=@($LegacyLog)
 }
 $selected=$selectedPaths[-1]
 # Bound the filtered evidence, not the raw tail: verbose D3D traffic can
 # otherwise evict the connection/encoder phases within just a few seconds.
 $filtered=@(& {foreach($path in $selectedPaths){Get-Content -LiteralPath $path | Where-Object {
 $_ -match '(?i)(Info|Warning|Error|Debug):' -and
 $_ -match '(?i)RHREMOTE_PHASE|RHREMOTE_GPU_PRIORITY|encoder|capture|display|desktop|session|client|audio|quicksync|qsv|creating|initializ|sunshine version|launch|configuration|stream|probe|duplicat|resolution|refresh|adapter|ready|performance|latency|access.lost|0x887a0026' -and
 $_ -notmatch '(?i)password|private.?key|certificate|token|credential|pin=|rikey|https?://'
 } | ForEach-Object { "$_" }}} | Select-Object -Last $Tail)
 [pscustomobject]@{path=$selected;paths=$selectedPaths;retainedAcrossEngineRestarts=$retained;previousWrapperIncluded=($retained-and$selectedPaths-contains$PreviousWrapperLog);tailLimit=$Tail;lines=$filtered}
}
function Test-RHHostTimingWrapperOwnership {
 param([object[]]$Services)
 # The unified host replaces SunshineService in place; its service name need not
 # be RHRemoteHost. Check the actual executable and service-mode argument.
 @($Services|Where-Object {$_.Name-in@('SunshineService','RHRemoteHost')-and $_.PathName-match'(?i)(?:^|[\\/])RHRemoteHost\.exe"?\s+--sunshine-service(?:\s|$)'}).Count-gt0
}
$services=@(Get-CimInstance Win32_Service|Where-Object {$_.Name-in@('SunshineService','RHRemoteHost')})
$wrapperOwned=Test-RHHostTimingWrapperOwnership -Services $services
$timing=Get-RHHostTimingEvidence -LegacyLog 'C:\Program Files\Sunshine\config\sunshine.log' -WrapperLog (Join-Path $env:ProgramData 'RHRemoteHost\sunshine-service.log') -WrapperOwned $wrapperOwned
$config=@{}
Get-Content -LiteralPath 'C:\Program Files\Sunshine\config\sunshine.conf' | ForEach-Object {
 if($_ -match '^\s*(encoder|capture|output_name|adapter_name|stream_audio|dd_configuration_option|qsv_preset|qsv_coder|hevc_mode|av1_mode|minimum_fps_target)\s*=\s*(.*)$'){$config[$matches[1]]=$matches[2]}
}
$events=Join-Path $env:ProgramData 'RHRemoteHost\service-events.log'
$result=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');clockUtcMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();utcOffsetMinutes=[TimeZoneInfo]::Local.GetUtcOffset([DateTime]::Now).TotalMinutes;config=$config;configSnapshotOnly=$true;logEvidence=@{path=$timing.path;paths=$timing.paths;retainedAcrossEngineRestarts=$timing.retainedAcrossEngineRestarts;previousWrapperIncluded=$timing.previousWrapperIncluded;tailLimit=$timing.tailLimit};services=@($services|Select-Object Name,DisplayName,State,ProcessId);serviceEvents=if(Test-Path -LiteralPath $events){@(Get-Content -LiteralPath $events -Tail 100|Where-Object {$_-match'^\d+ (service_start|sunshine_started|sunshine_exited|service_stop) '}|ForEach-Object {"$_"})}else{@()};processes=@(Get-Process sunshine,sunshinesvc,RHRemoteHost -ErrorAction SilentlyContinue | ForEach-Object {@{name=$_.ProcessName;pid=$_.Id;started=$_.StartTime.ToString('o');cpu=$_.CPU}});log=$timing.lines}
'B64:'+ [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($result|ConvertTo-Json -Depth 5 -Compress)))
