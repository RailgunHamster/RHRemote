# Read-only timing evidence. Never emits host credentials or app command lines.
$ErrorActionPreference='Stop'
$log='C:\Program Files\Sunshine\config\sunshine.log'
$lines=@(Get-Content -LiteralPath $log -Tail 1600 | Where-Object {
 $_ -match '(?i)(Info|Warning|Error|Debug):' -and
 $_ -match '(?i)RHREMOTE_PHASE|RHREMOTE_GPU_PRIORITY|encoder|capture|display|desktop|session|client|audio|quicksync|qsv|creating|initializ|sunshine version|launch|configuration|stream|probe|duplicat|resolution|refresh|adapter|ready|performance|latency|access.lost|0x887a0026' -and
 $_ -notmatch '(?i)password|private.?key|certificate|token|credential|pin=|rikey|https?://'
} | ForEach-Object { "$_" })
$config=@{}
Get-Content -LiteralPath 'C:\Program Files\Sunshine\config\sunshine.conf' | ForEach-Object {
 if($_ -match '^\s*(encoder|capture|output_name|adapter_name|stream_audio|dd_configuration_option|qsv_preset|qsv_coder|hevc_mode|av1_mode|minimum_fps_target)\s*=\s*(.*)$'){$config[$matches[1]]=$matches[2]}
}
$events=Join-Path $env:ProgramData 'RHRemoteHost\service-events.log'
$result=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');clockUtcMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();utcOffsetMinutes=[TimeZoneInfo]::Local.GetUtcOffset([DateTime]::Now).TotalMinutes;config=$config;services=@(Get-CimInstance Win32_Service|Where-Object {$_.Name-in@('SunshineService','RHRemoteHost')}|Select-Object Name,DisplayName,State,ProcessId);serviceEvents=if(Test-Path -LiteralPath $events){@(Get-Content -LiteralPath $events -Tail 100|Where-Object {$_-match'^\d+ (service_start|sunshine_started|sunshine_exited|service_stop) '}|ForEach-Object {"$_"})}else{@()};processes=@(Get-Process sunshine,sunshinesvc,RHRemoteHost -ErrorAction SilentlyContinue | ForEach-Object {@{name=$_.ProcessName;pid=$_.Id;started=$_.StartTime.ToString('o');cpu=$_.CPU}});log=$lines}
'B64:'+ [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($result|ConvertTo-Json -Depth 5 -Compress)))
