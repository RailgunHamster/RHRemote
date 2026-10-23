param([string]$Shell='powershell.exe')
$ErrorActionPreference='Stop'
$root=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-video-cancel-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root|Out-Null
$source=[IO.File]::ReadAllText((Join-Path $PSScriptRoot natural-video-source.ps1))
$results=@()
foreach($mode in @('before_start','during_validation')){
 $folder=Join-Path $root $mode;New-Item -ItemType Directory -Path $folder|Out-Null
 $media=Join-Path $folder media;New-Item -ItemType Directory -Path $media|Out-Null
 $movie=Join-Path $media 'bbb_sunflower_1080p_30fps_normal.mp4'
 [IO.File]::WriteAllText($movie,'fixture only; no real media process is started')
 [IO.File]::WriteAllText((Join-Path $media source.json),(@{movie=$movie;movieSha256='fixture-sha';metadata=@{streams=@(@{width=1920;height=1080;avg_frame_rate='30/1'})}}|ConvertTo-Json -Depth 5))
 [IO.File]::WriteAllText((Join-Path $folder setup-result.json),'{"display":{"monitor":{"MonitorDevicePath":"fixture-monitor"}}}')
 $display=@'
Add-Type @"
public class RhVideoCancelFixtureDisplay {public string MonitorDevicePath="fixture-monitor";public int SourceWidth=1920,SourceHeight=1080,PositionX=1920,PositionY=0;}
public static class RhDisplayConfig {public static RhVideoCancelFixtureDisplay[] Query(){return new[]{new RhVideoCancelFixtureDisplay()};}}
"@
'@
 [IO.File]::WriteAllText((Join-Path $folder displayconfig.ps1),$display,[Text.UTF8Encoding]::new($false))
 $adapted=$source.Replace("'D:\RHRemoteTools\benchmark-media\bbb-1080p30-20261023'",("'"+$media.Replace("'","''")+"'"))
 $worker=Join-Path $folder source.ps1;[IO.File]::WriteAllText($worker,$adapted,[Text.UTF8Encoding]::new($false))
 $wrapper=@'
$ErrorActionPreference='Stop'
function Get-FileHash {
 param([string]$LiteralPath,[string]$Algorithm)
 [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'hash-entered'),'ready')
 $until=(Get-Date).AddSeconds(5)
 while(-not(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'stop-motion'))){if((Get-Date)-gt$until){throw 'Cancellation fixture watchdog expired'};Start-Sleep -Milliseconds 5}
 [pscustomobject]@{Hash='fixture-sha'}
}
& (Join-Path $PSScriptRoot source.ps1) -Seconds 30
'@
 $entry=Join-Path $folder wrapper.ps1;[IO.File]::WriteAllText($entry,$wrapper,[Text.UTF8Encoding]::new($false))
 $stop=Join-Path $folder stop-motion
 if($mode-eq'before_start'){[IO.File]::WriteAllText($stop,'cancel')}
 $child=Start-Process -FilePath $Shell -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+$entry+'"')) -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $folder stderr.log) -RedirectStandardOutput (Join-Path $folder stdout.log)
 try{
  if($mode-eq'during_validation'){
   $until=(Get-Date).AddSeconds(5)
   do{Start-Sleep -Milliseconds 10}while(-not(Test-Path (Join-Path $folder hash-entered))-and(Get-Date)-lt$until)
   if(-not(Test-Path (Join-Path $folder hash-entered))){throw 'Fixture did not enter startup validation'}
   [IO.File]::WriteAllText($stop,'cancel')
  }
  $clock=[Diagnostics.Stopwatch]::StartNew()
  if(-not$child.WaitForExit(5000)){throw 'Early cancellation did not finish'}
  if($child.ExitCode-ne0){throw ('Source fixture failed: '+[IO.File]::ReadAllText((Join-Path $folder stderr.log)))}
  $status=Get-Content (Join-Path $folder motion-status.json) -Raw|ConvertFrom-Json
  if($status.phase-ne'cancelled_before_playback'-or-not$status.ended-or-not$status.cancelled-or$status.pid-or-not(Test-Path -LiteralPath $stop)){throw 'Cancellation was lost or playback was started'}
  if($mode-eq'before_start'-and(Test-Path (Join-Path $folder hash-entered))){throw 'Cancelled worker still performed expensive startup'}
  $results+=@{case=$mode;result='PASS';exitMs=$clock.ElapsedMilliseconds;shell=$Shell;playbackStarted=$false}
 }finally{if(-not$child.HasExited){$child.Kill();$child.WaitForExit()}}
}
$results|ConvertTo-Json -Depth 4
