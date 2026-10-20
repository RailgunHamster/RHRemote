param(
 [ValidateSet('action','state','actions')][string]$Mode='action',
 [string]$Action,
 [hashtable]$Arguments=@{},
 [int]$Port=48751,
 [string]$TokenFile='D:\RHRemoteTools\secrets\rhremote-control-token.txt'
)
$ErrorActionPreference='Stop'
$token=[IO.File]::ReadAllText($TokenFile).Trim()
$headers=@{Authorization='Bearer '+$token}
if($Mode -eq 'action'){
 if(-not $Action){throw 'Action is required'}
 $body=@{id=$Action;args=$Arguments}|ConvertTo-Json -Depth 12 -Compress
 $result=Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/action" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
}else{$result=Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/$Mode" -Headers $headers -TimeoutSec 10}
if($result.ok -ne $true){throw 'RHRemote action did not succeed'}
$result
