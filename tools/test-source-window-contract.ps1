$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'source-window-contract.ps1')
$checks=0
function Assert($value,$message){if(-not$value){throw $message};$script:checks++}
function Reject($before,$after,$seconds){$rejected=$false;try{$null=Test-RHRemoteSourceWindow $before $after $seconds}catch{$rejected=$true};Assert $rejected 'Invalid source scope was accepted'}
$before=@{phase='playing';ended=$false;error=$null;elapsed_s=8.0}
$after=@{phase='playing';ended=$false;error=$null;elapsed_s=161.0}
$valid=Test-RHRemoteSourceWindow $before $after 150.85
Assert ($valid.measurementCovered-and$valid.controllerHealthy-and$valid.sourceSeconds-eq153-and-not$valid.physicalPaintMeasured) 'Actual source scope contract changed'
foreach($patch in @(@{phase='ended'},@{ended=$true},@{error='Owned source window changed'},@{ended=$null},@{phase=$null})){
 $changed=$after.Clone();foreach($k in $patch.Keys){$changed[$k]=$patch[$k]};Reject $before $changed 150.85
 $changed=$before.Clone();foreach($k in $patch.Keys){$changed[$k]=$patch[$k]};Reject $changed $after 150.85
}
$short=$after.Clone();$short.elapsed_s=106.0;Reject $before $short 150.85
$ended=$short.Clone();$ended.phase='ended';$ended.ended=$true;$ended.error='Viewport changed';Reject $before $ended 150.85
foreach($value in @($null,$true,'150',[double]::NaN,[double]::PositiveInfinity,0,-1)){Reject $before $after $value}
foreach($value in @($null,$true,'161',[double]::NaN,[double]::NegativeInfinity)){$changed=$after.Clone();$changed.elapsed_s=$value;Reject $before $changed 150.85}
$reverse=$after.Clone();$reverse.elapsed_s=7;Reject $before $reverse 1
$exact=Test-RHRemoteSourceWindow $before $after 153
Assert $exact.measurementCovered 'An exactly covered window was rejected'
@{checks=$checks;metadataOnly=$true;sourceLifecycleAndCoverage=$true;powerShell=$PSVersionTable.PSVersion.ToString()}|ConvertTo-Json -Compress
