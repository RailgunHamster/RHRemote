$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-retention.ps1')
$policy=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-retention.json') -Raw|ConvertFrom-Json
$releases=@();$index=0
foreach($tag in @('v0.2.18','v0.2.17','v0.2.16','v0.2.15','v0.2.14','v0.2.19-preview.1','v0.2.19-preview.2','v0.2.17-preview.9','manual-server-tag')){
 $index++;$releases+=[pscustomobject]@{id="$index";tag_name=$tag;draft=$false;prerelease=$tag.Contains('preview');assets=@(@{id="$index-a";name='package.zip';size=100});updated_at='2026-11-01T00:00:00Z'}
}
$plan=Get-RHRemoteReleaseRetentionPlan $releases $policy 'v0.2.18'
foreach($tag in @('v0.2.18','v0.2.17','v0.2.16','v0.2.15','v0.2.19-preview.2','manual-server-tag')){if($tag -notin $plan.keep.tag){throw "Required release removed: $tag"}}
foreach($tag in @('v0.2.14','v0.2.19-preview.1','v0.2.17-preview.9')){if($tag -notin $plan.remove.tag){throw "Expired release retained: $tag"}}
if($plan.remove.Count -ne 3 -or $plan.removeBytes -ne 300){throw 'Release byte accounting is inconsistent'}
$fingerprint=Get-RHRemoteReleaseFingerprint $releases[0];$releases[0].assets[0].size=101
if((Get-RHRemoteReleaseFingerprint $releases[0]) -eq $fingerprint){throw 'Changed assets did not invalidate the deletion plan'}
$draft=[pscustomobject]@{id='draft';tag_name='v0.2.20-preview.1';draft=$true;prerelease=$true;assets=@()}
if('v0.2.20-preview.1' -notin (Get-RHRemoteReleaseRetentionPlan ($releases+@($draft)) $policy 'v0.2.18').keep.tag){throw 'Unpublished work must remain protected'}
$invalid=@{stableVersions=0;previewVersions=1;preserveGitTags=$true};$rejected=$false
try{$null=Get-RHRemoteReleaseRetentionPlan $releases $invalid 'v0.2.18'}catch{$rejected=$true}
if(-not $rejected){throw 'An invalid retention policy was accepted'}
'PASS: recent stable releases, Linux exception, active previews, drafts, byte accounting and stale-asset detection'
