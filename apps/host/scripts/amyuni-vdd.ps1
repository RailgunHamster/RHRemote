# Control of the already installed Amyuni "USB Mobile Monitor Virtual Display" (usbmmidd_v2, shipped with RustDesk
# on claw). Interface and control code as used by RustDesk's virtual_display_manager.rs: one 4-byte request to the
# device interface, 0x10 plugs in one monitor, 0x00 plugs one out. The driver has no watchdog: a plugged monitor
# stays until it is plugged out (or the driver/machine restarts), so callers need their own recovery.
# Windows PowerShell 5.1; include with Invoke-RemoteScript -Include or dot-source.

if (-not ('RhAmyuni' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

public static class RhAmyuni {
    const uint IOCTL_PLUG = 2307084;
    static readonly Guid InterfaceGuid = new Guid("b5ffd75f-da40-4353-8ff8-b6daf6f1d8ca");

    [StructLayout(LayoutKind.Sequential)] struct SP_DEVICE_INTERFACE_DATA { public int cbSize; public Guid InterfaceClassGuid; public int Flags; public IntPtr Reserved; }
    [DllImport("setupapi.dll", SetLastError = true)] static extern IntPtr SetupDiGetClassDevs(ref Guid g, IntPtr e, IntPtr h, int flags);
    [DllImport("setupapi.dll", SetLastError = true)] static extern bool SetupDiEnumDeviceInterfaces(IntPtr set, IntPtr info, ref Guid g, int i, ref SP_DEVICE_INTERFACE_DATA d);
    [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr set, ref SP_DEVICE_INTERFACE_DATA d, IntPtr detail, int size, out int required, IntPtr info);
    [DllImport("setupapi.dll")] static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern SafeFileHandle CreateFile(string name, uint access, uint share, IntPtr sa, uint disp, uint flags, IntPtr tmpl);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool DeviceIoControl(SafeFileHandle h, uint code, byte[] inBuf, int inSize, IntPtr outBuf, int outSize, out int returned, IntPtr ov);

    public static string FindDevicePath() {
        Guid g = InterfaceGuid;
        IntPtr set = SetupDiGetClassDevs(ref g, IntPtr.Zero, IntPtr.Zero, 0x12); // DIGCF_PRESENT | DIGCF_DEVICEINTERFACE
        if (set == new IntPtr(-1)) throw new System.ComponentModel.Win32Exception();
        try {
            var d = new SP_DEVICE_INTERFACE_DATA(); d.cbSize = Marshal.SizeOf(typeof(SP_DEVICE_INTERFACE_DATA));
            if (!SetupDiEnumDeviceInterfaces(set, IntPtr.Zero, ref g, 0, ref d)) return null;
            int required; SetupDiGetDeviceInterfaceDetail(set, ref d, IntPtr.Zero, 0, out required, IntPtr.Zero);
            IntPtr buf = Marshal.AllocHGlobal(required);
            try {
                Marshal.WriteInt32(buf, IntPtr.Size == 8 ? 8 : 6); // cbSize of SP_DEVICE_INTERFACE_DETAIL_DATA_W
                if (!SetupDiGetDeviceInterfaceDetail(set, ref d, buf, required, out required, IntPtr.Zero)) throw new System.ComponentModel.Win32Exception();
                return Marshal.PtrToStringUni(buf + 4);
            } finally { Marshal.FreeHGlobal(buf); }
        } finally { SetupDiDestroyDeviceInfoList(set); }
    }

    public static void Plug(bool add) {
        string path = FindDevicePath();
        if (path == null) throw new InvalidOperationException("Amyuni IDD interface not present");
        using (var h = CreateFile(path, 0xC0000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero)) { // GENERIC_READ|WRITE, share R/W, OPEN_EXISTING
            if (h.IsInvalid) throw new System.ComponentModel.Win32Exception();
            int n;
            if (!DeviceIoControl(h, IOCTL_PLUG, new byte[] { (byte)(add ? 0x10 : 0x00), 0, 0, 0 }, 4, IntPtr.Zero, 0, out n, IntPtr.Zero))
                throw new System.ComponentModel.Win32Exception();
        }
    }
}
'@
}

# Monitors whose device instance hangs off the Amyuni adapter (hardware id usbmmidd).
function Get-AmyuniMonitors {
    $adapters = @(Get-CimInstance Win32_PnPEntity | Where-Object { $_.PNPClass -eq 'Display' -and ($_.HardwareID -match 'usbmmidd' -or $_.Name -match 'USB Mobile Monitor') })
    $ids = @($adapters | ForEach-Object { $_.DeviceID })
    @(Get-CimInstance Win32_PnPEntity -Filter "PNPClass='Monitor'" | Where-Object {
        $m = $_; $parent = (Get-PnpDeviceProperty -InstanceId $m.DeviceID -KeyName 'DEVPKEY_Device_Parent' -ErrorAction SilentlyContinue).Data
        $parent -and ($ids -contains $parent) })
}
