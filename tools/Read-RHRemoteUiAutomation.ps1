param(
 [Parameter(Mandatory=$true)][string]$RuntimeFile,
 [Parameter(Mandatory=$true)][string]$ReportPath,
 [string]$TokenFile='D:\RHRemoteTools\secrets\rhremote-control-token.txt'
)
$ErrorActionPreference='Stop'
$runtime=Get-Content -LiteralPath $RuntimeFile -Raw | ConvertFrom-Json
$normal=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'RHRemote')).TrimEnd('\')
$data=[IO.Path]::GetFullPath([string]$runtime.data).TrimEnd('\')
if($data -eq $normal -or -not (Test-Path -LiteralPath $data -PathType Container)){throw 'Use a live isolated preview runtime'}
$owner=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$runtime.pid)
if(-not $owner -or $owner.ExecutablePath -ne $runtime.exe -or [IO.Path]::GetFileName($owner.ExecutablePath) -ne 'RHRemote.exe'){throw 'Preview process identity mismatch'}
$view=(& (Join-Path $PSScriptRoot 'Invoke-RHRemoteUiTest.ps1') -Port $runtime.port -TokenFile $TokenFile -Operation inspect).result
if(-not $view.nativeWindowHandle){throw 'Preview must expose its exact native window handle'}
$handle=[IntPtr][long]$view.nativeWindowHandle
if($handle -eq [IntPtr]::Zero){throw 'Owned main window is not available'}
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
if(-not ('RhRemoteUiaForeground' -as [type])){
 Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class RhRemoteUiaForeground { [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool IsIconic(IntPtr hwnd); }'
}
$root=[Windows.Automation.AutomationElement]::FromHandle($handle)
if(-not $root -or $root.Current.ProcessId -ne [int]$runtime.pid){throw 'UIA root does not belong to the preview'}
$condition=[Windows.Automation.OrCondition]::new(
 [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::IsKeyboardFocusableProperty,$true),
 [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::HasKeyboardFocusProperty,$true)
)
$items=$root.FindAll([Windows.Automation.TreeScope]::Descendants,$condition)
if($items.Count -gt 512){throw 'Unexpectedly large owned accessibility tree'}
$controls=@(for($i=0;$i -lt $items.Count;$i++){
 $current=$items[$i].Current
 if($current.ProcessId -ne [int]$runtime.pid){throw 'UIA returned a different process'}
 [pscustomobject]@{name=$(if($current.IsPassword){'密码输入框'}else{$current.Name});id=$current.AutomationId;type=$current.ControlType.ProgrammaticName;enabled=$current.IsEnabled;focusable=$current.IsKeyboardFocusable;focused=$current.HasKeyboardFocus;offscreen=$current.IsOffscreen}
})
$focused=@($controls | Where-Object focused)
$active=[RhRemoteUiaForeground]::GetForegroundWindow() -eq $handle
$report=[ordered]@{pid=[int]$runtime.pid;windowHandle=$handle.ToInt64();windowName=$root.Current.Name;windowActive=$active;windowMinimized=[RhRemoteUiaForeground]::IsIconic($handle);controlCount=$controls.Count;focused=$focused;controls=$controls;readOnly=$true;systemInputInjected=$false;queriedOtherApplications=$false}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding utf8
[pscustomobject]@{pid=$report.pid;windowActive=$active;controls=$controls.Count;focused=$focused;report=$ReportPath;readOnly=$true} | ConvertTo-Json -Depth 5
