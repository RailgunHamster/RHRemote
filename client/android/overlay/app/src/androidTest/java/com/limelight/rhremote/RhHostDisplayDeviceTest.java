package com.limelight.rhremote;

import android.app.Instrumentation;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in paired helper RPC probe. No Activity, desktop input, screenshots or credential export. */
@RunWith(AndroidJUnit4.class)
public class RhHostDisplayDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    JSONObject call(Context context,String address,byte[] cert,String path,JSONObject body)throws Exception {
        long begin=System.nanoTime();JSONObject result=RhActions.obj("path",path,"request",body);
        try{result.put("response",RhHost.request(context,address,cert,path,body));result.put("ok",true);}
        catch(Exception failure){StringWriter detail=new StringWriter();failure.printStackTrace(new PrintWriter(detail));result.put("ok",false).put("error",failure.toString()).put("detail",detail.toString());}
        return result.put("durationMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin));
    }
    SSLContext pairedTls(Context context,byte[] der)throws Exception {
        X509Certificate pinned=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        LimelightCryptoProvider crypto=PlatformBinding.getCryptoProvider(context);
        X509KeyManager keys=new X509KeyManager(){
            public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket){return "Sunshine";}
            public String chooseServerAlias(String type,Principal[] issuers,Socket socket){return null;}
            public X509Certificate[] getCertificateChain(String alias){return new X509Certificate[]{crypto.getClientCertificate()};}
            public PrivateKey getPrivateKey(String alias){return crypto.getClientPrivateKey();}
            public String[] getClientAliases(String type,Principal[] issuers){return new String[]{"Sunshine"};}
            public String[] getServerAliases(String type,Principal[] issuers){return null;}
        };
        X509TrustManager trust=new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{throw new CertificateException("Client trust unsupported");}
            public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{if(chain.length!=1||!pinned.equals(chain[0]))throw new CertificateException("Pinned server certificate mismatch");}
        };
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(new KeyManager[]{keys},new TrustManager[]{trust},null);return tls;
    }
    JSONObject urlInventory(Context context,String address,byte[] der)throws Exception {
        SSLContext tls=pairedTls(context,der);HttpsURLConnection connection=(HttpsURLConnection)new URL("https://"+address+":47995/v1/displays").openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(tls.getSocketFactory());connection.setHostnameVerifier((name,session)->{try{return session.getPeerCertificates().length==1&&java.util.Arrays.equals(der,session.getPeerCertificates()[0].getEncoded());}catch(Exception failure){return false;}});
        connection.setConnectTimeout(2500);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
        JSONObject result=RhActions.obj("path","/v1/displays","urlDiagnostic",true);ByteArrayOutputStream received=new ByteArrayOutputStream();
        try{result.put("status",connection.getResponseCode()).put("declaredBytes",connection.getContentLength());JSONObject headers=new JSONObject();for(java.util.Map.Entry<String,java.util.List<String>> header:connection.getHeaderFields().entrySet())headers.put(header.getKey()==null?"statusLine":header.getKey(),new JSONArray(header.getValue()));result.put("headers",headers);
            try(InputStream source=connection.getInputStream()){byte[] buffer=new byte[4096];int count;while((count=source.read(buffer))!=-1){assertTrue("Bounded display response",received.size()+count<=131072);received.write(buffer,0,count);}}
            catch(IOException failure){result.put("readError",failure.toString());}
        }finally{connection.disconnect();}
        boolean complete=result.optInt("status")==200&&result.optInt("declaredBytes",-1)==received.size();
        return result.put("bodyBytes",received.size()).put("body",new String(received.toByteArray(),StandardCharsets.UTF_8)).put("lengthMatches",result.optInt("declaredBytes",-1)==received.size()).put("ok",complete);
    }
    JSONObject rawInventory(Context context,String address,byte[] der)throws Exception {
        SSLContext tls=pairedTls(context,der);
        JSONObject result=RhActions.obj("path","/v1/displays","rawTls",true);ByteArrayOutputStream received=new ByteArrayOutputStream();
        try(SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket()){
            socket.connect(new InetSocketAddress(address,47995),2500);socket.setSoTimeout(15000);socket.startHandshake();
            socket.getOutputStream().write(("GET /v1/displays HTTP/1.1\r\nHost: "+address+":47995\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            try{byte[] buffer=new byte[4096];int count;while((count=socket.getInputStream().read(buffer))!=-1){assertTrue("Bounded display response",received.size()+count<=131072);received.write(buffer,0,count);}}
            catch(IOException failure){result.put("readError",failure.toString());}
        }
        byte[] all=received.toByteArray();String text=new String(all,StandardCharsets.UTF_8);int separator=text.indexOf("\r\n\r\n");assertTrue("Missing HTTP headers",separator>=0);
        String headers=text.substring(0,separator);int headerBytes=headers.getBytes(StandardCharsets.UTF_8).length+4;String body=new String(all,headerBytes,all.length-headerBytes,StandardCharsets.UTF_8);
        result.put("headers",headers).put("bodyBytes",all.length-headerBytes).put("totalBytes",all.length).put("body",body);
        for(String header:headers.split("\r\n"))if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))result.put("declaredBytes",Integer.parseInt(header.substring(header.indexOf(':')+1).trim()));
        boolean complete=result.optInt("declaredBytes",-1)==all.length-headerBytes;
        return result.put("lengthMatches",complete).put("ok",complete&&headers.startsWith("HTTP/1.1 200 "));
    }
    @Test public void pairedDisplayContract()throws Exception {
        String address=InstrumentationRegistry.getArguments().getString("sessionHost","");
        org.junit.Assume.assumeFalse("Explicit real host is required",address.isEmpty());
        Context context=instrument.getTargetContext();assertFalse("Do not probe during an active client stream",RhActions.hasStream());
        String run=InstrumentationRegistry.getArguments().getString("sessionRun","");assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        File folder=new File(context.getExternalFilesDir("ui-evidence"),"host-display-"+run);assertFalse("Use a fresh evidence directory",folder.exists());assertTrue(folder.mkdirs());
        String uuid=InstrumentationRegistry.getArguments().getString("sessionUuid","C3448560-C539-6209-F595-88C1E8D2769B");
        ComputerDatabaseManager database=new ComputerDatabaseManager(context);ComputerDetails paired;
        try{paired=database.getComputerByUUID(uuid);}finally{database.close();}
        assertNotNull("Only an existing paired host may be probed",paired);assertNotNull("Pinned certificate required",paired.serverCert);
        byte[] cert=paired.serverCert.getEncoded();String preferences=RhShared.canonical(RhShared.read(context));
        JSONObject result=RhActions.obj("host",uuid,"version",com.limelight.BuildConfig.VERSION_NAME,"newPairing",false,"systemInputInjected",false,"osScreenshots",false);
        JSONArray calls=new JSONArray();result.put("calls",calls);
        try{
            calls.put(call(context,address,cert,"/v1/health",null));RhShared.write(new File(folder,"host-display-probe.json"),result);
            if(InstrumentationRegistry.getArguments().getString("rawTls","false").equals("true")){
                int repeats=Integer.parseInt(InstrumentationRegistry.getArguments().getString("probeRepeats","1"));assertTrue(repeats>=1&&repeats<=6);
                for(int i=0;i<repeats;i++){calls.put(rawInventory(context,address,cert).put("iteration",i));RhShared.write(new File(folder,"host-display-probe.json"),result);calls.put(urlInventory(context,address,cert).put("iteration",i));RhShared.write(new File(folder,"host-display-probe.json"),result);}
            }
            JSONObject inventory=call(context,address,cert,"/v1/displays",null);calls.put(inventory);RhShared.write(new File(folder,"host-display-probe.json"),result);
            assertTrue("Read-only display inventory failed: "+inventory,inventory.optBoolean("ok"));
            if(InstrumentationRegistry.getArguments().getString("prepareAuto","false").equals("true")){
                JSONArray displays=inventory.getJSONObject("response").getJSONObject("result").getJSONArray("displays");JSONObject physical=null;
                for(int i=0;i<displays.length();i++){JSONObject item=displays.getJSONObject(i);if(item.optBoolean("available")&&!item.optBoolean("virtual")&&(physical==null||item.optBoolean("primary")))physical=item;}
                assertNotNull("Auto probe requires an available physical screen; it must not create or resize a virtual screen",physical);
                JSONObject body=RhActions.obj("mode","auto","displayId","","width",physical.getInt("width"),"height",physical.getInt("height"));
                JSONObject prepared=call(context,address,cert,"/v1/display/prepare",body);calls.put(prepared);RhShared.write(new File(folder,"host-display-probe.json"),result);
                assertTrue("Display preparation failed: "+prepared,prepared.optBoolean("ok"));
                assertEquals(physical.getString("id"),prepared.getJSONObject("response").getJSONObject("result").getJSONObject("selected").getString("id"));
            }
            for(int i=0;i<calls.length();i++)assertTrue("Incomplete paired helper response: "+calls.getJSONObject(i),calls.getJSONObject(i).optBoolean("ok"));
        }finally{
            assertEquals("Probe changed shared preferences",preferences,RhShared.canonical(RhShared.read(context)));
            result.put("preferencesUnchanged",true);RhShared.write(new File(folder,"host-display-probe.json"),result);
        }
    }
}
