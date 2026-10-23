package com.limelight.rhremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import com.limelight.Game;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Default recovery resolver and real HTTP body IO; only the ARM JNI boundary is a fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhRecoveryTransportTest.NativeTransport.class})
public class RhRecoveryTransportTest {
    @Implements(value=RhTunnel.class,isInAndroidSdk=false)
    public static class NativeTransport {
        static CountDownLatch entered,exit;static boolean block;static volatile long started,cancelled;static volatile int starts,stops,port;
        @Implementation protected static String startNative(String key,String peer,String relay,boolean only,long id){
            started=id;starts++;entered.countDown();
            if(block){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(exit.getCount()!=0&&System.nanoTime()<end)try{exit.await(20,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}return "{\"ok\":false,\"error\":\"cancelled\"}";}
            return RhActions.obj("ok",true,"result",RhActions.obj("address","127.0.0.1","portOffset",port-47989,"path","relay")).toString();
        }
        @Implementation protected static void cancelStartNative(long id){cancelled=id;}
        @Implementation protected static void stopNative(){stops++;}
    }
    private Activity activity;private RhProfile profile;private RhRecovery recovery;private Consumer<Boolean> cleanup;
    private ServerSocket server;private Thread serverThread;private volatile Socket accepted;
    private final CountDownLatch request=new CountDownLatch(1),body=new CountDownLatch(1);
    private volatile boolean wrongUuid;
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();RhStatistics.initialize(activity);RhStatistics.configure(false);RhActions.initialize(activity,null);
        byte[] certificate=new AndroidCryptoProvider(activity).getClientCertificate().getEncoded();
        activity.setIntent(new Intent(activity,Game.class).putExtra(Game.EXTRA_PC_UUID,"host").putExtra(Game.EXTRA_PC_NAME,"PC").putExtra(Game.EXTRA_HOST,"192.168.0.104").putExtra(Game.EXTRA_PORT,47989).putExtra(Game.EXTRA_SERVER_CERT,certificate));
        ComputerDetails source=new ComputerDetails();source.uuid="host";source.name="PC";source.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);
        RhShared.capture(activity,java.util.Collections.singletonList(source));RhShared.saveAddresses(activity,"host","rhr:"+String.join("",java.util.Collections.nCopies(64,"a")),new JSONArray());
        profile=RhProfile.fromJson("host",RhActions.obj("width",1920,"height",1080,"fps",30,"bitrateMbps",25,"codec","H.264","swapButtons",false,"trackpad",false,"statistics",false));profile.routeOrder.clear();profile.routeOrder.add("relay");
        ReflectionHelpers.setStaticField(RhNetwork.class,"last",RhActions.obj("route","lan","selectedAddress","192.168.0.104"));
        ReflectionHelpers.setStaticField(RhTunnel.class,"loaded",true);NativeTransport.entered=new CountDownLatch(1);NativeTransport.exit=new CountDownLatch(1);NativeTransport.block=false;NativeTransport.started=NativeTransport.cancelled=0;NativeTransport.starts=NativeTransport.stops=0;
        server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));NativeTransport.port=server.getLocalPort();
        serverThread=new Thread(()->{
            try(Socket socket=server.accept()){
                accepted=socket;socket.setSoTimeout(2500);BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                String line;while((line=in.readLine())!=null&&!line.isEmpty()){}request.countDown();if(!body.await(3,TimeUnit.SECONDS))return;
                byte[] xml=("<root><uniqueid>"+(wrongUuid?"another-host":"host")+"</uniqueid></root>").getBytes(StandardCharsets.UTF_8);
                OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+xml.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(xml);out.flush();
            }catch(IOException expected){}catch(InterruptedException e){Thread.currentThread().interrupt();}
        },"test recovery HTTP server");serverThread.start();
    }
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void start(){recovery=new RhRecovery(activity,profile,done->cleanup=done);assertTrue(recovery.stageFailed(1,111));idle();assertNotNull(cleanup);cleanup.accept(true);}
    private void awaitReleased()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);do{idle();if(!RhActions.hasStream())return;Thread.sleep(10);}while(System.nanoTime()<end);fail(recovery.state().toString());}
    @After public void finish()throws Exception{
        NativeTransport.exit.countDown();body.countDown();if(recovery!=null)recovery.cancel();if(cleanup!=null)cleanup.accept(true);
        if(accepted!=null)accepted.close();server.close();serverThread.join(3000);assertFalse(serverThread.isAlive());
        if(recovery!=null)awaitReleased();ReflectionHelpers.setStaticField(RhTunnel.class,"loaded",false);RhStatistics.finish();assertFalse(RhActions.hasStream());
    }
    @Test public void recoveryCancellationReachesTheCurrentJniAttemptWithoutWaitingForItsMonitor()throws Exception{
        NativeTransport.block=true;start();assertTrue(NativeTransport.entered.await(2,TimeUnit.SECONDS));
        long began=System.nanoTime();recovery.cancel();assertTrue("Cancellation waited for the JNI startup monitor",System.nanoTime()-began<TimeUnit.SECONDS.toNanos(1));
        assertTrue(NativeTransport.started>0);assertEquals(NativeTransport.started,NativeTransport.cancelled);assertTrue(RhActions.hasStream());
        assertTrue(recovery.state().optBoolean("workerRunning"));assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        NativeTransport.exit.countDown();awaitReleased();assertEquals(1,NativeTransport.starts);assertTrue(NativeTransport.stops>=2);
    }
    @Test public void cancellationDuringActualHttpBodyCannotLaunchAnOldGame()throws Exception{
        start();assertTrue(request.await(2,TimeUnit.SECONDS));
        assertTrue(RhActions.dispatch("stream.disconnect",RhActions.obj()).optBoolean("ok"));body.countDown();awaitReleased();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertEquals(1,NativeTransport.starts);assertTrue(NativeTransport.stops>=2);
    }
    @Test public void wrongHttpUuidClosesTheProxyAndCannotEnterHandoff()throws Exception{
        wrongUuid=true;body.countDown();start();assertTrue(request.await(2,TimeUnit.SECONDS));awaitReleased();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertEquals(1,NativeTransport.starts);assertTrue(NativeTransport.stops>=2);
        JSONObject attempt=RhNetwork.state().getJSONArray("attempts").getJSONObject(0);assertFalse(attempt.getBoolean("ok"));assertEquals("uuid_mismatch",attempt.getString("error"));
    }
    @Test public void realResolverHoldsTheSuccessfulRouteUntilGameControllerHandoff()throws Exception{
        body.countDown();start();assertTrue(request.await(2,TimeUnit.SECONDS));Intent next=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do{idle();next=Shadows.shadowOf(activity).getNextStartedActivity();if(next!=null)break;Thread.sleep(10);}while(System.nanoTime()<end);
        assertNotNull(next);assertEquals("127.0.0.1",next.getStringExtra(Game.EXTRA_HOST));assertEquals(server.getLocalPort(),next.getIntExtra(Game.EXTRA_PORT,0));
        assertArrayEquals(activity.getIntent().getByteArrayExtra(Game.EXTRA_SERVER_CERT),next.getByteArrayExtra(Game.EXTRA_SERVER_CERT));assertTrue(RhActions.ownsRecovery(recovery));assertEquals(1,NativeTransport.stops);
    }
}
