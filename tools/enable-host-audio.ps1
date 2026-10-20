# Restore audio availability after the earlier muted test. Never changes audio devices or host volume.
# Run over the existing authorized administrator SSH connection after both test clients are idle.
$ErrorActionPreference='Stop'
if($env:COMPUTERNAME -ine $ExpectedComputer){throw 'Unexpected Sunshine host'}
$conf='C:\Program Files\Sunshine\config\sunshine.conf'
$original=[IO.File]::ReadAllText($conf)
$pattern='(?m)^[ \t]*stream_audio[ \t]*=[^\r\n]*'
$new=if([regex]::IsMatch($original,$pattern)){[regex]::Replace($original,$pattern,'stream_audio = enabled')}else{$original+[Environment]::NewLine+'stream_audio = enabled'+[Environment]::NewLine}
$r=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');computer=$env:COMPUTERNAME;changed=($new -ne $original);success=$false}
try{
 if($new -ne $original){
  $backup='C:\RHRemoteTools\audio-config-backups';New-Item -ItemType Directory -Force -Path $backup|Out-Null
  [IO.File]::WriteAllText((Join-Path $backup ('sunshine-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'.conf')),$original,[Text.UTF8Encoding]::new($false))
  [IO.File]::WriteAllText($conf,$new,[Text.UTF8Encoding]::new($false))
  Restart-Service SunshineService
 }
 $deadline=(Get-Date).AddSeconds(90);$healthy=$false
 do{Start-Sleep -Milliseconds 500;try{$request=[Net.HttpWebRequest]::Create('http://127.0.0.1:47989/serverinfo');$request.Proxy=$null;$request.Timeout=2000;$response=$request.GetResponse();$healthy=([int]$response.StatusCode -eq 200);$response.Close()}catch{$healthy=$false}}while(-not $healthy -and (Get-Date)-lt $deadline)
 if(-not $healthy){throw 'Sunshine did not return after audio reload'}
 $r.audio_available=([IO.File]::ReadAllText($conf) -match '(?m)^stream_audio = enabled\s*$');$r.service_running=(Get-Service SunshineService).Status -eq 'Running';$r.success=$r.audio_available -and $r.service_running
}catch{
 if($new -ne $original){[IO.File]::WriteAllText($conf,$original,[Text.UTF8Encoding]::new($false));Restart-Service SunshineService -ErrorAction SilentlyContinue}
 $r.error=$_.Exception.Message
}
'B64:'+([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($r|ConvertTo-Json -Depth 4))))
if(-not $r.success){exit 1}
