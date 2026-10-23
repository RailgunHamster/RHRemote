package com.limelight.rhremote;

import android.content.Context;
import android.content.ContextWrapper;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real loopback TLS, using only freshly generated test certificates. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhPinnedAdmissionTest {
    private AndroidCryptoProvider identity(String name) {
        Context context=RuntimeEnvironment.getApplication();
        File directory=new File(context.getCacheDir(),name);assertTrue(directory.isDirectory()||directory.mkdirs());
        return new AndroidCryptoProvider(new ContextWrapper(context){@Override public File getFilesDir(){return directory;}});
    }
    private static final class Host implements AutoCloseable {
        final ServerSocket http;
        final SSLServerSocket https;
        final ExecutorService workers=Executors.newFixedThreadPool(2);
        final AtomicInteger plainRequests=new AtomicInteger();
        final Future<?> tls,plain;
        Host(AndroidCryptoProvider identity)throws Exception {
            KeyStore store=KeyStore.getInstance("PKCS12");store.load(null,null);
            char[] password="test-only".toCharArray();
            store.setKeyEntry("host",identity.getClientPrivateKey(),password,new java.security.cert.Certificate[]{identity.getClientCertificate()});
            KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password);
            SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(keys.getKeyManagers(),null,null);
            http=new ServerSocket(0,2,InetAddress.getLoopbackAddress());
            https=(SSLServerSocket)ssl.getServerSocketFactory().createServerSocket(0,2,InetAddress.getLoopbackAddress());
            https.setEnabledProtocols(new String[]{"TLSv1.2"});
            tls=workers.submit(()->serve(https,true));plain=workers.submit(()->serve(http,false));
        }
        private void serve(ServerSocket listener,boolean secure) {
            try(Socket socket=listener.accept()) {
                socket.setSoTimeout(4000);
                BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                if(!secure)plainRequests.incrementAndGet();
                String body="<root status_code=\"200\"><hostname>test-host</hostname><uniqueid>expected-host</uniqueid>"+
                    "<HttpsPort>"+https.getLocalPort()+"</HttpsPort><appversion>7.1.0</appversion>"+
                    "<PairStatus>"+(secure?"1":"0")+"</PairStatus><state>SUNSHINE_SERVER_FREE</state></root>";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                OutputStream output=socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);output.flush();
            }catch(IOException expected){/* Closing the fixture or rejecting its TLS pin closes this socket. */}
        }
        @Override public void close()throws Exception {
            https.close();http.close();workers.shutdownNow();
            assertTrue("Fixture threads did not stop",workers.awaitTermination(5,TimeUnit.SECONDS));
            tls.get(5,TimeUnit.SECONDS);plain.get(5,TimeUnit.SECONDS);
        }
    }
    @Test public void savedPinRecoversActualPairStatusOverTls()throws Exception {
        AndroidCryptoProvider identity=identity("matching-host");
        try(Host host=new Host(identity)) {
            NvHTTP client=new NvHTTP(new ComputerDetails.AddressTuple("127.0.0.1",host.http.getLocalPort()),
                host.https.getLocalPort(),"test-client",identity.getClientCertificate(),identity,true);
            ComputerDetails details=client.getRhConnectionDetails();
            assertEquals("expected-host",details.uuid);assertEquals(PairingManager.PairState.PAIRED,details.pairState);
            assertEquals(0,host.plainRequests.get());
        }
    }
    @Test public void wrongSavedPinFailsWithoutFallingBackToPlainHttp()throws Exception {
        AndroidCryptoProvider actual=identity("actual-host"),other=identity("wrong-host");
        assertNotEquals(actual.getClientCertificate(),other.getClientCertificate());
        try(Host host=new Host(actual)) {
            NvHTTP client=new NvHTTP(new ComputerDetails.AddressTuple("127.0.0.1",host.http.getLocalPort()),
                host.https.getLocalPort(),"test-client",other.getClientCertificate(),other,true);
            try{client.getRhConnectionDetails();fail("Changed certificate was accepted or downgraded to HTTP");}
            catch(SSLHandshakeException expected){/* The pinned identity is required. */}
            assertEquals(0,host.plainRequests.get());
        }
    }
}
