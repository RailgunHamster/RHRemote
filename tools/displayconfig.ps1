# Dot-source helper: active display paths from QueryDisplayConfig (exact refresh ratios, not WMI integers).
# [RhDisplayConfig]::Query() returns one RhDisplayPath per active path, or throws RhDisplayConfigException with
# the Win32 error code (e.g. from a non-interactive session). Read-only.
if (-not ('RhDisplayConfig' -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public class RhDisplayConfigException : Exception {
    public int Code;
    public RhDisplayConfigException(string where, int code) : base(where + " failed with Win32 error " + code) { Code = code; }
}

public class RhDisplayPath {
    public string GdiDeviceName, MonitorName, MonitorDevicePath, AdapterDevicePath;
    public uint SourceWidth, SourceHeight; public int PositionX, PositionY;
    public uint RefreshNumerator, RefreshDenominator, VSyncNumerator, VSyncDenominator;
    public uint ActiveWidth, ActiveHeight;
    public uint Rotation, Scaling, OutputTechnology, ScanLineOrdering;
    public bool TargetAvailable;
    public int AdvancedColorQuery; public bool? AdvancedColorSupported, AdvancedColorEnabled; public uint? BitsPerColorChannel;
}

public static class RhDisplayConfig {
    [StructLayout(LayoutKind.Sequential)] struct LUID { public uint Low; public int High; }
    [StructLayout(LayoutKind.Sequential)] struct RATIONAL { public uint Num; public uint Den; }
    [StructLayout(LayoutKind.Sequential)] struct PATH_SOURCE { public LUID adapterId; public uint id; public uint modeInfoIdx; public uint statusFlags; }
    [StructLayout(LayoutKind.Sequential)] struct PATH_TARGET {
        public LUID adapterId; public uint id; public uint modeInfoIdx; public uint outputTechnology; public uint rotation;
        public uint scaling; public RATIONAL refreshRate; public uint scanLineOrdering; public int targetAvailable; public uint statusFlags; }
    [StructLayout(LayoutKind.Sequential)] struct PATH_INFO { public PATH_SOURCE source; public PATH_TARGET target; public uint flags; }
    [StructLayout(LayoutKind.Sequential)] struct REGION { public uint cx; public uint cy; }
    [StructLayout(LayoutKind.Sequential)] struct SIGNAL_INFO {
        public ulong pixelRate; public RATIONAL hSync; public RATIONAL vSync; public REGION activeSize; public REGION totalSize;
        public uint videoStandard; public uint scanLineOrdering; }
    [StructLayout(LayoutKind.Sequential)] struct SOURCE_MODE { public uint width; public uint height; public uint pixelFormat; public int x; public int y; }
    [StructLayout(LayoutKind.Explicit)] struct MODE_UNION { [FieldOffset(0)] public SIGNAL_INFO target; [FieldOffset(0)] public SOURCE_MODE source; }
    [StructLayout(LayoutKind.Sequential)] struct MODE_INFO { public uint infoType; public uint id; public LUID adapterId; public MODE_UNION mode; }
    [StructLayout(LayoutKind.Sequential)] struct HEADER { public uint type; public uint size; public LUID adapterId; public uint id; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct SOURCE_NAME {
        public HEADER header; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string gdiName; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct TARGET_NAME {
        public HEADER header; public uint flags; public uint outputTechnology; public ushort edidManufactureId; public ushort edidProductCodeId;
        public uint connectorInstance; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string friendlyName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string devicePath; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct ADAPTER_NAME {
        public HEADER header; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string devicePath; }
    [StructLayout(LayoutKind.Sequential)] struct COLOR_INFO { public HEADER header; public uint value; public uint colorEncoding; public uint bitsPerColorChannel; }

    [DllImport("user32.dll")] static extern int GetDisplayConfigBufferSizes(uint flags, out uint numPaths, out uint numModes);
    [DllImport("user32.dll")] static extern int QueryDisplayConfig(uint flags, ref uint numPaths, [Out] PATH_INFO[] paths, ref uint numModes, [Out] MODE_INFO[] modes, IntPtr topology);
    [DllImport("user32.dll")] static extern int SetDisplayConfig(uint numPaths, PATH_INFO[] paths, uint numModes, MODE_INFO[] modes, uint flags);
    [DllImport("user32.dll")] static extern int DisplayConfigGetDeviceInfo(ref SOURCE_NAME info);
    [DllImport("user32.dll")] static extern int DisplayConfigGetDeviceInfo(ref TARGET_NAME info);
    [DllImport("user32.dll")] static extern int DisplayConfigGetDeviceInfo(ref ADAPTER_NAME info);
    [DllImport("user32.dll")] static extern int DisplayConfigGetDeviceInfo(ref COLOR_INFO info);

    const uint QDC_ONLY_ACTIVE_PATHS = 2;
    const uint MODE_SOURCE = 1, MODE_TARGET = 2;
    const uint NO_MODE = 0xFFFFFFFF;

    static HEADER Header(uint type, int size, LUID adapter, uint id) {
        HEADER h = new HEADER(); h.type = type; h.size = (uint)size; h.adapterId = adapter; h.id = id; return h;
    }

    // Used only by an explicitly requested two-display test layout. Preserve modes
    // and rebase the original primary to (0,0), without saving to the database.
    public static void KeepPrimary(string gdiName) {
        uint np = 0, nm = 0; PATH_INFO[] paths = null; MODE_INFO[] modes = null; int rc = 122;
        for (int attempt = 0; attempt < 3 && rc == 122; attempt++) {
            rc = GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, out np, out nm);
            if (rc != 0) throw new RhDisplayConfigException("GetDisplayConfigBufferSizes", rc);
            paths = new PATH_INFO[np]; modes = new MODE_INFO[nm];
            rc = QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, ref np, paths, ref nm, modes, IntPtr.Zero);
        }
        if (rc != 0) throw new RhDisplayConfigException("QueryDisplayConfig", rc);
        if (np != 2) throw new InvalidOperationException("Expected exactly two active test displays");
        int primary = -1;
        for (int i = 0; i < np; i++) {
            var sn = new SOURCE_NAME(); sn.header = Header(1, Marshal.SizeOf(typeof(SOURCE_NAME)), paths[i].source.adapterId, paths[i].source.id);
            if (DisplayConfigGetDeviceInfo(ref sn) == 0 && sn.gdiName == gdiName) primary = i;
        }
        if (primary < 0) throw new InvalidOperationException("Original primary display not found");
        uint sourceIndex = paths[primary].source.modeInfoIdx;
        if (sourceIndex >= nm || modes[sourceIndex].infoType != MODE_SOURCE) throw new InvalidOperationException("Primary source mode missing");
        int dx = modes[sourceIndex].mode.source.x, dy = modes[sourceIndex].mode.source.y;
        for (int i = 0; i < nm; i++) if (modes[i].infoType == MODE_SOURCE) {
            modes[i].mode.source.x -= dx; modes[i].mode.source.y -= dy;
        }
        if (primary != 0) { PATH_INFO swap = paths[0]; paths[0] = paths[primary]; paths[primary] = swap; }
        rc = SetDisplayConfig(np, paths, nm, modes, 0x20 | 0x40);
        if (rc != 0) throw new RhDisplayConfigException("SetDisplayConfig(validate)", rc);
        rc = SetDisplayConfig(np, paths, nm, modes, 0x20 | 0x80);
        if (rc != 0) throw new RhDisplayConfigException("SetDisplayConfig(temporary apply)", rc);
    }

    public static List<RhDisplayPath> Query() {
        uint np, nm; int rc;
        const int ERROR_INSUFFICIENT_BUFFER = 122;
        PATH_INFO[] paths; MODE_INFO[] modes;
        do {
            rc = GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, out np, out nm);
            if (rc != 0) throw new RhDisplayConfigException("GetDisplayConfigBufferSizes", rc);
            paths = new PATH_INFO[np]; modes = new MODE_INFO[nm];
            rc = QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, ref np, paths, ref nm, modes, IntPtr.Zero);
        } while (rc == ERROR_INSUFFICIENT_BUFFER);
        if (rc != 0) throw new RhDisplayConfigException("QueryDisplayConfig", rc);

        var result = new List<RhDisplayPath>();
        for (int i = 0; i < np; i++) {
            PATH_INFO p = paths[i];
            var d = new RhDisplayPath();
            d.RefreshNumerator = p.target.refreshRate.Num; d.RefreshDenominator = p.target.refreshRate.Den;
            d.Rotation = p.target.rotation; d.Scaling = p.target.scaling; d.OutputTechnology = p.target.outputTechnology;
            d.ScanLineOrdering = p.target.scanLineOrdering; d.TargetAvailable = p.target.targetAvailable != 0;

            if (p.source.modeInfoIdx != NO_MODE && p.source.modeInfoIdx < nm && modes[p.source.modeInfoIdx].infoType == MODE_SOURCE) {
                SOURCE_MODE s = modes[p.source.modeInfoIdx].mode.source;
                d.SourceWidth = s.width; d.SourceHeight = s.height; d.PositionX = s.x; d.PositionY = s.y;
            }
            if (p.target.modeInfoIdx != NO_MODE && p.target.modeInfoIdx < nm && modes[p.target.modeInfoIdx].infoType == MODE_TARGET) {
                SIGNAL_INFO t = modes[p.target.modeInfoIdx].mode.target;
                d.VSyncNumerator = t.vSync.Num; d.VSyncDenominator = t.vSync.Den;
                d.ActiveWidth = t.activeSize.cx; d.ActiveHeight = t.activeSize.cy;
            }

            var sn = new SOURCE_NAME(); sn.header = Header(1, Marshal.SizeOf(typeof(SOURCE_NAME)), p.source.adapterId, p.source.id);
            if (DisplayConfigGetDeviceInfo(ref sn) == 0) d.GdiDeviceName = sn.gdiName;
            var tn = new TARGET_NAME(); tn.header = Header(2, Marshal.SizeOf(typeof(TARGET_NAME)), p.target.adapterId, p.target.id);
            if (DisplayConfigGetDeviceInfo(ref tn) == 0) { d.MonitorName = tn.friendlyName; d.MonitorDevicePath = tn.devicePath; }
            var an = new ADAPTER_NAME(); an.header = Header(4, Marshal.SizeOf(typeof(ADAPTER_NAME)), p.target.adapterId, 0);
            if (DisplayConfigGetDeviceInfo(ref an) == 0) d.AdapterDevicePath = an.devicePath;
            var ci = new COLOR_INFO(); ci.header = Header(9, Marshal.SizeOf(typeof(COLOR_INFO)), p.target.adapterId, p.target.id);
            d.AdvancedColorQuery = DisplayConfigGetDeviceInfo(ref ci);
            if (d.AdvancedColorQuery == 0) {
                d.AdvancedColorSupported = (ci.value & 1) != 0; d.AdvancedColorEnabled = (ci.value & 2) != 0;
                d.BitsPerColorChannel = ci.bitsPerColorChannel;
            }
            result.Add(d);
        }
        return result;
    }
}
"@
}
