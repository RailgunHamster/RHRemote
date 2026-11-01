# One immutable timestamp is shared by the notes and both platform update feeds.
function New-RHRemoteReleaseNotes {
 param([Parameter(Mandatory)][string]$Notes,[Parameter(Mandatory)][DateTimeOffset]$PublishedAt)
 if($Notes.Length -gt 131072 -or $PublishedAt.Year -lt 2020){throw 'Invalid release notes or publication time'}
 [pscustomobject]@{
  publishedAt=$PublishedAt.ToUniversalTime().ToString('o')
  notes=('发布时间：'+$PublishedAt.ToOffset([TimeSpan]::FromHours(8)).ToString("yyyy-MM-dd HH:mm 'UTC+08:00'")+"`n`n"+$Notes.Trim()+"`n")
 }
}
