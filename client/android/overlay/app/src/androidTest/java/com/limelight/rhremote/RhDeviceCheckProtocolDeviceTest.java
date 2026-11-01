package com.limelight.rhremote;

import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Android NvHTTP and mTLS, exclusively owned loopback peers and generated identities. */
@RunWith(AndroidJUnit4.class)
public final class RhDeviceCheckProtocolDeviceTest {
    static AndroidCryptoProvider identity(Context context,File folder){assertTrue(folder.mkdir());return new AndroidCryptoProvider(new ContextWrapper(context){@Override public File getFilesDir(){return folder;}});}
    static final class Host implements AutoCloseable {
        volatile String kind;final String uuid;final ServerSocket http,tls;final ExecutorService workers=Executors.newFixedThreadPool(2);
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final AtomicInteger plainRequests=new AtomicInteger(),secureRequests=new AtomicInteger();
        final AtomicBoolean clientVerified=new AtomicBoolean(),bodyClosed=new AtomicBoolean();final CountDownLatch headers=new CountDownLatch(1);volatile boolean stopped;
        Host(String kind,String uuid,AndroidCryptoProvider server,X509Certificate clientCert)throws Exception{
            this.kind=kind;this.uuid=uuid;InetAddress loopback=InetAddress.getByName(kind.equals("route-no-pin")?"127.0.0.7":kind.equals("route-paired")?"127.0.0.8":"127.0.0.1");
            KeyStore keys=KeyStore.getInstance("PKCS12");keys.load(null,null);char[] password="fixture-only".toCharArray();keys.setKeyEntry("fixture",server.getClientPrivateKey(),password,new java.security.cert.Certificate[]{server.getClientCertificate()});
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password);
            TrustManager[] trust={new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[]{clientCert};}public void checkServerTrusted(X509Certificate[] chain,String type){throw new AssertionError();}public void checkClientTrusted(X509Certificate[] chain,String type)throws java.security.cert.CertificateException{if(chain.length!=1||!chain[0].equals(clientCert))throw new java.security.cert.CertificateException("Unexpected fixture client");}}};
            SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),trust,new SecureRandom());
            http=new ServerSocket();http.setReuseAddress(true);http.bind(new InetSocketAddress(loopback,kind.startsWith("route-")?47989:0),2);tls=kind.equals("tls-failure")?new ServerSocket(0,2,loopback):ssl.getServerSocketFactory().createServerSocket(0,2,loopback);
            if(tls instanceof SSLServerSocket){((SSLServerSocket)tls).setNeedClientAuth(true);((SSLServerSocket)tls).setEnabledProtocols(new String[]{"TLSv1.2"});}
            http.setSoTimeout(100);tls.setSoTimeout(100);workers.submit(()->serve(http,false,clientCert));workers.submit(()->serve(tls,true,clientCert));
        }
        void serve(ServerSocket listener,boolean secure,X509Certificate client){while(!stopped){
            try(Socket socket=listener.accept()){
                sockets.add(socket);socket.setSoTimeout(2500);
                if(secure&&kind.equals("tls-failure")){socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));continue;}
                BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();if(first==null)continue;assertTrue(first,first.startsWith("GET /serverinfo?"));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                if(secure){secureRequests.incrementAndGet();assertEquals(client,((SSLSocket)socket).getSession().getPeerCertificates()[0]);clientVerified.set(true);}else plainRequests.incrementAndGet();
                if(kind.equals("drop-all"))continue;
                if(secure&&(kind.equals("timeout")||kind.equals("cancel-body"))){socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();headers.countDown();try{bodyClosed.set(input.read()==-1);}catch(IOException reset){bodyClosed.set(true);}continue;}
                boolean unauthorized=secure&&(kind.equals("https-http401")||kind.equals("https-xml401")||kind.equals("pin-change401"))||!secure&&kind.equals("http401-no-pin");
                String actual=kind.equals("wrong-uuid")||!secure&&kind.equals("port-discovery-wrong-uuid")?"other-fixture":uuid;
                if(!secure&&kind.equals("pin-change-drop-http"))continue;
                String body=unauthorized?"<root status_code=\"401\" status_message=\"Unauthorized\"/>":"<root status_code=\"200\"><appversion>7.1.0</appversion><hostname>Fixture</hostname><uniqueid>"+actual+"</uniqueid><HttpsPort>"+tls.getLocalPort()+"</HttpsPort><PairStatus>"+(kind.equals("https-unpaired")?"0":"1")+"</PairStatus><state>SUNSHINE_SERVER_FREE</state></root>";
                if(kind.equals("malformed"))body="<root>";if(kind.equals("oversize"))body=new String(new char[65537]).replace('\0','x');
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);int status=unauthorized&&!kind.equals("https-xml401")?401:200;
                socket.getOutputStream().write(("HTTP/1.1 "+status+" Fixture\r\nContent-Type: application/xml\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
            }catch(SocketTimeoutException wait){}catch(IOException rejected){/* Pin rejection, owned cancellation and listener close. */}
        }}
        @Override public void close()throws Exception{stopped=true;http.close();tls.close();for(Socket socket:sockets)socket.close();workers.shutdownNow();assertTrue(workers.awaitTermination(4,TimeUnit.SECONDS));}
    }
    @Test public void actualAndroidProtocolReportsDistinguishTrustAuthenticationAndNetwork()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("deviceCheckRun","");org.junit.Assume.assumeFalse("Explicit isolated emulator run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        File folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());File privateFolder=new File(context.getCacheDir(),run);assertTrue(privateFolder.mkdir());
        AndroidCryptoProvider original=identity(context,new File(privateFolder,"original")),changed=identity(context,new File(privateFolder,"changed"));JSONArray results=new JSONArray();
        String[][] cases={{"http-unpaired","unpaired"},{"https-paired","paired"},{"https-unpaired","unpaired"},{"https-http401","auth_rejected"},{"https-xml401","auth_rejected"},{"pin-change","certificate_mismatch"},{"pin-change401","certificate_mismatch"},{"wrong-uuid","identity_mismatch"},{"malformed","invalid_response"},{"oversize","invalid_response"},{"tls-failure","tls_failure"},{"timeout","timeout"},{"cancel-body","unavailable"},{"http401-no-pin","unavailable"},{"port-discovery-wrong-uuid","identity_mismatch"},{"route-no-pin","unpaired"},{"route-paired","paired"}};
        for(String[] entry:cases){String kind=entry[0],uuid=UUID.randomUUID().toString();boolean pin=!kind.startsWith("http-")&&!kind.equals("http401-no-pin")&&!kind.equals("route-no-pin");ExecutorService clientWorker=Executors.newSingleThreadExecutor();
            try(Host host=new Host(kind,uuid,kind.startsWith("pin-change")?changed:original,kind.startsWith("route-")?com.limelight.binding.PlatformBinding.getCryptoProvider(context).getClientCertificate():original.getClientCertificate())){
                NvHTTP client=new NvHTTP(new ComputerDetails.AddressTuple("127.0.0.1",host.http.getLocalPort()),kind.equals("port-discovery-wrong-uuid")?0:host.tls.getLocalPort(),"fixture",pin?original.getClientCertificate():null,original,true);
                long began=SystemClock.elapsedRealtimeNanos();Future<JSONObject> response=clientWorker.submit(()->{
                    if(!kind.startsWith("route-"))return client.getRhDeviceCheck(uuid,kind.equals("timeout")?250:2000);
                    ComputerDetails target=new ComputerDetails();target.uuid=uuid;target.name="Fixture";target.manualAddress=new ComputerDetails.AddressTuple(host.http.getInetAddress().getHostAddress(),host.http.getLocalPort());target.serverCert=pin?original.getClientCertificate():null;
                    RhProfile profile=RhProfile.load(context,uuid);profile.routeOrder=new ArrayList<>(Collections.singletonList("lan"));RhCancellation operation=new RhCancellation(){};
                    ComputerDetails resolved=RhNetwork.resolve(context,target,profile,RhActions.obj("address",target.manualAddress.address,"addresses",new JSONArray(),"readOnlyDeviceCheck",true),operation);
                    assertTrue(resolved instanceof RhDeviceCheck.Resolved);return RhDeviceCheck.inspect(context,resolved,operation);
                });
                if(kind.equals("cancel-body")){assertTrue(host.headers.await(3,TimeUnit.SECONDS));assertFalse(response.isDone());client.cancelRhRequests();}
                JSONObject report=response.get(5,TimeUnit.SECONDS);assertEquals(kind,entry[1],report.getString("outcome"));assertEquals(uuid,report.getString("targetUuid"));
                if(pin&&!kind.equals("port-discovery-wrong-uuid")&&!kind.startsWith("route-"))assertEquals("No fallback to HTTP after HTTPS failure",0,host.plainRequests.get());
                if(kind.startsWith("route-")){assertEquals("Reuse the actual route preflight response",1,host.plainRequests.get());assertEquals(pin?1:0,host.secureRequests.get());}
                if(kind.equals("port-discovery-wrong-uuid")){assertEquals(1,host.plainRequests.get());assertEquals(0,host.secureRequests.get());}
                if(kind.equals("timeout")||kind.equals("cancel-body")){long end=SystemClock.elapsedRealtime()+1500;while(!host.bodyClosed.get()&&SystemClock.elapsedRealtime()<end)Thread.sleep(5);assertTrue(kind,host.bodyClosed.get());}
                results.put(RhActions.obj("case",kind,"expected",entry[1],"report",report,"elapsedNs",SystemClock.elapsedRealtimeNanos()-began,"plainRequests",host.plainRequests.get(),"secureRequests",host.secureRequests.get(),"testClientCertificateVerified",host.clientVerified.get(),"bodyConnectionClosed",host.bodyClosed.get()));
            }finally{clientWorker.shutdownNow();assertTrue(clientWorker.awaitTermination(4,TimeUnit.SECONDS));}
        }
        JSONObject result=RhActions.obj("scope","Actual NvHTTP on Android with generated private fixture identities, loopback mTLS only; no PIN, stream, real host or OS input","package",context.getPackageName(),"version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"pid",android.os.Process.myPid(),"publicTestCertificate",android.util.Base64.encodeToString(original.getClientCertificate().getEncoded(),android.util.Base64.NO_WRAP),"cases",results);
        String repairUuid=UUID.randomUUID().toString();
        try(Host host=new Host("repair-preflight",repairUuid,changed,com.limelight.binding.PlatformBinding.getCryptoProvider(context).getClientCertificate())){
            ComputerDetails target=new ComputerDetails();target.uuid=repairUuid;target.name="Fixture";target.activeAddress=new ComputerDetails.AddressTuple("127.0.0.1",host.http.getLocalPort());target.serverCert=original.getClientCertificate();
            ComputerDetails prepared=RhNetwork.refreshPairingDetails(context,target,new RhCancellation(){});assertEquals(repairUuid,prepared.uuid);assertSame(target.serverCert,prepared.serverCert);assertNull(target.pairState);assertEquals(1,host.plainRequests.get());assertEquals(0,host.secureRequests.get());
            result.put("pairingPreparation",RhActions.obj("uuidVerified",true,"savedPinPreserved",true,"sourceUnmodified",true,"plainRequests",host.plainRequests.get(),"secureRequests",host.secureRequests.get(),"pinOrPairRequestSent",false));
        }
        try(Writer output=new OutputStreamWriter(new FileOutputStream(new File(folder,"result.json")),StandardCharsets.UTF_8)){output.write(result.toString(2));}
    }
}
