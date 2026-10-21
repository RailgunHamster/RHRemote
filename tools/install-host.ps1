param([string]$SourceDirectory=(Join-Path (Split-Path $PSScriptRoot -Parent) 'dist/host'),[string]$SunshineConfig='C:\Program Files\Sunshine\config',[switch]$EnableSecureAttention)
$ErrorActionPreference='Stop'
$admin=([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if(-not $admin){throw 'Run the RHRemoteHost installer in an administrator PowerShell.'}
foreach($name in @('RHRemoteHost.exe','scripts/display.ps1','scripts/displayconfig.ps1','scripts/amyuni-vdd.ps1','scripts/tablet-mode.ps1')){if(-not(Test-Path -LiteralPath (Join-Path $SourceDirectory $name))){throw ('Missing installer file: '+$name)}}
if(-not(Test-Path -LiteralPath (Join-Path $SunshineConfig 'credentials/cacert.pem'))){throw 'Sunshine certificate not found.'}
$install=Join-Path $env:ProgramFiles 'RHRemoteHost';$data=Join-Path $env:ProgramData 'RHRemoteHost'
New-Item -ItemType Directory -Force -Path $install,$data|Out-Null
# Only the service and administrators can replace executable helpers, pairing-backed service state, or IPC files.
foreach($path in @($install,$data)){
    $acl=New-Object Security.AccessControl.DirectorySecurity;$acl.SetAccessRuleProtection($true,$false)
    foreach($sidText in @('S-1-5-18','S-1-5-32-544')){$sid=New-Object Security.Principal.SecurityIdentifier $sidText;$rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow');$acl.AddAccessRule($rule)}
    Set-Acl -LiteralPath $path -AclObject $acl
}
[IO.File]::WriteAllText((Join-Path $data 'host-settings.json'),(@{sunshineConfig=$SunshineConfig}|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
if(-not(Test-Path -LiteralPath (Join-Path $data 'owned-monitor.json'))){
    # Older tablet setup used the tools drive, which is not always the system drive.
    $legacyFiles=@(@((Join-Path $env:SystemDrive 'RHRemoteTools\tablet-display\owned-monitor.json'),(Join-Path ([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($SourceDirectory))) 'RHRemoteTools\tablet-display\owned-monitor.json'))|Select-Object -Unique|Where-Object {Test-Path -LiteralPath $_})
    $legacyIds=@($legacyFiles|ForEach-Object {$owned=[IO.File]::ReadAllText($_)|ConvertFrom-Json;if($owned.device_id -notmatch '^DISPLAY\\'){throw 'Unexpected legacy virtual monitor ownership.'};[string]$owned.device_id}|Select-Object -Unique)
    if($legacyIds.Count -gt 1){throw 'Conflicting legacy virtual monitor ownership; no service was stopped.'}
    if($legacyIds.Count -eq 1){[IO.File]::WriteAllText((Join-Path $data 'owned-monitor.json'),(@{device_id=$legacyIds[0]}|ConvertTo-Json -Compress))}
}
$service=Get-Service RHRemoteHost -ErrorAction SilentlyContinue
if($service){Stop-Service RHRemoteHost;$service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(20))}
Copy-Item -LiteralPath (Join-Path $SourceDirectory 'RHRemoteHost.exe') -Destination $install -Force
New-Item -ItemType Directory -Force -Path (Join-Path $install 'scripts')|Out-Null
Get-ChildItem -LiteralPath (Join-Path $SourceDirectory 'scripts') -File|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $install 'scripts') -Force}
$policyPath='HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System'
if($EnableSecureAttention){
    $policy=Get-ItemProperty -LiteralPath $policyPath -Name SoftwareSASGeneration -ErrorAction SilentlyContinue
    $backup=Join-Path $data 'sas-policy-before.json'
    if(-not(Test-Path -LiteralPath $backup)){[IO.File]::WriteAllText($backup,(@{exists=($null -ne $policy);value=if($policy){$policy.SoftwareSASGeneration}else{$null}}|ConvertTo-Json -Compress))}
    # Preserve an existing services-and-accessibility policy; enable only services otherwise.
    $appliedPolicy=if($policy -and $policy.SoftwareSASGeneration -in @(2,3)){3}else{1}
    if(-not $policy -or $policy.SoftwareSASGeneration -ne $appliedPolicy){New-ItemProperty -LiteralPath $policyPath -Name SoftwareSASGeneration -PropertyType DWord -Value $appliedPolicy -Force|Out-Null}
    $savedPolicy=[IO.File]::ReadAllText($backup)|ConvertFrom-Json;$savedPolicy|Add-Member NoteProperty appliedValue $appliedPolicy -Force;[IO.File]::WriteAllText($backup,($savedPolicy|ConvertTo-Json -Compress))
}
$binary='"'+(Join-Path $install 'RHRemoteHost.exe')+'" --service --sunshine-config "'+$SunshineConfig+'"'
if(-not $service){New-Service -Name RHRemoteHost -DisplayName 'RHRemote Host' -BinaryPathName $binary -StartupType Automatic|Out-Null}else{$changed=Invoke-CimMethod -InputObject (Get-CimInstance Win32_Service -Filter "Name='RHRemoteHost'") -MethodName Change -Arguments @{PathName=$binary;StartMode='Automatic'};if($changed.ReturnValue -ne 0){throw ('Unable to configure RHRemoteHost service: '+$changed.ReturnValue)}}
$rule=Get-NetFirewallRule -Name 'RHRemoteHost-LAN' -ErrorAction SilentlyContinue
if($rule){Remove-NetFirewallRule -Name 'RHRemoteHost-LAN'}
New-NetFirewallRule -Name 'RHRemoteHost-LAN' -DisplayName 'RHRemote Host (paired clients, LAN)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47995 -RemoteAddress LocalSubnet -Program (Join-Path $install 'RHRemoteHost.exe') -Profile Any|Out-Null
Start-Service RHRemoteHost
[pscustomobject]@{service=(Get-Service RHRemoteHost).Status.ToString();port=47995;authentication='Sunshine paired client certificates';secureAttention=[bool]$EnableSecureAttention;sunshineRestarted=$false}
