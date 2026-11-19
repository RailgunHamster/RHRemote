param(
    [Parameter(Mandatory=$true)][string]$TestExecutable,
    [string]$Filter='RhRemoteClipboardHost*',
    [string]$MsysRoot='C:\msys64',
    [int]$TimeoutSeconds=120
)
# Runs real-clipboard tests on a private, non-interactive window station. Each window station has its own
# clipboard, so the tests never read or replace the clipboard of the signed-in user.
$ErrorActionPreference='Stop'
if(-not(Test-Path -LiteralPath $TestExecutable)){throw "Missing test executable: $TestExecutable"}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class RhIsolatedStation {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    struct STARTUPINFO { public int cb; public string lpReserved; public string lpDesktop; public string lpTitle; public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags; public short wShowWindow, cbReserved2; public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError; }
    [StructLayout(LayoutKind.Sequential)]
    struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }
    [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateWindowStationW(string name, int flags, uint access, IntPtr attributes);
    [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateDesktopW(string name, IntPtr device, IntPtr mode, int flags, uint access, IntPtr attributes);
    [DllImport("user32.dll", SetLastError=true)] static extern IntPtr GetProcessWindowStation();
    [DllImport("user32.dll", SetLastError=true)] static extern bool SetProcessWindowStation(IntPtr station);
    [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32.dll")] static extern bool CloseWindowStation(IntPtr station);
    [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool GetUserObjectInformationW(IntPtr handle, int index, System.Text.StringBuilder info, int length, out int needed);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CreateProcessW(string app, string command, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr env, string dir, ref STARTUPINFO si, out PROCESS_INFORMATION pi);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle, uint ms);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process, uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);

    public static int Run(string command, string directory, int timeoutMs) {
        // A null name creates the logon session's service window station, which standard users may create.
        IntPtr station = CreateWindowStationW(null, 0, 0x37F /* WINSTA_ALL_ACCESS */, IntPtr.Zero);
        if (station == IntPtr.Zero) throw new Exception("CreateWindowStation failed: " + Marshal.GetLastWin32Error());
        var nameBuffer = new System.Text.StringBuilder(256);
        int needed;
        if (!GetUserObjectInformationW(station, 2 /* UOI_NAME */, nameBuffer, 512, out needed)) throw new Exception("Window station name unavailable");
        string name = nameBuffer.ToString();
        string desktopName = "RHRemoteClipboardTest" + System.Diagnostics.Process.GetCurrentProcess().Id;
        IntPtr previous = GetProcessWindowStation();
        IntPtr desktop = IntPtr.Zero;
        try {
            if (!SetProcessWindowStation(station)) throw new Exception("SetProcessWindowStation failed: " + Marshal.GetLastWin32Error());
            desktop = CreateDesktopW(desktopName, IntPtr.Zero, IntPtr.Zero, 0, 0x10000000 /* GENERIC_ALL */, IntPtr.Zero);
            if (desktop == IntPtr.Zero) throw new Exception("CreateDesktop failed: " + Marshal.GetLastWin32Error());
        } finally {
            SetProcessWindowStation(previous);
        }
        try {
            STARTUPINFO si = new STARTUPINFO();
            si.cb = Marshal.SizeOf(typeof(STARTUPINFO));
            si.lpDesktop = name + "\\" + desktopName;
            PROCESS_INFORMATION pi;
            if (!CreateProcessW(null, command, IntPtr.Zero, IntPtr.Zero, false, 0x08000000 /* CREATE_NO_WINDOW */, IntPtr.Zero, directory, ref si, out pi))
                throw new Exception("CreateProcess failed: " + Marshal.GetLastWin32Error());
            try {
                if (WaitForSingleObject(pi.hProcess, (uint)timeoutMs) != 0) { TerminateProcess(pi.hProcess, 124); return 124; }
                uint code; GetExitCodeProcess(pi.hProcess, out code); return (int)code;
            } finally { CloseHandle(pi.hThread); CloseHandle(pi.hProcess); }
        } finally {
            CloseDesktop(desktop);
            CloseWindowStation(station);
        }
    }
}
'@
$exe=(Resolve-Path -LiteralPath $TestExecutable).Path
$log=Join-Path ([IO.Path]::GetTempPath()) ('rhremote-clipboard-isolated-'+[guid]::NewGuid().ToString('N')+'.log')
$env:PATH=(Join-Path $MsysRoot 'ucrt64\bin')+';'+$env:PATH
$env:RHREMOTE_CLIPBOARD_ISOLATED='1'
try {
    $command="cmd.exe /d /c `"`"$exe`" --gtest_filter=$Filter > `"$log`" 2>&1`""
    $code=[RhIsolatedStation]::Run($command,(Split-Path $exe -Parent),$TimeoutSeconds*1000)
    if(Test-Path -LiteralPath $log){Get-Content -LiteralPath $log}
    if($code -ne 0){throw "Isolated clipboard tests failed with exit code $code"}
} finally {
    Remove-Item Env:RHREMOTE_CLIPBOARD_ISOLATED -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
}
