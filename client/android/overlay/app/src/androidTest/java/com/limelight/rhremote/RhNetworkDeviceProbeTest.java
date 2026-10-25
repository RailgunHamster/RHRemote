package com.limelight.rhremote;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Read-only probes from the actual application UID, not the privileged ADB shell. */
@RunWith(AndroidJUnit4.class)
public class RhNetworkDeviceProbeTest {
    @Test public void recordApplicationRouteAndBoundedTcpHttpReachability()throws Exception{
        Instrumentation i=InstrumentationRegistry.getInstrumentation();Context c=i.getTargetContext();
        i.startActivitySync(new Intent().setClassName(c,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ConnectivityManager manager=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
        JSONObject report=new JSONObject().put("uid",android.os.Process.myUid()).put("boundNetwork",String.valueOf(manager.getBoundNetworkForProcess())).put("activeNetwork",String.valueOf(manager.getActiveNetwork()));
        JSONArray networks=new JSONArray();for(Network n:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(n);networks.put(new JSONObject().put("network",n.toString()).put("vpn",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).put("wifi",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).put("validated",caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));}report.put("networks",networks);
        JSONArray probes=new JSONArray();
        for(String host:new String[]{"100.73.139.109","101.43.203.147"}){
            long start=SystemClock.elapsedRealtime();JSONObject p=new JSONObject().put("host",host);try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(host,host.startsWith("100.")?47989:33449),8000);p.put("tcp",true);}catch(Exception e){p.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}p.put("elapsedMs",SystemClock.elapsedRealtime()-start);probes.put(p);
        }
        report.put("tcp",probes);
        long start=SystemClock.elapsedRealtime();HttpURLConnection connection=null;
        try{connection=(HttpURLConnection)new URL("http://100.73.139.109:47989/serverinfo").openConnection(Proxy.NO_PROXY);connection.setConnectTimeout(8000);connection.setReadTimeout(8000);report.put("serverinfoHttp",connection.getResponseCode());}
        catch(Exception e){report.put("serverinfoError",e.getClass().getSimpleName()+": "+e.getMessage());}
        finally{if(connection!=null)connection.disconnect();}report.put("serverinfoElapsedMs",SystemClock.elapsedRealtime()-start);
        Bundle result=new Bundle();result.putString("stream","RHREMOTE_NETWORK_DEVICE_PROOF "+report+"\n");i.sendStatus(0,result);
    }
}
