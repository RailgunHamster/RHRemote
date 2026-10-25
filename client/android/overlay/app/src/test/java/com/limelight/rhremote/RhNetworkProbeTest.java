package com.limelight.rhremote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real HTTP response/cancellation boundaries, not a shadow of timeout configuration. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhNetworkProbeTest {
    @Test public void delayedServerInfoSurvivesWanBudgetWhileLanRemainsShort()throws Exception {
        try(ServerSocket listener=new ServerSocket(0,2,InetAddress.getLoopbackAddress())){
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<?> host=worker.submit(()->{for(int i=0;i<2;i++)try(Socket socket=listener.accept()){
                BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                Thread.sleep(1500);byte[] xml="<root><uniqueid>paired-host</uniqueid></root>".getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "+xml.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(xml);
            }catch(IOException expected){/* The LAN client timed out first. */}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}});
            try{
                try{RhNetwork.check("127.0.0.1","paired-host",listener.getLocalPort(),new RhNetwork.Probe(),RhNetwork.probeTimeoutMs("lan"));fail("LAN did not retain its short timeout");}catch(SocketTimeoutException expected){}
                assertEquals("127.0.0.1",RhNetwork.check("127.0.0.1","paired-host",listener.getLocalPort(),new RhNetwork.Probe(),RhNetwork.probeTimeoutMs("tailscale_relay")));
                host.get(5,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
    @Test public void cancellingLongWanProbeClosesItsActualHttpSocket()throws Exception {
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch waiting=new CountDownLatch(1);RhNetwork.Probe probe=new RhNetwork.Probe();
            Future<?> host=workers.submit(()->{try(Socket socket=listener.accept()){
                socket.setSoTimeout(5000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}waiting.countDown();assertEquals(-1,socket.getInputStream().read());
            }catch(IOException error){throw new RuntimeException(error);}});
            Future<?> client=workers.submit(()->{try{RhNetwork.check("127.0.0.1","paired-host",listener.getLocalPort(),probe,RhNetwork.probeTimeoutMs("public_direct"));fail("Cancelled probe returned a host");}catch(Exception expected){}});
            try{assertTrue(waiting.await(3,TimeUnit.SECONDS));long began=System.nanoTime();probe.cancel();assertTrue("Cancelling a WAN probe blocked its caller",System.nanoTime()-began<TimeUnit.SECONDS.toNanos(1));client.get(2,TimeUnit.SECONDS);host.get(2,TimeUnit.SECONDS);}
            finally{probe.cancel();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
}
