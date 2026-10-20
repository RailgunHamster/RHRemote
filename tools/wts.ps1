# Dot-source helper: Get-WtsSessions lists terminal sessions (id, station, state, user, active_console) through
# the WTS API instead of parsing localized qwinsta output. Read-only.
if (-not ('RhWts' -as [type])) {
    Add-Type -TypeDefinition @"
using System; using System.Collections.Generic; using System.Runtime.InteropServices;
public static class RhWts {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct SESSION_INFO { public int SessionId; public string WinStationName; public int State; }
    [DllImport("wtsapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool WTSEnumerateSessionsW(IntPtr server, int reserved, int version, out IntPtr info, out int count);
    [DllImport("wtsapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool WTSQuerySessionInformationW(IntPtr server, int id, int infoClass, out IntPtr buffer, out int bytes);
    [DllImport("wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr p);
    [DllImport("kernel32.dll")] static extern int WTSGetActiveConsoleSessionId();
    static readonly string[] States = { "Active", "Connected", "ConnectQuery", "Shadow", "Disconnected", "Idle", "Listen", "Reset", "Down", "Init" };
    static string Query(int id, int cls) {
        IntPtr b; int n; if (!WTSQuerySessionInformationW(IntPtr.Zero, id, cls, out b, out n)) return null;
        try { return Marshal.PtrToStringUni(b); } finally { WTSFreeMemory(b); }
    }
    public static List<string[]> Sessions() {
        IntPtr p; int c; var list = new List<string[]>();
        if (!WTSEnumerateSessionsW(IntPtr.Zero, 0, 1, out p, out c)) throw new Exception("WTSEnumerateSessions error " + Marshal.GetLastWin32Error());
        int size = Marshal.SizeOf(typeof(SESSION_INFO)); int console = WTSGetActiveConsoleSessionId();
        for (int i = 0; i < c; i++) {
            var s = (SESSION_INFO)Marshal.PtrToStructure(new IntPtr(p.ToInt64() + i * size), typeof(SESSION_INFO));
            string user = Query(s.SessionId, 5), domain = Query(s.SessionId, 7);
            list.Add(new string[] { s.SessionId.ToString(), s.WinStationName, s.State < States.Length ? States[s.State] : s.State.ToString(),
                                    string.IsNullOrEmpty(user) ? "" : domain + "\\" + user, (s.SessionId == console).ToString() });
        }
        WTSFreeMemory(p); return list;
    }
}
"@
}

function Get-WtsSessions {
    @([RhWts]::Sessions() | ForEach-Object { [ordered]@{ id = [int]$_[0]; station = $_[1]; state = $_[2]; user = $_[3]; active_console = [bool]::Parse($_[4]) } })
}
