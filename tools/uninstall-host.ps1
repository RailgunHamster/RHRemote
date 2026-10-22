$ErrorActionPreference='Stop'
$admin=([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if(-not $admin){throw 'Run in an administrator PowerShell.'}
$unified=Get-CimInstance Win32_Service -Filter "Name='SunshineService'" -ErrorAction SilentlyContinue
if($unified-and$unified.PathName-match'--sunshine-service'){throw 'The unified RHRemote-Sunshine service owns these firewall and SAS settings. Use the unified package rollback instead.'}
$service=Get-Service RHRemoteHost -ErrorAction SilentlyContinue
if($service){Stop-Service RHRemoteHost;$service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(30));& sc.exe delete RHRemoteHost|Out-Null;if($LASTEXITCODE -ne 0){throw 'Could not remove RHRemoteHost service.'}}
Get-NetFirewallRule -Name 'RHRemoteHost-LAN' -ErrorAction SilentlyContinue|Remove-NetFirewallRule
$data=Join-Path $env:ProgramData 'RHRemoteHost';$backup=Join-Path $data 'sas-policy-before.json';$path='HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System'
if(Test-Path -LiteralPath $backup){
    $saved=[IO.File]::ReadAllText($backup)|ConvertFrom-Json;$current=Get-ItemProperty -LiteralPath $path -Name SoftwareSASGeneration -ErrorAction SilentlyContinue
    if($current -and $null -ne $saved.appliedValue -and $current.SoftwareSASGeneration -eq $saved.appliedValue){
        if($saved.exists){New-ItemProperty -LiteralPath $path -Name SoftwareSASGeneration -Value ([int]$saved.value) -PropertyType DWord -Force|Out-Null}else{Remove-ItemProperty -LiteralPath $path -Name SoftwareSASGeneration}
    }
}
[pscustomobject]@{serviceRemoved=$true;sunshineChanged=$false;retainedData=$data}
