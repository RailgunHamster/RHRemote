# Run through Invoke-RemoteScript with BaseDirectory, ArchiveName and ArchiveSha256.
$ErrorActionPreference='Stop'
if($ArchiveName -notmatch '^RHRemote-Sunshine-[a-zA-Z0-9-]+\.zip$' -or $ArchiveSha256 -notmatch '^[A-Fa-f0-9]{64}$'){throw 'Invalid immutable candidate metadata'}
$archive=Join-Path $BaseDirectory $ArchiveName
if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $ArchiveSha256){throw 'Server candidate archive hash mismatch'}
$stage=Join-Path $BaseDirectory ([IO.Path]::GetFileNameWithoutExtension($ArchiveName))
if(Test-Path -LiteralPath $stage){throw 'Immutable deployment stage already exists'}
Expand-Archive -LiteralPath $archive -DestinationPath $stage
$log=$stage+'-install.log'
$start=[Diagnostics.ProcessStartInfo]::new('powershell.exe')
$start.Arguments='-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+(Join-Path $stage 'install-server.ps1')+'" -SourceDirectory "'+$stage+'"'
$start.UseShellExecute=$false;$start.CreateNoWindow=$true
$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
$process=[Diagnostics.Process]::Start($start)
$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
$process.WaitForExit()
[IO.File]::WriteAllText($log,($stdout.Result+[Environment]::NewLine+$stderr.Result),[Text.UTF8Encoding]::new($false))
if($process.ExitCode -ne 0){Get-Content -LiteralPath $log -Tail 50;throw 'Candidate deployment failed; inspect the automatic rollback result'}
$text=[IO.File]::ReadAllText($log)
'B64:'+ [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
