package com.limelight.rhremote;

import android.content.Context;
import com.limelight.nvstream.http.ComputerDetails;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** UUID preflight only. Streaming still verifies the paired Sunshine certificate. */
public final class RhNetwork {
    private static volatile JSONObject last=new JSONObject();
    private static final ExecutorService PROBES=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),new ThreadPoolExecutor.AbortPolicy());
    public static JSONObject state(){try{return new JSONObject(last.toString());}catch(JSONException e){return new JSONObject();}}
    public static void validate(String value)throws IOException {
        if(value.isEmpty()||value.length()>253||!value.equals(value.trim())||value.matches(".*[\\s/\\\\@?#%].*"))throw new IOException("请输入 IP 或主机名，不包含协议、端口或路径");
        try {URI uri=new URI("http://"+(value.contains(":")&&!value.startsWith("[")?"["+value+"]":value));if(uri.getHost()==null||uri.getPort()!=-1||uri.getRawUserInfo()!=null)throw new IOException("IP 或主机名无效");}
        catch(URISyntaxException e){throw new IOException("IP 或主机名无效",e);}
    }
    public static String kind(String address){
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
    private static String check(String address,String uuid)throws Exception {
        String host=address.replace("[","").replace("]","");HttpURLConnection connection=(HttpURLConnection)new URI("http",null,host,47989,"/serverinfo","uniqueid=0123456789ABCDEF",null).toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(1000);connection.setReadTimeout(1000);
        try(InputStream in=connection.getInputStream()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new IOException("响应过大");out.write(b,0,n);}
            org.xmlpull.v1.XmlPullParser xml=android.util.Xml.newPullParser();xml.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);xml.setInput(new StringReader(out.toString("UTF-8")));
            while(xml.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)if(xml.getEventType()==org.xmlpull.v1.XmlPullParser.START_TAG&&xml.getName().equals("uniqueid")){if(!uuid.equalsIgnoreCase(xml.nextText()))throw new IOException("uuid_mismatch");return address;}
            throw new IOException("uuid_missing");
        }finally{connection.disconnect();}
    }
    public static ComputerDetails resolve(Context c,ComputerDetails source,RhProfile profile)throws Exception {
        return resolve(c,source,profile,null);
    }
    public static ComputerDetails resolve(Context c,ComputerDetails source,RhProfile profile,JSONObject options)throws Exception {
        JSONObject metadata=metadata(c,source);
        if(options!=null){metadata=new JSONObject(metadata.toString());if(options.has("address"))metadata.put("address",options.getString("address"));if(options.has("addresses"))metadata.put("addresses",options.getJSONArray("addresses"));}
        List<String> candidates=addresses(metadata);candidates.removeIf(a->!profile.networkMode.equals("auto")&&!kind(a).equals("auto")&&!kind(a).equals(profile.networkMode));
        candidates.sort(Comparator.comparingInt(a->kind(a).equals("lan")?0:kind(a).equals("tailscale")?1:kind(a).equals("auto")?2:3));
        CompletionService<JSONObject> queue=new ExecutorCompletionService<>(PROBES);List<Future<JSONObject>> tasks=new ArrayList<>();JSONArray attempts=new JSONArray();String selected=null;int best=Integer.MAX_VALUE;long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(2200),grace=end;
        try {
            for(int i=0;i<candidates.size();i++){final int rank=i;String address=candidates.get(i);tasks.add(queue.submit(()->{long start=System.nanoTime();try{check(address,source.uuid);return RhActions.obj("address",address,"rank",rank,"ok",true,"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}catch(Exception e){return RhActions.obj("address",address,"rank",rank,"ok",false,"error",e.getMessage()==null?"serverinfo_unreachable":e.getMessage(),"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}}));}
            for(int remaining=tasks.size();remaining>0;remaining--){long wait=Math.min(end,grace)-System.nanoTime();if(wait<=0)break;Future<JSONObject> future=queue.poll(wait,TimeUnit.NANOSECONDS);if(future==null)break;JSONObject result=future.get();attempts.put(result);if(result.optBoolean("ok")&&result.getInt("rank")<best){best=result.getInt("rank");selected=result.getString("address");grace=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(180);if(best==0)break;}}
        }finally{for(Future<?> f:tasks)f.cancel(true);}
        last=RhActions.obj("uuid",source.uuid,"selectedAddress",selected==null?JSONObject.NULL:selected,"attempts",attempts,"path","unknown","mediaUdp","not_tested");
        if(selected==null)throw new IOException("所有地址不可达或 UUID 不符；检查两端 Tailscale、分应用 VPN、访问权限与防火墙");
        ComputerDetails resolved=new ComputerDetails(source);resolved.activeAddress=new ComputerDetails.AddressTuple(selected.replace("[","").replace("]",""),47989);resolved.manualAddress=resolved.activeAddress;resolved.state=ComputerDetails.State.ONLINE;return resolved;
    }
    public static JSONObject diagnose(Context c,ComputerDetails source,RhProfile profile)throws Exception {return diagnose(c,source,profile,null);}
    public static JSONObject diagnose(Context c,ComputerDetails source,RhProfile profile,JSONObject options)throws Exception {
        ComputerDetails resolved=resolve(c,source,profile,options);JSONObject result=state();String address=resolved.activeAddress.address;
        JSONArray ports=new JSONArray();for(int port:new int[]{47984,47989,47995,48010})try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(address,port),500);ports.put(RhActions.obj("port",port,"ok",true));}catch(IOException e){ports.put(RhActions.obj("port",port,"ok",false));}result.put("tcpPorts",ports);
        if(source.serverCert!=null)try {
            JSONObject info=RhHost.request(c,address,source.serverCert.getEncoded(),"/v1/network",null).getJSONObject("result");result.put("host",info);
            JSONObject ticket=RhHost.request(c,address,source.serverCert.getEncoded(),"/v1/network/probe",null).getJSONObject("result");
            int port=ticket.getInt("udpPort");if(port!=47995)throw new IOException("诊断端口无效");byte[] token=ticket.getString("token").getBytes(StandardCharsets.US_ASCII);
            try(DatagramSocket socket=new DatagramSocket()){socket.connect(InetAddress.getByName(address),port);socket.setSoTimeout(1200);socket.send(new DatagramPacket(token,token.length));byte[] response=new byte[128];DatagramPacket packet=new DatagramPacket(response,response.length);socket.receive(packet);info.put("mediaUdp",packet.getLength()==token.length&&Arrays.equals(token,Arrays.copyOf(response,packet.getLength()))?"reachable":"invalid_reply");}
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
