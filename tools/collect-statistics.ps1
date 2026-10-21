param([ValidateSet('windows','android')][string]$Platform='windows',[switch]$KeepRaw,[string]$RunId='')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$params=@{Mode='action';Action='statistics.state'}
if($Platform -eq 'android'){$params.Port=48754;$params.TokenFile='D:\RHRemoteTools\secrets\rhremote-android-control-token.txt'}
$s=& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') @params
if(-not $s.ok){throw 'Statistics state unavailable'}
$path=[string]$s.result.latest;if(-not $path){throw 'No statistics run available'}
if($RunId){
 if($Platform-ne'windows'-or$RunId-notmatch'^\d+-[a-f0-9-]+$'){throw 'Explicit run selection requires a valid Windows run ID'}
 $path=Join-Path (Split-Path -Parent $path) $RunId
 if(-not(Test-Path -LiteralPath (Join-Path $path 'manifest.json'))){throw 'Selected Windows trace does not exist'}
}
if($Platform -eq 'android'){
 $prefix='/data/user/0/com.rhremote.client/files/statistics/'
 if(-not $path.StartsWith($prefix)){throw 'Unexpected app statistics path'}
 $id=$path.Substring($prefix.Length);if($id -notmatch '^\d+-[a-f0-9-]+$'){throw 'Invalid run ID'}
 $adb='C:\Users\40608\AppData\Local\Android\Sdk\platform-tools\adb.exe';$serial='192.168.0.103:5555'
  $androidRoot=[IO.Path]::GetFullPath((Join-Path $repo 'results/statistics/android'))
 if(Test-Path -LiteralPath $androidRoot){foreach($old in (Get-ChildItem -LiteralPath $androidRoot -Directory)){
  $resolved=[IO.Path]::GetFullPath($old.FullName)
  if([IO.Path]::GetDirectoryName($resolved) -ne $androidRoot){throw 'Statistics cleanup escaped root'}
  if($old.Name -ne $id -and (Test-Path -LiteralPath (Join-Path $resolved 'analysis-complete.json')) -and -not(Test-Path -LiteralPath (Join-Path $resolved 'keep'))){Remove-Item -LiteralPath $resolved -Recurse -Force}
 }}
 $out=Join-Path $androidRoot $id;New-Item -ItemType Directory -Force -Path $out|Out-Null
 $deadline=(Get-Date).AddSeconds(8)
 do{
  $rows=@(& $adb -s $serial exec-out run-as com.rhremote.client cat "files/statistics/$id/native.jsonl")
  if($LASTEXITCODE -ne 0){throw 'Could not export app trace'}
  if($rows.Count -gt 0 -and $rows[-1] -match '"event":"trace_end"'){break}
  Start-Sleep -Milliseconds 250
 }while((Get-Date)-lt $deadline)
 if($rows[-1] -notmatch '"event":"trace_end"'){throw 'Statistics writer is still active or trace ended incompletely'}
 [IO.File]::WriteAllLines((Join-Path $out 'native.jsonl'),$rows,[Text.UTF8Encoding]::new($false))
 $manifest=@(& $adb -s $serial exec-out run-as com.rhremote.client cat "files/statistics/$id/manifest.json")
 [IO.File]::WriteAllLines((Join-Path $out 'manifest.json'),$manifest,[Text.UTF8Encoding]::new($false));$path=$out
}
$before=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$evidence=Join-Path $path 'host-evidence.json'
& (Join-Path $PSScriptRoot 'Invoke-RemoteScript.ps1') -HostName winserver -SftpDir /D:/RHRemoteTools/scripts -ScriptPath (Join-Path $PSScriptRoot 'diagnose-host-startup.ps1') -OutFile $evidence -TimeoutSec 30
$after=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$hostData=Get-Content -LiteralPath $evidence -Raw|ConvertFrom-Json
$hostData|Add-Member NoteProperty clockOffsetUs ([long](($hostData.clockUtcMs-($before+$after)/2)*1000))
$hostData|Add-Member NoteProperty clockUncertaintyUs ([long](($after-$before)*500))
[IO.File]::WriteAllText($evidence,($hostData|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$arguments=@((Join-Path $PSScriptRoot 'analyze-statistics.py'),$path,'--host-evidence',$evidence);if($KeepRaw){$arguments+='--keep-raw'}
& python @arguments;if($LASTEXITCODE -ne 0){throw 'Statistics analysis failed'}
if($Platform -eq 'android' -and -not $KeepRaw -and (Test-Path -LiteralPath (Join-Path $path 'analysis-complete.json'))){
 # Fixed validated app-private paths only. Keep manifest+completion until next-run pruning.
 & $adb -s $serial shell run-as com.rhremote.client rm "files/statistics/$id/native.jsonl"
 $marker='{"schema":"rhremote.statistics.cleanup/v1","rawDeleted":true}'
 $marker | & $adb -s $serial shell run-as com.rhremote.client sh -c "'cat > files/statistics/$id/analysis-complete.json'"
}
