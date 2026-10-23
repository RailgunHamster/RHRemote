param([ValidateSet('github','gitee')][string]$Provider,[Parameter(Mandatory)][string]$CandidateDirectory,[switch]$Preview,[string]$StableBaseline)
$ErrorActionPreference='Stop';$plan=Get-Content -LiteralPath (Join-Path $CandidateDirectory 'publish-plan.json') -Raw|ConvertFrom-Json
if(([bool]$plan.preview) -ne [bool]$Preview){throw 'Preview download verification requires the matching plan and switch'}
if($Preview -and ($Provider -ne 'github' -or $plan.tag -notmatch '^v\d+\.\d+\.\d+-preview\.[1-9]\d*$' -or -not $StableBaseline)){throw 'Preview verification requires GitHub, a preview tag and the stable baseline'}
Add-Type -AssemblyName System.Net.Http
$client=New-Object Net.Http.HttpClient;$client.Timeout=[TimeSpan]::FromMinutes(5);$client.DefaultRequestHeaders.UserAgent.ParseAdd('RHRemote-anonymous-release-verifier')
$base=if($Provider -eq 'github'){'https://github.com/RailgunHamster/RHRemote/releases/download/'+$plan.tag+'/'}else{'https://gitee.com/railgunhamster/RHRemote/releases/download/'+$plan.tag+'/'}
$results=@()
try{
 foreach($asset in $plan.artifacts){
  $timer=[Diagnostics.Stopwatch]::StartNew();$url=$base+$asset.fileName
  $response=$client.GetAsync($url,[Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
  if(-not $response.IsSuccessStatusCode){throw "$Provider anonymous download failed: $($asset.fileName), HTTP $([int]$response.StatusCode)"}
  $stream=$response.Content.ReadAsStreamAsync().GetAwaiter().GetResult();$sha=[Security.Cryptography.SHA256]::Create();$buffer=New-Object byte[] 131072;$count=0L
  try{while(($n=$stream.Read($buffer,0,$buffer.Length)) -gt 0){$count+=$n;if($count -gt $asset.size){throw 'Downloaded asset exceeded expected size'};$null=$sha.TransformBlock($buffer,0,$n,$buffer,0)};$null=$sha.TransformFinalBlock([byte[]]@(),0,0);$hash=[BitConverter]::ToString($sha.Hash).Replace('-','').ToLowerInvariant()}
  finally{$stream.Dispose();$sha.Dispose();$response.Dispose()}
  if($count -ne $asset.size -or $hash -ne $asset.sha256){throw "$Provider anonymous asset checksum differs: $($asset.fileName)"}
  $results+=@{file=$asset.fileName;bytes=$count;sha256=$hash;elapsed_ms=$timer.ElapsedMilliseconds;result='PASS'}
  Write-Output "$Provider anonymous SHA256 verified: $($asset.fileName)"
 }
 if($Preview){
  $release=$client.GetStringAsync(('https://api.github.com/repos/RailgunHamster/RHRemote/releases/tags/'+$plan.tag)).GetAwaiter().GetResult()|ConvertFrom-Json
  if($release.draft -or -not $release.prerelease -or $release.tag_name -ne $plan.tag){throw 'Preview release metadata does not match its channel'}
  $results+=@{file='preview/channel';result='PASS';releaseId=$release.id;prerelease=$true}
  $baseline=Get-Content -LiteralPath $StableBaseline -Raw|ConvertFrom-Json
  foreach($entry in $baseline.PSObject.Properties){
   $bytes=$client.GetByteArrayAsync($entry.Value.url).GetAwaiter().GetResult()
   if($entry.Name -eq 'githubLatest'){
    $latest=[Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json
    if($latest.tag_name -ne $entry.Value.tag -or $latest.id -ne $entry.Value.id -or $latest.prerelease -or $latest.draft){throw 'Preview changed the latest stable release'}
   }else{
    $hash=[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($bytes)).Replace('-','').ToLowerInvariant()
    if($hash -ne $entry.Value.sha256){throw ('Preview changed stable feed '+$entry.Name)}
   }
   $results+=@{file=('stable/'+$entry.Name);result='PASS'}
  }
 }else{foreach($feed in @('releases.win.json','releases.android.json')){
  $url=if($Provider -eq 'github'){'https://github.com/RailgunHamster/RHRemote/releases/latest/download/'+$feed}else{'https://gitee.com/railgunhamster/RHRemote/releases/download/latest/'+$feed}
  $bytes=$client.GetByteArrayAsync($url).GetAwaiter().GetResult();$hash=[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($bytes)).Replace('-','').ToLowerInvariant()
  if($hash -ne ($plan.artifacts|Where-Object fileName -eq $feed).sha256){throw 'Latest feed differs from immutable version'}
  $results+=@{file=('latest/'+$feed);result='PASS'}
 }}
 $repo=Split-Path $PSScriptRoot -Parent
 $resultName=if($Preview){$Provider+'-preview-downloads-'+$plan.tag+'.json'}else{$Provider+'-release-downloads.json'}
 [IO.File]::WriteAllText((Join-Path $repo ('results/clients-v2/'+$resultName)),($results|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}finally{$client.Dispose()}
