<#
.SYNOPSIS
  Bounded encoder or software-thread comparison on an idle RHRemote-Sunshine service.
.DESCRIPTION
  Saves the exact config bytes, changes only the encoder and optional software-thread value, and registers
  automatic restoration before stopping the service. Restoration never overwrites a
  concurrently changed config or interrupts active media. Run elevated on the host.
  Config contents and pairing credentials are never included in the result.
.PARAMETER MinThreads
  Zero preserves the original thread setting. Values1..64 are an optional bounded
  software-only comparison; the full original bytes are restored, including removal
  of a key appended for the trial. This controls swscale workers and software slices.
#>
param(
    [ValidateSet('start','restore','status')][string]$Action='status',
    [ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$RunId,
    [ValidateSet('quicksync','mediafoundation','software')][string]$Encoder='quicksync',
    [ValidateRange(0,64)][int]$MinThreads=0,
    [ValidateRange(120,600)][int]$DurationSeconds=480,
    [ValidateRange(10,300)][int]$StartupWaitSeconds=25,
    [string]$ExpectedUuid,
    [switch]$LibraryOnly
)
$ErrorActionPreference='Stop'

function Get-RHBytesHash([byte[]]$Bytes) {
    $hash=[Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($hash.ComputeHash($Bytes))).Replace('-','') }
    finally { $hash.Dispose() }
}

function Assert-RHEncoderWindowBudget([int]$Duration,[int]$StartupWait) {
    if($Duration -lt 120 -or $Duration -gt 600 -or $StartupWait -lt 10 -or $StartupWait -gt 300 -or $Duration-$StartupWait -lt 60) { throw 'Startup observation must leave at least60seconds for recovery within the bounded comparison window' }
}

function New-RHEncoderBytes([byte[]]$Bytes,[string]$Value) {
    if($Value -notin @('software','quicksync','mediafoundation')) { throw 'Unsupported comparison encoder' }
    if($Bytes.Length -ge 2 -and (($Bytes[0] -eq 255 -and $Bytes[1] -eq 254) -or ($Bytes[0] -eq 254 -and $Bytes[1] -eq 255))) { throw 'UTF-16 config is unsupported; original bytes retained' }
    # Latin-1 is a reversible byte mapping: non-ASCII comments and BOM are untouched.
    $encoding=[Text.Encoding]::GetEncoding(28591)
    $offset=0
    if($Bytes.Length -ge 3 -and $Bytes[0] -eq 239 -and $Bytes[1] -eq 187 -and $Bytes[2] -eq 191) { $offset=3 }
    $text=$encoding.GetString($Bytes,$offset,$Bytes.Length-$offset)
    $matches=[regex]::Matches($text,'(?m)^[ \t]*encoder[ \t]*=[ \t]*(?<value>[^#\r\n]*?)(?<suffix>[ \t]*(?:#[^\r\n]*)?)(?=\r?$)')
    if($matches.Count -ne 1 -or $matches[0].Groups['value'].Value.Trim() -notin @('software','quicksync','mediafoundation')) { throw 'Expected exactly one supported encoder line' }
    $group=$matches[0].Groups['value']
    $replacement=$text.Substring(0,$group.Index)+$Value+$text.Substring($group.Index+$group.Length)
    [byte[]]$prefix=if($offset) { $Bytes[0..2] } else { @() }
    ,([byte[]]($prefix+$encoding.GetBytes($replacement)))
}

function New-RHSoftwareThreadBytes([byte[]]$Bytes,[int]$Value) {
    if($Value -lt 1 -or $Value -gt 64) { throw 'Software comparison thread count must be1..64' }
    if($Bytes.Length -ge 2 -and (($Bytes[0] -eq 255 -and $Bytes[1] -eq 254) -or ($Bytes[0] -eq 254 -and $Bytes[1] -eq 255))) { throw 'UTF-16 config is unsupported; original bytes retained' }
    $encoding=[Text.Encoding]::GetEncoding(28591);$offset=0
    if($Bytes.Length -ge 3 -and $Bytes[0] -eq 239 -and $Bytes[1] -eq 187 -and $Bytes[2] -eq 191) { $offset=3 }
    $text=$encoding.GetString($Bytes,$offset,$Bytes.Length-$offset)
    $matches=[regex]::Matches($text,'(?m)^[ \t]*min_threads[ \t]*=[ \t]*(?<value>[^#\r\n]*?)(?<suffix>[ \t]*(?:#[^\r\n]*)?)(?=\r?$)')
    if($matches.Count -gt 1) { throw 'Ambiguous software thread key' }
    if($matches.Count -eq 1) {
        $group=$matches[0].Groups['value'];$current=0
        if(-not[int]::TryParse($group.Value.Trim(),[ref]$current) -or $current -lt 1 -or $current -gt 64) { throw 'Existing software thread value is unsupported' }
        $replacement=$text.Substring(0,$group.Index)+$Value.ToString([Globalization.CultureInfo]::InvariantCulture)+$text.Substring($group.Index+$group.Length)
    } else {
        $newline=if($text.Contains("`r`n")) { "`r`n" } else { "`n" }
        $separator=if($text.Length -and -not $text.EndsWith("`n")) { $newline } else { '' }
        $replacement=$text+$separator+'min_threads = '+$Value.ToString([Globalization.CultureInfo]::InvariantCulture)+$newline
    }
    [byte[]]$prefix=if($offset) { $Bytes[0..2] } else { @() }
    ,([byte[]]($prefix+$encoding.GetBytes($replacement)))
}

function Set-RHConfigBytes([string]$Path,[byte[]]$Bytes,[string]$ExpectedHash) {
    if((Get-RHBytesHash ([IO.File]::ReadAllBytes($Path))) -cne $ExpectedHash) { throw 'Concurrent config change; refusing to overwrite' }
    $acl=Get-Acl -LiteralPath $Path
    $originalSddl=$acl.Sddl
    $temporary=$Path+'.rh-encoder-'+[guid]::NewGuid().ToString('N')
    try {
        [IO.File]::WriteAllBytes($temporary,$Bytes)
        # ReplaceFile preserves the DACL but can take the primary group from its
        # replacement. Give the temporary file the complete destination security
        # descriptor first (same directory, so inherited ACEs are unchanged).
        Set-Acl -LiteralPath $temporary -AclObject $acl
        if((Get-RHBytesHash ([IO.File]::ReadAllBytes($Path))) -cne $ExpectedHash) { throw 'Concurrent config change before replacement' }
        [IO.File]::Replace($temporary,$Path,[NullString]::Value)
        # ReplaceFile can also merge inherited descriptor metadata differently
        # on another volume/OS. Reapply the recorded descriptor to the resulting
        # file rather than assuming the temporary-file copy covered every case.
        if((Get-Acl -LiteralPath $Path).Sddl -cne $originalSddl) {
            $restoreAcl=[Security.AccessControl.FileSecurity]::new()
            $restoreAcl.SetSecurityDescriptorSddlForm($originalSddl)
            Set-Acl -LiteralPath $Path -AclObject $restoreAcl
        }
        $actualSddl=(Get-Acl -LiteralPath $Path).Sddl
        if((Get-RHBytesHash ([IO.File]::ReadAllBytes($Path))) -cne (Get-RHBytesHash $Bytes) -or $actualSddl -cne $originalSddl) { throw ('Config bytes or ACL did not survive replacement; expected descriptor='+$originalSddl+'; actual descriptor='+$actualSddl) }
    } finally { if(Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

if($LibraryOnly) { return }
if($MinThreads -gt 0 -and $Encoder -ne 'software') { throw 'Thread comparisons require the software encoder' }
if(-not $RunId) { throw 'A unique run ID is required' }
$root=Join-Path $env:ProgramData 'RHRemote-Sunshine\encoder-comparisons'
$work=Join-Path $root $RunId
$statePath=Join-Path $work 'lease.json'
$config=Join-Path $env:ProgramFiles 'Sunshine\config\sunshine.conf'
$engine=Join-Path $env:ProgramFiles 'Sunshine\sunshine.exe'
$taskName='RHRemote-Encoder-Restore-'+$RunId

function Save-Lease($Lease) {
    $temporary=$statePath+'.tmp'
    [IO.File]::WriteAllText($temporary,($Lease|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $statePath -Force
}

function Get-OwnedService {
    $service=Get-CimInstance Win32_Service -Filter "Name='SunshineService'"
    if(-not $service -or $service.PathName -notlike '*RHRemoteHost.exe*--sunshine-service*') { throw 'Unexpected service owner' }
    $service
}

function Test-OwnedIdle($InitializingLease=$null) {
    $service=Get-OwnedService
    if($service.State -eq 'Stopped') { return $true }
    if($service.State -ne 'Running') { return $false }
    $children=@(Get-CimInstance Win32_Process -Filter "Name='sunshine.exe'" | Where-Object { $_.ParentProcessId -eq $service.ProcessId -and $_.ExecutablePath -eq $engine })
    if($children.Count -ne 1) { return $false }
    if(-not ('RhEncoderThreadInventory' -as [type])) {
        Add-Type @'
using System;using System.Collections.Generic;using System.Diagnostics;using System.Runtime.InteropServices;
public static class RhEncoderThreadInventory {
 [DllImport("kernel32.dll",SetLastError=true)]static extern IntPtr OpenThread(uint access,bool inherit,uint id);
 [DllImport("kernel32.dll")]static extern int GetThreadDescription(IntPtr handle,out IntPtr description);
 [DllImport("kernel32.dll")]static extern IntPtr LocalFree(IntPtr pointer);
 [DllImport("kernel32.dll")]static extern bool CloseHandle(IntPtr handle);
 public static string[] Names(int pid){var names=new List<string>();using(var p=Process.GetProcessById(pid)){foreach(ProcessThread thread in p.Threads){var h=OpenThread(0x0800,false,(uint)thread.Id);if(h==IntPtr.Zero)continue;try{IntPtr text;if(GetThreadDescription(h,out text)>=0){try{var value=Marshal.PtrToStringUni(text);if(!String.IsNullOrEmpty(value))names.Add(value);}finally{LocalFree(text);}}}finally{CloseHandle(h);}}}return names.ToArray();}
}
'@
    }
    $names=@([RhEncoderThreadInventory]::Names([int]$children[0].ProcessId))
    if(@($names|Where-Object { $_ -match '^(session::(video|audio)|video::|audio::|stream::(video|audio)Broadcast)' }).Count -ne 0 -or @(Get-NetTCPConnection -LocalPort 48010 -State Established -ErrorAction SilentlyContinue).Count -ne 0) { return $false }
    if($names -contains 'rtsp::handler') { return $true }
    # The experiment's own encoder probe runs before RTSP/HTTP is listening.
    # A missing handler alone is never proof of idleness: require this exact
    # newly started service/child, a functioning name query and no admission port.
    if($InitializingLease -and $InitializingLease.trialServicePid -eq $service.ProcessId -and $InitializingLease.trialEnginePid -eq $children[0].ProcessId -and $names -contains 'session_monitor' -and $names -contains 'TaskPool::worker') {
        $created=(Get-Process -Id $service.ProcessId).StartTime.ToUniversalTime().ToString('o')
        if($created -ceq $InitializingLease.trialServiceCreatedUtc -and @(Get-NetTCPConnection -LocalPort 47989,48010 -ErrorAction SilentlyContinue).Count -eq 0) { return $true }
    }
    $false
}

function Wait-OwnedReady([string]$Uuid,[int]$TimeoutSeconds=25) {
    (Get-Service SunshineService).WaitForStatus('Running',[TimeSpan]::FromSeconds(25))
    $deadline=(Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $info=Invoke-RestMethod 'http://127.0.0.1:47989/serverinfo?uniqueid=0123456789ABCDEF' -TimeoutSec 3
            if([string]$info.root.uniqueid -ne $Uuid) { throw 'Unexpected UUID after service restart' }
            return
        } catch { $problem=$_.Exception.Message }
        Start-Sleep -Milliseconds 300
    } while((Get-Date) -lt $deadline)
    throw ('Service did not become ready: '+$problem)
}

function Restore-Lease($Lease) {
    $service=Get-OwnedService
    if($service.PathName -cne $Lease.servicePath -or (Get-FileHash -LiteralPath $engine -Algorithm SHA256).Hash -cne $Lease.engineSha256) { throw 'Service identity changed; restoration needs review' }
    [byte[]]$original=[IO.File]::ReadAllBytes((Join-Path $work 'original.conf'))
    if((Get-RHBytesHash $original) -cne $Lease.originalHash) { throw 'Original config backup hash differs' }
    $current=(Get-FileHash -LiteralPath $config -Algorithm SHA256).Hash
    if($current -ceq $Lease.trialHash) {
        Set-RHConfigBytes $config $original $Lease.trialHash
        $Lease.phase='original_bytes_restored';Save-Lease $Lease
    } elseif($current -cne $Lease.originalHash) { throw 'Concurrent config change; refusing automatic restoration' }
    if((Get-Acl -LiteralPath $config).Sddl -cne $Lease.originalSddl) { throw 'Config security descriptor differs; restoration needs review' }
    if($Lease.restartNeeded -or $service.State -eq 'Stopped') {
        if(-not (Test-OwnedIdle $Lease)) {
            $Lease.phase='original_bytes_restored_waiting_for_idle';Save-Lease $Lease
            throw 'Original bytes restored; active media postpones service restart'
        }
        $service=Get-OwnedService
        if($service.State -eq 'Running') { Stop-Service SunshineService; (Get-Service SunshineService).WaitForStatus('Stopped',[TimeSpan]::FromSeconds(25)) }
        Start-Service SunshineService;Wait-OwnedReady $Lease.uuid
    }
    $Lease.restartNeeded=$false;$Lease.phase='restored';$Lease.restoredUtc=[DateTime]::UtcNow.ToString('o');Save-Lease $Lease
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    $Lease
}

switch($Action) {
    'start' {
        Assert-RHEncoderWindowBudget $DurationSeconds $StartupWaitSeconds
        if(Test-Path -LiteralPath $work) { throw 'Immutable comparison ID already exists; inspect status instead' }
        if(-not $ExpectedUuid -or -not (Test-OwnedIdle)) { throw 'Expected UUID and idle owned service are required' }
        $service=Get-OwnedService
        if($service.State -ne 'Running') { throw 'The comparison requires a running service' }
        $info=Invoke-RestMethod 'http://127.0.0.1:47989/serverinfo?uniqueid=0123456789ABCDEF' -TimeoutSec 5
        if([string]$info.root.uniqueid -ne $ExpectedUuid) { throw 'Unexpected UUID' }
        [byte[]]$original=[IO.File]::ReadAllBytes($config)
        [byte[]]$trial=New-RHEncoderBytes $original $Encoder
        if($MinThreads -gt 0) { $trial=New-RHSoftwareThreadBytes $trial $MinThreads }
        $originalHash=Get-RHBytesHash $original;$trialHash=Get-RHBytesHash $trial
        if($originalHash -ceq $trialHash) { throw 'Encoder already selected; no comparison change required' }
        New-Item -ItemType Directory -Path $work -Force|Out-Null
        $acl=New-Object Security.AccessControl.DirectorySecurity
        $acl.SetAccessRuleProtection($true,$false)
        foreach($sid in @('S-1-5-18','S-1-5-32-544')) { $rule=New-Object Security.AccessControl.FileSystemAccessRule ([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow');$acl.AddAccessRule($rule) }
        Set-Acl -LiteralPath $work -AclObject $acl
        [IO.File]::WriteAllBytes((Join-Path $work 'original.conf'),$original)
        Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $work 'control.ps1')
        $deadline=(Get-Date).AddSeconds($DurationSeconds)
        $lease=[pscustomobject]@{runId=$RunId;uuid=$ExpectedUuid;encoder=$Encoder;minThreads=$MinThreads;startupWaitSeconds=$StartupWaitSeconds;originalHash=$originalHash;trialHash=$trialHash;originalSddl=(Get-Acl -LiteralPath $config).Sddl;engineSha256=(Get-FileHash -LiteralPath $engine -Algorithm SHA256).Hash;servicePath=$service.PathName;deadlineUtc=$deadline.ToUniversalTime().ToString('o');restartNeeded=$false;phase='prepared';startedUtc=[DateTime]::UtcNow.ToString('o');restoredUtc=$null;trialServicePid=0;trialEnginePid=0;trialServiceCreatedUtc=$null}
        Save-Lease $lease
        $arguments='-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+(Join-Path $work 'control.ps1')+'" -Action restore -RunId '+$RunId
        $taskAction=New-ScheduledTaskAction -Execute powershell.exe -Argument $arguments
        $principal=New-ScheduledTaskPrincipal -UserId SYSTEM -LogonType ServiceAccount -RunLevel Highest
        $settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 2) -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1)
        Register-ScheduledTask -TaskName $taskName -Action $taskAction -Principal $principal -Trigger (New-ScheduledTaskTrigger -Once -At $deadline) -Settings $settings|Out-Null
        try {
            # Fresh inventory immediately before the stop; the initial snapshot cannot authorize it.
            if(-not (Test-OwnedIdle) -or (Get-OwnedService).ProcessId -ne $service.ProcessId) { throw 'Service became busy or changed during preparation' }
            Stop-Service SunshineService;(Get-Service SunshineService).WaitForStatus('Stopped',[TimeSpan]::FromSeconds(25))
            $lease.restartNeeded=$true;Save-Lease $lease
            Set-RHConfigBytes $config $trial $originalHash
            Start-Service SunshineService
            $trialService=Get-OwnedService
            $lease.trialServicePid=[int]$trialService.ProcessId
            $lease.trialServiceCreatedUtc=(Get-Process -Id $trialService.ProcessId).StartTime.ToUniversalTime().ToString('o')
            $children=@(Get-CimInstance Win32_Process -Filter "Name='sunshine.exe'"|Where-Object { $_.ParentProcessId -eq $trialService.ProcessId -and $_.ExecutablePath -eq $engine })
            if($children.Count -eq 1) { $lease.trialEnginePid=[int]$children[0].ProcessId }
            $lease.phase='initializing';Save-Lease $lease
            Wait-OwnedReady $ExpectedUuid $StartupWaitSeconds
            $lease.phase='trial_active';Save-Lease $lease
        } catch {
            $errorText=$_.Exception.Message
            try { $null=Restore-Lease $lease } catch { throw ($errorText+'; automatic restore task retained: '+$_.Exception.Message) }
            throw $errorText
        }
    }
    'restore' { $lease=Get-Content -LiteralPath $statePath -Raw|ConvertFrom-Json;$lease=Restore-Lease $lease }
    'status' { $lease=Get-Content -LiteralPath $statePath -Raw|ConvertFrom-Json }
}
$currentService=Get-OwnedService
$result=[ordered]@{runId=$RunId;phase=$lease.phase;encoder=$lease.encoder;minThreads=$lease.minThreads;deadlineUtc=$lease.deadlineUtc;restoredUtc=$lease.restoredUtc;originalHash=$lease.originalHash;trialHash=$lease.trialHash;currentHash=(Get-FileHash -LiteralPath $config -Algorithm SHA256).Hash;restoreTaskPresent=[bool](Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue);serviceState=$currentService.State;servicePid=$currentService.ProcessId;utc=[DateTime]::UtcNow.ToString('o')}
$result|ConvertTo-Json -Compress
