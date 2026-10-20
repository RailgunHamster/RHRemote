# Bounded source workload: receives events only in this owned test window, never through global hooks.
param([ValidateRange(30,180)][int]$Seconds=180)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
. (Join-Path $root 'displayconfig.ps1')
$setup=Get-Content -LiteralPath (Join-Path $root 'setup-result.json') -Raw|ConvertFrom-Json
$target=@([RhDisplayConfig]::Query()|Where-Object {$_.MonitorDevicePath -eq $setup.display.monitor.MonitorDevicePath})
if($target.Count -ne 1 -or $target[0].SourceWidth -ne 2520 -or $target[0].SourceHeight -ne 1680 -or ($target[0].PositionX -eq 0 -and $target[0].PositionY -eq 0)){throw 'Expected the owned native secondary display.'}
Add-Type -AssemblyName System.Windows.Forms,System.Drawing
Add-Type -ReferencedAssemblies System.Windows.Forms,System.Drawing -TypeDefinition @'
using System;using System.Diagnostics;using System.Drawing;using System.IO;using System.Runtime.InteropServices;using System.Windows.Forms;
public sealed class RhInteractionProbe:Form {
 [DllImport("user32.dll")]static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32.dll")]static extern int GetSystemMetrics(int metric);
 readonly Stopwatch clock=new Stopwatch();readonly Timer timer=new Timer();readonly string folder;readonly int seconds;
 readonly Font titleFont=new Font("Segoe UI",48,FontStyle.Regular,GraphicsUnit.Pixel),bodyFont=new Font("Consolas",30,FontStyle.Regular,GraphicsUnit.Pixel);
 long frames;int phase,mainDown,mainUp,menuDown,menuUp,wheel,move,held;Point last;
 protected override bool ShowWithoutActivation{get{return true;}}
 protected override CreateParams CreateParams{get{var p=base.CreateParams;p.ExStyle|=0x80;return p;}}
 RhInteractionProbe(int x,int y,int duration,string dir){
  folder=dir;seconds=duration;AutoScaleMode=AutoScaleMode.None;FormBorderStyle=FormBorderStyle.None;StartPosition=FormStartPosition.Manual;Bounds=new Rectangle(x,y,2520,1680);ShowInTaskbar=false;TopMost=true;DoubleBuffered=true;KeyPreview=true;
  timer.Interval=15;timer.Tick+=delegate{if(clock.Elapsed.TotalSeconds>=seconds||File.Exists(Path.Combine(folder,"stop-motion"))){Close();return;}int now=(int)(clock.Elapsed.TotalSeconds*30);if(now!=phase){phase=now;Invalidate();}if(phase%15==0)WriteStatus(false);};
  Shown+=delegate{clock.Start();timer.Start();WriteStatus(false);};FormClosed+=delegate{timer.Stop();WriteStatus(true);titleFont.Dispose();bodyFont.Dispose();};
 }
 void Event(string kind,int x,int y,int delta){File.AppendAllText(Path.Combine(folder,"input-events.jsonl"),"{\"ms\":"+clock.ElapsedMilliseconds+",\"kind\":\""+kind+"\",\"x\":"+x+",\"y\":"+y+",\"delta\":"+delta+"}\n");}
 protected override void OnMouseDown(MouseEventArgs e){Focus();if(e.Button==MouseButtons.Left){mainDown++;held|=1;Event("primary_down",e.X,e.Y,0);}if(e.Button==MouseButtons.Right){menuDown++;held|=2;Event("secondary_down",e.X,e.Y,0);}base.OnMouseDown(e);WriteStatus(false);}
 protected override void OnMouseUp(MouseEventArgs e){if(e.Button==MouseButtons.Left){mainUp++;held&=~1;Event("primary_up",e.X,e.Y,0);}if(e.Button==MouseButtons.Right){menuUp++;held&=~2;Event("secondary_up",e.X,e.Y,0);}base.OnMouseUp(e);WriteStatus(false);}
 protected override void OnMouseMove(MouseEventArgs e){last=e.Location;if(e.Button!=MouseButtons.None){move++;}base.OnMouseMove(e);}
 protected override void OnMouseWheel(MouseEventArgs e){wheel+=e.Delta;Event("wheel",e.X,e.Y,e.Delta);base.OnMouseWheel(e);WriteStatus(false);}
 void WriteStatus(bool ended){File.WriteAllText(Path.Combine(folder,"motion-status.json"),"{\"ended\":"+(ended?"true":"false")+",\"elapsed_s\":"+clock.Elapsed.TotalSeconds.ToString("F3",System.Globalization.CultureInfo.InvariantCulture)+",\"painted_frames\":"+frames+",\"width\":"+Width+",\"height\":"+Height+",\"x\":"+Left+",\"y\":"+Top+",\"swap_buttons\":"+GetSystemMetrics(23)+",\"primary_down\":"+mainDown+",\"primary_up\":"+mainUp+",\"secondary_down\":"+menuDown+",\"secondary_up\":"+menuUp+",\"wheel_delta\":"+wheel+",\"drag_moves\":"+move+",\"held\":"+held+"}");}
 protected override void OnPaint(PaintEventArgs e){frames++;var g=e.Graphics;g.Clear(Color.FromArgb(244,247,252));g.DrawString("RHRemote - client validation",titleFont,Brushes.MidnightBlue,100,80);g.DrawString("2520 x 1680 | Target 30 FPS | Audio OFF",bodyFont,Brushes.DimGray,100,175);
  g.DrawString("Frame "+frames+"    "+clock.Elapsed.TotalSeconds.ToString("F1")+" s",bodyFont,Brushes.DimGray,100,235);
  using(var blue=new SolidBrush(Color.FromArgb(237,242,255)))g.FillRectangle(blue,100,400,1060,330);using(var purple=new SolidBrush(Color.FromArgb(244,239,255)))g.FillRectangle(purple,1300,400,1060,330);
  g.DrawString("TAP / DOUBLE TAP",titleFont,Brushes.RoyalBlue,150,450);g.DrawString("Primary: "+mainDown+" down / "+mainUp+" up",bodyFont,Brushes.MidnightBlue,150,550);
  g.DrawString("LONG PRESS",titleFont,Brushes.MediumPurple,1350,450);g.DrawString("Menu: "+menuDown+" down / "+menuUp+" up",bodyFont,Brushes.MidnightBlue,1350,550);
  g.DrawRectangle(Pens.LightSteelBlue,100,820,2260,270);g.DrawString("DRAG / TWO-FINGER SCROLL / PINCH",bodyFont,Brushes.DimGray,150,850);g.DrawString("Drag moves: "+move+"    Wheel delta: "+wheel+"    Held buttons: "+held,bodyFont,Brushes.DimGray,150,935);g.FillEllipse(Brushes.RoyalBlue,last.X-12,last.Y-12,24,24);
  int offset=(phase*17)%2200;g.FillRectangle(Brushes.RoyalBlue,100+offset,1250,40,180);base.OnPaint(e);
 }
 public static void Run(int x,int y,int seconds,string folder){IntPtr old=SetThreadDpiAwarenessContext(new IntPtr(-4));try{using(var form=new RhInteractionProbe(x,y,seconds,folder))Application.Run(form);}finally{SetThreadDpiAwarenessContext(old);}}
}
'@
Remove-Item -LiteralPath (Join-Path $root 'stop-motion'),(Join-Path $root 'input-events.jsonl') -ErrorAction SilentlyContinue
[RhInteractionProbe]::Run($target[0].PositionX,$target[0].PositionY,$Seconds,$root)
