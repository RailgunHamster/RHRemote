<#
.SYNOPSIS
  Run a local PowerShell script on a remote Windows host over SSH (Windows PowerShell 5.1 on the target).

.DESCRIPTION
  The script is copied with scp to -RemoteDir\rh-<guid>.ps1 and run with "powershell -File"; its
  first statement deletes the file. Nothing is sent over the SSH session's stdin (ssh -n): Windows OpenSSH
  intermittently lost stdin data or its EOF, which left the remote side waiting forever.
  -Parameters are injected as string variables ahead of the script body; -Include scripts are
  concatenated before it (the remote side cannot dot-source local helper files).
  If the remote output contains a line starting with "B64:", its payload is decoded as UTF-8
  (avoids console code-page mangling of non-ASCII text) and written to -OutFile or returned.
  -TimeoutSec (0 = none): on overrun the local ssh is killed, the remote powershell whose command line
  holds this run's guid is stopped, and the call throws.
#>
param(
    [Parameter(Mandatory)] [string] $HostName,
    [Parameter(Mandatory)] [string] $ScriptPath,
    [hashtable] $Parameters = @{},
    [string[]] $Include = @(),
    [string] $OutFile,
    [int] $ConnectTimeout = 10,
    [int] $TimeoutSec = 0,
    # winserver's sshd chroots sftp to D:, so scp sees D:\RHRemoteTools\scripts as /RHRemoteTools/scripts.
    [string] $RemoteDir = 'D:\RHRemoteTools\scripts',
    [string] $SftpDir = '/RHRemoteTools/scripts'
)

$ErrorActionPreference = 'Stop'
$remoteDir = $RemoteDir

function Invoke-Ssh([string] $Exe, [string] $Arguments, [int] $Timeout) {
    $psi = New-Object Diagnostics.ProcessStartInfo $Exe
    $psi.Arguments = $Arguments
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [Text.Encoding]::UTF8; $psi.StandardErrorEncoding = [Text.Encoding]::UTF8
    $proc = [Diagnostics.Process]::Start($psi)
    $proc.StandardInput.Close()
    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    $finished = if ($Timeout -gt 0) { $proc.WaitForExit($Timeout * 1000) } else { $proc.WaitForExit(); $true }
    if (-not $finished) { $proc.Kill(); $null = $proc.WaitForExit(5000); return [pscustomobject]@{ timed_out = $true; exit = $null; out = ''; err = '' } }
    $proc.WaitForExit()
    [pscustomobject]@{ timed_out = $false; exit = $proc.ExitCode; out = $stdoutTask.Result; err = $stderrTask.Result }
}

$body = (@($Include | ForEach-Object { Get-Content -Raw -Encoding UTF8 $_ }) + (Get-Content -Raw -Encoding UTF8 $ScriptPath)) -join "`n"
$prelude = ($Parameters.GetEnumerator() | ForEach-Object {
    $v = "$($_.Value)" -replace "'", "''"
    "`$$($_.Key) = '$v'"
}) -join "`n"
$full = "try { Remove-Item -LiteralPath `$PSCommandPath -Force } catch { }`n" +
        "[Console]::OutputEncoding = [Text.Encoding]::UTF8`n`$ProgressPreference = 'SilentlyContinue'`n$prelude`n$body"

$id = [guid]::NewGuid().ToString('N')
$remotePath = "$remoteDir\rh-$id.ps1"
$local = Join-Path ([IO.Path]::GetTempPath()) "rh-$id.ps1"
$sshOpts = "-o BatchMode=yes -o ConnectTimeout=$ConnectTimeout"
[IO.File]::WriteAllText($local, $full, (New-Object Text.UTF8Encoding $true))
try {
    $scpArgs = "-q $sshOpts `"$local`" ${HostName}:$SftpDir/rh-$id.ps1"
    $copy = Invoke-Ssh 'scp' $scpArgs 60
    if ($copy.timed_out -or $copy.exit -ne 0) {
        # EncodedCommand works whether the host's sshd default shell is cmd (winserver) or PowerShell (claw).
        $mkEnc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("New-Item -ItemType Directory -Force -Path '$remoteDir' | Out-Null"))
        $null = Invoke-Ssh 'ssh' "-n $sshOpts $HostName `"powershell -NoProfile -NonInteractive -EncodedCommand $mkEnc`"" 30
        $copy = Invoke-Ssh 'scp' $scpArgs 60
        if ($copy.timed_out -or $copy.exit -ne 0) { throw "scp to $HostName failed (exit $($copy.exit), timed_out=$($copy.timed_out)): $($copy.err.Trim())" }
    }
} finally { Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue }

$run = Invoke-Ssh 'ssh' "-n $sshOpts $HostName `"powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $remotePath`"" $TimeoutSec
if ($run.timed_out) {
    $stop = "Get-CimInstance Win32_Process -Filter `"Name='powershell.exe'`" | Where-Object { `$_.CommandLine -like '*rh-$id.ps1*' } | " +
            "ForEach-Object { Stop-Process -Id `$_.ProcessId -Force; 'stopped ' + `$_.ProcessId }; Remove-Item -LiteralPath '$remotePath' -Force -ErrorAction SilentlyContinue"
    $stopEnc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($stop))
    $cleanup = Invoke-Ssh 'ssh' "-n $sshOpts $HostName `"powershell -NoProfile -NonInteractive -EncodedCommand $stopEnc`"" 30
    throw "ssh $HostName timed out after $TimeoutSec s running $ScriptPath (remote cleanup: $("$($cleanup.out)".Trim()))"
}
$raw = @(($run.out + $run.err) -split "`r?`n" | Where-Object { $_ -ne '' })

$payloadLine = $raw | Where-Object { "$_" -like 'B64:*' } | Select-Object -Last 1
$other = $raw | Where-Object { "$_" -notlike 'B64:*' }
if ($other) { $other | ForEach-Object { Write-Host "$_" } }

if ($payloadLine) {
    $text = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String("$payloadLine".Substring(4)))
    if ($OutFile) {
        $dir = Split-Path -Parent $OutFile
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
        [IO.File]::WriteAllText($OutFile, $text, (New-Object Text.UTF8Encoding($false)))
        Write-Host "wrote $OutFile ($($text.Length) chars)"
    } else {
        $text
    }
}

if ($run.exit -ne 0) { throw "ssh $HostName exited with $($run.exit)" }
