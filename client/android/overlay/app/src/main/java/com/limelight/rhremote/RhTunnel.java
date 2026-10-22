package com.limelight.rhremote;

import android.content.Context;
import android.system.Os;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** App-scoped Rust QUIC transport; no VPN permission or adapter is required. */
public final class RhTunnel {
    private static boolean loaded;
    private static JSONObject state=new JSONObject();
    private static native String startNative(String key,String peer,String relay,boolean relayOnly);
    private static native void stopNative();
    private static native String identityNative(String key);
    public static synchronized String identity(Context context)throws Exception {
        if(!loaded){System.loadLibrary("rhremote_transport");loaded=true;}
        JSONObject reply=new JSONObject(identityNative(new File(context.getFilesDir(),"transport.key").getAbsolutePath()));
        if(!reply.optBoolean("ok"))throw new IOException(reply.optString("error","独立连接身份不可用"));return reply.getString("endpointId");
    }
    public static synchronized JSONObject start(Context context,String peer,String relay,boolean relayOnly)throws Exception {
        if(!loaded){System.loadLibrary("rhremote_transport");loaded=true;}
        JSONObject reply=new JSONObject(startNative(new File(context.getFilesDir(),"transport.key").getAbsolutePath(),peer,relay,relayOnly));
        if(!reply.optBoolean("ok"))throw new IOException(reply.optString("error","独立连接失败"));
        state=reply.getJSONObject("result");
        Os.setenv("RHREMOTE_TUNNEL_PORT_OFFSET",String.valueOf(state.getInt("portOffset")),true);
        return new JSONObject(state.toString());
    }
    public static synchronized JSONObject state(){try{return new JSONObject(state.toString());}catch(Exception e){return new JSONObject();}}
    public static synchronized int port(String host,int port){return "127.77.0.2".equals(host)&&port>=47984&&port<=48010?port+state.optInt("portOffset"):port;}
    public static synchronized void stop(){if(loaded)stopNative();state=new JSONObject();try{Os.unsetenv("RHREMOTE_TUNNEL_PORT_OFFSET");}catch(Exception ignored){}}
}
