# Bounded playback of the attributed benchmark asset on our existing secondary display.
# No screen/audio recording; FFplay audio decoding is disabled with -an.
param([ValidateRange(30,180)][int]$Seconds=180)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$stop=Join-Path $root 'stop-motion'
function Cancel-BeforePlayback {
 if(-not(Test-Path -LiteralPath $stop)){return $false}
 $result=@{kind='natural_video';phase='cancelled_before_playback';ended=$true;cancelled=$true;audio_enabled=$false;pid=$null}
 [IO.File]::WriteAllText((Join-Path $root 'motion-status.json'),($result|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
 return $true
}
if(Cancel-BeforePlayback){return}
. (Join-Path $root 'displayconfig.ps1')
$setup=Get-Content -LiteralPath (Join-Path $root 'setup-result.json') -Raw|ConvertFrom-Json
$target=@([RhDisplayConfig]::Query()|Where-Object MonitorDevicePath -eq $setup.display.monitor.MonitorDevicePath)
if($target.Count-ne1-or$target[0].SourceWidth-ne1920-or$target[0].SourceHeight-ne1080-or($target[0].PositionX-eq0-and$target[0].PositionY-eq0)){throw 'Expected the owned secondary 1920x1080 monitor'}
$mediaRoot='D:\RHRemoteTools\benchmark-media\bbb-1080p30-20261023'
$media=Get-Content -LiteralPath (Join-Path $mediaRoot 'source.json') -Raw|ConvertFrom-Json
$movie=Join-Path $mediaRoot 'bbb_sunflower_1080p_30fps_normal.mp4'
if($media.movie-ne$movie-or(Get-FileHash -LiteralPath $movie -Algorithm SHA256).Hash-ne$media.movieSha256){throw 'Benchmark asset identity differs'}
if($media.metadata.streams[0].width-ne1920-or$media.metadata.streams[0].height-ne1080-or$media.metadata.streams[0].avg_frame_rate-ne'30/1'){throw 'Expected a 1080p30 benchmark asset'}
if(Cancel-BeforePlayback){return}
$player='C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\bin\ffplay.exe'
if(-not(Test-Path -LiteralPath $player)){throw 'Existing FFplay is required'}
Add-Type @'
using System;using System.Runtime.InteropServices;
public static class RhNaturalVideoWindow {
 [StructLayout(LayoutKind.Sequential)]struct Rect{public int left,top,right,bottom;}
 delegate bool EnumProc(IntPtr hwnd,IntPtr value);
 [DllImport("user32.dll")]static extern bool EnumWindows(EnumProc callback,IntPtr value);
 [DllImport("user32.dll")]static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
 [DllImport("user32.dll")]static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll")]static extern bool GetWindowRect(IntPtr hwnd,out Rect rect);
 [DllImport("user32.dll")]static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 public static int[] Read(int pid){
  var previous=SetThreadDpiAwarenessContext(new IntPtr(-4));if(previous==IntPtr.Zero)throw new InvalidOperationException("Physical pixel query unavailable");
  try{int[] result=null;EnumWindows((hwnd,value)=>{uint owner;GetWindowThreadProcessId(hwnd,out owner);if(owner!=pid||!IsWindowVisible(hwnd))return true;Rect r;if(GetWindowRect(hwnd,out r)){result=new[]{r.left,r.top,r.right-r.left,r.bottom-r.top};return false;}return true;},IntPtr.Zero);return result;}
  finally{SetThreadDpiAwarenessContext(previous);}
 }
}
'@
$log=Join-Path $root 'natural-video-player.log'
$status=Join-Path $root 'motion-status.json'
$arguments='-hide_banner -loglevel info -stats -an -autoexit -noborder -window_title "RHRemote Natural Video - Blender Foundation" -left '+$target[0].PositionX+' -top '+$target[0].PositionY+' -x 1920 -y 1080 -ss 30 -t '+$Seconds+' -i "'+$movie+'"'
$psi=[Diagnostics.ProcessStartInfo]::new($player)
$psi.Arguments=$arguments;$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardError=$true
$process=$null;$logStream=$null;$copy=$null;$failure=$null
$clock=[Diagnostics.Stopwatch]::StartNew()
function Write-Status([bool]$Ended){
 $position=$null;$dropped=$null
 if(Test-Path -LiteralPath $log){
  $reader=[IO.StreamReader]::new([IO.FileStream]::new($log,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite))
  try{$content=$reader.ReadToEnd()}finally{$reader.Dispose()}
  $playMatches=[regex]::Matches($content,'(?m)([0-9]+\.[0-9]+)\s+(?:M-V|A-V):.*?fd=\s*(\d+)')
  if($playMatches.Count){$last=$playMatches[$playMatches.Count-1];$position=[double]::Parse($last.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture);$dropped=[int]$last.Groups[2].Value}
 }
 $r=[ordered]@{kind='natural_video';phase=$(if($Ended){'ended'}else{'playing'});ended=$Ended;elapsed_s=$clock.Elapsed.TotalSeconds;width=1920;height=1080;x=$target[0].PositionX;y=$target[0].PositionY;source_file_fps=30;physical_source_paint_measured=$false;media_time_s=$position;player_dropped_frames=$dropped;audio_enabled=$false;pid=$(if($process){$process.Id});cpu_seconds=$(if($process){try{$process.TotalProcessorTime.TotalSeconds}catch{0}});error=$failure;attribution='(c) Blender Foundation / www.bigbuckbunny.org'}
 [IO.File]::WriteAllText($status,($r|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
}
if(Cancel-BeforePlayback){return}
try{
 $process=[Diagnostics.Process]::Start($psi)
 $logStream=[IO.FileStream]::new($log,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite,1,[IO.FileOptions]::Asynchronous)
 $copy=$process.StandardError.BaseStream.CopyToAsync($logStream)
 $until=(Get-Date).AddSeconds(10)
 do{
  if($process.HasExited){throw 'The benchmark player exited during startup'}
  $rect=[RhNaturalVideoWindow]::Read($process.Id)
  if($rect){break};Start-Sleep -Milliseconds 200
 }while((Get-Date)-lt$until)
 if(-not$rect-or$rect[0]-ne$target[0].PositionX-or$rect[1]-ne$target[0].PositionY-or$rect[2]-ne1920-or$rect[3]-ne1080){throw 'Player is not confined to the owned secondary monitor'}
 Write-Status $false
 do{
  if($process.HasExited){throw 'The benchmark player exited before its bounded duration'}
  Start-Sleep -Milliseconds 500;Write-Status $false
 }while($clock.Elapsed.TotalSeconds-lt($Seconds-2)-and-not(Test-Path -LiteralPath $stop))
}catch{$failure=$_.Exception.Message;throw}
finally{
 if($process-and-not$process.HasExited){$null=$process.CloseMainWindow();if(-not$process.WaitForExit(3000)){$process.Kill();$process.WaitForExit()}}
 if($copy){$null=$copy.Wait(3000)}
 if($logStream){$logStream.Dispose()}
 Write-Status $true
 if($process){$process.Dispose()}
}
