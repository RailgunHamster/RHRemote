param([ValidateSet('github','gitee')][string]$Provider,[Parameter(Mandatory)][string]$CandidateDirectory)
$ErrorActionPreference='Stop';$plan=Get-Content -LiteralPath (Join-Path $CandidateDirectory 'publish-plan.json') -Raw|ConvertFrom-Json
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
 foreach($feed in @('releases.win.json','releases.android.json')){
  $url=if($Provider -eq 'github'){'https://github.com/RailgunHamster/RHRemote/releases/latest/download/'+$feed}else{'https://gitee.com/railgunhamster/RHRemote/releases/download/latest/'+$feed}
  $bytes=$client.GetByteArrayAsync($url).GetAwaiter().GetResult();$hash=[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($bytes)).Replace('-','').ToLowerInvariant()
  if($hash -ne ($plan.artifacts|Where-Object fileName -eq $feed).sha256){throw 'Latest feed differs from immutable version'}
  $results+=@{file=('latest/'+$feed);result='PASS'}
 }
 $repo=Split-Path $PSScriptRoot -Parent
 [IO.File]::WriteAllText((Join-Path $repo ('results/clients-v2/'+$Provider+'-release-downloads.json')),($results|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}finally{$client.Dispose()}
