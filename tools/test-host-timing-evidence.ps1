# Load only the pure log-selection function; no service access or remote host.
$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'diagnose-host-startup.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Collector parsing failed'}
$fn=@($ast.FindAll({param($node) $node-is[Management.Automation.Language.FunctionDefinitionAst]-and $node.Name-in@('Get-RHHostTimingEvidence','Test-RHHostTimingWrapperOwnership')},$false))
if($fn.Count-ne2){throw 'Exactly two collector functions required'}
foreach($definition in $fn){. ([scriptblock]::Create($definition.Extent.Text))}
function Assert([bool]$Condition,[string]$Message){if(-not$Condition){throw $Message}}
$root=Join-Path ([IO.Path]::GetTempPath()) ('rh-host-timing-fixture-'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $root
$legacy=Join-Path $root 'engine.log';$wrapper=Join-Path $root 'wrapper.log'
try {
 foreach($name in @('SunshineService','RHRemoteHost')){
  Assert (Test-RHHostTimingWrapperOwnership @([pscustomobject]@{Name=$name;PathName='"C:\Program Files\RHRemote\RHRemoteHost.exe" --sunshine-service --quiet'})) 'Unified host can replace either supported service name'
 }
 foreach($service in @(
  @{Name='Other';PathName='C:\RHRemoteHost.exe --sunshine-service'},
  @{Name='SunshineService';PathName='C:\sunshinesvc.exe --sunshine-service'},
  @{Name='SunshineService';PathName='C:\RHRemoteHost.exe --console'},
  @{Name='SunshineService';PathName='C:\OtherRHRemoteHost.exe --sunshine-service'},
  @{Name='SunshineService';PathName='C:\RHRemoteHost.exe --sunshine-service-other'}
 )){Assert (-not(Test-RHHostTimingWrapperOwnership @([pscustomobject]$service))) 'Only supported wrapper service executables qualify'}
 [IO.File]::WriteAllLines($legacy,@('[now] Info: RHREMOTE_PHASE stage=current duration_us=3'))
 [IO.File]::WriteAllLines($wrapper,@('[before] Info: RHREMOTE_PHASE stage=old duration_us=5','[after] Info: RHREMOTE_PHASE stage=new duration_us=7'))
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog $wrapper -WrapperOwned $true
 Assert ($r.retainedAcrossEngineRestarts-and $r.path-eq$wrapper-and $r.lines.Count-eq2-and $r.lines[0]-match'stage=old') 'Owned service must retain pre-restart timing'
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog $wrapper
 Assert (-not$r.retainedAcrossEngineRestarts-and $r.path-eq$legacy-and $r.lines.Count-eq1-and $r.lines[0]-match'stage=current') 'Unowned wrapper must not override standalone engine evidence'
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog (Join-Path $root 'absent') -WrapperOwned $true
 Assert (-not$r.retainedAcrossEngineRestarts-and $r.path-eq$legacy) 'Missing wrapper must fall back to engine log'
 $threw=$false
 try {$null=Get-RHHostTimingEvidence -LegacyLog (Join-Path $root 'absent1') -WrapperLog (Join-Path $root 'absent2') -WrapperOwned $true}catch{$threw=$true}
 Assert $threw 'Missing evidence must fail instead of claiming an empty valid log'
 $lines=@('Info: RHREMOTE_PHASE stage=too-old duration_us=1')
 foreach($secret in @('password','private_key','certificate','token','credential','pin=','rikey','https://','http://')){$lines+='Info: encoder '+$secret+' fixture-secret'}
 $lines+='Debug: unrelated fixture';$lines+='Info: RHREMOTE_GPU_PRIORITY requested=5 actual=5';$lines+='Info: RHREMOTE_PHASE stage=last duration_us=11'
 [IO.File]::WriteAllLines($wrapper,$lines)
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog $wrapper -WrapperOwned $true -Tail 2
 Assert ($r.lines.Count-eq2-and $r.tailLimit-eq2-and $r.lines[0]-match'GPU_PRIORITY'-and $r.lines[1]-match'stage=last') 'Evidence bound and secret filtering must preserve only recent timing evidence'
 foreach($line in $r.lines){Assert ($line-is[string]-and $line.PSObject.Properties.Name-notcontains'PSPath') 'Evidence must be plain strings without expanded file metadata'}
 $roundtrip=$r|ConvertTo-Json -Depth 4|ConvertFrom-Json
 Assert ($roundtrip.lines.Count-eq2-and $roundtrip.lines[0]-is[string]) 'JSON transport must retain string lines'
 $noise=@('Info: Creating encoder [h264_qsv]')+@('Debug: unrelated D3D allocation')*9000+@('Info: RHREMOTE_PHASE stage=actual-open duration_us=123')
 [IO.File]::WriteAllLines($wrapper,$noise)
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog $wrapper -WrapperOwned $true -Tail 4
 Assert ($r.lines.Count-eq2-and$r.lines[0]-match'Creating encoder'-and$r.lines[1]-match'actual-open') 'Unrelated verbose traffic must not evict the actual encoder anchor'
 $previous=Join-Path $root 'sunshine-service.previous.log'
 [IO.File]::WriteAllLines($previous,@('Info: RHREMOTE_PHASE stage=rotated-session duration_us=19','Info: encoder password fixture-secret'))
 [IO.File]::WriteAllLines($wrapper,@('Info: RHREMOTE_PHASE stage=restored-service duration_us=23'))
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog $wrapper -WrapperOwned $true
 Assert ($r.previousWrapperIncluded-and$r.paths.Count-eq2-and$r.paths[0]-eq$previous-and$r.paths[1]-eq$wrapper-and$r.lines.Count-eq2-and$r.lines[0]-match'rotated-session'-and$r.lines[1]-match'restored-service') 'Restoration rotation must retain measured session before current lifetime, with secrets excluded'
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog $wrapper -WrapperOwned $true -Tail 1
 Assert ($r.lines.Count-eq1-and$r.lines[0]-match'restored-service') 'Multiple log sources must share one output bound'
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog $wrapper
 Assert (-not$r.previousWrapperIncluded-and$r.paths.Count-eq1-and$r.paths[0]-eq$legacy) 'An unowned previous log cannot be included'
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog (Join-Path $root 'missing-current.log') -WrapperOwned $true
 Assert ($r.retainedAcrossEngineRestarts-and$r.previousWrapperIncluded-and$r.path-eq$previous-and$r.lines.Count-eq1) 'A rotation gap must retain the owned previous log'
 $r=Get-RHHostTimingEvidence -LegacyLog $legacy -WrapperLog $wrapper -PreviousWrapperLog $wrapper -WrapperOwned $true
 Assert ($r.paths.Count-eq1-and$r.lines.Count-eq1) 'Identical explicit source paths must not duplicate evidence'
 'PASS: retained and rotated logs, ownership, fallback, missing evidence, shared bound, privacy filter, plain strings, JSON transport'
} finally {
 # Only this freshly created fixture directory, never a caller-supplied path.
 $resolved=[IO.Path]::GetFullPath($root)
 $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
 if(-not$resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase)-or (Split-Path -Leaf $resolved)-notmatch'^rh-host-timing-fixture-[a-f0-9]{32}$'){throw 'Unsafe fixture cleanup target'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
