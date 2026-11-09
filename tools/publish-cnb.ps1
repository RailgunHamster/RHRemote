# Run on the host that owns the DPAPI credential, using PowerShell 7.
# Each version is published only after its immutable assets pass anonymous checks.
[CmdletBinding()]
param(
 [Parameter(Mandatory)][string]$CandidateDirectory,
 [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$Commit,
 [Parameter(Mandatory)][string]$BundlePath,
 [string]$TokenFile="$env:LOCALAPPDATA\RHFiles-Publishing\cnb-token.xml",
 [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -lt 7){throw 'Use PowerShell 7 (pwsh)'}
. (Join-Path $PSScriptRoot 'cnb-release.ps1')
$repository='railgunhamster/RHRemote'
$candidate=[IO.Path]::GetFullPath($CandidateDirectory)
$plan=[IO.File]::ReadAllText((Join-Path $candidate 'publish-plan.json'))|ConvertFrom-Json
if($plan.preview -or $plan.version -notmatch '^\d+\.\d+\.\d+$' -or $plan.tag -cne "v$($plan.version)" -or $plan.commit -cne $Commit){throw 'Expected an audited stable release plan'}
$names=@{}
foreach($asset in $plan.artifacts){
 if($asset.fileName -notmatch '^[A-Za-z0-9._-]+$' -or $asset.fileName -in @('.','..') -or $names.ContainsKey($asset.fileName) -or $asset.sha256 -notmatch '^[a-fA-F0-9]{64}$'){throw 'Invalid or duplicate asset'}
 $names[$asset.fileName]=$true
 Assert-RHRemoteCnbFile (Get-Item -LiteralPath (Join-Path $candidate $asset.fileName)) $asset.size $asset.sha256
}
foreach($required in @('releases.win.json','releases.android.json','RELEASE-NOTES.md')){if(-not $names.ContainsKey($required)){throw 'Stable publication must include both platform feeds and notes'}}
if([version]$plan.version-ge[version]'0.2.23'){
 . (Join-Path $PSScriptRoot 'server-update-signing.ps1')
 $serverFeed=Test-RHRServerUpdateManifest -Manifest (Join-Path $candidate 'releases.server.win.json') -Signature (Join-Path $candidate 'releases.server.win.json.sig')
 $serverAsset=@($plan.artifacts|Where-Object fileName -ceq $serverFeed.fileName)
 if($serverFeed.releaseTag-cne$plan.tag-or$serverAsset.Count-ne1-or$serverAsset[0].size-ne$serverFeed.size-or$serverAsset[0].sha256-ine$serverFeed.sha256){throw 'Server feed differs from immutable publication plan'}
}
$windows=[IO.File]::ReadAllText((Join-Path $candidate 'releases.win.json'))|ConvertFrom-Json
$android=[IO.File]::ReadAllText((Join-Path $candidate 'releases.android.json'))|ConvertFrom-Json
if(-not @($windows.Assets).Count -or $android.schemaVersion -ne 1 -or $android.android.packageName -cne 'com.rhremote.client' -or $android.android.version -cne $plan.version -or $android.android.tag -cne $plan.tag){throw 'Platform feeds differ from the audited release'}
foreach($entry in @($windows.Assets)+@($android.android)){
 $name=if($entry.FileName){$entry.FileName}else{$entry.fileName}
 $asset=@($plan.artifacts|Where-Object fileName -ceq $name)
 if($asset.Count -ne 1 -or $entry.version -cne $plan.version -or $entry.size -ne $asset[0].size -or $entry.sha256 -ine $asset[0].sha256){throw 'Feed asset differs from immutable publication plan'}
}
if(-not(Test-Path -LiteralPath $BundlePath -PathType Leaf)){throw 'Audited Git bundle is required'}
if($ValidateOnly){Write-Output 'PASS: audited CNB publication inputs';return}
$secret=$null;$token=$null;$lock=$null;$client=$null;$stage='authentication'
try {
 $lock=[IO.File]::Open((Join-Path (Split-Path -Parent $TokenFile) 'cnb-publish.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
 $secret=Import-Clixml -LiteralPath $TokenFile
 if($secret -isnot [Security.SecureString]){throw 'Expected DPAPI SecureString'}
 $token=[Net.NetworkCredential]::new('', $secret).Password
 $client=New-RHRemoteCnbHttpClient
 $repo=Invoke-RHRemoteCnbApi $client $token "/$repository" -AllowMissing
 if(-not $repo){$null=Invoke-RHRemoteCnbApi $client $token '/railgunhamster/-/repos' POST @{name='RHRemote';visibility='public';description='RHRemote-Moonlight clients and RHRemote-Sunshine server'} }
 $stage='audited Git source'
 $bare=Join-Path (Split-Path -Parent $TokenFile) 'RHRemote.git'
 if(-not(Test-Path -LiteralPath $bare)){$null=& git init --bare $bare 2>$null;if($LASTEXITCODE){throw 'Cannot initialize publisher repository'}}
 $null=& git -C $bare fetch $BundlePath 'refs/heads/main:refs/heads/main' ("refs/tags/$($plan.tag):refs/tags/$($plan.tag)") 2>$null
 if($LASTEXITCODE){throw 'Cannot import audited bundle'}
 $tagCommit=(& git -C $bare rev-parse ("refs/tags/$($plan.tag)^{}"))
 if($tagCommit -cne $Commit){throw 'Tag differs from audited source'}
 $helper=(Join-Path $PSScriptRoot 'cnb-git-credential.ps1').Replace('\','/')
 # The encrypted credential helper responds only to cnb.cool; no token in argv/config.
 $pwsh=(Get-Process -Id $PID).Path.Replace('\','/')
 $credential='!"'+$pwsh+'" -NoProfile -NonInteractive -File "'+$helper+'"'
 $psi=[Diagnostics.ProcessStartInfo]::new('git');$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
 foreach($arg in @('-C',$bare,'-c','credential.helper=','-c',('credential.helper='+$credential),'push',('https://cnb.cool/'+$repository+'.git'),'refs/heads/main:refs/heads/main',("refs/tags/$($plan.tag):refs/tags/$($plan.tag)"))){$psi.ArgumentList.Add($arg)}
 $psi.Environment['GIT_TERMINAL_PROMPT']='0'
 $psi.Environment['RHREMOTE_CNB_TOKEN_FILE']=[IO.Path]::GetFullPath($TokenFile)
 $process=[Diagnostics.Process]::Start($psi)
 try{$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();if(-not $process.WaitForExit(180000)){$process.Kill();throw 'Source push timed out'};if($process.ExitCode){throw 'Source push failed; credential-bearing diagnostics suppressed'}}finally{$process.Dispose()}
 $stage='release creation'
 $release=Invoke-RHRemoteCnbApi $client $token "/$repository/-/releases/tags/$($plan.tag)" -AllowMissing
 if(-not $release){$release=Invoke-RHRemoteCnbApi $client $token "/$repository/-/releases" POST @{tag_name=$plan.tag;target_commitish=$Commit;name=('RHRemote '+$plan.tag);body=[IO.File]::ReadAllText((Join-Path $candidate 'RELEASE-NOTES.md'));draft=$false;prerelease=$true;make_latest='false'}}
 $scratch=Join-Path $candidate 'cnb-verification'
 $null=New-Item -ItemType Directory -Force -Path $scratch
 foreach($asset in $plan.artifacts){
  $stage='asset '+$asset.fileName
  $existing=@($release.assets|Where-Object name -ceq $asset.fileName)
  if($existing.Count -eq 0){Send-RHRemoteCnbAsset $client $token $repository $release.id (Get-Item -LiteralPath (Join-Path $candidate $asset.fileName))}
  $destination=Join-Path $scratch $asset.fileName
  $null=Receive-RHRemoteCnbPublicFile $client (Get-RHRemoteCnbPublicUrl $repository $plan.tag $asset.fileName) $destination $asset.size
  Assert-RHRemoteCnbFile (Get-Item -LiteralPath $destination) $asset.size $asset.sha256
  Write-Output ('PASS anonymous asset: '+$asset.fileName)
 }
 $stage='latest promotion'
 $latest=Invoke-RHRemoteCnbApi $client $token "/$repository/-/releases/latest" -AllowMissing
 if($latest -and $latest.tag_name -match '^v(\d+\.\d+\.\d+)$' -and [version]$Matches[1] -gt [version]$plan.version){throw 'A newer stable CNB release exists; refusing rollback'}
 $null=Invoke-RHRemoteCnbApi $client $token "/$repository/-/releases/$($release.id)" PATCH @{prerelease=$false;draft=$false;make_latest='true'}
 $feeds=@('releases.win.json','releases.android.json');if($names.ContainsKey('releases.history.json')){$feeds+='releases.history.json'}
 if($names.ContainsKey('releases.server.win.json')){$feeds+=@('releases.server.win.json','releases.server.win.json.sig')}
 foreach($name in $feeds){
  $asset=$plan.artifacts|Where-Object fileName -ceq $name
  $destination=Join-Path $scratch ('latest-'+$name)
  $null=Receive-RHRemoteCnbPublicFile $client (Get-RHRemoteCnbPublicUrl $repository latest $name) $destination 2MB
  Assert-RHRemoteCnbFile (Get-Item -LiteralPath $destination) $asset.size $asset.sha256
 }
 $result=@{version=$plan.version;repository=$repository;commit=$Commit;anonymousAssets=@($plan.artifacts).Count;latestFeeds=$feeds.Count;credentialCopied=$false;completedUtc=[DateTime]::UtcNow.ToString('o')}
 $result|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $candidate 'cnb-publication.json') -Encoding utf8
 $result|ConvertTo-Json -Compress
}catch{Write-Output ('Failure type: '+$_.Exception.GetType().Name);Write-Output $_.ScriptStackTrace;throw "CNB publication stopped at $stage; credential-bearing diagnostics suppressed. Existing artifacts were not overwritten."}finally{if($client){$client.Dispose()};if($secret){$secret.Dispose()};$token=$null;if($lock){$lock.Dispose()}}
try{& (Join-Path $PSScriptRoot 'clean-release-history.ps1') -Provider cnb -TokenFile $TokenFile -ReportDirectory (Join-Path $candidate ('retention-'+[guid]::NewGuid().ToString('N'))) -Apply}
catch{Write-Warning 'Publication succeeded; release retention needs attention. Existing cleanup reports identify any completed deletions.'}
