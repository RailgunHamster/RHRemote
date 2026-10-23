# Bounded synthetic source for a 1080p throughput test. No screen or audio capture.
# Deployed by tablet-motion-control.ps1 into its owned test directory.
param([ValidateRange(30,180)][int]$Seconds=120)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
. (Join-Path $root 'displayconfig.ps1')
$setup=Get-Content -LiteralPath (Join-Path $root 'setup-result.json') -Raw|ConvertFrom-Json
$target=@([RhDisplayConfig]::Query()|Where-Object MonitorDevicePath -eq $setup.display.monitor.MonitorDevicePath)
if($target.Count -ne 1 -or $target[0].SourceWidth -ne 1920 -or $target[0].SourceHeight -ne 1080 -or ($target[0].PositionX -eq 0 -and $target[0].PositionY -eq 0)){throw 'Expected the owned secondary 1920x1080 test display'}
Add-Type -AssemblyName System.Windows.Forms,System.Drawing
Add-Type -ReferencedAssemblies System.Windows.Forms,System.Drawing -TypeDefinition @'
using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;
public sealed class RhHighMotion : Form {
 [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("winmm.dll")] static extern uint timeBeginPeriod(uint period);
 [DllImport("winmm.dll")] static extern uint timeEndPeriod(uint period);
 readonly Stopwatch clock=new Stopwatch();
 readonly Timer timer=new Timer();
 readonly Bitmap[] patterns=new Bitmap[12];
 readonly Font font=new Font("Consolas",30,FontStyle.Bold,GraphicsUnit.Pixel);
 readonly string folder;
 readonly int seconds;
 long frames;
 int phase=-1;
 protected override bool ShowWithoutActivation { get { return true; } }
 protected override CreateParams CreateParams { get {var p=base.CreateParams;p.ExStyle|=0x08000000|0x80;return p;} }
 RhHighMotion(int x,int y,int duration,string directory){
  folder=directory;seconds=duration;AutoScaleMode=AutoScaleMode.None;FormBorderStyle=FormBorderStyle.None;
  StartPosition=FormStartPosition.Manual;Bounds=new Rectangle(x,y,1920,1080);ShowInTaskbar=false;TopMost=true;DoubleBuffered=true;
  byte[] data=new byte[1920*1080*4];
  for(int n=0;n<patterns.Length;n++){
   for(int yy=0;yy<1080;yy++)for(int xx=0;xx<1920;xx++){
    uint v=unchecked((uint)(((xx+n*13)/12)*73856093)^((uint)((yy+n*7)/12)*19349663u)^((uint)n*83492791u));
    v=unchecked((v^(v>>16))*2246822519u);v^=v>>13;
    int i=(yy*1920+xx)*4;data[i]=(byte)v;data[i+1]=(byte)(v>>8);data[i+2]=(byte)(v>>16);data[i+3]=255;
   }
   var bitmap=new Bitmap(1920,1080,PixelFormat.Format32bppRgb);
   var bits=bitmap.LockBits(new Rectangle(0,0,1920,1080),ImageLockMode.WriteOnly,PixelFormat.Format32bppRgb);
   try{Marshal.Copy(data,0,bits.Scan0,data.Length);}finally{bitmap.UnlockBits(bits);}
   patterns[n]=bitmap;
  }
  timer.Interval=8;
  timer.Tick+=delegate{
   if(clock.Elapsed.TotalSeconds>=seconds||File.Exists(Path.Combine(folder,"stop-motion"))){Close();return;}
   int next=(int)(clock.Elapsed.TotalSeconds*30);if(next!=phase){phase=next;Invalidate();}
  };
  Shown+=delegate{clock.Restart();timer.Start();Status(false);};
  FormClosed+=delegate{timer.Stop();Status(true);foreach(var bitmap in patterns)if(bitmap!=null)bitmap.Dispose();font.Dispose();};
 }
 void Status(bool ended){
  var culture=CultureInfo.InvariantCulture;
  File.WriteAllText(Path.Combine(folder,"motion-status.json"),"{\"kind\":\"synthetic_high_motion\",\"ended\":"+(ended?"true":"false")+
   ",\"elapsed_s\":"+clock.Elapsed.TotalSeconds.ToString("F3",culture)+",\"painted_frames\":"+frames+
   ",\"width\":1920,\"height\":1080,\"x\":"+Left+",\"y\":"+Top+
   ",\"cpu_seconds\":"+Process.GetCurrentProcess().TotalProcessorTime.TotalSeconds.ToString("F3",culture)+"}");
 }
 protected override void OnPaint(PaintEventArgs e){
  frames++;e.Graphics.DrawImageUnscaled(patterns[Math.Max(0,phase)%patterns.Length],0,0);
  e.Graphics.FillRectangle(Brushes.White,50,50,1100,150);
  e.Graphics.DrawString("RHRemote synthetic motion | 1920x1080 | Source 30 FPS",font,Brushes.Black,65,65);
  e.Graphics.DrawString("Frame "+frames+" | "+clock.Elapsed.TotalSeconds.ToString("F2",CultureInfo.InvariantCulture)+" s",font,Brushes.Black,65,120);
  if(frames%30==0)Status(false);base.OnPaint(e);
 }
 public static void Run(int x,int y,int seconds,string folder){
  var previous=SetThreadDpiAwarenessContext(new IntPtr(-4));bool timerSet=timeBeginPeriod(1)==0;
  try{using(var window=new RhHighMotion(x,y,seconds,folder))Application.Run(window);}
  finally{if(timerSet)timeEndPeriod(1);SetThreadDpiAwarenessContext(previous);}
 }
}
'@
Remove-Item -LiteralPath (Join-Path $root 'stop-motion') -ErrorAction SilentlyContinue
[RhHighMotion]::Run($target[0].PositionX,$target[0].PositionY,$Seconds,$root)
