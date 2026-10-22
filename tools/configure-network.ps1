param(
    [string]$InstallDirectory=(Join-Path $env:ProgramFiles 'Sunshine'),
    [int]$BasePort=47989,
    [switch]$Apply
)
$ErrorActionPreference='Stop'
if($BasePort-lt1024-or$BasePort-gt65514){throw 'Unsupported Sunshine port range'}
$adapters=@(Get-NetAdapter -IncludeHidden|Where-Object {$_.InterfaceDescription -eq 'Tailscale Tunnel'}|Select-Object -ExpandProperty Name)
$ranges=@('100.64.0.0/10','fd7a:115c:a1e0::/48')
$rules=@(
    @{name='RHRemote-Network-Diagnostic-LAN';protocol='UDP';ports=@(47995);program=(Join-Path $InstallDirectory 'host\RHRemoteHost.exe');remote=@('LocalSubnet');interfaces=@()}
)
if($adapters.Count){
    $rules+=@(
        @{name='RHRemote-Tailscale-Host-TCP';protocol='TCP';ports=@(47995);program=(Join-Path $InstallDirectory 'host\RHRemoteHost.exe');remote=$ranges;interfaces=$adapters},
        @{name='RHRemote-Tailscale-Host-UDP';protocol='UDP';ports=@(47995);program=(Join-Path $InstallDirectory 'host\RHRemoteHost.exe');remote=$ranges;interfaces=$adapters},
        @{name='RHRemote-Tailscale-Stream-TCP';protocol='TCP';ports=@(($BasePort-5),$BasePort,($BasePort+21));program=(Join-Path $InstallDirectory 'sunshine.exe');remote=$ranges;interfaces=$adapters},
        @{name='RHRemote-Tailscale-Stream-UDP';protocol='UDP';ports=@(($BasePort+9),($BasePort+10),($BasePort+11),($BasePort+13),($BasePort+21));program=(Join-Path $InstallDirectory 'sunshine.exe');remote=$ranges;interfaces=$adapters}
    )
}
if(-not$Apply){[pscustomobject]@{tailscaleInstalled=($adapters.Count-gt0);rules=$rules;managementPortOpened=$false};return}
if(-not([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Run configure-network.ps1 -Apply in administrator PowerShell'}
$created=@()
try{
    foreach($rule in $rules){
        $existing=Get-NetFirewallRule -Name $rule.name -ErrorAction SilentlyContinue
        if($existing){
            if($existing.Description-ne'RHRemote paired network v1'){throw ('Refusing to replace an unrelated firewall rule: '+$rule.name)}
            # Existing rules are immutable. Version the rule name if policy changes.
            continue
        }
        if(-not(Test-Path -LiteralPath $rule.program)){throw ('Missing installed binary: '+$rule.program)}
        $arguments=@{Name=$rule.name;DisplayName=$rule.name;Description='RHRemote paired network v1';Direction='Inbound';Action='Allow';Enabled='True';Profile='Any';Protocol=$rule.protocol;LocalPort=$rule.ports;RemoteAddress=$rule.remote;Program=$rule.program}
        if($rule.interfaces.Count){$arguments.InterfaceAlias=$rule.interfaces}
        New-NetFirewallRule @arguments|Out-Null;$created+=$rule.name
    }
    [pscustomobject]@{ok=$true;created=$created;tailscaleInstalled=($adapters.Count-gt0);managementPortOpened=$false}
}catch{
    foreach($name in $created){Remove-NetFirewallRule -Name $name -ErrorAction SilentlyContinue}
    throw
}
