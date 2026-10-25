param([ValidateSet('windows','android')][string]$Platform='windows',[switch]$KeepRaw,[string]$RunId='')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$params=@{Mode='action';Action='statistics.state'}
if($Platform -eq 'android'){$params.Port=48754;$params.TokenFile='D:\RHRemoteTools\secrets\rhremote-android-control-token.txt'}
$s=& (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') @params
if(-not $s.ok){throw 'Statistics state unavailable'}
$path=[string]$s.result.latest
if($RunId){
 if($RunId-notmatch'^\d+-[a-f0-9-]+$'){throw 'Invalid statistics run ID'}
 if($Platform-eq'windows'){
  $directory=[string]$s.result.directory;if(-not $directory){throw 'Statistics directory unavailable'}
  $path=Join-Path $directory $RunId
  if(-not(Test-Path -LiteralPath (Join-Path $path 'manifest.json'))){throw 'Selected Windows trace does not exist'}
 }else{$path='/data/user/0/com.rhremote.client/files/statistics/'+$RunId}
}elseif(-not $path){throw 'No statistics run available'}
if($Platform -eq 'android'){
 $prefix='/data/user/0/com.rhremote.client/files/statistics/'
 if(-not $path.StartsWith($prefix)){throw 'Unexpected app statistics path'}
 $id=$path.Substring($prefix.Length);if($id -notmatch '^\d+-[a-f0-9-]+$'){throw 'Invalid run ID'}
  $androidRoot=[IO.Path]::GetFullPath((Join-Path $repo 'results/statistics/android'))
 if(Test-Path -LiteralPath $androidRoot){foreach($old in (Get-ChildItem -LiteralPath $androidRoot -Directory)){
  $resolved=[IO.Path]::GetFullPath($old.FullName)
  if([IO.Path]::GetDirectoryName($resolved) -ne $androidRoot){throw 'Statistics cleanup escaped root'}
  if($old.Name -ne $id -and (Test-Path -LiteralPath (Join-Path $resolved 'analysis-complete.json')) -and -not(Test-Path -LiteralPath (Join-Path $resolved 'keep'))){Remove-Item -LiteralPath $resolved -Recurse -Force}
 }}
 $export=& (Join-Path $PSScriptRoot 'export-android-statistics.ps1') -RunId $id -Directory $androidRoot
 $path=$export.directory
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
if($Platform-eq'windows'){
 & python (Join-Path $PSScriptRoot 'analyze-connection.py') $path
 if($LASTEXITCODE-ne0){throw 'Connection-attempt summary failed; individual reports and raw evidence are retained'}
}
if($Platform -eq 'android' -and -not $KeepRaw -and (Test-Path -LiteralPath (Join-Path $path 'analysis-complete.json'))){
 & (Join-Path $PSScriptRoot 'Invoke-RHRemoteAction.ps1') -Action statistics.complete -Port 48754 -TokenFile $params.TokenFile -Arguments @{runId=$id;sha256=$export.nativeSha256}|Out-Null
}
