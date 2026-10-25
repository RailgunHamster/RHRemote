# A real, hash-checked movie on one explicitly selected existing display.
# Run in the active console via the existing bounded source task; no display-mode change.
param(
 [Parameter(Mandatory)][string]$MediaManifest,
 [Parameter(Mandatory)][string]$ExpectedMonitorDevicePath,
 [Parameter(Mandatory)][string]$OutputDirectory,
 [ValidateRange(30,180)][int]$Seconds=120,
 [ValidateRange(1,120)][int]$SourceFps=60,
 [string]$BrowserPath='C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
 [switch]$DisableAcceleratedVideoDecode,
 [switch]$EnableMediaLog
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($OutputDirectory)
if(-not(Test-Path -LiteralPath $root -PathType Container)){throw 'Existing owned source output directory required'}
$status=Join-Path $root 'motion-status.json';$stop=Join-Path $root 'stop-motion'
$failure=$null;$browser=$null;$server=$null;$packet=$null;$rect=$null;$profile=$null
$jobClosed=$false;$profileRemoved=$false;$ended=$false
$cpuSeconds=$null;$cpuSampleElapsed=$null;$members=@();$memberRoles=@()
$clock=[Diagnostics.Stopwatch]::StartNew()
$id=[guid]::NewGuid().ToString('N')
$frames=Join-Path $root ('browser-movie-frames-'+$id+'.jsonl')
$mediaLog=Join-Path $root ('browser-movie-debug-'+$id+'.log')
function Write-Status([string]$Phase){
 $r=[ordered]@{kind='natural_video';producer='browser_movie';phase=$Phase;ended=$ended;error=$failure;audio_enabled=$false;source_file_fps=$SourceFps;source_clock='browser performance.now';physical_source_paint_measured=$false;raw_frames=$frames;raw_frames_closed=($null-eq$server);job_closed=$jobClosed;profile_removed=$profileRemoved;startup_elapsed_s=$clock.Elapsed.TotalSeconds;cpu_seconds=$cpuSeconds;cpu_sample_elapsed_s=$cpuSampleElapsed;job_members=$members;job_member_roles=$memberRoles;disable_accelerated_video_decode_requested=$DisableAcceleratedVideoDecode.IsPresent;actual_decoder_backend_known=$false;media_log=$(if($EnableMediaLog){$mediaLog}else{$null})}
 if($packet){
  $r.elapsed_s=$packet.nowMs/1000;$r.media_time_s=$packet.currentTime;$r.player_dropped_frames=$packet.quality.droppedVideoFrames
  $r.browser=$packet|Select-Object schema,seq,phase,ended,error,nowMs,timeOriginMs,currentTime,paused,seeking,readyState,width,height,playbackRate,muted,volume,visible,pageHasFocus,innerWidth,innerHeight,devicePixelRatio,callbackCount,lastFrame,quality
  $r.width=$packet.width;$r.height=$packet.height
 }
 if($rect){$r.window=@{x=$rect[0];y=$rect[1];width=$rect[2];height=$rect[3];clientWidth=$rect[4];clientHeight=$rect[5];pid=$rect[6]}}
 if($browser){$r.browser_root_pid=$browser.RootPid}
 if($server){$p=$server.Latest();if($p){$r.telemetry_age_ms=([Diagnostics.Stopwatch]::GetTimestamp()-$p.ReceivedQpc)*1000.0/[Diagnostics.Stopwatch]::Frequency;$r.received_packets=$p.Sequence}}
 [IO.File]::WriteAllText($status,($r|ConvertTo-Json -Depth 8 -Compress),[Text.UTF8Encoding]::new($false))
}
try{
 Write-Status 'load_display_helpers'
 if(Test-Path -LiteralPath $stop){throw 'Source was cancelled before startup'}
 . (Join-Path $PSScriptRoot 'displayconfig.ps1')
 $display=@([RhDisplayConfig]::Query()|Where-Object MonitorDevicePath -ceq $ExpectedMonitorDevicePath)
 if($display.Count-ne1){throw 'Expected exactly one explicitly selected active display'}
 $width=$display[0].SourceWidth;$height=$display[0].SourceHeight
 $media=Get-Content -LiteralPath $MediaManifest -Raw|ConvertFrom-Json
 $video=@($media.metadata.streams|Where-Object {$_.width-gt0-and$_.height-gt0})
 if($video.Count-ne1-or$video[0].width-ne$width-or$video[0].height-ne$height-or$video[0].avg_frame_rate-ne($SourceFps.ToString()+'/1')){throw 'Actual movie dimensions/rate do not match the selected display and requested source'}
 if((Get-FileHash -LiteralPath $media.movie).Hash-cne$media.movieSha256){throw 'Real movie SHA256 differs from its manifest'}
 if(-not(Test-Path -LiteralPath $BrowserPath)){throw 'Existing browser is required'}
 Write-Status 'compile_source_transport'
 Add-Type -Path (Join-Path $PSScriptRoot 'browser-movie-source.cs')
 $configuration=@{width=$width;height=$height;seconds=$Seconds;startSeconds=30}|ConvertTo-Json -Compress
 $html=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'browser-movie-source.html')).Replace('__RH_MOVIE_CONFIG__',$configuration)
 $server=[RhMovieServer]::new($media.movie,$html,$frames)
 $profile=Join-Path $root ('browser-movie-profile-'+$id)
 if(Test-Path -LiteralPath $profile){throw 'Fresh owned browser profile already exists'}
 $null=New-Item -ItemType Directory -Path $profile
 # A separate fresh profile is never synchronized or reused. Only its job-owned processes are controlled.
 $arguments='--user-data-dir="'+$profile+'" --no-first-run --no-default-browser-check --disable-sync --autoplay-policy=no-user-gesture-required --force-device-scale-factor=1 --window-position='+$display[0].PositionX+','+$display[0].PositionY+' --window-size='+$width+','+$height+' --kiosk "'+$server.Url+'" --edge-kiosk-type=fullscreen'
 if($DisableAcceleratedVideoDecode){$arguments+=' --disable-accelerated-video-decode'}
 if($EnableMediaLog){$arguments+=' --enable-logging --log-file="'+$mediaLog+'" --vmodule=*video_decoder*=2,*media_foundation*=2'}
 Write-Status 'launch_owned_browser'
 $browser=[RhMovieBrowser]::new($BrowserPath,$arguments,$root)
 $launch=[Diagnostics.Stopwatch]::StartNew();$placed=$false
 do{
  Start-Sleep -Milliseconds 200
  if(Test-Path -LiteralPath $stop){break}
  $cpuSeconds=$browser.CpuSeconds();$cpuSampleElapsed=$clock.Elapsed.TotalSeconds;$members=$browser.Members()
  $rect=$browser.ReadWindow((-not$placed),$display[0].PositionX,$display[0].PositionY,$width,$height)
  if($rect){
   $placed=$true
   if($rect[0]-ne$display[0].PositionX-or$rect[1]-ne$display[0].PositionY-or$rect[2]-ne$width-or$rect[3]-ne$height-or$rect[4]-ne$width-or$rect[5]-ne$height){throw 'Owned movie window/client no longer exactly matches the existing display'}
  }
  $envelope=$server.Latest()
  if($envelope){
   $packet=$envelope.Json|ConvertFrom-Json
   if($packet.schema-ne'rhremote.browser-source/v1'-or$packet.error){throw ('Browser source failed: '+$packet.error)}
   if($packet.phase-eq'playing'){
    if(-not$placed-or$packet.width-ne$width-or$packet.height-ne$height-or$packet.muted-ne$true-or$packet.volume-ne0-or$packet.playbackRate-ne1-or$packet.visible-ne'visible'-or$packet.innerWidth*$packet.devicePixelRatio-ne$width-or$packet.innerHeight*$packet.devicePixelRatio-ne$height){throw 'Actual source viewport, visibility, real-time rate or mute differs'}
    if($null-eq$packet.quality.totalVideoFrames-or$null-eq$packet.quality.droppedVideoFrames-or$null-eq$packet.lastFrame.presentedFrames){throw 'Browser source has no actual frame/quality counters'}
    if(-not$memberRoles.Count){
     $memberRoles=@(Get-CimInstance Win32_Process -Filter "Name='msedge.exe'"|Where-Object {$members-contains$_.ProcessId}|ForEach-Object{
      $p=$_;$role='browser';if($p.CommandLine-match'--type=([a-z-]+)'){$role=$Matches[1]};@{pid=$p.ProcessId;role=$role;createdUtc=$p.CreationDate.ToUniversalTime().ToString('o');name=$p.Name}
     })
    }
   }
   # A slow observer must not declare an older cached packet stale while new packets arrived.
   $fresh=$server.Latest()
   if(-not$fresh-or([Diagnostics.Stopwatch]::GetTimestamp()-$fresh.ReceivedQpc)/[Diagnostics.Stopwatch]::Frequency-gt5){throw 'Browser source telemetry became stale'}
   $packet=$fresh.Json|ConvertFrom-Json
  }
  if($launch.Elapsed.TotalSeconds-gt45-and(-not$packet-or$packet.phase-ne'playing')){throw 'Owned browser did not become ready'}
  Write-Status $(if($packet){$packet.phase}else{'waiting_for_page'})
 }while($launch.Elapsed.TotalSeconds-lt($Seconds+15)-and-not($packet-and$packet.ended))
}catch{$failure=$_.Exception.Message;Write-Status 'error';throw}
finally{
 if($server){
  $server.StopRequested=$true;$deadline=(Get-Date).AddSeconds(3)
  do{
   Start-Sleep -Milliseconds 100;$last=$server.Latest()
   if($last){try{$packet=$last.Json|ConvertFrom-Json}catch{}}
  }while((-not$packet-or-not$packet.ended)-and(Get-Date)-lt$deadline)
 }
 if($browser){$browser.Dispose();$jobClosed=$browser.CleanupComplete}
 if($server){$server.Dispose();$server=$null}
 try{if($profile-and(Test-Path -LiteralPath $profile)-and$jobClosed){
  $resolved=[IO.Path]::GetFullPath($profile);$expectedPrefix=$root.TrimEnd('\')+'\browser-movie-profile-'
  if(-not$resolved.StartsWith($expectedPrefix,[StringComparison]::OrdinalIgnoreCase)-or[IO.Path]::GetFileName($resolved)-cne('browser-movie-profile-'+$id)){throw 'Owned profile cleanup target escaped its exact directory'}
  $links=@(Get-Item -LiteralPath $resolved;Get-ChildItem -LiteralPath $resolved -Recurse -Force)|Where-Object {$_.Attributes-band[IO.FileAttributes]::ReparsePoint}
  if(@($links).Count){throw 'Owned profile contains reparse points; retain for inspection'}
  for($attempt=0;$attempt-lt3;$attempt++){
   try{Remove-Item -LiteralPath $resolved -Recurse -Force;$profileRemoved=-not(Test-Path -LiteralPath $resolved);break}catch{Start-Sleep -Milliseconds 500}
  }
 }}catch{if(-not$failure){$failure=$_.Exception.Message}}
 $ended=$true;Write-Status 'ended'
}
if(-not$jobClosed-or-not$profileRemoved){throw 'Owned browser job/profile cleanup incomplete'}
