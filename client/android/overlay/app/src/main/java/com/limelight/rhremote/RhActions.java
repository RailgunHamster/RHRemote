package com.limelight.rhremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** UI and local HTTP controls share exactly this dispatcher. Token never leaves app-private storage. */
public final class RhActions {
    public interface Target { JSONObject perform(String id,JSONObject args) throws Exception; JSONObject state() throws Exception; }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static WeakReference<Target> home=new WeakReference<>(null),stream=new WeakReference<>(null);
    private static volatile RhRecovery recovering;
    private static volatile RhAdmission admitting;
    private static JSONArray catalog;private static String token;private static boolean started;
    private static Context appContext;
    private static String lastConnectionError="";
    public static JSONObject obj(Object... pairs){JSONObject value=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return value;}
    public static synchronized void initialize(Context context,Target target){
        appContext=context.getApplicationContext();
        RhStatistics.initialize(context);
        if(target!=null)home=new WeakReference<>(target);if(started)return;
        try {
            try(InputStream in=context.getAssets().open("rhremote-actions.json")){catalog=new JSONArray(new String(read(in,32768),StandardCharsets.UTF_8));}
            File path=new File(context.getFilesDir(),"rhremote-control-token.txt");
            if(path.exists()){try(InputStream in=new FileInputStream(path)){token=new String(read(in,128),StandardCharsets.US_ASCII).trim();}}
            else{byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));token=s.toString();try(OutputStream out=context.openFileOutput(path.getName(),Context.MODE_PRIVATE)){out.write(token.getBytes(StandardCharsets.US_ASCII));}}
            if(token.length()!=64)throw new IOException("invalid control token");started=true;
            new Thread(RhActions::serve,"RHRemote control").start();
        }catch(Exception e){throw new IllegalStateException("RHRemote control initialization failed",e);}
    }
    public static synchronized boolean attachStream(Target target){
        RhAdmission pending=admitting;
        RhRecovery recovery=recovering;
        if(pending!=null&&!pending.canAttach()||recovery!=null&&!recovery.canAttach())return false;
        stream=new WeakReference<>(target);if(pending!=null)pending.attached();if(recovery!=null)recovery.attached();return true;
    }
    static synchronized void beginAdmission(RhAdmission target){if(hasStream())throw new IllegalStateException("请先断开当前连接");admitting=target;}
    static synchronized void endAdmission(RhAdmission target){if(admitting==target)admitting=null;}
    static boolean ownsAdmission(RhAdmission target){return target!=null&&admitting==target;}
    static RhAdmission admission(){return admitting;}
    public static void detachStream(Target target){if(stream.get()==target)stream.clear();}
    static synchronized void beginRecovery(RhRecovery target){if(admitting!=null||recovering!=null&&recovering!=target)throw new IllegalStateException("上一连接尚未清理");recovering=target;}
    static synchronized void endRecovery(Target target){if(recovering==target)recovering=null;}
    static RhRecovery recovery(){return recovering;}
    static boolean ownsRecovery(Target target){return recovering==target;}
    public static boolean hasStream(){return stream.get()!=null||recovering!=null||admitting!=null;}
    static String lastConnectionError(){return lastConnectionError;}
    static void clearConnectionError(){lastConnectionError="";}
    static void connectionError(String message){lastConnectionError=message;Target target=home.get();if(target instanceof RhHome)((RhHome)target).message(message);}
    private static Target activeTarget(){return recovering!=null?recovering:admitting!=null?admitting:stream.get();}
    public static JSONObject dispatch(String id,JSONObject args){
        if(Looper.myLooper()==Looper.getMainLooper())return execute(id,args);
        CountDownLatch done=new CountDownLatch(1);JSONObject[] response=new JSONObject[1];MAIN.post(()->{response[0]=execute(id,args);done.countDown();});
        try{if(done.await(12,TimeUnit.SECONDS))return response[0];}catch(InterruptedException e){Thread.currentThread().interrupt();}
        return obj("ok",false,"action",id,"error","UI dispatcher timeout");
    }
    private static JSONObject execute(String id,JSONObject args){
        if(id.equals("stream.shortcuts.list")){try{return obj("ok",true,"action",id,"result",RhShortcuts.catalog(appContext));}catch(Exception e){return obj("ok",false,"error","shortcut catalog unavailable");}}
        try{boolean known=false;for(int i=0;i<catalog.length();i++)if(id.equals(catalog.getJSONObject(i).getString("id"))){known=true;break;}if(!known)throw new IllegalArgumentException("unknown action");
            if(hasStream()&&(id.equals("device.connect")||id.equals("device.pair")||id.equals("updates.install")||id.equals("device.network.diagnose")))throw new IllegalStateException("请先断开当前连接");
            Target target=(id.startsWith("stream.")||id.equals("input.gesture"))?activeTarget():home.get();if(target==null)throw new IllegalStateException("action target unavailable");
            return obj("ok",true,"action",id,"result",target.perform(id,args));
        }catch(Exception e){return obj("ok",false,"action",id,"error",e.getMessage()==null?"operation failed":e.getMessage());}
    }
    public static void click(String id,JSONObject args){JSONObject response=dispatch(id,args);if(!response.optBoolean("ok")){Target target=home.get();if(target instanceof RhHome)((RhHome)target).message(response.optString("error"));}}
    private static JSONObject snapshot(){
        if(Looper.myLooper()==Looper.getMainLooper()){try{JSONObject state=home.get()==null?new JSONObject():home.get().state();Target target=activeTarget();JSONObject active=target==null?new JSONObject():target.state();state.put("streaming",active.optBoolean("connected"));state.put("connecting",target!=null&&!active.optBoolean("connected"));state.put("lastConnectionError",lastConnectionError);if(target!=null)state.put("stream",active);return obj("ok",true,"state",state);}catch(Exception e){return obj("ok",false,"error","state unavailable");}}
        JSONObject[] result=new JSONObject[1];CountDownLatch latch=new CountDownLatch(1);MAIN.post(()->{result[0]=snapshot();latch.countDown();});try{if(latch.await(5,TimeUnit.SECONDS))return result[0];}catch(InterruptedException e){Thread.currentThread().interrupt();}return obj("ok",false,"error","state timeout");
    }
    private static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("request too large");out.write(b,0,n);}return out.toByteArray();}
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();int b;while((b=in.read())!=-1){if(b=='\n')break;if(b!='\r')out.write(b);if(out.size()>4096)throw new IOException("header too large");}return out.toString("US-ASCII");}
    private static void serve(){
        ThreadPoolExecutor workers=new ThreadPoolExecutor(3,3,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12),new ThreadPoolExecutor.CallerRunsPolicy());
        try(ServerSocket listener=new ServerSocket(48752,8,InetAddress.getByName("127.0.0.1"))){while(true){Socket socket=listener.accept();workers.execute(()->handle(socket));}}catch(IOException ignored){started=false;}finally{workers.shutdown();}
    }
    private static void handle(Socket socket){try(Socket owned=socket){socket.setSoTimeout(15000);InputStream in=socket.getInputStream();String[] request=line(in).split(" ");Map<String,String> headers=new HashMap<>();int count=0;String value;while(!(value=line(in)).isEmpty()){if(++count>32)throw new IOException("too many headers");int colon=value.indexOf(':');if(colon<=0)throw new IOException("invalid header");headers.put(value.substring(0,colon).toLowerCase(Locale.ROOT),value.substring(colon+1).trim());}
        JSONObject result;int status=200;
        if(!("Bearer "+token).equals(headers.get("authorization"))||headers.containsKey("origin")){status=403;result=obj("ok",false,"error","unauthorized");}
        else if(request.length!=3){status=400;result=obj("ok",false,"error","invalid request");}
        else if(request[0].equals("GET")&&request[1].equals("/v1/state")){result=snapshot();}
        else if(request[0].equals("GET")&&request[1].equals("/v1/actions")){result=obj("ok",true,"actions",catalog);}
        else if(request[0].equals("GET")&&request[1].startsWith("/v1/statistics/")){
            String[] path=request[1].split("/",-1);
            if(path.length!=5){status=404;result=obj("ok",false,"error","unknown statistics file");}
            else{
                RhStatisticsExport.Export trace;
                try{trace=RhStatistics.export(path[3],path[4]);}
                catch(Exception unavailable){trace=null;}
                if(trace==null){status=409;result=obj("ok",false,"error","closed statistics file unavailable");}
                else try(RhStatisticsExport.Export exported=trace){
                    OutputStream output=socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+exported.length+"\r\nX-Content-SHA256: "+exported.sha256+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    byte[] chunk=new byte[65536];int n;while((n=exported.input.read(chunk))!=-1)output.write(chunk,0,n);output.flush();return;
                }
            }
        }
        else if(request[0].equals("POST")&&request[1].equals("/v1/action")){
            int length=Integer.parseInt(headers.getOrDefault("content-length","-1"));if(length<0||length>8192)throw new IOException("bounded body required");byte[] body=new byte[length];new DataInputStream(in).readFully(body);JSONObject action=new JSONObject(new String(body,StandardCharsets.UTF_8));result=dispatch(action.getString("id"),action.optJSONObject("args")==null?new JSONObject():action.getJSONObject("args"));if(!result.optBoolean("ok"))status=422;
        }else{status=404;result=obj("ok",false,"error","unknown endpoint");}
        byte[] body=result.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+status+" Result\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }catch(Exception ignored){/* Malformed requests terminate without exposing parser input or credentials. */}}
}
