# Explicit release/attachment retention. Never removes Git refs or local artifacts.
[CmdletBinding()]
param(
 [Parameter(Mandatory)][ValidateSet('github','cnb')][string]$Provider,
 [Parameter(Mandatory)][string]$ReportDirectory,
 [string]$TokenFile="$env:LOCALAPPDATA\RHFiles-Publishing\cnb-token.xml",
 [switch]$Apply
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -lt 7){throw 'Release retention requires PowerShell 7'}
. (Join-Path $PSScriptRoot 'release-retention.ps1')
$policy=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-retention.json') -Raw|ConvertFrom-Json
$reportRoot=[IO.Path]::GetFullPath($ReportDirectory)
if(Test-Path -LiteralPath $reportRoot){throw 'Use a new report directory for each cleanup'}
$null=New-Item -ItemType Directory -Path $reportRoot
$api=if($Provider -eq 'github'){'https://api.github.com/repos/RailgunHamster/RHRemote'}else{'https://api.cnb.cool/railgunhamster/RHRemote/-'}
$public=if($Provider -eq 'github'){'https://github.com/RailgunHamster/RHRemote'}else{'https://cnb.cool/railgunhamster/RHRemote/-'}
$handler=[Net.Http.HttpClientHandler]::new();$handler.AllowAutoRedirect=$false
$client=[Net.Http.HttpClient]::new($handler);$client.Timeout=[TimeSpan]::FromSeconds(45)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('RHRemote-Release-Retention')
$token=$null;$secret=$null;$lock=$null;$removed=@();$stage='credential lookup'
function Request([string]$Method,[string]$Path,[switch]$Missing){
 $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),$api+$Path)
 $request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
 $request.Headers.Accept.ParseAdd($(if($Provider -eq 'github'){'application/vnd.github+json'}else{'application/vnd.cnb.api+json'}))
 try{$response=$client.SendAsync($request).GetAwaiter().GetResult();try{
  if($Missing -and [int]$response.StatusCode -eq 404){return $null}
  if(-not $response.IsSuccessStatusCode){throw "Release API $Method failed (HTTP $([int]$response.StatusCode))"}
  $body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult();if($body.Length -gt 8MB){throw 'Release metadata exceeds limit'}
  if($body.Trim()){return $body|ConvertFrom-Json -Depth 60}
 }finally{$response.Dispose()}}finally{$request.Dispose()}
}
function AnonymousHash([string]$Url){
 for($hop=0;$hop -lt 6;$hop++){
  $uri=[uri]$Url;if($uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Port -ne 443){throw 'Unsafe public feed URL'}
  $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get,$uri)
  try{$response=$client.SendAsync($request).GetAwaiter().GetResult();try{
   if([int]$response.StatusCode -in @(301,302,303,307,308)){$Url=[uri]::new($uri,$response.Headers.Location).AbsoluteUri;continue}
   if(-not $response.IsSuccessStatusCode){throw "Public feed failed (HTTP $([int]$response.StatusCode))"}
   $bytes=$response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();if($bytes.Length -gt 2MB){throw 'Unexpected public feed size'}
   return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
  }finally{$response.Dispose()}}finally{$request.Dispose()}
 }
 throw 'Public feed redirect limit exceeded'
}
try{
 if($Provider -eq 'cnb'){
  $lock=[IO.File]::Open((Join-Path (Split-Path $TokenFile -Parent) 'cnb-publish.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
  $secret=Import-Clixml -LiteralPath $TokenFile;if($secret -isnot [Security.SecureString]){throw 'Expected CNB DPAPI credential'}
  $token=[Net.NetworkCredential]::new('', $secret).Password
 }else{
  $start=[Diagnostics.ProcessStartInfo]::new('git');$start.ArgumentList.Add('credential');$start.ArgumentList.Add('fill');$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
  $start.Environment['GIT_TERMINAL_PROMPT']='0';$start.Environment['GCM_INTERACTIVE']='never'
  $process=[Diagnostics.Process]::Start($start)
  try{$output=$process.StandardOutput.ReadToEndAsync();$errors=$process.StandardError.ReadToEndAsync();$process.StandardInput.Write("protocol=https`nhost=github.com`n`n");$process.StandardInput.Close();if(-not $process.WaitForExit(10000)){$process.Kill();throw 'GitHub credential lookup timed out'};foreach($line in ($output.Result -split "`r?`n")){if($line.StartsWith('password=')){$token=$line.Substring(9)}}}finally{$process.Dispose();$output=$null;$errors=$null}
 }
 if(-not $token){throw 'Release credential unavailable'}
 $stage='release inventory';$releases=@()
 for($page=1;$page -le 20;$page++){$query=if($Provider -eq 'github'){"?per_page=100&page=$page"}else{"?page_size=100&page=$page"};$batch=@(Request GET ("/releases"+$query));$releases+=$batch;if($batch.Count -lt 100){break};if($page -eq 20){throw 'Incomplete release inventory'}}
 $latest=Request GET '/releases/latest';$plan=Get-RHRemoteReleaseRetentionPlan $releases $policy $latest.tag_name
 $releases|ConvertTo-Json -Depth 60|Set-Content -LiteralPath (Join-Path $reportRoot 'releases-before.json') -Encoding utf8
 $plan|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $reportRoot 'plan.json') -Encoding utf8
 $feeds=@{};foreach($name in @('releases.win.json','releases.android.json','releases.history.json')){$feeds[$name]=AnonymousHash "$public/releases/latest/download/$name"}
 $linux=AnonymousHash "$public/releases/download/v0.2.15/packages.linux.json"
 if($Apply){foreach($entry in $plan.remove){
   $stage="delete $($entry.tag)"
   if($entry.id -notmatch '^[A-Za-z0-9_-]+$' -or $entry.tag -notmatch '^v\d+\.\d+\.\d+(?:-preview\.[1-9]\d*)?$'){throw 'Unsafe release deletion target'}
   $currentLatest=Request GET '/releases/latest';if($currentLatest.tag_name -cne $plan.latest){throw 'Latest release changed during cleanup'}
   $current=Request GET ("/releases/"+$entry.id);if((Get-RHRemoteReleaseFingerprint $current) -cne $entry.fingerprint){throw 'Release changed after inventory; left it intact'}
   $null=Request DELETE ("/releases/"+$entry.id)
   if($null -ne (Request GET ("/releases/"+$entry.id) -Missing)){throw 'Release deletion was not confirmed'}
   $removed+=$entry
  }}
 $stage='verification';foreach($name in $feeds.Keys){if((AnonymousHash "$public/releases/latest/download/$name") -cne $feeds[$name]){throw 'An update feed changed during cleanup'}}
 if((AnonymousHash "$public/releases/download/v0.2.15/packages.linux.json") -cne $linux){throw 'Linux package feed changed during cleanup'}
 foreach($entry in $plan.keep){$current=Request GET ("/releases/"+$entry.id);if((Get-RHRemoteReleaseFingerprint $current) -cne $entry.fingerprint){throw 'A retained release changed'}}
 $result=@{provider=$Provider;applied=[bool]$Apply;kept=@($plan.keep.tag);removed=@($removed|ForEach-Object {$_.tag});removedReleases=$removed.Count;removedAssetBytes=[long]($removed|Measure-Object bytes -Sum).Sum;feedsUnchanged=$true;gitRefsChanged=$false;completedUtc=[DateTime]::UtcNow.ToString('o')}
 $result|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $reportRoot 'conclusion.json') -Encoding utf8
 $result|ConvertTo-Json -Depth 12 -Compress
}catch{
 @{provider=$Provider;stage=$stage;removed=@($removed|ForEach-Object {$_.tag});failed=$true;message='Cleanup stopped; credential-bearing diagnostics suppressed.'}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $reportRoot 'failure.json') -Encoding utf8
 throw "Release retention stopped at $stage; credentials suppressed."
}finally{$token=$null;if($secret){$secret.Dispose()};if($lock){$lock.Dispose()};$client.Dispose()}
