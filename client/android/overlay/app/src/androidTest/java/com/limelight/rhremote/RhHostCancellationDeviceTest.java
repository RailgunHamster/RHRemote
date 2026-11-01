package com.limelight.rhremote;

import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native Android pinned TLS body cancellation. Only a server owned by this test is contacted. */
@RunWith(AndroidJUnit4.class)
public final class RhHostCancellationDeviceTest {
    @Test public void cancellingAnEstablishedPinnedTlsBodyReadClosesTheSocket()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("hostCancelRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        File folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());File identityFolder=new File(context.getCacheDir(),run+"-tls-identity");assertTrue(identityFolder.mkdir());
        AndroidCryptoProvider identity=new AndroidCryptoProvider(new ContextWrapper(context){@Override public File getFilesDir(){return identityFolder;}});
        KeyStore store=KeyStore.getInstance("PKCS12");store.load(null,null);char[] password="fixture-only".toCharArray();store.setKeyEntry("host",identity.getClientPrivateKey(),password,new java.security.cert.Certificate[]{identity.getClientCertificate()});
        KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password);SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(keys.getKeyManagers(),null,null);JSONArray cases=new JSONArray();
        for(String connection:new String[]{"close","keep-alive"}){
            ExecutorService workers=Executors.newFixedThreadPool(3);RhCancellation operation=new RhCancellation(){};CountDownLatch headers=new CountDownLatch(1);AtomicReference<Socket> accepted=new AtomicReference<>();AtomicReference<Thread> reader=new AtomicReference<>();
            // Bind successfully before starting the client; never probe an existing service.
            try(SSLServerSocket listener=(SSLServerSocket)ssl.getServerSocketFactory().createServerSocket(47995,2,InetAddress.getByName("127.0.0.9"))){
                listener.setEnabledProtocols(new String[]{"TLSv1.2"});
                Future<?> server=workers.submit(()->{try(Socket socket=listener.accept()){accepted.set(socket);socket.setSoTimeout(4000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();assertTrue(first,first.startsWith("GET /v1/transport/authorize "));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nConnection: "+connection+"\r\nContent-Type: application/json\r\nContent-Length: 64\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();headers.countDown();assertEquals(-1,socket.getInputStream().read());}catch(IOException failure){throw new RuntimeException(failure);}});
                Future<?> client=workers.submit(()->{reader.set(Thread.currentThread());try{RhHost.request(context,"127.0.0.9",identity.getClientCertificate().getEncoded(),"/v1/transport/authorize",null,operation);fail("Cancelled body returned success");}catch(Exception expected){}});
                assertTrue(headers.await(5,TimeUnit.SECONDS));Thread.sleep(30);assertFalse(client.isDone());String stack=Arrays.toString(reader.get().getStackTrace());assertTrue("Body read must have started: "+stack,stack.contains("RhHost.readResponse"));
                long began=SystemClock.elapsedRealtimeNanos();Future<?> cancelled=workers.submit(operation::cancelRequests);client.get(2,TimeUnit.SECONDS);long bodyNs=SystemClock.elapsedRealtimeNanos()-began;cancelled.get(1,TimeUnit.SECONDS);long cancelNs=SystemClock.elapsedRealtimeNanos()-began;server.get(1,TimeUnit.SECONDS);
                cases.put(RhActions.obj("connection",connection,"bodyExitNs",bodyNs,"cancelCallNs",cancelNs,"serverSawEof",true,"readerStackBeforeCancel",stack));
            }finally{Socket owned=accepted.get();if(owned!=null)owned.close();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
        }
        JSONObject result=RhActions.obj("scope","Native Android pinned TLS, headers received and body withheld; no remote host or shared credentials","package",context.getPackageName(),"pid",android.os.Process.myPid(),"version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"cases",cases);
        try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"result.json")),"UTF-8")){out.write(result.toString(2));}
    }
}
