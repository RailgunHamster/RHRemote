param(
 [Parameter(Mandatory)][ValidatePattern('^session70-[a-f0-9]{12}$')][string]$RunId,
 [Parameter(Mandatory)][string]$EvidenceDirectory,
 [ValidateRange(60,600)][int]$Seconds=300
)
$ErrorActionPreference='Stop'
$expected='D:\RHRemoteTools\ui-input70-'+$RunId
if([IO.Path]::GetFullPath($EvidenceDirectory).TrimEnd('\') -ne $expected){throw 'Probe evidence escaped its owned directory'}
if(-not (Test-Path -LiteralPath $expected) -or (Test-Path -LiteralPath (Join-Path $expected 'probe-state.json'))){throw 'Use a fresh owned probe directory'}
Add-Type -AssemblyName System.Windows.Forms,System.Drawing,System.Web.Extensions
try{
 Add-Type -ReferencedAssemblies System.Windows.Forms,System.Drawing,System.Web.Extensions -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Web.Script.Serialization;
using System.Windows.Forms;

public sealed class RhSessionInputProbe70 : Form {
 [StructLayout(LayoutKind.Sequential)] struct LastInput {public uint Size,Tick;}
 [StructLayout(LayoutKind.Sequential)] struct Point32 {public int X,Y;}
 [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LastInput value);
 [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);
 [DllImport("user32.dll")] static extern bool IsWindow(IntPtr window);
 [DllImport("user32.dll")] static extern IntPtr WindowFromPoint(Point32 point);
 [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr window,uint flags);
 [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
 [DllImport("user32.dll")] static extern short GetKeyState(int key);
 [DllImport("user32.dll")] static extern int GetSystemMetrics(int metric);
 [DllImport("user32.dll",SetLastError=true)] static extern bool RegisterHotKey(IntPtr window,int id,uint modifiers,uint key);
 [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr window,int id);
 [DllImport("user32.dll",SetLastError=true)] static extern IntPtr OpenInputDesktop(uint flags,bool inherit,uint access);
 [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr desktop);
 [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool GetUserObjectInformationW(IntPtr value,int index,StringBuilder text,int bytes,out int needed);
 readonly string root,run;readonly int duration;readonly Stopwatch clock=new Stopwatch();
 readonly Timer timer=new Timer();readonly JavaScriptSerializer json=new JavaScriptSerializer();
 readonly Dictionary<string,int> counts=new Dictionary<string,int>();readonly Dictionary<string,bool> registrations=new Dictionary<string,bool>();
 readonly List<object> desktops=new List<object>();readonly IntPtr priorForeground;
 readonly List<object> keyMessages=new List<object>();
 readonly string[] hotkeys={"ctrl-alt-left","ctrl-alt-right","ctrl-alt-up","ctrl-alt-down"};
 readonly int[] keys={37,39,38,40};
 readonly Font heading=new Font("Segoe UI",22),body=new Font("Consolas",15);
 readonly ConcurrentQueue<string> pendingEvents=new ConcurrentQueue<string>();
 System.Threading.Thread writer;Exception writerFailure;
 volatile string latestSnapshot,lastCommand="";volatile bool finishWriter;
 bool ended;int mouseDown,mouseUp;string closingReason="",desktopSignature="";
 public static uint IdleMs(){var value=new LastInput{Size=(uint)Marshal.SizeOf(typeof(LastInput))};if(!GetLastInputInfo(ref value))throw new Exception("Could not check input inactivity");return unchecked((uint)Environment.TickCount-value.Tick);}
 protected override bool ShowWithoutActivation {get{return true;}}
 RhSessionInputProbe70(string directory,string id,int seconds){
  root=directory;run=id;duration=seconds;priorForeground=GetForegroundWindow();
  Text="RHRemote input verification "+run;StartPosition=FormStartPosition.Manual;
  AutoScaleMode=AutoScaleMode.None;KeyPreview=true;DoubleBuffered=true;ShowInTaskbar=true;TopMost=true;
  var primary=Screen.PrimaryScreen;if(primary==null)throw new Exception("No existing primary screen");
  Bounds=new Rectangle(primary.WorkingArea.Left+100,primary.WorkingArea.Top+100,800,480);
  timer.Interval=100;
  timer.Tick+=delegate{
   if(clock.Elapsed.TotalSeconds>=duration){closingReason="deadline";Close();return;}
   WriteStatus();
  };
  Shown+=delegate{clock.Start();timer.Start();WriteStatus();writer=new System.Threading.Thread(WriteLoop);writer.IsBackground=true;writer.Name="Owned probe evidence";writer.Start();};
  Activated+=delegate{RegisterKeys();WriteStatus();};Deactivate+=delegate{UnregisterKeys();WriteStatus();};
  FormClosing+=delegate{if(closingReason.Length==0)closingReason="window-close";if(GetForegroundWindow()==Handle&&IsWindow(priorForeground))SetForegroundWindow(priorForeground);};
  FormClosed+=delegate{timer.Stop();UnregisterKeys();ended=true;WriteStatus();finishWriter=true;heading.Dispose();body.Dispose();};
 }
 void RegisterKeys(){UnregisterKeys();for(int i=0;i<keys.Length;i++)registrations[hotkeys[i]]=RegisterHotKey(Handle,i+1,0x4003,(uint)keys[i]);}
 void UnregisterKeys(){if(!IsHandleCreated)return;for(int i=0;i<keys.Length;i++){UnregisterHotKey(Handle,i+1);registrations[hotkeys[i]]=false;}}
 void Event(string name){int value;counts.TryGetValue(name,out value);counts[name]=value+1;pendingEvents.Enqueue(json.Serialize(new{utc=DateTime.UtcNow.ToString("o"),name=name,count=value+1})+"\n");Invalidate();}
 void KeyMessage(string route,int key,int modifiers){
  // Only the fixed test keys in this owned window; never record character input.
  if(keyMessages.Count>=512||!(key==16||key==17||key==18||key==91||key==37||key==38||key==39||key==40||key==65||key==67||key==70||key==83||key==27||key==115))return;
  int threadMask=0;int[] keys={16,17,18,91,92};for(int i=0;i<keys.Length;i++)if((GetKeyState(keys[i])&0x8000)!=0)threadMask|=1<<i;
  keyMessages.Add(new{utc=DateTime.UtcNow.ToString("o"),route=route,key=key,modifiers=modifiers,threadModifiers=threadMask,asyncModifiers=Held()});
 }
 protected override void WndProc(ref Message message){if(message.Msg==0x0100||message.Msg==0x0101||message.Msg==0x0104||message.Msg==0x0105)KeyMessage("win32-"+message.Msg.ToString("x"),message.WParam.ToInt32(),0);if(message.Msg==0x0312){int id=message.WParam.ToInt32();if(id>=1&&id<=4){Event(hotkeys[id-1]);WriteStatus();return;}}base.WndProc(ref message);}
 protected override bool ProcessCmdKey(ref Message message,Keys data){KeyMessage("command-before",(int)(data&Keys.KeyCode),(int)(data&Keys.Modifiers));if(data==(Keys.Alt|Keys.F4)){closingReason="alt-f4";Event("alt-f4");}bool handled=base.ProcessCmdKey(ref message,data);KeyMessage(handled?"command-consumed":"command-passed",(int)(data&Keys.KeyCode),(int)(data&Keys.Modifiers));return handled;}
 protected override void OnMouseDown(MouseEventArgs e){mouseDown++;Focus();base.OnMouseDown(e);WriteStatus();}
 protected override void OnMouseUp(MouseEventArgs e){mouseUp++;base.OnMouseUp(e);WriteStatus();}
 protected override void OnKeyDown(KeyEventArgs e){
  KeyMessage("managed-down",(int)e.KeyCode,(int)e.Modifiers);
  string name=null;
  if(e.Control&&!e.Alt&&!e.Shift){if(e.KeyCode==Keys.C)name="ctrl-c";if(e.KeyCode==Keys.A)name="ctrl-a";if(e.KeyCode==Keys.S)name="ctrl-s";if(e.KeyCode==Keys.F)name="ctrl-f";}
  if(e.KeyCode==Keys.Escape)name="escape";
  if(name!=null){Event(name);e.Handled=true;e.SuppressKeyPress=true;}
  base.OnKeyDown(e);WriteStatus();
 }
 object Desktop(){
  var desktop=OpenInputDesktop(0,false,1);string name="";int error=0;
  if(desktop==IntPtr.Zero)error=Marshal.GetLastWin32Error();
  else{try{int needed;var text=new StringBuilder(256);if(GetUserObjectInformationW(desktop,2,text,text.Capacity*2,out needed))name=text.ToString();else error=Marshal.GetLastWin32Error();}finally{CloseDesktop(desktop);}}
  string signature=name+":"+error;
  if(signature!=desktopSignature){desktopSignature=signature;if(desktops.Count<32)desktops.Add(new{utc=DateTime.UtcNow.ToString("o"),name=name,error=error});}
  return new{name=name,error=error,accessible=desktop!=IntPtr.Zero};
 }
 int Held(){int mask=0;int[] modifiers={16,17,18,91,92};for(int i=0;i<modifiers.Length;i++)if((GetAsyncKeyState(modifiers[i])&0x8000)!=0)mask|=1<<i;return mask;}
 void ReadCommand(){
  string file=Path.Combine(root,"probe-command.json");if(!File.Exists(file))return;
  try{var value=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(File.ReadAllText(file,Encoding.UTF8));if((string)value["run"]!=run)return;string id=(string)value["id"];if(id==lastCommand)return;lastCommand=id;
   if((string)value["command"]=="stop")BeginInvoke((Action)delegate{if(!ended){closingReason="command";Close();}});
  }catch(IOException){}catch(ArgumentException){}
 }
 void WriteStatus(){
  if(!IsHandleCreated)return;var screen=Screen.FromHandle(Handle);var hit=WindowFromPoint(new Point32{X=Left+120,Y=Top+140});
  var state=new{ok=true,run=run,utc=DateTime.UtcNow.ToString("o"),pid=Process.GetCurrentProcess().Id,sessionId=Process.GetCurrentProcess().SessionId,hwnd=Handle.ToInt64().ToString(),ended=ended,closingReason=closingReason,elapsedMs=clock.ElapsedMilliseconds,
   foregroundOwn=GetForegroundWindow()==Handle,clickTargetOwn=hit==Handle||GetAncestor(hit,2)==Handle,bounds=new{x=Left,y=Top,width=Width,height=Height},monitor=screen.DeviceName,
   screens=Array.ConvertAll(Screen.AllScreens,s=>new{device=s.DeviceName,primary=s.Primary,x=s.Bounds.X,y=s.Bounds.Y,width=s.Bounds.Width,height=s.Bounds.Height}),
   desktop=Desktop(),desktopTransitions=desktops,heldModifiers=Held(),registeredHotkeys=registrations,events=counts,keyMessages=keyMessages,mouseDown=mouseDown,mouseUp=mouseUp,swapButtons=GetSystemMetrics(23),lastCommand=lastCommand,evidenceIoOffUiThread=true};
  latestSnapshot=json.Serialize(state);
 }
 // Observing the window must not delay its input callback or stop command.
 // Coalesce immutable snapshots; preserve every named event in a separate queue.
 void WriteLoop(){
  string written=null,target=Path.Combine(root,"probe-state.json");
  try{for(;;){
   string snapshot=latestSnapshot;
   if(snapshot!=null&&!Object.ReferenceEquals(snapshot,written)){
    byte[] payload=new UTF8Encoding(false).GetBytes(snapshot);
    using(var file=new FileStream(target,FileMode.OpenOrCreate,FileAccess.Write,FileShare.ReadWrite)){file.SetLength(0);file.Write(payload,0,payload.Length);}
    written=snapshot;
   }
   string row;while(pendingEvents.TryDequeue(out row))File.AppendAllText(Path.Combine(root,"probe-events.jsonl"),row,Encoding.UTF8);
   if(finishWriter&&Object.ReferenceEquals(written,latestSnapshot)&&pendingEvents.IsEmpty)return;
   if(!finishWriter)ReadCommand();System.Threading.Thread.Sleep(30);
  }}catch(Exception failure){writerFailure=failure;try{BeginInvoke((Action)delegate{if(!ended){closingReason="evidence-error";Close();}});}catch(InvalidOperationException){}}
 }
 void DrainWriter(){
  if(writer!=null&&!writer.Join(5000))throw new IOException("Owned evidence writer did not finish; results are unconfirmed");
  if(writerFailure!=null)throw new IOException("Owned evidence writer failed; results are unconfirmed",writerFailure);
 }
 protected override void OnPaint(PaintEventArgs e){e.Graphics.Clear(Color.FromArgb(242,245,252));e.Graphics.DrawString("RHRemote input verification",heading,Brushes.MidnightBlue,30,40);e.Graphics.DrawString(run,body,Brushes.DimGray,30,100);e.Graphics.DrawString("Monitor: "+Screen.FromHandle(Handle).DeviceName,body,Brushes.RoyalBlue,30,155);e.Graphics.DrawString("Only this test window records named shortcuts.",body,Brushes.DimGray,30,210);e.Graphics.DrawString("Closes automatically in "+duration+" seconds.",body,Brushes.DimGray,30,255);base.OnPaint(e);}
 public static void Run(string root,string run,int seconds){
  var old=SetThreadDpiAwarenessContext(new IntPtr(-4));try{
   if(IdleMs()<10000)throw new Exception("Console input was active in the last 10 seconds; probe not opened");
   if(Screen.AllScreens.Length!=2)throw new Exception("The two existing screens are required");
   Application.EnableVisualStyles();using(var form=new RhSessionInputProbe70(root,run,seconds)){Application.Run(form);form.DrainWriter();}
  }finally{SetThreadDpiAwarenessContext(old);}
 }
}
'@
 [RhSessionInputProbe70]::Run($expected,$RunId,$Seconds)
 $finalFile=Join-Path $expected 'probe-state.json'
 if(Test-Path -LiteralPath $finalFile){
  $final=Get-Content -LiteralPath $finalFile -Raw -Encoding UTF8 | ConvertFrom-Json
  if($final.run -eq $RunId -and $final.pid -eq $PID){$final.ended=$true;[IO.File]::WriteAllText($finalFile,($final | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))}
 }
}catch{
 $errorProof=@{ok=$false;run=$RunId;pid=$PID;ended=$true;error=$_.Exception.Message}
 [IO.File]::WriteAllText((Join-Path $expected 'probe-state.json'),($errorProof | ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
 throw
}
