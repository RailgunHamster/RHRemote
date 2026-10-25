// Bounded, loopback-only real-movie benchmark transport and owned browser lifetime.
// No browser debugging port, existing browser profile, screen/audio recording or URL ACL changes.
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public sealed class RhMoviePacket {
    public string Json; public long ReceivedQpc; public long Sequence;
}

public sealed class RhMovieServer : IDisposable {
    readonly TcpListener listener;
    readonly Thread worker;
    readonly string prefix, movie;
    readonly byte[] html;
    readonly object gate = new object();
    readonly StreamWriter records;
    readonly ConcurrentDictionary<int,TcpClient> clients = new ConcurrentDictionary<int,TcpClient>();
    volatile bool stopping;
    int nextClient;
    RhMoviePacket latest;
    public volatile bool StopRequested;
    public readonly int Port;
    public readonly string Token;
    public RhMovieServer(string moviePath, string page, string outputPath) {
        if (!File.Exists(moviePath)) throw new FileNotFoundException("Real movie is absent");
        movie = moviePath; Token = Guid.NewGuid().ToString("N"); prefix = "/" + Token + "/";
        html = Encoding.UTF8.GetBytes(page.Replace("__RH_MOVIE_NONCE__",Token));
        records = new StreamWriter(new FileStream(outputPath,FileMode.CreateNew,FileAccess.Write,FileShare.Read),new UTF8Encoding(false));
        listener = new TcpListener(IPAddress.Loopback,0);
        try { listener.Start(16); Port = ((IPEndPoint)listener.LocalEndpoint).Port; }
        catch { records.Dispose(); throw; }
        worker = new Thread(Accept); worker.IsBackground = true; worker.Start();
    }
    public string Url { get { return "http://127.0.0.1:" + Port + prefix + "index.html"; } }
    public RhMoviePacket Latest() { lock(gate) { return latest == null ? null : new RhMoviePacket {Json=latest.Json,ReceivedQpc=latest.ReceivedQpc,Sequence=latest.Sequence}; } }
    static string Quote(string value) {
        var b = new StringBuilder("\"");
        foreach(char c in value) {
            if(c=='"'||c=='\\') { b.Append('\\'); b.Append(c); }
            else if(c<32) b.Append("\\u"+((int)c).ToString("x4",CultureInfo.InvariantCulture));
            else b.Append(c);
        }
        return b.Append('"').ToString();
    }
    void Accept() {
        while(!stopping) {
            try {
                TcpClient client = listener.AcceptTcpClient(); int id = Interlocked.Increment(ref nextClient);
                clients[id]=client;
                ThreadPool.QueueUserWorkItem(delegate { Serve(id,client); });
            } catch(SocketException) { if(!stopping) StopRequested=true; }
              catch(ObjectDisposedException) { break; }
        }
    }
    static void Header(Stream s,int code,string type,long length,string extra) {
        string reason = code==200?"OK":code==206?"Partial Content":code==400?"Bad Request":code==404?"Not Found":code==411?"Length Required":code==413?"Content Too Large":code==416?"Range Not Satisfiable":"Method Not Allowed";
        string h="HTTP/1.1 "+code+" "+reason+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Type: "+type+"\r\nContent-Length: "+length.ToString(CultureInfo.InvariantCulture)+"\r\n"+extra+"\r\n";
        byte[] bytes=Encoding.ASCII.GetBytes(h); s.Write(bytes,0,bytes.Length); s.Flush();
    }
    // One byte range, including a suffix and clipping an explicit end to EOF.
    public static bool Range(string text,long size,out long begin,out long end) {
        begin=0;end=size-1;if(size<=0)return false;if(text==null)return true;
        if(!text.StartsWith("bytes=",StringComparison.Ordinal))return false;
        string[] parts=text.Substring(6).Split('-');
        if(parts.Length!=2)return false;
        if(parts[0].Length==0) {
            long suffix;if(!long.TryParse(parts[1],NumberStyles.None,CultureInfo.InvariantCulture,out suffix)||suffix<=0)return false;
            begin=Math.Max(0,size-suffix);return true;
        }
        if(!long.TryParse(parts[0],NumberStyles.None,CultureInfo.InvariantCulture,out begin))return false;
        if(parts[1].Length>0&&!long.TryParse(parts[1],NumberStyles.None,CultureInfo.InvariantCulture,out end))return false;
        end=Math.Min(end,size-1);
        return begin>=0&&begin<size&&end>=begin&&end<size;
    }
    void Serve(int id,TcpClient client) {
        try {
            client.ReceiveTimeout=10000;client.SendTimeout=10000;
            using(var s=new BufferedStream(client.GetStream(),16384)) {
                var input=new List<byte>();int state=0;
                while(input.Count<16384&&state<4) {
                    int c=s.ReadByte();if(c<0)return;input.Add((byte)c);
                    state=c==(state%2==0?13:10)?state+1:(c==13?1:0);
                }
                if(state!=4) { Header(s,400,"text/plain",0,"");return; }
                string[] lines=Encoding.ASCII.GetString(input.ToArray()).Split(new[]{"\r\n"},StringSplitOptions.None);
                string[] first=lines[0].Split(' ');
                if(first.Length!=3||first[2]!="HTTP/1.1") { Header(s,400,"text/plain",0,"");return; }
                string method=first[0],path=first[1];var headers=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
                for(int i=1;i<lines.Length&&lines[i].Length>0;i++) {
                    int colon=lines[i].IndexOf(':');if(colon<=0) { Header(s,400,"text/plain",0,"");return; }
                    string name=lines[i].Substring(0,colon).Trim();if(headers.ContainsKey(name)){Header(s,400,"text/plain",0,"");return;}
                    headers.Add(name,lines[i].Substring(colon+1).Trim());
                }
                if(path==prefix+"telemetry"&&method=="POST") {
                    string lengthText;long length;
                    if(headers.ContainsKey("Transfer-Encoding")) { Header(s,400,"text/plain",0,"");return; }
                    if(!headers.TryGetValue("Content-Length",out lengthText)||!long.TryParse(lengthText,NumberStyles.None,CultureInfo.InvariantCulture,out length)) {Header(s,411,"text/plain",0,"");return;}
                    if(length<2||length>1048576){Header(s,413,"text/plain",0,"");return;}
                    byte[] body=new byte[(int)length];int used=0;
                    while(used<body.Length){int count=s.Read(body,used,body.Length-used);if(count==0)return;used+=count;}
                    string json=new UTF8Encoding(false,true).GetString(body);long qpc=Stopwatch.GetTimestamp();
                    lock(gate) {
                        long seq=latest==null?1:latest.Sequence+1;
                        records.WriteLine("{\"receivedQpc\":"+qpc+",\"sequence\":"+seq+",\"json\":"+Quote(json)+"}");records.Flush();
                        latest=new RhMoviePacket {Json=json,ReceivedQpc=qpc,Sequence=seq};
                    }
                    byte[] answer=Encoding.ASCII.GetBytes(StopRequested?"{\"stop\":true}":"{\"stop\":false}");
                    Header(s,200,"application/json",answer.Length,"");s.Write(answer,0,answer.Length);return;
                }
                if(method!="GET"&&method!="HEAD"){Header(s,405,"text/plain",0,"");return;}
                if(path==prefix+"index.html") {
                    Header(s,200,"text/html; charset=utf-8",html.Length,"Content-Security-Policy: default-src 'none'; media-src 'self'; connect-src 'self'; script-src 'nonce-"+Token+"'; style-src 'nonce-"+Token+"'\r\n");
                    if(method!="HEAD")s.Write(html,0,html.Length);return;
                }
                if(path!=prefix+"asset.mp4"){Header(s,404,"text/plain",0,"");return;}
                long size=new FileInfo(movie).Length,begin,end;string range;headers.TryGetValue("Range",out range);
                if(!Range(range,size,out begin,out end)){Header(s,416,"video/mp4",0,"Content-Range: bytes */"+size+"\r\n");return;}
                long remaining=end-begin+1;
                Header(s,range==null?200:206,"video/mp4",remaining,"Accept-Ranges: bytes\r\n"+(range==null?"":"Content-Range: bytes "+begin+"-"+end+"/"+size+"\r\n"));
                if(method=="HEAD")return;
                using(var source=new FileStream(movie,FileMode.Open,FileAccess.Read,FileShare.Read)) {
                    source.Position=begin;byte[] buffer=new byte[65536];
                    while(remaining>0&&!stopping){int count=source.Read(buffer,0,(int)Math.Min(buffer.Length,remaining));if(count==0)throw new EndOfStreamException();s.Write(buffer,0,count);remaining-=count;}
                }
            }
        } catch(IOException) { } catch(SocketException) { } catch(ObjectDisposedException) { }
          catch(Exception) { StopRequested=true; }
        finally { TcpClient ignored;clients.TryRemove(id,out ignored);client.Close(); }
    }
    public void Dispose() {
        stopping=true;listener.Stop();foreach(var client in clients.Values)client.Close();worker.Join(2000);
        lock(gate){records.Dispose();}
    }
}

public sealed class RhMovieBrowser : IDisposable {
    [StructLayout(LayoutKind.Sequential)] struct BasicLimit { public long processTime,jobTime;public uint flags;public UIntPtr minimum,maximum;public uint active;public UIntPtr affinity;public uint priority,scheduling; }
    [StructLayout(LayoutKind.Sequential)] struct Io { public ulong readOps,writeOps,otherOps,readBytes,writeBytes,otherBytes; }
    [StructLayout(LayoutKind.Sequential)] struct ExtendedLimit { public BasicLimit basic;public Io io;public UIntPtr processMemory,jobMemory,peakProcess,peakJob; }
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct Startup { public int cb;public string reserved,desktop,title;public int x,y,w,h,cx,cy,fill,flags;public short show,reservedSize;public IntPtr reservedData,input,output,error; }
    [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {public IntPtr process,thread;public uint pid,tid;}
    [StructLayout(LayoutKind.Sequential)] struct Rect { public int left,top,right,bottom; }
    delegate bool EnumProc(IntPtr window,IntPtr value);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attributes,string name);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int type,ref ExtendedLimit value,int length);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool member);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateProcess(string application,StringBuilder command,IntPtr processAttributes,IntPtr threadAttributes,bool inherit,uint flags,IntPtr environment,string directory,ref Startup startup,out ProcessInfo process);
    [DllImport("kernel32.dll",SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process,uint exitCode);
    [DllImport("kernel32.dll")] static extern bool TerminateJobObject(IntPtr job,uint exitCode);
    [DllImport("kernel32.dll")] static extern bool QueryInformationJobObject(IntPtr job,int type,IntPtr information,int size,IntPtr returned);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access,bool inherit,uint pid);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc callback,IntPtr value);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window,out uint pid);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr window);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr window,out Rect rect);
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr window,out Rect rect);
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int w,int h,uint flags);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr window,uint message,IntPtr wparam,IntPtr lparam);
    [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
    IntPtr job,window;
    public readonly uint RootPid;
    public bool CleanupComplete { get; private set; }
    public RhMovieBrowser(string executable,string arguments,string directory) {
        if(IntPtr.Size!=8)throw new PlatformNotSupportedException("The movie benchmark requires a 64-bit host");
        job=CreateJobObject(IntPtr.Zero,null);if(job==IntPtr.Zero)throw new Win32Exception();
        var limits=new ExtendedLimit();limits.basic.flags=0x2000; // KILL_ON_JOB_CLOSE, no breakaway.
        if(!SetInformationJobObject(job,9,ref limits,Marshal.SizeOf(typeof(ExtendedLimit)))){int error=Marshal.GetLastWin32Error();Dispose();throw new Win32Exception(error);}
        var startup=new Startup();startup.cb=Marshal.SizeOf(typeof(Startup));ProcessInfo info;
        if(!CreateProcess(executable,new StringBuilder("\""+executable+"\" "+arguments),IntPtr.Zero,IntPtr.Zero,false,4|0x08000000,IntPtr.Zero,directory,ref startup,out info)){int error=Marshal.GetLastWin32Error();Dispose();throw new Win32Exception(error);}
        RootPid=info.pid;
        try {
            if(!AssignProcessToJobObject(job,info.process)) { int error=Marshal.GetLastWin32Error();TerminateProcess(info.process,1);throw new Win32Exception(error,"Owned browser job assignment failed"); }
            if(ResumeThread(info.thread)==uint.MaxValue)throw new Win32Exception();
        } catch {Dispose();throw;}
        finally {CloseHandle(info.thread);CloseHandle(info.process);}
    }
    bool Owned(uint pid) {
        IntPtr handle=OpenProcess(0x1000,false,pid);if(handle==IntPtr.Zero)return false;
        try {bool member;return IsProcessInJob(handle,job,out member)&&member;}finally{CloseHandle(handle);}
    }
    public double CpuSeconds() {
        IntPtr data=Marshal.AllocHGlobal(48);
        try {
            if(job==IntPtr.Zero||!QueryInformationJobObject(job,1,data,48,IntPtr.Zero))throw new Win32Exception();
            return (Marshal.ReadInt64(data,0)+Marshal.ReadInt64(data,8))/10000000.0;
        } finally {Marshal.FreeHGlobal(data);}
    }
    public uint[] Members() {
        const int size=65536;IntPtr data=Marshal.AllocHGlobal(size);
        try {
            if(job==IntPtr.Zero||!QueryInformationJobObject(job,3,data,size,IntPtr.Zero))throw new Win32Exception();
            int count=Marshal.ReadInt32(data,4);
            if(count<0||count>(size-8)/8)throw new InvalidOperationException("Owned job process list exceeds its bound");
            var result=new uint[count];for(int i=0;i<count;i++)result[i]=checked((uint)Marshal.ReadInt64(data,8+i*8));return result;
        } finally {Marshal.FreeHGlobal(data);}
    }
    public int[] ReadWindow(bool place,int x,int y,int w,int h) {
        IntPtr previous=SetThreadDpiAwarenessContext(new IntPtr(-4));if(previous==IntPtr.Zero)throw new Win32Exception();
        try {
            var found=new List<IntPtr>();EnumWindows(delegate(IntPtr handle,IntPtr ignored){uint pid;GetWindowThreadProcessId(handle,out pid);if(IsWindowVisible(handle)&&Owned(pid))found.Add(handle);return true;},IntPtr.Zero);
            if(found.Count==0)return null;if(found.Count!=1)throw new InvalidOperationException("Owned browser has ambiguous visible windows");
            window=found[0];if(IsIconic(window))throw new InvalidOperationException("Owned source is minimized");
            if(place&&!SetWindowPos(window,new IntPtr(-1),x,y,w,h,0x10|0x40))throw new Win32Exception();
            Rect outer,client;if(!GetWindowRect(window,out outer)||!GetClientRect(window,out client))throw new Win32Exception();
            uint owner;GetWindowThreadProcessId(window,out owner);
            return new[]{outer.left,outer.top,outer.right-outer.left,outer.bottom-outer.top,client.right-client.left,client.bottom-client.top,(int)owner};
        } finally {SetThreadDpiAwarenessContext(previous);}
    }
    public void Dispose() {
        if(job==IntPtr.Zero)return;
        if(window!=IntPtr.Zero)PostMessage(window,0x10,IntPtr.Zero,IntPtr.Zero);
        // The job owns only the suspended-created browser and its descendants.
        // Closing it also covers parent failure and avoids killing any existing browser.
        TerminateJobObject(job,0);
        IntPtr accounting=Marshal.AllocHGlobal(48);
        try {
            var deadline=Stopwatch.StartNew();
            do {
                if(!QueryInformationJobObject(job,1,accounting,48,IntPtr.Zero))break;
                if(Marshal.ReadInt32(accounting,40)==0){CleanupComplete=true;break;}
                Thread.Sleep(50);
            } while(deadline.ElapsedMilliseconds<5000);
        } finally {Marshal.FreeHGlobal(accounting);CloseHandle(job);job=IntPtr.Zero;}
    }
}
