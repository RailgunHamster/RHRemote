if (-not ('RhTabletMode' -as [type])) {
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class RhTabletMode {
 [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
 public struct Mode {
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string device;
  public short spec,driver,size,extra; public uint fields;
  public int x,y,orientation,fixedOutput;
  public short color,duplex,yResolution,ttOption,collate;
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string form;
  public short logPixels; public uint bits,width,height,flags,frequency;
  public uint icmMethod,icmIntent,media,dither,reserved1,reserved2,panningWidth,panningHeight;
 }
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern bool EnumDisplaySettings(string name,int mode,ref Mode data);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string name,ref Mode data,IntPtr hwnd,uint flags,IntPtr param);
 public static bool Supports(string name,int width,int height) {
  var mode=new Mode(); mode.size=(short)Marshal.SizeOf(typeof(Mode));
  for(int index=0;EnumDisplaySettings(name,index,ref mode);index++)
   if(mode.width==width && mode.height==height && mode.frequency==60) return true;
  return false;
 }
 public static void Apply(string name,int width,int height,int x,int y) {
  var mode=new Mode(); mode.size=(short)Marshal.SizeOf(typeof(Mode));
  if(!EnumDisplaySettings(name,-1,ref mode)) throw new Exception("Cannot read current virtual display mode");
  mode.width=(uint)width;mode.height=(uint)height;mode.frequency=60;mode.x=x;mode.y=y;
  mode.fields=0x80000|0x100000|0x400000|0x20;
  int result=ChangeDisplaySettingsEx(name,ref mode,IntPtr.Zero,2,IntPtr.Zero);
  if(result!=0) throw new Exception("Virtual display mode validation failed: "+result);
  result=ChangeDisplaySettingsEx(name,ref mode,IntPtr.Zero,0,IntPtr.Zero);
  if(result!=0) throw new Exception("Virtual display mode apply failed: "+result);
 }
}
'@
}
