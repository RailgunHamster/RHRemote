package com.limelight.rhremote;

import android.content.Context;
import android.os.Looper;
import com.limelight.nvstream.http.ComputerDetails;
import java.net.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** The real diagnose UDP receive waits on our silent loopback fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhDiagnosticUdpIoTest.Network.class,RhDiagnosticUdpIoTest.Host.class,RhDiagnosticUdpIoTest.Transport.class})
public class RhDiagnosticUdpIoTest {
    static final class Ports implements AutoCloseable {
        final ServerSocket tcp;final DatagramSocket udp;
        Ports()throws Exception{
            // TCP and UDP are independent Windows allocation ranges. The fixture
            // maps them separately, retaining the valid production ticket port.
            tcp=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            try{udp=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));}catch(Exception failure){tcp.close();throw failure;}
        }
        @Override public void close()throws Exception{udp.close();tcp.close();}
    }
    @Implements(value=RhNetwork.class,isInAndroidSdk=false)
    public static class Network {
        @Implementation protected static ComputerDetails resolve(Context c,ComputerDetails source,RhProfile p,JSONObject options,RhCancellation operation)throws Exception{operation.checkpoint();ComputerDetails copy=new ComputerDetails(source);copy.activeAddress=new ComputerDetails.AddressTuple("127.0.0.1",47989);return copy;}
    }
    @Implements(value=RhHost.class,isInAndroidSdk=false)
    public static class Host {
        @Implementation protected static JSONObject request(Context context,String host,byte[] pin,String path,JSONObject body,RhCancellation operation)throws Exception{operation.checkpoint();if(path.equals("/v1/network/probe")){Transport.udpPhase=true;return RhActions.obj("result",RhActions.obj("udpPort",47995,"token","diagnostic-fixture"));}return RhActions.obj("result",RhActions.obj());}
    }
    @Implements(value=RhTunnel.class,isInAndroidSdk=false)
    public static class Transport {static int port,udpPort;static boolean udpPhase;@Implementation protected static int port(String host,int original){return udpPhase?udpPort:port;}}
    @Test public void cancellingDiagnosticClosesTheActualUdpReceiveBeforeItsTimeout()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ExecutorService workers=Executors.newFixedThreadPool(2);RhDiagnosticWork work=RhDiagnosticWork.shared(context);CountDownLatch received=new CountDownLatch(1);
        try(Ports ports=new Ports()){ServerSocket tcp=ports.tcp;DatagramSocket udp=ports.udp;
            Transport.port=tcp.getLocalPort();Transport.udpPort=udp.getLocalPort();Transport.udpPhase=false;udp.setSoTimeout(3000);Future<?> server=workers.submit(()->{try{byte[] bytes=new byte[128];udp.receive(new DatagramPacket(bytes,bytes.length));received.countDown();}catch(Exception failure){throw new RuntimeException(failure);}});
            ComputerDetails source=new ComputerDetails();source.uuid="udp-test-host";source.manualAddress=new ComputerDetails.AddressTuple("127.0.0.1",47989);source.serverCert=com.limelight.binding.PlatformBinding.getCryptoProvider(context).getClientCertificate();
            work.start(source,RhProfile.load(context,source.uuid),null,workers);boolean ready=received.await(3,TimeUnit.SECONDS);assertTrue("UDP packet missing: "+work.state()+" serverDone="+server.isDone(),ready);long began=System.nanoTime();work.cancel(work.state().getLong("operationId"));assertTrue("UI cancellation blocked",System.nanoTime()-began<TimeUnit.MILLISECONDS.toNanos(250));
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(850);do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(!work.busy())break;Thread.sleep(5);}while(System.nanoTime()<deadline);
            assertFalse("UDP receive waited for the original 1200ms timeout: "+work.state(),work.busy());assertEquals("cancelled",work.state().getString("phase"));server.get(1,TimeUnit.SECONDS);assertFalse(RhActions.inspecting());
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
}
