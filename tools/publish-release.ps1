# Explicit publish command. Credentials stay on their owning machine, never in CLI arguments or output.
param(
 [ValidateSet('github','gitee')][string]$Provider,
 [string]$CandidateDirectory,[string]$Commit,[string]$BundlePath='',
 [switch]$MakePublic,[switch]$SourceOnly,[switch]$Preview,[switch]$ValidateOnly
)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
if($Commit -notmatch '^[a-f0-9]{40}$'){throw 'Expected an exact release commit'}
$candidate=[IO.Path]::GetFullPath($CandidateDirectory)
$plan=Get-Content -LiteralPath (Join-Path $candidate 'publish-plan.json') -Raw|ConvertFrom-Json
$versionPattern=if($Preview){'^\d+\.\d+\.\d+-preview\.[1-9]\d*$'}else{'^\d+\.\d+\.\d+$'}
if($plan.version -notmatch $versionPattern -or $plan.tag -ne ('v'+$plan.version)){throw 'Unexpected release identity'}
if(([bool]$plan.preview) -ne [bool]$Preview){throw 'Preview requires both an explicit switch and a matching plan'}
if($Preview -and $plan.commit -ne $Commit){throw 'Preview plan source commit differs from the audited source'}
if($Preview -and ($Provider -ne 'github' -or $SourceOnly)){throw 'Preview publication currently supports GitHub release assets only'}
if($Preview -and (@($plan.artifacts).Count -eq 0 -or @($plan.artifacts|Where-Object {$_.fileName -match '^releases\.' -or $_.fileName -eq 'RELEASES'}).Count -gt 0)){throw 'Preview must contain assets and cannot contain stable update feeds'}
$tag=[string]$plan.tag;$tagRef='refs/tags/'+$tag;$releaseTitle='RHRemote '+$tag
foreach($asset in $plan.artifacts){
 if($asset.fileName -notmatch '^[A-Za-z0-9._-]+$'){throw 'Invalid asset filename'}
 $file=Join-Path $candidate $asset.fileName
 if((Get-Item -LiteralPath $file).Length -ne $asset.size -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $asset.sha256){throw ('Asset changed: '+$asset.fileName)}
}
if($ValidateOnly){[pscustomobject]@{provider=$Provider;version=$plan.version;tag=$tag;prerelease=[bool]$Preview;makeLatest=(-not $Preview);artifacts=@($plan.artifacts).Count};return}
$token=$null;$pointer=[IntPtr]::Zero;$headers=$null;$credentialOutput=$null
try{
 if($Provider -eq 'github'){
  $owner='RailgunHamster';$api='https://api.github.com/repos/RailgunHamster/RHRemote';$gitUrl='https://github.com/RailgunHamster/RHRemote.git'
  $start=New-Object Diagnostics.ProcessStartInfo 'git';$start.Arguments='credential fill';$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
  $start.EnvironmentVariables['GIT_TERMINAL_PROMPT']='0';$start.EnvironmentVariables['GCM_INTERACTIVE']='never'
  $p=[Diagnostics.Process]::Start($start);$credentialOutput=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync();$p.StandardInput.Write("protocol=https`nhost=github.com`n`n");$p.StandardInput.Close()
  if(-not $p.WaitForExit(10000)){$p.Kill();throw 'GitHub credential lookup timed out'}
  $fields=@{};foreach($line in ($credentialOutput.Result -split "`r?`n")){if($line -match '^([^=]+)=(.*)$'){$fields[$matches[1]]=$matches[2]}}
  $token=$fields.password;if(-not $token){throw 'GitHub credential unavailable'};$fields.Clear()
 }else{
  $owner='railgunhamster';$api='https://gitee.com/api/v5/repos/railgunhamster/RHRemote';$gitUrl='https://gitee.com/railgunhamster/RHRemote.git'
  $secret=Import-Clixml -LiteralPath (Join-Path $env:USERPROFILE '.rhfiles-gitee-token.xml')
  if($secret -isnot [Security.SecureString]){throw 'Expected Gitee DPAPI SecureString'}
  $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret);$token=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
 }
 $headers=@{Authorization='Bearer '+$token;'User-Agent'='RHRemote-publisher';Accept='application/json'}
 function Api([string]$Method,[string]$Path,[object]$Body=$null,[switch]$Allow404){
  try{
   $parameters=@{Uri=($api+$Path);Method=$Method;Headers=$headers;TimeoutSec=30;MaximumRedirection=0}
   if($null -ne $Body){
    if($Provider -eq 'gitee'){
     $form=@{access_token=$token};foreach($key in $Body.Keys){$form[$key]=if($Body[$key] -is [bool]){$Body[$key].ToString().ToLowerInvariant()}else{[string]$Body[$key]}}
     $encoded=($form.GetEnumerator()|ForEach-Object {[Uri]::EscapeDataString([string]$_.Key)+'='+[Uri]::EscapeDataString([string]$_.Value)}) -join '&'
     $parameters.ContentType='application/x-www-form-urlencoded; charset=utf-8';$parameters.Body=[Text.Encoding]::UTF8.GetBytes($encoded)
    }
    else{$parameters.ContentType='application/json';$parameters.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 8 -Compress))}
   }
   Invoke-RestMethod @parameters
  }catch{$code=if($_.Exception.Response){[int]$_.Exception.Response.StatusCode}else{0};if($Allow404 -and $code -eq 404){return $null};$message='';try{$parsed=$_.ErrorDetails.Message|ConvertFrom-Json;$message=[string]$parsed.message;if($message.Length -gt 240 -or $message -match '(?i)token|password|secret|authorization'){$message=''};$message=$message.Replace($token,'[redacted]')}catch{};throw "$Provider API $Method $Path failed (HTTP $code; $message; credentials suppressed)"}
 }
 $accountUri=if($Provider -eq 'github'){'https://api.github.com/user'}else{'https://gitee.com/api/v5/user'}
 try{$account=Invoke-RestMethod -Uri $accountUri -Headers $headers -TimeoutSec 20}catch{throw 'Publisher account verification failed'}
 if($account.login -cne $owner){throw 'Unexpected publisher account'}
 $repository=Api GET '';if($repository.name -ine 'RHRemote'){throw 'Unexpected repository'}
 function RunGit([string[]]$Arguments){
  $psi=New-Object Diagnostics.ProcessStartInfo 'git';$psi.Arguments=($Arguments|ForEach-Object {'"'+($_.Replace('"','\"'))+'"'}) -join ' '
  $psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
  $psi.EnvironmentVariables['GIT_TERMINAL_PROMPT']='0';$psi.EnvironmentVariables['GCM_INTERACTIVE']='never'
  $psi.EnvironmentVariables['GIT_CONFIG_COUNT']='2';$psi.EnvironmentVariables['GIT_CONFIG_KEY_0']='http.'+$gitUrl+'.extraheader'
  $psi.EnvironmentVariables['GIT_CONFIG_VALUE_0']='Authorization: Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($owner+':'+$token))
  $psi.EnvironmentVariables['GIT_CONFIG_KEY_1']='credential.helper';$psi.EnvironmentVariables['GIT_CONFIG_VALUE_1']=''
  $proc=[Diagnostics.Process]::Start($psi);$stdout=$proc.StandardOutput.ReadToEndAsync();$errors=$proc.StandardError.ReadToEndAsync()
  if(-not $proc.WaitForExit(120000)){$proc.Kill();throw 'Source upload timed out'}
  if($proc.ExitCode -ne 0){throw "Git source operation failed (exit $($proc.ExitCode); credential-bearing diagnostics suppressed)"}
  $stdout.Result.Trim()
 }
 if($Provider -eq 'gitee'){
  if(-not(Test-Path -LiteralPath $BundlePath)){throw 'Curated source bundle is required on Gitee publisher host'}
  $sourceRoot=Join-Path $env:USERPROFILE ('RHRemote-publish\'+$Commit.Substring(0,12)+'.git')
  if(-not(Test-Path -LiteralPath $sourceRoot)){$null=New-Item -ItemType Directory -Force -Path $sourceRoot;$null=RunGit -Arguments @('init','--bare',$sourceRoot)}
  $null=RunGit -Arguments @('-C',$sourceRoot,'fetch',$BundlePath,'refs/heads/main:refs/heads/main',($tagRef+':'+$tagRef))
 }else{$sourceRoot=Split-Path $PSScriptRoot -Parent}
 $head=RunGit -Arguments @('-C',$sourceRoot,'rev-parse','refs/heads/main');if($head -ne $Commit){throw 'Source commit differs from audited release'}
 $null=RunGit -Arguments @('-C',$sourceRoot,'push',$gitUrl,'refs/heads/main:refs/heads/main',($tagRef+':'+$tagRef))
 Write-Output "$Provider source uploaded: $Commit"
 if($MakePublic){$null=Api PATCH '' @{name='RHRemote';private=$false;default_branch='main'};$repository=Api GET '';if($repository.private){throw 'Repository remained private'}}
 if($SourceOnly){Write-Output "$Provider audited source mirror updated: $Commit";return}
 $notes=[IO.File]::ReadAllText((Join-Path $candidate 'RELEASE-NOTES.md'))
 $release=Api GET ('/releases/tags/'+$tag) $null -Allow404
 if(-not $release.id){$body=@{tag_name=$tag;target_commitish=$Commit;name=$releaseTitle;body=$notes;prerelease=$true};if($Provider -eq 'github'){$body.draft=$true;$body.target_commitish='main'};$release=Api POST '/releases' $body}
 if(-not $release.id){throw 'Release API did not return a release ID'}
 Add-Type -AssemblyName System.Net.Http
 $client=New-Object Net.Http.HttpClient;$client.Timeout=[TimeSpan]::FromMinutes(10)
 $client.DefaultRequestHeaders.Authorization=New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer',$token)
 $client.DefaultRequestHeaders.UserAgent.ParseAdd('RHRemote-publisher')
 function Upload([object]$Release,[object]$Asset){
  $available=if($Provider -eq 'gitee'){Api GET ('/releases/'+$Release.id+'/attach_files?per_page=100')}else{$Release.assets}
  $existing=@($available|Where-Object {$_.name -eq $Asset.fileName})
  if($existing.Count -gt 0){if($existing[0].size -and $existing[0].size -ne $Asset.size){throw 'Existing release asset size differs; immutable asset not overwritten'};Write-Output "$Provider already has $($Asset.fileName)";return}
  $file=Join-Path $candidate $Asset.fileName;$stream=[IO.File]::OpenRead($file);$multipart=$null;$content=$null
  try{
   $content=New-Object Net.Http.StreamContent($stream);$content.Headers.ContentType=New-Object Net.Http.Headers.MediaTypeHeaderValue('application/octet-stream')
   if($Provider -eq 'github'){
    $upload=$Release.upload_url -replace '\{.*$','';if(-not $upload.StartsWith('https://uploads.github.com/repos/RailgunHamster/RHRemote/')){throw 'Unexpected upload destination'}
    $uri=$upload+'?name='+[Uri]::EscapeDataString($Asset.fileName);$response=$client.PostAsync($uri,$content).GetAwaiter().GetResult()
   }else{
    $multipart=New-Object Net.Http.MultipartFormDataContent
    $multipart.Add((New-Object Net.Http.StringContent($token)),'access_token');$multipart.Add($content,'file',$Asset.fileName)
    $response=$client.PostAsync(($api+'/releases/'+$Release.id+'/attach_files'),$multipart).GetAwaiter().GetResult()
   }
   if(-not $response.IsSuccessStatusCode){throw "$Provider upload $($Asset.fileName) failed (HTTP $([int]$response.StatusCode); response suppressed)"}
   $response.Dispose();Write-Output "$Provider uploaded $($Asset.fileName) ($($Asset.size) bytes)"
  }finally{if($multipart){$multipart.Dispose()}elseif($content){$content.Dispose()};$stream.Dispose()}
 }
 try{foreach($asset in $plan.artifacts){Upload $release $asset}
  if($Provider -eq 'github'){$release=Api PATCH ('/releases/'+$release.id) @{draft=$false;prerelease=[bool]$Preview;make_latest=$(if($Preview){'false'}else{'true'});name=$releaseTitle;body=$notes}}
  else{
   $null=Api PATCH ('/releases/'+$release.id) @{tag_name=$tag;name=$releaseTitle;body=$notes;prerelease=$false}
   # Gitee supports download/latest without a separately named latest tag.
   $release=Api GET ('/releases/tags/'+$tag)
   if(-not $release.id){throw 'Published release could not be read back'}
  }
 }finally{$client.Dispose()}
 $result=[ordered]@{provider=$Provider;repository=$repository.html_url;private=[bool]$repository.private;tag=$tag;release_id=$release.id;assets=$plan.artifacts.Count;commit=$Commit;published=$true}
 if($Provider -eq 'gitee'){'B64:'+ [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($result|ConvertTo-Json -Compress)))}else{$result|ConvertTo-Json -Compress}
}finally{if($pointer -ne [IntPtr]::Zero){[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)};$token=$null;$headers=$null;$credentialOutput=$null;$secret=$null}
