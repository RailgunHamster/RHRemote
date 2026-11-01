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
    private static String failureKind(Throwable failure){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof LinkageError)return "component";
            if(cause instanceof javax.net.ssl.SSLException||cause instanceof java.security.cert.CertificateException)return "certificate";
        }
        return "uuid_mismatch".equals(failure.getMessage())?"identity":unavailable(failure)?"network":"other";
    }
    /** Presentation only: preserve the existing transient/fatal exception distinction. */
    static IOException resolutionFailure(JSONArray attempts,int excluded){
        if(unavailable(attempts))return new Unavailable("连接路径暂时不可达，请检查网络后重试");
        if(attempts.length()==0)return new IOException(excluded>0?"本轮连接路径已尝试且串流失败，已停止自动恢复；可重新连接":"当前设置中没有可用的连接路径，请检查已保存地址和启用的路径");
        int component=0,identity=0,certificate=0,total=0;
        for(int i=0;i<attempts.length();i++){JSONObject row=attempts.optJSONObject(i);if(row==null||row.optBoolean("deferred"))continue;total++;String kind=row.optString("failureKind");if(kind.equals("component"))component++;if(kind.equals("identity")||row.optString("error").equals("uuid_mismatch"))identity++;if(kind.equals("certificate"))certificate++;}
        if(component==total&&total>0)return new IOException("内置连接组件不可用，请更新应用后重试");
        if(certificate==total&&total>0)return new IOException("连接证书或 TLS 验证失败，请检查设备身份和连接地址");
        if(identity==total&&total>0)return new IOException("设备标识与已保存的设备不符，请检查连接地址");
        List<String> reasons=new ArrayList<>();if(certificate>0)reasons.add("部分路径的证书或 TLS 验证失败");if(identity>0)reasons.add("部分地址的设备标识不符");if(component>0)reasons.add("内置连接组件不可用");
        if(!reasons.isEmpty()){if(component+certificate+identity<total)reasons.add("其他路径也未能连接");return new IOException(android.text.TextUtils.join("；",reasons)+"；请检查地址和网络"+(component>0?"或更新应用":""));}
        return new IOException("连接地址检查失败，请运行网络诊断查看各路径结果");
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
        return host(RhShared.read(c),uuid);
    }
    private static JSONObject host(JSONObject shared,String uuid)throws Exception {
        JSONArray list=shared.getJSONArray("devices");for(int i=0;i<list.length();i++)if(uuid.equals(list.getJSONObject(i).getString("uuid")))return list.getJSONObject(i);throw new IOException("未找到设备连接地址");
    }
    public static JSONObject metadata(Context c,ComputerDetails source)throws Exception {
        return metadata(RhShared.read(c),source);
    }
    static JSONObject metadata(JSONObject shared,ComputerDetails source)throws Exception {
        try{return host(shared,source.uuid);}catch(IOException e){ComputerDetails.AddressTuple a=source.manualAddress!=null?source.manualAddress:source.activeAddress!=null?source.activeAddress:source.localAddress;if(a==null)throw e;return RhActions.obj("address",a.address);}
    }
    public static String savedSummary(Context c,ComputerDetails source){
        try{return savedSummary(RhShared.read(c),source);}catch(Exception ignored){return "尚未保存地址";}
    }
    static String savedSummary(JSONObject shared,ComputerDetails source){
        try{List<String> rows=new ArrayList<>();for(String address:addresses(metadata(shared,source))){String kind=kind(address);rows.add(kind.equals("transport")?"内置直连 / 中继：已配置":(kind.equals("tailscale")?"Tailscale：":kind.equals("lan")?"局域网：":"公网 / 主机名：")+address);}return android.text.TextUtils.join("\n",rows);}catch(Exception ignored){return "尚未保存地址";}
    }
    public static String lastRoute(String uuid){
        JSONObject state=state();if(!uuid.equals(state.optString("uuid"))||state.isNull("selectedAddress"))return "最近选路：尚无记录";
        int index=Arrays.asList(RhProfile.ROUTES).indexOf(state.optString("route"));return "最近选路："+(index<0?"未知路径":RhProfile.ROUTE_LABELS[index]);
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
        return source.serverCert!=null&&(source.pairState==PairingManager.PairState.PAIRED || source.pairState==null);
    }
    static String pairingStatus(ComputerDetails source){return connectInsteadOfPair(source)?source.pairState==PairingManager.PairState.PAIRED?"verified":"stored":source.pairState==null?"unknown":"unpaired";}
    static String pairingLabel(ComputerDetails source){switch(pairingStatus(source)){case "verified":return "已配对";case "stored":return "已保存配对";case "unknown":return "未确认";default:return "未配对";}}
    static String primaryAction(ComputerDetails source){return connectInsteadOfPair(source)?"device.connect":pairingStatus(source).equals("unknown")?"device.check":"device.pair";}
    static String primaryLabel(ComputerDetails source){switch(primaryAction(source)){case "device.connect":return "连接";case "device.check":return "检查连接";default:return "配对";}}
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
    /** Explicit pairing discovers the endpoint over UUID-checked HTTP without
     * discarding the saved pin. Streaming admission remains strictly pinned. */
    static ComputerDetails refreshPairingDetails(Context context,ComputerDetails source,RhCancellation operation)throws Exception{
        ComputerDetails discovery=new ComputerDetails(source);discovery.serverCert=null;
        ComputerDetails result=refreshConnectionDetails(context,discovery,operation);result.serverCert=source.serverCert;return result;
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
        return check(address,uuid,port,operation,timeoutMs,null);
    }
    private static String check(String address,String uuid,int port,RhCancellation operation,int timeoutMs,java.util.function.Consumer<String> evidence)throws Exception {
        checkpoint(operation);
        String host=address.replace("[","").replace("]","");
        HttpURLConnection connection=(HttpURLConnection)new URI("http",null,host,port,"/serverinfo","uniqueid=0123456789ABCDEF",null).toURL().openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(timeoutMs);connection.setReadTimeout(timeoutMs);connection.setInstanceFollowRedirects(false);
        // Android's platform client closes an established body read on disconnect;
        // verify this on Android rather than substituting the host JVM's HTTP stack.
        try(RhCancellation.Watch request=operation==null?null:operation.watch(connection::disconnect)){
            checkpoint(operation);try(InputStream in=connection.getInputStream()){return readServerInfo(in,address,uuid,operation,evidence);}
        }finally{connection.disconnect();}
    }
    private static String readServerInfo(InputStream in,String address,String uuid,RhCancellation operation,java.util.function.Consumer<String> evidence)throws Exception{
        checkpoint(operation);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){checkpoint(operation);if(out.size()+n>65536)throw new IOException("响应过大");out.write(b,0,n);}checkpoint(operation);
        String body=out.toString("UTF-8");org.xmlpull.v1.XmlPullParser xml=android.util.Xml.newPullParser();xml.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);xml.setInput(new StringReader(body));
        while(xml.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)if(xml.getEventType()==org.xmlpull.v1.XmlPullParser.START_TAG&&xml.getName().equals("uniqueid")){if(!uuid.equalsIgnoreCase(xml.nextText()))throw new IOException("uuid_mismatch");checkpoint(operation);if(evidence!=null)evidence.accept(body);return address;}
        throw new IOException("uuid_missing");
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
        JSONArray attempts=new JSONArray();String selected=null,selectedRemote="",chosenRoute="",chosenPath="unknown";int offset=0,excludedCandidates=0;
        final boolean capture=options!=null&&options.optBoolean("readOnlyDeviceCheck");final java.util.concurrent.ConcurrentHashMap<String,String> evidence=capture?new java.util.concurrent.ConcurrentHashMap<>():null;
        String retainedRelay="";
        try{for(String route:profile.routeOrder) {
            checkpoint(operation);
            if(route.equals("punch_direct")||route.equals("relay")) {
                for(String address:candidates)if(kind(address).equals("transport")) {
                    if(RhRecoveryPolicy.excluded(failed,route,address)){excludedCandidates++;continue;}
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
                        offset=tunnel.getInt("portOffset");final String endpoint=tunnel.getString("address");if(capture)check(endpoint,source.uuid,47989+offset,operation,probeTimeoutMs(route),body->evidence.put(endpoint,body));else check(endpoint,source.uuid,47989+offset,operation,probeTimeoutMs(route));
                        selected=tunnel.getString("address");selectedRemote=address;chosenPath=tunnel.optString("path","unknown");chosenRoute=route;
                        attempts.put(RhActions.obj("address",address,"route",route,"ok",true,"reusedTunnel",tunnel.optBoolean("reused"),"timeoutMs",probeTimeoutMs(route),"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)));accepted=true;retainedRelay="";break;
                    }catch(Exception|LinkageError e){checkpoint(operation);attempts.put(RhActions.obj("address",address,"route",route,"ok",false,"deferred",retain,"retryable",unavailable(e),"failureKind",failureKind(e),"timeoutMs",probeTimeoutMs(route),"error",e.getMessage(),"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)));}
                    finally{if(!accepted&&!retain){RhTunnel.stop();if(reuse)retainedRelay="";}}
                }
                if(selected!=null)break;continue;
            }
            // Another Android app's VPN path cannot be inferred from its IP.
            // Tailscale candidates therefore belong to the explicit unknown bucket.
            List<String> group=new ArrayList<>();for(String address:candidates){String kind=kind(address);if(route.equals("lan")&&(kind.equals("lan")||kind.equals("auto"))||route.equals("public_direct")&&kind.equals("internet")||route.equals("tailscale_relay")&&kind.equals("tailscale")){if(RhRecoveryPolicy.excluded(failed,route,address)){excludedCandidates++;continue;}group.add(address);}}
            if(group.isEmpty())continue;
            // An intervening usable route has priority over the retained relay.
            // Dispose before its probes, so a rejected tunnel cannot survive into it.
            if(!retainedRelay.isEmpty()){RhTunnel.stop();retainedRelay="";}
            int timeoutMs=probeTimeoutMs(route);
            CompletionService<JSONObject> queue=new ExecutorCompletionService<>(PROBES);List<Future<JSONObject>> tasks=new ArrayList<>();List<Probe> probes=new ArrayList<>();for(int i=0;i<group.size();i++)probes.add(new Probe());
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(2L*timeoutMs+200),grace=end;long best=Long.MAX_VALUE;
            try(RhCancellation.Watch request=operation==null?null:operation.watch(()->{for(Probe probe:probes)probe.cancel();})) {
                for(int i=0;i<group.size();i++){String address=group.get(i);Probe probe=probes.get(i);tasks.add(RhProbeWork.submit(queue,()->{long at=System.nanoTime();try{if(capture)check(address,source.uuid,47989,probe,timeoutMs,body->evidence.put(address,body));else check(address,source.uuid,47989,probe,timeoutMs);return RhActions.obj("address",address,"route",route,"ok",true,"timeoutMs",timeoutMs,"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at));}catch(Exception e){return RhActions.obj("address",address,"route",route,"ok",false,"retryable",unavailable(e),"failureKind",failureKind(e),"timeoutMs",timeoutMs,"error",e.getMessage(),"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at));}},operation));}
                for(int remaining=tasks.size();remaining>0;){checkpoint(operation);long wait=Math.min(end,grace)-System.nanoTime();if(wait<=0)break;Future<JSONObject> future=queue.poll(Math.min(wait,TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS);if(future==null)continue;remaining--;JSONObject result=future.get();attempts.put(result);if(result.optBoolean("ok")&&result.getLong("elapsedMs")<best){best=result.getLong("elapsedMs");selected=result.getString("address");grace=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(50);}}
                if(selected==null)for(String address:group){boolean recorded=false;for(int i=0;i<attempts.length();i++){JSONObject row=attempts.getJSONObject(i);if(route.equals(row.optString("route"))&&address.equals(row.optString("address")))recorded=true;}if(!recorded)attempts.put(RhActions.obj("address",address,"route",route,"ok",false,"retryable",true,"error","probe_deadline","timeoutMs",timeoutMs));}
            }finally{for(Probe probe:probes)probe.cancel();for(Future<?> future:tasks)future.cancel(true);}
            if(selected!=null){selectedRemote=selected;chosenRoute=route;chosenPath=route.equals("tailscale_relay")?"unknown":"direct";offset=0;break;}
        }}finally{if(!retainedRelay.isEmpty())RhTunnel.stop();}
        checkpoint(operation);last=RhActions.obj("uuid",source.uuid,"selectedAddress",selected==null?JSONObject.NULL:selected,"remoteAddress",selectedRemote,"attempts",attempts,"route",chosenRoute,"routeOrder",new JSONArray(profile.routeOrder),"path",chosenPath,"portOffset",offset,"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began),"mediaUdp","not_tested");
        RhStatistics.event(selected==null?"network_resolve_failed":"network_resolve_end",-1,last);
        if(selected==null)throw resolutionFailure(attempts,excludedCandidates);
        ComputerDetails resolved=capture?new RhDeviceCheck.Resolved(source,evidence.get(selected)):new ComputerDetails(source);resolved.activeAddress=new ComputerDetails.AddressTuple(selected.replace("[","").replace("]",""),47989+offset);resolved.manualAddress=resolved.activeAddress;resolved.state=ComputerDetails.State.ONLINE;
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
        return diagnose(c,source,profile,options,null);
    }
    public static JSONObject diagnose(Context c,ComputerDetails source,RhProfile profile,JSONObject options,RhCancellation operation)throws Exception {
        ComputerDetails resolved=resolve(c,source,profile,options,operation);JSONObject result=state();String address=resolved.activeAddress.address;
        JSONArray ports=new JSONArray();for(int port:new int[]{47984,47989,47995,48010})try(Socket socket=new Socket();RhCancellation.Watch request=operation==null?null:operation.watch(()->{try{socket.close();}catch(IOException ignored){}})){
            checkpoint(operation);socket.connect(new InetSocketAddress(address,RhTunnel.port(address,port)),500);checkpoint(operation);ports.put(RhActions.obj("port",port,"ok",true));
        }catch(IOException e){checkpoint(operation);ports.put(RhActions.obj("port",port,"ok",false));}result.put("tcpPorts",ports);
        if(source.serverCert!=null)try {
            checkpoint(operation);JSONObject info=RhHost.request(c,address,source.serverCert.getEncoded(),"/v1/network",null,operation).getJSONObject("result");result.put("host",info);
            JSONObject ticket=RhHost.request(c,address,source.serverCert.getEncoded(),"/v1/network/probe",null,operation).getJSONObject("result");
            int port=ticket.getInt("udpPort");if(port!=47995)throw new IOException("诊断端口无效");byte[] token=ticket.getString("token").getBytes(StandardCharsets.US_ASCII);
            try(DatagramSocket socket=new DatagramSocket();RhCancellation.Watch request=operation==null?null:operation.watch(socket::close)){checkpoint(operation);socket.connect(InetAddress.getByName(address),RhTunnel.port(address,port));socket.setSoTimeout(1200);checkpoint(operation);socket.send(new DatagramPacket(token,token.length));byte[] response=new byte[128];DatagramPacket packet=new DatagramPacket(response,response.length);socket.receive(packet);checkpoint(operation);info.put("mediaUdp",packet.getLength()==token.length&&Arrays.equals(token,Arrays.copyOf(response,packet.getLength()))?"reachable":"invalid_reply");}
            catch(SocketTimeoutException e){info.put("mediaUdp","timeout");}
        }catch(Exception e){checkpoint(operation);result.put("hostError",e.getMessage());}
        checkpoint(operation);last=result;return result;
    }
    public static String summary(JSONObject result){
        StringBuilder out=new StringBuilder("地址：").append(result.optString("selectedAddress","无可用地址")).append("\n");
        JSONObject info=result.optJSONObject("host");String udp=info==null?"not_tested":info.optString("mediaUdp");out.append(udp.equals("reachable")?"诊断 UDP 双向可达（视频待串流确认）":udp.equals("timeout")?"诊断 UDP 超时，检查防火墙 / VPN 访问权限":"视频 UDP 尚未验证");
        JSONArray attempts=result.optJSONArray("attempts");if(attempts!=null)for(int i=0;i<attempts.length();i++){JSONObject row=attempts.optJSONObject(i);out.append('\n').append(row.optString("address")).append("：").append(row.optBoolean("ok")?"UUID 匹配":row.optString("error","失败"));}
        if(result.has("hostError"))out.append('\n').append(result.optString("hostError"));return out.toString();
    }
}
