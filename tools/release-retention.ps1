function Get-RHRemoteReleaseRetentionPlan($Releases,$Policy,[string]$LatestTag) {
 if($Policy.stableVersions -lt 1 -or $Policy.stableVersions -gt 20 -or $Policy.previewVersions -lt 0 -or $Policy.previewVersions -gt 5 -or $Policy.preserveGitTags -ne $true){throw 'Invalid release retention policy'}
 $recognized=@();$keep=@{};$reason=@{}
 foreach($tag in @($Policy.protectedTags)+@($LatestTag)){if($tag){$keep[$tag]=$true;$reason[$tag]='protected'}}
 foreach($release in $Releases){
  $tag=[string]$release.tag_name
  if($tag -notmatch '^v(\d+\.\d+\.\d+)(?:-preview\.([1-9]\d*))?$' -or $release.draft){$keep[$tag]=$true;$reason[$tag]='draft or unrecognized tag';continue}
  $recognized+=@{release=$release;version=[version]$Matches[1];preview=if($Matches[2]){[long]$Matches[2]}else{0L}}
 }
 $stable=@($recognized|Where-Object {$_.preview -eq 0 -and -not $_.release.prerelease}|Sort-Object version -Descending)
 if(-not $stable.Count){throw 'No stable releases found; refusing cleanup'}
 foreach($item in @($stable|Select-Object -First $Policy.stableVersions)){$keep[$item.release.tag_name]=$true;$reason[$item.release.tag_name]='recent stable'}
 $activePreview=@($recognized|Where-Object {$_.preview -gt 0 -and $_.version -gt $stable[0].version}|Sort-Object @{Expression='version';Descending=$true},@{Expression='preview';Descending=$true}|Select-Object -First $Policy.previewVersions)
 foreach($item in $activePreview){$keep[$item.release.tag_name]=$true;$reason[$item.release.tag_name]='active preview'}
 $retained=@();$remove=@()
 foreach($release in $Releases){
  $entry=[ordered]@{id=[string]$release.id;tag=[string]$release.tag_name;bytes=[long](@($release.assets|ForEach-Object {[long]$_.size})|Measure-Object -Sum).Sum;fingerprint=Get-RHRemoteReleaseFingerprint $release}
  if($keep.ContainsKey($entry.tag)){$entry.reason=$reason[$entry.tag];$retained+=[pscustomobject]$entry}else{$remove+=[pscustomobject]$entry}
 }
 [pscustomobject]@{keep=$retained;remove=$remove;removeBytes=[long]($remove|Measure-Object bytes -Sum).Sum;latest=$LatestTag}
}
function Get-RHRemoteReleaseFingerprint($Release){
 $record=[ordered]@{id=[string]$Release.id;tag=[string]$Release.tag_name;updated=[string]$Release.updated_at;draft=[bool]$Release.draft;preview=[bool]$Release.prerelease;assets=@($Release.assets|Sort-Object name|ForEach-Object {[ordered]@{id=[string]$_.id;name=[string]$_.name;size=[long]$_.size;digest=[string]$_.digest}})}
 $hash=[Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($record|ConvertTo-Json -Depth 10 -Compress)))
 [Convert]::ToHexString($hash).ToLowerInvariant()
}
