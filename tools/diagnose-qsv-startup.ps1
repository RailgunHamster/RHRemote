# Synthetic video only. No desktop/audio capture and no changes to running services.
$ErrorActionPreference='Stop'
$paths=@(Get-Process ffmpeg -ErrorAction SilentlyContinue|ForEach-Object {$_.Path}|Where-Object {$_}|Select-Object -Unique)
if(-not $paths.Count){throw 'No existing ffmpeg executable was found; no downloads or installation performed.'}
$ffmpeg=$paths[0];$bench=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');session=(Get-Process -Id $PID).SessionId;exe=$ffmpeg;tests=@()}
foreach($codec in @('h264_qsv','hevc_qsv')){
 $psi=New-Object Diagnostics.ProcessStartInfo $ffmpeg
 $psi.Arguments="-hide_banner -loglevel info -nostdin -f lavfi -i testsrc2=size=1920x1080:rate=30 -vf format=nv12 -c:v $codec -preset veryfast -frames:v 60 -an -f null -"
 $psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
 $watch=[Diagnostics.Stopwatch]::StartNew();$p=[Diagnostics.Process]::Start($psi);$out=$p.StandardOutput.ReadToEndAsync();$err=$p.StandardError.ReadToEndAsync()
 if(-not $p.WaitForExit(25000)){$p.Kill();$p.WaitForExit();$bench.tests+=@{codec=$codec;timeout=$true;elapsed_ms=$watch.ElapsedMilliseconds};continue}
 $p.WaitForExit();$text=$err.Result
 $safe=@($text -split "`r?`n"|Where-Object {$_ -match 'version|Stream #|frame=|Error|failed|device|MFX|QSV|speed=' -and $_ -notmatch '(?i)https?://|token|password' }|ForEach-Object {"$_"})
 $bench.tests+=@{codec=$codec;exit=$p.ExitCode;elapsed_ms=$watch.ElapsedMilliseconds;log=$safe}
}
'B64:'+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($bench|ConvertTo-Json -Depth 5 -Compress)))
