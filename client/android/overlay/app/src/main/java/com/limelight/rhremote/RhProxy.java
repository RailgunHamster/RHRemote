package com.limelight.rhremote;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.net.ssl.*;

/** Explicit per-request proxy routing. No process-wide proxy or VPN settings. */
public final class RhProxy {
    private static TlsBridge bridge;
    static JSONObject defaults(){return RhActions.obj("enabled",false,"protocol","http","host","127.0.0.1","port","7890","updates",true,"sync",true,"remote",true);}
    static JSONObject load(Context context)throws IOException {
        File file=new File(context.getFilesDir(),"proxy-local.json");if(!file.exists())return defaults();
        if(!file.isFile()||file.length()>4096)throw new IOException("代理配置文件无效");
        try{return validate(new JSONObject(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8)));}catch(Exception invalid){throw new IOException("代理设置已损坏，请重新保存");}
    }
    static JSONObject validate(JSONObject input)throws Exception {
        JSONObject value=new JSONObject(input.toString());
        for(String key:new String[]{"enabled","updates","sync","remote"})if(!(value.opt(key) instanceof Boolean))throw new IOException("代理开关无效");
        String protocol=value.getString("protocol"),host=value.getString("host"),port=value.getString("port");
        if(!protocol.equals("http")&&!protocol.equals("https"))throw new IOException("请选择 HTTP 或 HTTPS");
        if(host.isEmpty()||host.length()>253||!host.equals(host.trim())||!host.matches("[A-Za-z0-9_.:-]+")||host.contains(":")&&!host.matches("[0-9A-Fa-f:]+"))throw new IOException("代理地址只填写主机名或 IP，不含协议和端口");
        if(!port.matches("[0-9]{1,5}")||Integer.parseInt(port)<1||Integer.parseInt(port)>65535)throw new IOException("代理端口应为 1–65535");
        new URI(protocol,null,host,Integer.parseInt(port),null,null,null).toURL();value.put("port",String.valueOf(Integer.parseInt(port)));return value;
    }
    static synchronized JSONObject configure(Context context,JSONObject draft)throws Exception {
        JSONObject value=validate(draft);RhShared.write(new File(context.getFilesDir(),"proxy-local.json"),value);
        if(bridge!=null){bridge.close();bridge=null;}return value;
    }
    public static String url(Context context,String scope)throws IOException {
        JSONObject value=load(context);if(!value.optBoolean("enabled")||!value.optBoolean(scope))return null;
        try{return new URI(value.getString("protocol"),null,value.getString("host"),Integer.parseInt(value.getString("port")),null,null,null).toString();}catch(Exception invalid){throw new IOException("代理设置无效");}
    }
    static synchronized Proxy route(Context context,String scope)throws IOException {
        String address=url(context,scope);if(address==null)return Proxy.NO_PROXY;URI uri=URI.create(address);String host=uri.getHost();if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        if(uri.getScheme().equals("http"))return new Proxy(Proxy.Type.HTTP,new InetSocketAddress(host,uri.getPort()));
        if(bridge==null||!bridge.target.equals(address)){if(bridge!=null)bridge.close();bridge=new TlsBridge(address,host,uri.getPort());}
        return new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",bridge.listener.getLocalPort()));
    }
    public static HttpURLConnection open(Context context,URL target,String scope)throws IOException {return (HttpURLConnection)target.openConnection(route(context,scope));}

    /** Wraps just the upstream proxy hop in verified TLS; origin TLS stays intact. */
    private static final class TlsBridge implements Closeable {
        final String target;final ServerSocket listener;final Set<Socket> sockets=Collections.synchronizedSet(new HashSet<>());final Semaphore limit=new Semaphore(8);
        volatile boolean closed;
        TlsBridge(String target,String host,int port)throws IOException {
            this.target=target;listener=new ServerSocket();listener.bind(new InetSocketAddress("127.0.0.1",0),8);
            daemon("RHRemote-proxy-accept",()->{while(!closed)try{Socket local=listener.accept();if(!limit.tryAcquire()){local.close();continue;}sockets.add(local);daemon("RHRemote-proxy-TLS",()->forward(local,host,port));}catch(IOException ended){break;}});
        }
        void forward(Socket local,String host,int port){Socket raw=new Socket();sockets.add(raw);try {
            raw.connect(new InetSocketAddress(host,port),7000);raw.setSoTimeout(15000);raw.setTcpNoDelay(true);local.setSoTimeout(15000);
            SSLSocket secure=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,host,port,true);
            SSLParameters parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(parameters);secure.startHandshake();
            daemon("RHRemote-proxy-reply",()->{try{copy(secure.getInputStream(),local.getOutputStream());}catch(IOException ignored){}finally{closeSocket(local);closeSocket(secure);}});
            try{copy(local.getInputStream(),secure.getOutputStream());}finally{closeSocket(secure);}
        }catch(IOException unavailable){/* The owning HTTP operation receives EOF and reports the failure. */}
        finally{closeSocket(local);closeSocket(raw);sockets.remove(local);sockets.remove(raw);limit.release();}}
        public void close(){closed=true;try{listener.close();}catch(IOException ignored){}synchronized(sockets){for(Socket socket:sockets)closeSocket(socket);sockets.clear();}}
        static void copy(InputStream in,OutputStream out)throws IOException{byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1){out.write(bytes,0,n);out.flush();}}
        static void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}}
        static void daemon(String name,Runnable action){Thread thread=new Thread(action,name);thread.setDaemon(true);thread.start();}
    }
}
