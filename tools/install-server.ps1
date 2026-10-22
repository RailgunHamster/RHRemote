param(
    [string]$SourceDirectory=$PSScriptRoot,
    [string]$InstallDirectory=(Join-Path $env:ProgramFiles 'Sunshine'),
    [string]$SunshineConfig='',
    [string]$RestoreBackup='',
    [int]$VerifyTimeoutSec=60
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'server-package.ps1')
if(-not([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Run the RHRemote-Sunshine installer in administrator PowerShell'}
$install=[IO.Path]::GetFullPath($InstallDirectory).TrimEnd('\','/')
if($install-eq[IO.Path]::GetPathRoot($install)-or$install-eq$env:ProgramFiles){throw 'A dedicated server installation directory is required'}
if(-not$SunshineConfig){$SunshineConfig=Join-Path $install 'config'}
$SunshineConfig=[IO.Path]::GetFullPath($SunshineConfig)
$data=Join-Path $env:ProgramData 'RHRemoteHost'
$backupRoot=Join-Path $env:ProgramData 'RHRemote-Sunshine\backups'
function Protect-Directory([string]$Path){
    New-Item -ItemType Directory -Path $Path -Force|Out-Null
    $acl=New-Object Security.AccessControl.DirectorySecurity;$acl.SetAccessRuleProtection($true,$false)
    foreach($sidText in @('S-1-5-18','S-1-5-32-544')){
        $sid=New-Object Security.Principal.SecurityIdentifier $sidText
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Service-State([string]$Name){
    $s=Get-CimInstance Win32_Service -Filter ("Name='"+$Name+"'") -ErrorAction SilentlyContinue
    if($s){@{name=$s.Name;displayName=$s.DisplayName;path=$s.PathName;startMode=$s.StartMode;running=($s.State-eq'Running');startName=$s.StartName}}
}
function Stop-OwnedService([string]$Name){
    $before=Get-CimInstance Win32_Service -Filter ("Name='"+$Name+"'") -ErrorAction SilentlyContinue
    $s=Get-Service -Name $Name -ErrorAction SilentlyContinue
    if($s-and$s.Status-ne'Stopped'){Stop-Service -Name $Name;$s.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(28))}
    if($before-and$before.ProcessId){$process=Get-Process -Id $before.ProcessId -ErrorAction SilentlyContinue;if($process-and-not$process.WaitForExit(5000)){throw ('Stopped service process has not exited: '+$Name)}}
}
function Apply-Service([object]$State){
    $s=Get-CimInstance Win32_Service -Filter ("Name='"+$State.name+"'") -ErrorAction SilentlyContinue
    $mode=if($State.startMode-eq'Auto'){'Automatic'}else{[string]$State.startMode}
    if($s){
        $r=Invoke-CimMethod -InputObject $s -MethodName Change -Arguments @{PathName=[string]$State.path;DisplayName=[string]$State.displayName;StartMode=$mode}
        if($r.ReturnValue-ne0){throw ('Service update failed: '+$State.name+' '+$r.ReturnValue)}
    }else{New-Service -Name $State.name -DisplayName $State.displayName -BinaryPathName $State.path -StartupType $mode|Out-Null}
}
function Restore-Deployment([string]$Backup){
    $state=Get-Content -LiteralPath (Join-Path $Backup 'deployment-before.json') -Raw|ConvertFrom-Json
    if($state.schema-ne'rhremote.server-backup/v1'-or[IO.Path]::GetFullPath($state.install).TrimEnd('\','/')-ne$install){throw 'Backup belongs to another installation'}
    Stop-OwnedService 'SunshineService'
    if(Test-Path -LiteralPath (Join-Path $Backup 'install')){
        Get-ChildItem -LiteralPath (Join-Path $Backup 'install') -Force|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $install -Recurse -Force}
    }
    if(Test-Path -LiteralPath (Join-Path $Backup 'external-config')){
        $config=[IO.Path]::GetFullPath($state.config)
        if($config-ne$SunshineConfig){throw 'Backup configuration directory differs from the selected target'}
        Get-ChildItem -LiteralPath (Join-Path $Backup 'external-config') -Force|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $config -Recurse -Force}
    }
    foreach($name in @('host-settings.json','owned-monitor.json')){
        $old=Join-Path $Backup $name;$target=Join-Path $data $name
        if(Test-Path -LiteralPath $old){Copy-Item -LiteralPath $old -Destination $target -Force}
    }
    if($state.sunshine){Apply-Service $state.sunshine;if($state.sunshine.running){Start-Service -Name 'SunshineService'}}else{
        & sc.exe delete SunshineService|Out-Null;if($LASTEXITCODE-ne0){throw 'Could not remove the failed new service'}
    }
    if($state.host){Apply-Service $state.host;if($state.host.running){Start-Service -Name 'RHRemoteHost'}}
    if($state.firewallProgram){
        Get-NetFirewallRule -Name 'RHRemoteHost-LAN' -ErrorAction SilentlyContinue|Get-NetFirewallApplicationFilter|Set-NetFirewallApplicationFilter -Program $state.firewallProgram|Out-Null
    }else{Get-NetFirewallRule -Name 'RHRemoteHost-LAN' -ErrorAction SilentlyContinue|Remove-NetFirewallRule}
    foreach($name in $state.firewallCreated){Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue|Remove-NetFirewallRule}
}
if($RestoreBackup){Restore-Deployment ([IO.Path]::GetFullPath($RestoreBackup));[pscustomobject]@{restored=$true;backup=$RestoreBackup;service=(Get-Service SunshineService).Status.ToString()};return}
$source=[IO.Path]::GetFullPath($SourceDirectory).TrimEnd('\','/')
$package=Test-RHRPackage $source -AllowRuntimeFiles:($source-eq$install)
$oldSunshine=Service-State 'SunshineService';$oldHost=Service-State 'RHRemoteHost'
if($oldSunshine-and($oldSunshine.startName-ne'LocalSystem'-or$oldSunshine.path-notmatch'(?i)(sunshinesvc|RHRemoteHost)\.exe')){throw 'The existing SunshineService does not match a supported SYSTEM installation'}
if($oldHost-and$oldHost.startName-ne'LocalSystem'){throw 'The legacy host service does not use the expected SYSTEM account'}
$basePort=47989
$conf=Join-Path $SunshineConfig 'sunshine.conf'
if(Test-Path -LiteralPath $conf){foreach($line in [IO.File]::ReadAllLines($conf)){if($line-match'^\s*port\s*=\s*(\d+)\s*$'){$basePort=[int]$matches[1]}}}
if($basePort-lt1024-or$basePort-gt65514){throw 'Unsupported Sunshine port range'}
$serverInfo='http://127.0.0.1:'+$basePort+'/serverinfo?uniqueid=0123456789ABCDEF'
function Read-Identity {
    $response=Invoke-WebRequest -UseBasicParsing -Uri $serverInfo -TimeoutSec 2
    $text=if($response.Content-is[byte[]]){[Text.Encoding]::UTF8.GetString($response.Content)}else{[string]$response.Content}
    $document=[xml]$text
    [string]$document.root.uniqueid
}
$psi=New-Object Diagnostics.ProcessStartInfo (Join-Path $source 'sunshine.exe');$psi.Arguments='--version';$psi.WorkingDirectory=$source;$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
$preflight=[Diagnostics.Process]::Start($psi);$stdout=$preflight.StandardOutput.ReadToEndAsync();$stderr=$preflight.StandardError.ReadToEndAsync()
if(-not$preflight.WaitForExit(15000)){$preflight.Kill();throw 'Sunshine preflight timed out'}
if($preflight.ExitCode-ne0){throw 'The packaged Sunshine binary could not load'}
$beforeUuid=$null
try{$beforeUuid=Read-Identity}catch{}
Protect-Directory $backupRoot
$backup=Join-Path $backupRoot ((Get-Date -Format yyyyMMdd-HHmmss)+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
Protect-Directory $backup
$firewallProgram=@(Get-NetFirewallRule -Name 'RHRemoteHost-LAN' -ErrorAction SilentlyContinue|Get-NetFirewallApplicationFilter|Select-Object -ExpandProperty Program)|Select-Object -First 1
$newRules=@('RHRemote-Sunshine-TCP','RHRemote-Sunshine-UDP'|Where-Object {-not(Get-NetFirewallRule -Name $_ -ErrorAction SilentlyContinue)})
$networkPlan=& (Join-Path $PSScriptRoot 'configure-network.ps1') -InstallDirectory $install -BasePort $basePort
$networkNew=@($networkPlan.rules|Where-Object {-not(Get-NetFirewallRule -Name $_.name -ErrorAction SilentlyContinue)}|ForEach-Object {$_.name})
$before=@{schema='rhremote.server-backup/v1';install=$install;config=$SunshineConfig;sunshine=$oldSunshine;host=$oldHost;firewallProgram=$firewallProgram;firewallCreated=@($newRules)+@($networkNew);uuid=$beforeUuid;commit=$package.commit}
[IO.File]::WriteAllText((Join-Path $backup 'deployment-before.json'),($before|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$clock=[Diagnostics.Stopwatch]::StartNew()
try{
    Stop-OwnedService 'SunshineService'
    Stop-OwnedService 'RHRemoteHost'
    if(Test-Path -LiteralPath $install){Copy-Item -LiteralPath $install -Destination (Join-Path $backup 'install') -Recurse}
    if(-not$SunshineConfig.StartsWith($install+'\',[StringComparison]::OrdinalIgnoreCase)-and(Test-Path -LiteralPath $SunshineConfig)){Copy-Item -LiteralPath $SunshineConfig -Destination (Join-Path $backup 'external-config') -Recurse}
    foreach($name in @('host-settings.json','owned-monitor.json')){if(Test-Path -LiteralPath (Join-Path $data $name)){Copy-Item -LiteralPath (Join-Path $data $name) -Destination $backup}}
    New-Item -ItemType Directory -Path $install,$SunshineConfig -Force|Out-Null
    if($source-ne$install){
        foreach($file in $package.files){
            $target=Get-RHRChildPath $install $file.path
            New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force|Out-Null
            Copy-Item -LiteralPath (Get-RHRChildPath $source $file.path) -Destination $target -Force
        }
        Copy-Item -LiteralPath (Join-Path $source 'server-package.json') -Destination $install -Force
    }
    Protect-Directory (Join-Path $install 'host');Protect-Directory $data
    $null=Test-RHRPackage $install -AllowRuntimeFiles
    [IO.File]::WriteAllText((Join-Path $data 'host-settings.json'),(@{sunshineConfig=$SunshineConfig}|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
    if(-not(Test-Path -LiteralPath (Join-Path $data 'owned-monitor.json'))){
        $legacyPaths=@((Join-Path $env:SystemDrive 'RHRemoteTools\tablet-display\owned-monitor.json'),(Join-Path ([IO.Path]::GetPathRoot($source)) 'RHRemoteTools\tablet-display\owned-monitor.json'))|Select-Object -Unique
        $ids=@($legacyPaths|Where-Object {Test-Path -LiteralPath $_}|ForEach-Object {(Get-Content -LiteralPath $_ -Raw|ConvertFrom-Json).device_id}|Select-Object -Unique)
        if($ids.Count-gt1-or($ids.Count-eq1-and$ids[0]-notmatch'^DISPLAY\\')){throw 'Legacy monitor ownership is ambiguous'}
        if($ids.Count-eq1){[IO.File]::WriteAllText((Join-Path $data 'owned-monitor.json'),(@{device_id=$ids[0]}|ConvertTo-Json -Compress))}
    }
    $binary='"'+(Join-Path $install 'host\RHRemoteHost.exe')+'" --sunshine-service --sunshine-exe "'+(Join-Path $install 'sunshine.exe')+'" --sunshine-config "'+$SunshineConfig+'"'
    Apply-Service @{name='SunshineService';displayName='RHRemote-Sunshine';path=$binary;startMode=if($oldSunshine){$oldSunshine.startMode}else{'Auto'}}
    if($oldHost){Set-Service -Name RHRemoteHost -StartupType Disabled}
    $rule=Get-NetFirewallRule -Name 'RHRemoteHost-LAN' -ErrorAction SilentlyContinue
    if($rule){$rule|Get-NetFirewallApplicationFilter|Set-NetFirewallApplicationFilter -Program (Join-Path $install 'host\RHRemoteHost.exe')|Out-Null}else{
        New-NetFirewallRule -Name 'RHRemoteHost-LAN' -DisplayName 'RHRemote-Sunshine paired clients (LAN)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47995 -RemoteAddress LocalSubnet -Program (Join-Path $install 'host\RHRemoteHost.exe') -Profile Any|Out-Null
    }
    foreach($name in $newRules){
        $tcp=$name.EndsWith('TCP')
        $ports=if($tcp){@(($basePort-5),$basePort,($basePort+21))}else{@(($basePort+9),($basePort+10),($basePort+11),($basePort+13),($basePort+21))}
        New-NetFirewallRule -Name $name -DisplayName ('RHRemote-Sunshine '+$(if($tcp){'TCP'}else{'UDP'})+' (LAN)') -Direction Inbound -Action Allow -Protocol $(if($tcp){'TCP'}else{'UDP'}) -LocalPort $ports -RemoteAddress LocalSubnet -Program (Join-Path $install 'sunshine.exe') -Profile Any|Out-Null
    }
    $null=& (Join-Path $PSScriptRoot 'configure-network.ps1') -InstallDirectory $install -BasePort $basePort -Apply
    Start-Service -Name SunshineService
    $deadline=(Get-Date).AddSeconds($VerifyTimeoutSec);$ready=$false
    do{
        $service=Get-CimInstance Win32_Service -Filter "Name='SunshineService'"
        $listener=Get-NetTCPConnection -LocalPort 47995 -State Listen -ErrorAction SilentlyContinue|Where-Object OwningProcess -eq $service.ProcessId
        try{
            $uuid=Read-Identity
            $engine=Get-CimInstance Win32_Process -Filter ("Name='sunshine.exe' AND ParentProcessId="+$service.ProcessId)
            $readiness=@{state=$service.State;servicePid=$service.ProcessId;listenerPid=@($listener.OwningProcess);enginePid=@($engine.ProcessId);hasUuid=[bool]$uuid;elapsedMs=$clock.ElapsedMilliseconds}
            if($service.State-eq'Running'-and$listener-and$engine-and$uuid){$ready=$true;break}
        }catch{$readiness=@{state=$service.State;servicePid=$service.ProcessId;listenerPid=@($listener.OwningProcess);error=$_.Exception.Message;elapsedMs=$clock.ElapsedMilliseconds}}
        [IO.File]::WriteAllText((Join-Path $backup 'readiness-last.json'),($readiness|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
        Start-Sleep -Milliseconds 250
    }while((Get-Date)-lt$deadline)
    if(-not$ready){throw ('The unified service did not become ready: '+($readiness|ConvertTo-Json -Compress))}
    if($beforeUuid-and$uuid-ne$beforeUuid){throw 'Sunshine pairing identity changed'}
    if($oldHost){& sc.exe delete RHRemoteHost|Out-Null;if($LASTEXITCODE-ne0){throw 'Unable to remove the legacy host service'}}
    [pscustomobject]@{ok=$true;service='SunshineService';displayName='RHRemote-Sunshine';servicePid=$service.ProcessId;sunshinePid=$engine.ProcessId;hostEndpointOwner=$listener.OwningProcess;legacyHostRemoved=($null-eq(Get-Service RHRemoteHost -ErrorAction SilentlyContinue));uuidPreserved=($beforeUuid-eq$uuid);readyMs=$clock.ElapsedMilliseconds;backup=$backup;commit=$package.commit}|ConvertTo-Json -Depth 4
}catch{
    $failure=$_.Exception.Message
    try{Restore-Deployment $backup}catch{throw ('Deployment failed; automatic rollback also failed. Backup: '+$backup+'; rollback: '+$_.Exception.Message)}
    throw ('Deployment rolled back: '+$failure+'; backup: '+$backup)
}
