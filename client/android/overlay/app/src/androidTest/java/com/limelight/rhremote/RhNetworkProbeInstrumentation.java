package com.limelight.rhremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only application-UID probes using only framework APIs, including on release builds.
 * AndroidX's debug test runner must not load against R8-trimmed Kotlin/Trace classes. */
public final class RhNetworkProbeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try{result.putString("stream","RHREMOTE_NETWORK_DEVICE_PROOF "+probe(this)+"\n");}
        catch(Exception error){code=Activity.RESULT_CANCELED;result.putString("stream","DEVICE_PROBE_ERROR "+error+"\n");}
        finish(code,result);
    }
    static JSONObject probe(Instrumentation instrumentation)throws Exception {
        Context c=instrumentation.getTargetContext();
        instrumentation.startActivitySync(new Intent().setClassName(c,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ConnectivityManager manager=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
        JSONObject report=new JSONObject().put("uid",android.os.Process.myUid()).put("version",c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName)
            .put("boundNetwork",String.valueOf(manager.getBoundNetworkForProcess())).put("activeNetwork",String.valueOf(manager.getActiveNetwork())).put("startedUtcMs",System.currentTimeMillis());
        JSONArray networks=new JSONArray();for(Network n:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(n);networks.put(new JSONObject().put("network",n.toString()).put("vpn",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).put("wifi",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).put("validated",caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));}report.put("networks",networks);
        JSONArray probes=new JSONArray();for(String host:new String[]{"100.73.139.109","101.43.203.147"}){
            long start=SystemClock.elapsedRealtime();JSONObject p=new JSONObject().put("host",host);try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(host,host.startsWith("100.")?47989:33449),8000);p.put("tcp",true);}catch(Exception e){p.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}p.put("elapsedMs",SystemClock.elapsedRealtime()-start);probes.put(p);
        }report.put("tcp",probes);
        JSONArray http=new JSONArray();for(int timeout:new int[]{1000,8000}){
            long start=SystemClock.elapsedRealtime();HttpURLConnection connection=null;JSONObject row=new JSONObject().put("timeoutMs",timeout);
            try{connection=(HttpURLConnection)new URL("http://100.73.139.109:47989/serverinfo").openConnection(Proxy.NO_PROXY);connection.setConnectTimeout(timeout);connection.setReadTimeout(timeout);row.put("status",connection.getResponseCode());}
            catch(Exception e){row.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
            finally{if(connection!=null)connection.disconnect();}row.put("elapsedMs",SystemClock.elapsedRealtime()-start);http.put(row);
        }report.put("http",http);return report;
    }
}
