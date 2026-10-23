package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.ConnectionContext;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Actual NvHTTP, pinned mutual TLS and NvConnection stop; ARM-only bridge calls are excluded. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows=RhLaunchHttpTest.NoNative.class)
public class RhLaunchHttpTest {
    @Implements(value=MoonBridge.class,callNativeMethodsByDefault=true)
    public static class NoNative {
        @Implementation protected static void __staticInitializer__(){}
        @Implementation protected static String getLaunchUrlQueryParameters(){return "";}
    }
    private AndroidCryptoProvider identity(String name){
        Context context=RuntimeEnvironment.getApplication();File directory=new File(context.getCacheDir(),name);assertTrue(directory.isDirectory()||directory.mkdirs());
        return new AndroidCryptoProvider(new ContextWrapper(context){@Override public File getFilesDir(){return directory;}});
    }
    private static final class Host implements AutoCloseable {
        final SSLServerSocket tls;final ServerSocket plain;
        final ExecutorService workers=Executors.newFixedThreadPool(6);
        final CountDownLatch launchReceived=new CountDownLatch(1),cancelReceived=new CountDownLatch(1),releaseLaunch=new CountDownLatch(1),releaseCancel;
        final AtomicInteger launches=new AtomicInteger(),cancels=new AtomicInteger(),mutualTls=new AtomicInteger(),plainRequests=new AtomicInteger();
        final AtomicBoolean denyClient=new AtomicBoolean();
        final List<String> cancellationCredentials=Collections.synchronizedList(new ArrayList<>());
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        volatile String launchCredential;
        volatile boolean closed;
        final boolean capable;
        Host(AndroidCryptoProvider host,AndroidCryptoProvider client,boolean capable,boolean holdCancel)throws Exception {
            this.capable=capable;releaseCancel=new CountDownLatch(holdCancel?1:0);
            KeyStore store=KeyStore.getInstance("PKCS12");store.load(null,null);char[] password="fixture".toCharArray();
            store.setKeyEntry("host",host.getClientPrivateKey(),password,new java.security.cert.Certificate[]{host.getClientCertificate()});
            KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password);
            X509TrustManager clients=new X509TrustManager(){
                @Override public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException {if(denyClient.get()||chain.length!=1||!chain[0].equals(client.getClientCertificate()))throw new CertificateException("Fixture client denied");mutualTls.incrementAndGet();}
                @Override public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException {throw new CertificateException("Server-only fixture");}
                @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            };
            SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(keys.getKeyManagers(),new TrustManager[]{clients},null);
            tls=(SSLServerSocket)ssl.getServerSocketFactory().createServerSocket(0,4,InetAddress.getLoopbackAddress());tls.setEnabledProtocols(new String[]{"TLSv1.2"});tls.setNeedClientAuth(true);
            plain=new ServerSocket(0,2,InetAddress.getLoopbackAddress());workers.submit(()->accept(tls,true));workers.submit(()->accept(plain,false));
        }
        private void accept(ServerSocket listener,boolean secure){while(!closed)try{Socket socket=listener.accept();sockets.add(socket);workers.submit(()->serve(socket,secure));}catch(IOException|RejectedExecutionException done){return;}}
        private void serve(Socket socket,boolean secure){
            try(Socket owned=socket){
                owned.setSoTimeout(5000);BufferedReader input=new BufferedReader(new InputStreamReader(owned.getInputStream(),StandardCharsets.US_ASCII));
                String request=input.readLine();if(request==null)return;String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                URI uri=URI.create("https://fixture"+request.split(" ")[1]);String path=uri.getPath();String credential=null;
                if(uri.getRawQuery()!=null)for(String field:uri.getRawQuery().split("&"))if(field.startsWith("rhremoteLaunchToken="))credential=field.substring("rhremoteLaunchToken=".length());
                if(!secure)plainRequests.incrementAndGet();String body;
                if(path.equals("/serverinfo"))body="<root status_code=\"200\"><appversion>7.1.0</appversion><PairStatus>"+(secure?"1":"0")+"</PairStatus><RHLaunchCancel>"+(capable?"1":"0")+"</RHLaunchCancel><HttpsPort>"+tls.getLocalPort()+"</HttpsPort></root>";
                else if(path.equals("/launch")||path.equals("/resume")){
                    launches.incrementAndGet();launchCredential=credential;launchReceived.countDown();releaseLaunch.await(8,TimeUnit.SECONDS);
                    body="<root status_code=\"200\"><resume>1</resume><gamesession>1</gamesession></root>";
                }else if(path.equals("/rhremote/launch/cancel")){
                    cancels.incrementAndGet();cancellationCredentials.add(credential);cancelReceived.countDown();releaseCancel.await(3,TimeUnit.SECONDS);
                    body="<root status_code=\"200\"><cancel>1</cancel><RHLaunchState>cancelled</RHLaunchState></root>";
                }else body="<root status_code=\"404\"/>";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream output=owned.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
            }catch(IOException expected){/* Cancelled HTTP or rejected mutual TLS. */}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{sockets.remove(socket);}
        }
        @Override public void close()throws Exception {closed=true;releaseLaunch.countDown();releaseCancel.countDown();tls.close();plain.close();for(Socket socket:sockets)socket.close();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private static ConnectionContext stream(){
        ConnectionContext c=new ConnectionContext();c.negotiatedWidth=1920;c.negotiatedHeight=1080;c.riKey=new SecretKeySpec(new byte[16],"AES");c.riKeyId=1;
        c.streamConfig=new StreamConfiguration.Builder().setAudioConfiguration(new MoonBridge.AudioConfiguration(2,3)).setLaunchRefreshRate(30).build();return c;
    }
    private NvHTTP client(Host host,AndroidCryptoProvider server,AndroidCryptoProvider identity)throws Exception {
        RhStatistics.initialize(RuntimeEnvironment.getApplication());RhStatistics.configure(false);
        NvHTTP client=new NvHTTP(new ComputerDetails.AddressTuple("127.0.0.1",host.plain.getLocalPort()),host.tls.getLocalPort(),"fixture-client",server.getClientCertificate(),identity,true);
        client.getServerInfo(true);return client;
    }
    private static Thread launch(NvHTTP client,AtomicReference<Throwable> failure){Thread thread=new Thread(()->{try{client.launchApp(stream(),"resume",1,false);}catch(Throwable e){failure.set(e);}},"fixture actual launch");thread.start();return thread;}

    @Test public void connectionStopWaitsForScopedTlsCleanupWhileResponseIsUnknown()throws Exception {
        AndroidCryptoProvider server=identity("stop-host"),identity=identity("stop-client");
        try(Host host=new Host(server,identity,true,true)){
            NvHTTP h=client(host,server,identity);AtomicReference<Throwable> failure=new AtomicReference<>();Thread starter=launch(h,failure);
            assertTrue(host.launchReceived.await(5,TimeUnit.SECONDS));assertTrue(host.launchCredential.matches("[0-9a-f]{64}"));
            Activity activity=Robolectric.buildActivity(Activity.class).setup().get();NvConnection connection=new NvConnection(activity,new ComputerDetails.AddressTuple("127.0.0.1",host.plain.getLocalPort()),host.tls.getLocalPort(),"fixture-client",stream().streamConfig,identity,server.getClientCertificate());
            ReflectionHelpers.setField(connection,"rhHttp",h);ReflectionHelpers.setField(connection,"rhStarter",starter);
            connection.stop();assertTrue(host.cancelReceived.await(5,TimeUnit.SECONDS));assertFalse(connection.rhAwaitStopped(0));
            host.releaseCancel.countDown();assertTrue(connection.rhAwaitStopped(3000));starter.join(3000);assertFalse(starter.isAlive());assertTrue(failure.get() instanceof IOException);
            assertEquals(Collections.singletonList(host.launchCredential),host.cancellationCredentials);assertTrue(host.mutualTls.get()>=3);assertEquals(0,host.plainRequests.get());
        }
    }
    @Test public void cancellationBeforeLaunchRegistrationUsesIndependentTlsAndPreventsOriginalRequest()throws Exception {
        AndroidCryptoProvider server=identity("early-host"),identity=identity("early-client");
        try(Host host=new Host(server,identity,true,false)){
            NvHTTP h=client(host,server,identity);h.cancelRhRequests();try{h.launchApp(stream(),"resume",1,false);fail("Stopped HTTP instance launched");}catch(IOException expected){}
            assertTrue(h.awaitRhLaunchCleanup(3000));assertTrue(host.cancelReceived.await(3,TimeUnit.SECONDS));assertEquals(0,host.launches.get());assertEquals(1,host.cancels.get());assertTrue(host.cancellationCredentials.get(0).matches("[0-9a-f]{64}"));
        }
    }
    @Test public void failedMutualTlsCannotReleaseOwnershipAndLaterRetryUsesSameCredential()throws Exception {
        AndroidCryptoProvider server=identity("retry-host"),identity=identity("retry-client");
        try(Host host=new Host(server,identity,true,false)){
            NvHTTP h=client(host,server,identity);AtomicReference<Throwable> failure=new AtomicReference<>();Thread starter=launch(h,failure);assertTrue(host.launchReceived.await(5,TimeUnit.SECONDS));
            host.denyClient.set(true);h.cancelRhRequests();assertFalse(h.awaitRhLaunchCleanup(3500));assertEquals(0,host.cancels.get());
            host.denyClient.set(false);Thread.sleep(1100);assertTrue(h.awaitRhLaunchCleanup(3500));assertEquals(Collections.singletonList(host.launchCredential),host.cancellationCredentials);starter.join(3000);assertFalse(starter.isAlive());
        }
    }
    @Test public void oldHostLaunchAndCancellationDoNotSendExtensionOrCredential()throws Exception {
        AndroidCryptoProvider server=identity("legacy-host"),identity=identity("legacy-client");
        try(Host host=new Host(server,identity,false,false)){
            NvHTTP h=client(host,server,identity);AtomicReference<Throwable> failure=new AtomicReference<>();Thread starter=launch(h,failure);assertTrue(host.launchReceived.await(5,TimeUnit.SECONDS));assertNull(host.launchCredential);
            h.cancelRhRequests();assertTrue(h.awaitRhLaunchCleanup(1000));starter.join(3000);assertFalse(starter.isAlive());assertEquals(0,host.cancels.get());
        }
    }
}
