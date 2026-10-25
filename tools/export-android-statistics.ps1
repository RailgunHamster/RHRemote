param(
 [Parameter(Mandatory)][ValidatePattern('^\d+-[a-f0-9-]{12}$')][string]$RunId,
 [int]$Port=48754,
 [string]$TokenFile='D:\RHRemoteTools\secrets\rhremote-android-control-token.txt',
 [string]$Directory=(Join-Path (Split-Path $PSScriptRoot -Parent) 'results/statistics/android')
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($Directory)
$out=[IO.Path]::GetFullPath((Join-Path $root $RunId))
if([IO.Path]::GetDirectoryName($out)-ne$root){throw 'Statistics export escaped destination'}
New-Item -ItemType Directory -Force -Path $out|Out-Null
$token=[IO.File]::ReadAllText($TokenFile).Trim()
$headers=@{Authorization='Bearer '+$token}
$proof=[ordered]@{runId=$RunId;directory=$out;files=@()}
try{
 foreach($name in @('manifest.json','native.jsonl')){
  $file=Join-Path $out $name;$temporary=Join-Path $out ($name+'.download')
  $response=Invoke-WebRequest -Uri "http://127.0.0.1:$Port/v1/statistics/$RunId/$name" -Headers $headers -OutFile $temporary -PassThru -UseBasicParsing -TimeoutSec 90
  $expected=[string]@($response.Headers['X-Content-SHA256'])[0]
  $length=[long]([string]@($response.Headers['Content-Length'])[0])
  $hash=(Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
  if($expected-notmatch'^[a-f0-9]{64}$'-or$hash-ne$expected-or(Get-Item -LiteralPath $temporary).Length-ne$length){throw 'Statistics export length or SHA-256 differs'}
  if(Test-Path -LiteralPath $file){if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash-ine$hash){throw 'Refusing to overwrite a different exported trace'}}
  Move-Item -LiteralPath $temporary -Destination $file -Force
  $proof.files+=@{name=$name;size=$length;sha256=$hash}
 }
 $reader=[IO.File]::OpenText((Join-Path $out 'native.jsonl'))
 try{$last='';while($null-ne($line=$reader.ReadLine())){if($line){$last=$line}}}finally{$reader.Dispose()}
 if(($last|ConvertFrom-Json).event-ne'trace_end'){throw 'Exported trace has no terminal writer footer'}
 $proof.nativeSha256=($proof.files|Where-Object name -eq 'native.jsonl').sha256
 [IO.File]::WriteAllText((Join-Path $out 'export-proof.json'),($proof|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
 [pscustomobject]$proof
}finally{$headers.Clear();$token=$null}
