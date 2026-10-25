package com.limelight.rhremote;

import android.content.Context;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.binding.PlatformBinding;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** UUID preflight only. Streaming still verifies the paired Sunshine certificate. */
public final class RhNetwork {
    /** Exhausted transient network paths, distinct from identity/configuration errors. */
    static final class Unavailable extends IOException {Unavailable(String message){super(message);}}
    static boolean unavailable(Throwable failure){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof javax.net.ssl.SSLException||cause instanceof java.security.cert.CertificateException)return false;
        return failure instanceof Unavailable||failure instanceof SocketTimeoutException||failure instanceof SocketException||failure instanceof UnknownHostException;
    }
    private static boolean unavailable(JSONArray attempts){
        boolean tried=false;
        for(int i=0;i<attempts.length();i++){JSONObject row=attempts.optJSONObject(i);if(row.optBoolean("deferred"))continue;tried=true;if(!row.optBoolean("retryable"))return false;}
        return tried;
    }
    private static volatile JSONObject last=new JSONObject();
    private static final java.util.concurrent.ConcurrentHashMap<String,Long> LEARNED=new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService PROBES=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),new ThreadPoolExecutor.AbortPolicy());
    public static JSONObject state(){try{return new JSONObject(last.toString());}catch(JSONException e){return new JSONObject();}}
    public static void validate(String value)throws IOException {
        if(value.matches("rhr:[0-9a-fA-F]{64}"))return;
        if(value.isEmpty()||value.length()>253||!value.equals(value.trim())||value.matches(".*[\\s/\\\\@?#%].*"))throw new IOException("请输入 IP 或主机名，不包含协议、端口或路径");
        try {URI uri=new URI("http://"+(value.contains(":")&&!value.startsWith("[")?"["+value+"]":value));if(uri.getHost()==null||uri.getPort()!=-1||uri.getRawUserInfo()!=null)throw new IOException("IP 或主机名无效");}
        catch(URISyntaxException e){throw new IOException("IP 或主机名无效",e);}
    }
    public static String kind(String address){
        if(address.matches("rhr:[0-9a-fA-F]{64}"))return "transport";
        String s=address.replace("[","").replace("]","").toLowerCase(Locale.ROOT);
        if(s.startsWith("fd7a:115c:a1e0:"))return "tailscale";
        if(s.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))try{String[] p=s.split("\\.");int a=Integer.parseInt(p[0]),b=Integer.parseInt(p[1]);if(a==100&&b>=64&&b<=127)return "tailscale";if(a==10||a==127||a==192&&b==168||a==172&&b>=16&&b<=31||a==169&&b==254)return "lan";return "internet";}catch(NumberFormatException ignored){}
        if(s.equals("::1")||s.startsWith("fd")&&s.contains(":")||s.startsWith("fe80:"))return "lan";
        return s.contains(":")?"internet":"auto";
    }
    public static List<String> addresses(JSONObject host)throws Exception {
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();
        String primary=host.getString("address");validate(primary);unique.put(primary.toLowerCase(Locale.ROOT),primary);
        JSONArray extra=host.optJSONArray("addresses");if(extra!=null)for(int i=0;i<extra.length();i++){String a=extra.getString(i);validate(a);unique.putIfAbsent(a.toLowerCase(Locale.ROOT),a);}
        if(unique.size()>8)throw new IOException("每台设备最多保存 8 个地址");return new ArrayList<>(unique.values());
    }
    public static JSONObject host(Context c,String uuid)throws Exception {
        JSONArray list=RhShared.read(c).getJSONArray("devices");for(int i=0;i<list.length();i++)if(uuid.equals(list.getJSONObject(i).getString("uuid")))return list.getJSONObject(i);throw new IOException("未找到设备连接地址");
    }
    public static JSONObject metadata(Context c,ComputerDetails source)throws Exception {
        try{return host(c,source.uuid);}catch(IOException e){ComputerDetails.AddressTuple a=source.manualAddress!=null?source.manualAddress:source.activeAddress!=null?source.activeAddress:source.localAddress;if(a==null)throw e;return RhActions.obj("address",a.address);}
    }
    /** Extra direct addresses for the normal UUID/certificate-checked discovery poll.
     * Never opens the shared media tunnel or treats a relay identity as a hostname. */
    public static List<ComputerDetails.AddressTuple> pollingAddresses(Context c,ComputerDetails source) {
        List<ComputerDetails.AddressTuple> result=new ArrayList<>();
        if(source.uuid==null)return result;
        try {
            for(String address:addresses(host(c,source.uuid))) {
                if(kind(address).equals("transport"))continue;
                String host=address.replace("[","").replace("]","");
                if(host.startsWith("127.")||host.equals("::1"))continue;
                result.add(new ComputerDetails.AddressTuple(host,47989));
            }
        }catch(Exception ignored){/* Discovery still has its original address tuples. */}
        return result;
    }
    static boolean connectInsteadOfPair(ComputerDetails source) {
        return source.pairState==PairingManager.PairState.PAIRED ||
            source.pairState==null&&source.serverCert!=null;
    }
    /** Refresh transient details lost on process restart only at connection admission.
     * A stored certificate must be verified over HTTPS, without HTTP downgrade. */
    static ComputerDetails refreshConnectionDetails(Context context,ComputerDetails source)throws Exception {
        return refreshConnectionDetails(context,source,null);
    }
    static ComputerDetails refreshConnectionDetails(Context context,ComputerDetails source,RhCancellation operation)throws Exception {
        checkpoint(operation);
        if(source.pairState!=null&&source.httpsPort!=0)return source;
        ComputerDetails.AddressTuple address=source.activeAddress;
        if(address==null)throw new IOException("未选择连接地址");
        NvHTTP http=new NvHTTP(address,source.httpsPort,"0123456789ABCDEF",source.serverCert,PlatformBinding.getCryptoProvider(context),true);
        try(RhCancellation.Watch request=operation==null?null:operation.watch(http::cancelRhRequests)){
        checkpoint(operation);
        ComputerDetails fresh=http.getRhConnectionDetails();
        checkpoint(operation);
        if(fresh.uuid==null||!source.uuid.equalsIgnoreCase(fresh.uuid))throw new IOException("uuid_mismatch");
        ComputerDetails result=new ComputerDetails(source);result.update(fresh);
        result.activeAddress=address;result.manualAddress=address;
        return result;
        }
    }
    private static String check(String address,String uuid)throws Exception {return check(address,uuid,47989);}
    private static String check(String address,String uuid,int port)throws Exception {
        return check(address,uuid,port,null);
    }
    private static void checkpoint(RhCancellation operation)throws IOException {if(operation!=null)operation.checkpoint();}
    private static String check(String address,String uuid,int port,RhCancellation operation)throws Exception {
        return check(address,uuid,port,operation,1000);
    }
    /** WAN probes allow TCP retransmission and a delayed response; LAN keeps its short budget. */
    static int probeTimeoutMs(String route){return route.equals("lan")?1000:8000;}
    static final class Probe extends RhCancellation {void cancel(){cancelRequests();}}
    static String check(String address,String uuid,int port,RhCancellation operation,int timeoutMs)throws Exception {
        checkpoint(operation);
        String host=address.replace("[","").replace("]","");HttpURLConnection connection=(HttpURLConnection)new URI("http",null,host,port,"/serverinfo","uniqueid=0123456789ABCDEF",null).toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(timeoutMs);connection.setReadTimeout(timeoutMs);
        try(RhCancellation.Watch request=operation==null?null:operation.watch(connection::disconnect)){
            checkpoint(operation);
            try(InputStream in=connection.getInputStream()){
            checkpoint(operation);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new IOException("响应过大");out.write(b,0,n);}
            org.xmlpull.v1.XmlPullParser xml=android.util.Xml.newPullParser();xml.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);xml.setInput(new StringReader(out.toString("UTF-8")));
            while(xml.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)if(xml.getEventType()==org.xmlpull.v1.XmlPullParser.START_TAG&&xml.getName().equals("uniqueid")){if(!uuid.equalsIgnoreCase(xml.nextText()))throw new IOException("uuid_mismatch");return address;}
            throw new IOException("uuid_missing");
            }
        }finally{connection.disconnect();}
    }
    public static ComputerDetails resolve(Context c,ComputerDetails source,RhProfile profile)throws Exception {
        return resolve(c,source,profile,null);
    }
    public static ComputerDetails resolve(Context c,ComputerDetails source,RhProfile profile,JSONObject options)throws Exception {
        return resolve(c,source,profile,options,null);
    }
    public static ComputerDetails resolve(Context c,ComputerDetails source,RhProfile profile,JSONObject options,RhCancellation operation)throws Exception {
        checkpoint(operation);
        RhStatistics.event("network_resolve_begin",-1,RhActions.obj("routeOrder",new JSONArray(profile.routeOrder)));
        long began=System.nanoTime();JSONObject metadata=metadata(c,source);
        if(options!=null){metadata=new JSONObject(metadata.toString());if(options.has("address"))metadata.put("address",options.getString("address"));if(options.has("addresses"))metadata.put("addresses",options.getJSONArray("addresses"));}
        List<String> candidates=addresses(metadata);candidates.removeIf(a->!profile.networkMode.equals("auto")&&!kind(a).equals("auto")&&!kind(a).equals(profile.networkMode)&&!(profile.networkMode.equals("internet")&&kind(a).equals("transport")));
        JSONArray failed=options==null?null:options.optJSONArray("failedRoutes");
        JSONArray attempts=new JSONArray();String selected=null,selectedRemote="",chosenRoute="",chosenPath="unknown";int offset=0;
        String retainedRelay="";
        try{for(String route:profile.routeOrder) {
            checkpoint(operation);
            if(route.equals("punch_direct")||route.equals("relay")) {
                for(String address:candidates)if(kind(address).equals("transport")) {
                    if(RhRecoveryPolicy.excluded(failed,route,address))continue;
                    boolean reuse=route.equals("relay")&&address.equals(retainedRelay);
                    if(!retainedRelay.isEmpty()&&!reuse){RhTunnel.stop();retainedRelay="";}
                    long at=System.nanoTime();boolean accepted=false,retain=false;
                    try {
                        // The automatic punch attempt may already have a working relay.
                        // Reuse its non-forced endpoint only when the ordered relay route
                        // gets its turn. Explicit relay-only connections remain forced.
                        JSONObject tunnel=RhTunnel.start(c,address.substring(4),profile.relayUrl,route.equals("relay")&&!reuse,operation);
                        if(route.equals("punch_direct")&&!tunnel.optString("path").equals("direct")){
                            retain=tunnel.optString("path").equals("relay")&&profile.routeOrder.indexOf("relay")>profile.routeOrder.indexOf(route)&&!RhRecoveryPolicy.excluded(failed,"relay",address);
                            if(retain)retainedRelay=address;
                            throw new IOException(retain?"打洞未形成直连，已保留中继通道等待回退":"打洞未形成直连，继续尝试后续路径");
                        }
                        offset=tunnel.getInt("portOffset");check(tunnel.getString("address"),source.uuid,47989+offset,operation,probeTimeoutMs(route));
                        selected=tunnel.getString("address");selectedRemote=address;chosenPath=tunnel.optString("path","unknown");chosenRoute=route;
                        attempts.put(RhActions.obj("address",address,"route",route,"ok",true,"reusedTunnel",tunnel.optBoolean("reused"),"timeoutMs",probeTimeoutMs(route),"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)));accepted=true;retainedRelay="";break;
                    }catch(Exception|LinkageError e){checkpoint(operation);attempts.put(RhActions.obj("address",address,"route",route,"ok",false,"deferred",retain,"retryable",unavailable(e),"timeoutMs",probeTimeoutMs(route),"error",e.getMessage(),"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)));}
                    finally{if(!accepted&&!retain){RhTunnel.stop();if(reuse)retainedRelay="";}}
                }
                if(selected!=null)break;continue;
            }
            // Another Android app's VPN path cannot be inferred from its IP.
            // Tailscale candidates therefore belong to the explicit unknown bucket.
            List<String> group=new ArrayList<>();for(String address:candidates){if(RhRecoveryPolicy.excluded(failed,route,address))continue;String kind=kind(address);if(route.equals("lan")&&(kind.equals("lan")||kind.equals("auto"))||route.equals("public_direct")&&kind.equals("internet")||route.equals("tailscale_relay")&&kind.equals("tailscale"))group.add(address);}
            if(group.isEmpty())continue;
            // An intervening usable route has priority over the retained relay.
            // Dispose before its probes, so a rejected tunnel cannot survive into it.
            if(!retainedRelay.isEmpty()){RhTunnel.stop();retainedRelay="";}
            int timeoutMs=probeTimeoutMs(route);
            CompletionService<JSONObject> queue=new ExecutorCompletionService<>(PROBES);List<Future<JSONObject>> tasks=new ArrayList<>();List<Probe> probes=new ArrayList<>();for(int i=0;i<group.size();i++)probes.add(new Probe());
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(2L*timeoutMs+200),grace=end;long best=Long.MAX_VALUE;
            try(RhCancellation.Watch request=operation==null?null:operation.watch(()->{for(Probe probe:probes)probe.cancel();})) {
                for(int i=0;i<group.size();i++){String address=group.get(i);Probe probe=probes.get(i);tasks.add(queue.submit(()->{long at=System.nanoTime();try{check(address,source.uuid,47989,probe,timeoutMs);return RhActions.obj("address",address,"route",route,"ok",true,"timeoutMs",timeoutMs,"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at));}catch(Exception e){return RhActions.obj("address",address,"route",route,"ok",false,"retryable",unavailable(e),"timeoutMs",timeoutMs,"error",e.getMessage(),"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at));}}));}
                for(int remaining=tasks.size();remaining>0;){checkpoint(operation);long wait=Math.min(end,grace)-System.nanoTime();if(wait<=0)break;Future<JSONObject> future=queue.poll(Math.min(wait,TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS);if(future==null)continue;remaining--;JSONObject result=future.get();attempts.put(result);if(result.optBoolean("ok")&&result.getLong("elapsedMs")<best){best=result.getLong("elapsedMs");selected=result.getString("address");grace=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(50);}}
                if(selected==null)for(String address:group){boolean recorded=false;for(int i=0;i<attempts.length();i++){JSONObject row=attempts.getJSONObject(i);if(route.equals(row.optString("route"))&&address.equals(row.optString("address")))recorded=true;}if(!recorded)attempts.put(RhActions.obj("address",address,"route",route,"ok",false,"retryable",true,"error","probe_deadline","timeoutMs",timeoutMs));}
            }finally{for(Probe probe:probes)probe.cancel();for(Future<?> future:tasks)future.cancel(true);}
            if(selected!=null){selectedRemote=selected;chosenRoute=route;chosenPath=route.equals("tailscale_relay")?"unknown":"direct";offset=0;break;}
        }}finally{if(!retainedRelay.isEmpty())RhTunnel.stop();}
        checkpoint(operation);last=RhActions.obj("uuid",source.uuid,"selectedAddress",selected==null?JSONObject.NULL:selected,"remoteAddress",selectedRemote,"attempts",attempts,"route",chosenRoute,"routeOrder",new JSONArray(profile.routeOrder),"path",chosenPath,"portOffset",offset,"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began),"mediaUdp","not_tested");
        RhStatistics.event(selected==null?"network_resolve_failed":"network_resolve_end",-1,last);
        if(selected==null){
            if(unavailable(attempts))throw new Unavailable("连接路径暂时不可达");
            throw new IOException("已启用的连接路径均不可达或 UUID 不符；请运行网络诊断");
        }
        ComputerDetails resolved=new ComputerDetails(source);resolved.activeAddress=new ComputerDetails.AddressTuple(selected.replace("[","").replace("]",""),47989+offset);resolved.manualAddress=resolved.activeAddress;resolved.state=ComputerDetails.State.ONLINE;
        if(options==null&&!selected.equals("127.77.0.2")&&source.serverCert!=null)learn(c.getApplicationContext(),source,selected);
        return resolved;
    }
    public static void learn(Context context,ComputerDetails source,String address){
        if(source.uuid==null||source.serverCert==null||address.equals("127.77.0.2"))return;
        long now=System.nanoTime();boolean[] admitted={false};
        LEARNED.compute(source.uuid,(key,previous)->{if(previous!=null&&now-previous<TimeUnit.SECONDS.toNanos(30))return previous;admitted[0]=true;return now;});
        if(!admitted[0])return;
        try{PROBES.submit(()->{try{
            JSONObject response=RhHost.request(context,address,source.serverCert.getEncoded(),"/v1/network",null).getJSONObject("result");
            RhShared.rememberNetwork(context,source,response);
        }catch(Exception|LinkageError ignored){}});}catch(RejectedExecutionException ignored){LEARNED.remove(source.uuid,now);}
    }
    public static JSONObject diagnose(Context c,ComputerDetails source,RhProfile profile)throws Exception {return diagnose(c,source,profile,null);}
    public static JSONObject diagnose(Context c,ComputerDetails source,RhProfile profile,JSONObject options)throws Exception {
        ComputerDetails resolved=resolve(c,source,profile,options);JSONObject result=state();String address=resolved.activeAddress.address;
        JSONArray ports=new JSONArray();for(int port:new int[]{47984,47989,47995,48010})try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(address,RhTunnel.port(address,port)),500);ports.put(RhActions.obj("port",port,"ok",true));}catch(IOException e){ports.put(RhActions.obj("port",port,"ok",false));}result.put("tcpPorts",ports);
        if(source.serverCert!=null)try {
            JSONObject info=RhHost.request(c,address,source.serverCert.getEncoded(),"/v1/network",null).getJSONObject("result");result.put("host",info);
            JSONObject ticket=RhHost.request(c,address,source.serverCert.getEncoded(),"/v1/network/probe",null).getJSONObject("result");
            int port=ticket.getInt("udpPort");if(port!=47995)throw new IOException("诊断端口无效");byte[] token=ticket.getString("token").getBytes(StandardCharsets.US_ASCII);
            try(DatagramSocket socket=new DatagramSocket()){socket.connect(InetAddress.getByName(address),RhTunnel.port(address,port));socket.setSoTimeout(1200);socket.send(new DatagramPacket(token,token.length));byte[] response=new byte[128];DatagramPacket packet=new DatagramPacket(response,response.length);socket.receive(packet);info.put("mediaUdp",packet.getLength()==token.length&&Arrays.equals(token,Arrays.copyOf(response,packet.getLength()))?"reachable":"invalid_reply");}
            catch(SocketTimeoutException e){info.put("mediaUdp","timeout");}
        }catch(Exception e){result.put("hostError",e.getMessage());}
        last=result;return result;
    }
    public static String summary(JSONObject result){
        StringBuilder out=new StringBuilder("地址：").append(result.optString("selectedAddress","无可用地址")).append("\n");
        JSONObject info=result.optJSONObject("host");String udp=info==null?"not_tested":info.optString("mediaUdp");out.append(udp.equals("reachable")?"诊断 UDP 双向可达（视频待串流确认）":udp.equals("timeout")?"诊断 UDP 超时，检查防火墙 / VPN 访问权限":"视频 UDP 尚未验证");
        JSONArray attempts=result.optJSONArray("attempts");if(attempts!=null)for(int i=0;i<attempts.length();i++){JSONObject row=attempts.optJSONObject(i);out.append('\n').append(row.optString("address")).append("：").append(row.optBoolean("ok")?"UUID 匹配":row.optString("error","失败"));}
        if(result.has("hostError"))out.append('\n').append(result.optString("hostError"));return out.toString();
    }
}
