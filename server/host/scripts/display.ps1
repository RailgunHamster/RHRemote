param([Parameter(Mandatory)][string]$RequestFile,[Parameter(Mandatory)][string]$OutputFile)
$ErrorActionPreference='Stop'
$utf8=New-Object Text.UTF8Encoding $false
$root=Join-Path $env:ProgramData 'RHRemoteHost'
$created=$false;$newMonitorId=$null;$applied=$false;$previousMode=$null;$ownershipBefore=$null
try {
    foreach($path in @($RequestFile,$OutputFile)) {if([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($path)) -ne $root){throw 'Display worker paths must remain in its protected data directory.'}}
    $request=[IO.File]::ReadAllText($RequestFile)|ConvertFrom-Json
    if($request.mode -notin @('list','auto','physical','virtual')){throw 'Unknown display mode.'}
    . (Join-Path $PSScriptRoot 'displayconfig.ps1')
    $inputDesktop=[RhDisplayConfig]::SyncInputDesktop()
    $ownedFile=Join-Path $root 'owned-monitor.json'
    if(Test-Path -LiteralPath $ownedFile){$ownershipBefore=[IO.File]::ReadAllText($ownedFile)}
    $owned=if(Test-Path -LiteralPath $ownedFile){[IO.File]::ReadAllText($ownedFile)|ConvertFrom-Json}else{$null}
    function CaptureOrder {
        $path=Join-Path $root ('capture-'+[guid]::NewGuid().ToString('N')+'.json')
        try {$process=Start-Process -FilePath (Join-Path (Split-Path $PSScriptRoot -Parent) 'RHRemoteHost.exe') -ArgumentList @('--capture-order',('"'+$path+'"')) -WindowStyle Hidden -Wait -PassThru
            if($process.ExitCode -ne 0 -or -not(Test-Path -LiteralPath $path)){throw 'Unable to enumerate Sunshine-compatible capture outputs.'}
            $capture=[IO.File]::ReadAllText($path)|ConvertFrom-Json;$script:captureDiagnostics=@($capture.diagnostics);return @($capture.names)
        } finally {if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path -Force}}
    }
    function Inventory {
        $order=@(CaptureOrder)
        $paths=@([RhDisplayConfig]::Query())
        @($paths|ForEach-Object {
            $p=$_;$index=[Array]::IndexOf($order,$p.GdiDeviceName);$normalized=$p.MonitorDevicePath.Replace('#','\').ToLowerInvariant()
            $isOwned=($owned -and $normalized.Contains($owned.device_id.ToLowerInvariant()))
            $adapter=$p.AdapterDevicePath -replace '^\\\\\?\\','' -replace '#\{[^}]+\}$','' -replace '#','\'
            $hardware=@((Get-ItemProperty -LiteralPath ('Registry::HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Enum\'+$adapter) -Name HardwareID -ErrorAction SilentlyContinue).HardwareID) -join ' '
            $virtual=($isOwned -or $hardware -match '(?i)usbmmidd|parsec|iddsample|virtualdisplay')
            [pscustomobject]@{id=$p.MonitorDevicePath;name=$p.MonitorName;gdi=$p.GdiDeviceName;captureIndex=($index+1);available=($p.TargetAvailable -and $index -ge 0);virtual=[bool]$virtual;owned=[bool]$isOwned;width=[int]$p.SourceWidth;height=[int]$p.SourceHeight;x=[int]$p.PositionX;y=[int]$p.PositionY;rotation=$p.Rotation;refreshNumerator=$p.RefreshNumerator;refreshDenominator=$p.RefreshDenominator;primary=($p.PositionX -eq 0 -and $p.PositionY -eq 0)}
        })
    }
    $before=@(Inventory);$selected=$null;$created=$false
    if($request.mode -eq 'physical'){
        $selected=@($before|Where-Object {$_.available -and $_.id -eq $request.displayId})|Select-Object -First 1
        if(-not $selected){throw 'The selected monitor is unavailable. Refresh the monitor list.'}
    } elseif($request.mode -eq 'auto'){
        $selected=@($before|Where-Object {$_.available -and -not $_.virtual}|Sort-Object @{Expression='primary';Descending=$true},captureIndex)|Select-Object -First 1
    }
    if($request.mode -ne 'list' -and -not $selected){
        $width=[int]$request.width;$height=[int]$request.height
        if($width -lt 320 -or $height -lt 320 -or $width -gt 8192 -or $height -gt 8192 -or $width%2 -or $height%2 -or ([long]$width*$height) -gt 64000000){throw 'Invalid virtual monitor resolution.'}
        $selected=@($before|Where-Object {$_.owned})|Select-Object -First 1
        if(-not $selected){
        . (Join-Path $PSScriptRoot 'amyuni-vdd.ps1')
        $monitors=@(Get-AmyuniMonitors)
        if($monitors.Count -gt 1){throw 'Multiple Amyuni monitors exist; no monitor was changed.'}
        if($monitors.Count -eq 1 -and (-not $owned -or $owned.device_id -ne $monitors[0].DeviceID)){throw 'The existing virtual monitor is not owned by RHRemote.'}
        if($monitors.Count -eq 0){
            [RhAmyuni]::Plug($true);$created=$true
            for($i=0;$i -lt 10;$i++){Start-Sleep -Milliseconds 300;$monitors=@(Get-AmyuniMonitors);if($monitors.Count -eq 1){break}}
            if($monitors.Count -ne 1){throw 'The virtual monitor did not become available.'}
            $owned=@{device_id=[string]$monitors[0].DeviceID};[IO.File]::WriteAllText($ownedFile,($owned|ConvertTo-Json -Compress),$utf8)
            $newMonitorId=$owned.device_id
        }
        $current=@(Inventory);$selected=@($current|Where-Object {$_.owned})|Select-Object -First 1
        if(-not $selected){throw 'Cannot map the owned virtual monitor to the Windows desktop.'}
        }
        $changed=$created
        if($selected.width -ne $width -or $selected.height -ne $height){
            $previousMode=$selected
            . (Join-Path $PSScriptRoot 'tablet-mode.ps1')
            if(-not [RhTabletMode]::Supports($selected.gdi,$width,$height)){throw 'The virtual display driver does not support this resolution. Choose an available virtual resolution.'}
            $x=$selected.x;$y=$selected.y
            if($created -and @($before|Where-Object {-not $_.virtual}).Count){$x=[int](($before|ForEach-Object {$_.x+$_.width}|Measure-Object -Maximum).Maximum);$y=0}
            [RhTabletMode]::Apply($selected.gdi,$width,$height,$x,$y)
            $applied=$true
            $changed=$true
        }
        $after=if($changed){@(Inventory)}else{$before}
        foreach($physical in @($before|Where-Object {-not $_.virtual})){
            $actual=@($after|Where-Object {$_.id -eq $physical.id});if($actual.Count -ne 1){throw 'A physical monitor changed during virtual monitor setup.'}
            foreach($field in @('width','height','x','y','rotation','refreshNumerator','refreshDenominator')){if($actual[0].$field -ne $physical.$field){throw ('Physical monitor '+$field+' changed during virtual monitor setup.')}}
        }
        $selected=@($after|Where-Object {$_.owned -and $_.available})|Select-Object -First 1
        if(-not $selected -or $selected.width -ne $width -or $selected.height -ne $height){throw 'Virtual monitor resolution verification failed.'}
    } else {$after=$before}
    $settingsFile=Join-Path $root 'host-settings.json'
    $sunshineRoot=if(Test-Path -LiteralPath $settingsFile){([IO.File]::ReadAllText($settingsFile)|ConvertFrom-Json).sunshineConfig}else{Join-Path $env:ProgramFiles 'Sunshine\config'}
    $sunshineConfig=Join-Path $sunshineRoot 'sunshine.conf'
    $configuredOutput=if(Test-Path -LiteralPath $sunshineConfig){@([IO.File]::ReadAllLines($sunshineConfig)|Where-Object {$_ -match '^\s*output_name\s*='}|Select-Object -Last 1) -join ''}else{'unknown'}
    $initialCaptureIndex=if(-not $configuredOutput -or $configuredOutput -match '^\s*output_name\s*=\s*$'){1}else{0}
    $result=@{ok=$true;schema='rhremote.displays/v1';displays=@($after);selected=$selected;created=$created;captureDiagnostics=@($script:captureDiagnostics);inputDesktop=$inputDesktop;initialCaptureIndex=$initialCaptureIndex}
} catch {
    $result=@{ok=$false;error=$_.Exception.Message}
    try {
        if($created -and $newMonitorId){
            $monitors=@(Get-AmyuniMonitors)
            if($monitors.Count -eq 1 -and $monitors[0].DeviceID -eq $newMonitorId){[RhAmyuni]::Plug($false);$result.rollback='removed newly created virtual monitor';if($null -ne $ownershipBefore){[IO.File]::WriteAllText($ownedFile,$ownershipBefore,$utf8)}elseif(Test-Path -LiteralPath $ownedFile){Remove-Item -LiteralPath $ownedFile -Force}}
        } elseif($applied -and $previousMode){
            [RhTabletMode]::Apply($previousMode.gdi,$previousMode.width,$previousMode.height,$previousMode.x,$previousMode.y);$result.rollback='restored owned virtual monitor mode'
        }
    } catch {$result.rollbackError=$_.Exception.Message}
}
[IO.File]::WriteAllText($OutputFile,($result|ConvertTo-Json -Depth 6 -Compress),$utf8)
