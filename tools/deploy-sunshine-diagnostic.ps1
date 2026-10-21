# Inputs are injected by Invoke-RemoteScript. This replaces only the Sunshine executable.
$ErrorActionPreference='Stop'
if($DiagnosticMode-notin@('profile','optimized','restore')){throw 'Unknown diagnostic mode'}
if($DiagnosticRunId-notmatch'^\d+$'){throw 'Invalid diagnostic build ID'}
$exe='C:\Program Files\Sunshine\sunshine.exe'
$backup='C:\ProgramData\RHRemoteHost\sunshine-diagnostics-20261022'
$stateFile=Join-Path $backup 'deployment.json'
$original=Join-Path $backup 'original-sunshine.exe'
$service=@(Get-CimInstance Win32_Service -Filter "Name LIKE '%Sunshine%'"|Where-Object {$_.PathName-match'(?i)sunshinesvc\.exe'})
if($service.Count-ne1){throw 'Expected one Sunshine supervisor service'}
$currentHash=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash
$previous=if(Test-Path -LiteralPath $stateFile){Get-Content -LiteralPath $stateFile -Raw|ConvertFrom-Json}else{$null}
if($previous-and$currentHash-ne$previous.appliedHash){throw 'Sunshine binary changed outside this diagnostic deployment'}
if($DiagnosticMode-eq'restore'){
 if(-not$previous-or-not(Test-Path -LiteralPath $original)){throw 'Original binary backup unavailable'}
 $candidate=$original;$expected=[string]$previous.originalHash
}else{
 if($DiagnosticHash-notmatch'^[A-Fa-f0-9]{64}$'){throw 'Expected audited diagnostic SHA256'}
 $candidate="D:\RHRemoteTools\sunshine-diagnostics-$DiagnosticRunId\$DiagnosticMode\sunshine.exe";$expected=$DiagnosticHash
 if(-not(Test-Path -LiteralPath $candidate)){throw 'Diagnostic candidate is missing'}
 # Verify DLL loading and CLI behavior before stopping the active service.
 $psi=New-Object Diagnostics.ProcessStartInfo $candidate;$psi.Arguments='--version';$psi.WorkingDirectory=Split-Path -Parent $exe;$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
 $process=[Diagnostics.Process]::Start($psi);$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
 if(-not$process.WaitForExit(15000)){$process.Kill();throw 'Diagnostic version check did not finish'}
 if($process.ExitCode-ne0){throw ('Diagnostic executable could not load; exit '+$process.ExitCode)}
}
if((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash-ne$expected){throw 'Diagnostic candidate hash mismatch'}
if(-not(Test-Path -LiteralPath $backup)){New-Item -ItemType Directory -Path $backup|Out-Null}
if(-not(Test-Path -LiteralPath $original)){Copy-Item -LiteralPath $exe -Destination $original}
$originalHash=(Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash
$svc=Get-Service -Name $service[0].Name
Stop-Service -Name $svc.Name;$svc.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(25))
try{
 if(@(Get-Process sunshine -ErrorAction SilentlyContinue).Count){throw 'Sunshine child is still running; binary not replaced'}
 Copy-Item -LiteralPath $candidate -Destination $exe -Force
 Start-Service -Name $svc.Name
 $applied=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash
 $state=[ordered]@{mode=$DiagnosticMode;build=$DiagnosticRunId;at=[DateTimeOffset]::Now.ToString('o');originalHash=$originalHash;appliedHash=$applied;service=$svc.Name}
 [IO.File]::WriteAllText($stateFile,($state|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
 'B64:'+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($state|ConvertTo-Json -Compress)))
}catch{
 # A failed replacement always returns to the saved original executable.
 Stop-Service -Name $svc.Name -ErrorAction SilentlyContinue
 try{if((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash-ne$originalHash){Copy-Item -LiteralPath $original -Destination $exe -Force}}
 finally{Start-Service -Name $svc.Name}
 $rolledBack=[ordered]@{mode='rollback';build=$DiagnosticRunId;at=[DateTimeOffset]::Now.ToString('o');originalHash=$originalHash;appliedHash=$originalHash;service=$svc.Name}
 [IO.File]::WriteAllText($stateFile,($rolledBack|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
 throw
}
