package com.limelight.rhremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import com.limelight.Game;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.jni.MoonBridge;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.util.concurrent.Semaphore;
import java.time.Duration;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,shadows=RhRecoveryTest.NoNativeMoonBridge.class)
public class RhRecoveryTest {
    // Host JVM cannot load the Android ARM library. Keep native methods unshadowed:
    // an unexpected call to start/stop the native bridge still fails this test.
    @Implements(value=MoonBridge.class,callNativeMethodsByDefault=true)
    public static class NoNativeMoonBridge {
        @Implementation protected static void __staticInitializer__(){}
    }
    private Activity activity;
    private RhProfile profile;
    private RhRecovery recovery;
    private Consumer<Boolean> cleanup;
    private int resolutions;
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        activity.setIntent(new Intent(activity,Game.class).putExtra(Game.EXTRA_PC_UUID,"host")
            .putExtra(Game.EXTRA_HOST,"192.168.0.104").putExtra(Game.EXTRA_PORT,47989)
            .putExtra(Game.EXTRA_SERVER_CERT,new byte[]{1,2,3}));
        RhStatistics.initialize(activity);
        activity.getSharedPreferences("rhremote.statistics",0).edit().putBoolean("enabled",false).commit();
        profile=RhProfile.fromJson("host",new JSONObject("{\"width\":1920,\"height\":1080,\"fps\":30,\"bitrateMbps\":25,\"codec\":\"H.264\",\"audioEnabled\":false,\"swapButtons\":true,\"trackpad\":false,\"statistics\":false}"));
        Field field=RhNetwork.class.getDeclaredField("last");field.setAccessible(true);
        field.set(null,RhActions.obj("route","lan","selectedAddress","192.168.0.104"));
    }
    @After public void finish(){
        if(recovery!=null){recovery.cancel();if(cleanup!=null)cleanup.accept(true);}
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();RhStatistics.finish();
        assertFalse("Recovery reservation leaked",RhActions.hasStream());
    }
    private RhRecovery make(java.util.concurrent.Executor executor){
        return new RhRecovery(activity,profile,done->cleanup=done,(intent,p,failed,operation)->{
            resolutions++;assertEquals(1,failed.length());assertTrue(RhRecoveryPolicy.excluded(failed,"lan","192.168.0.104"));
            ComputerDetails d=new ComputerDetails();d.activeAddress=new ComputerDetails.AddressTuple("127.77.0.2",48989);return d;
        },executor);
    }
    private void idle(){org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void recoveryUsesANewGameInstanceInsteadOfReusingTheFinishingActivity()throws Exception{
        android.content.pm.ActivityInfo info=activity.getPackageManager().getActivityInfo(new android.content.ComponentName(activity,Game.class),0);
        assertEquals("singleTask loses the reconnect intent when the old Game finishes",android.content.pm.ActivityInfo.LAUNCH_MULTIPLE,info.launchMode);
    }
    @Test public void testInterruptionRequiresTestModeAndRealFrameThenUsesNormalCleanup(){
        recovery=new RhRecovery(activity,profile,done->cleanup=done,(intent,p,failed,operation)->{
            resolutions++;assertEquals("Established streams retry the current route first",0,failed.length());
            ComputerDetails d=new ComputerDetails();d.activeAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);return d;
        },Runnable::run);
        try{recovery.interruptForTest();fail("Production mode accepted injected failure");}catch(IllegalStateException expected){}
        RhStatistics.configure(true);
        try{recovery.interruptForTest();fail("Unestablished stream accepted injected failure");}catch(IllegalStateException expected){}
        recovery.frame();recovery.interruptForTest();idle();assertNotNull(cleanup);assertEquals(0,resolutions);
        try{recovery.interruptForTest();fail("Repeated failure injection changed recovery ownership");}catch(IllegalStateException expected){}
        cleanup.accept(true);cleanup=null;idle();assertEquals(1,resolutions);
        assertNotNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity());
    }
    @Test public void nextRouteWaitsForCleanupAndPreservesPairingAndProfile()throws Exception{
        recovery=make(Runnable::run);
        assertTrue(recovery.stageFailed(1,-1));idle();assertEquals(0,resolutions);assertTrue(RhActions.hasStream());
        assertNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity());
        cleanup.accept(true);cleanup=null;idle();
        Intent next=org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(1,resolutions);assertEquals("127.77.0.2",next.getStringExtra(Game.EXTRA_HOST));
        assertEquals(48989,next.getIntExtra(Game.EXTRA_PORT,0));assertArrayEquals(new byte[]{1,2,3},next.getByteArrayExtra(Game.EXTRA_SERVER_CERT));
        assertEquals(2,new JSONObject(next.getStringExtra(RhRecovery.EXTRA)).getInt("attempt"));
        JSONObject p=new JSONObject(next.getStringExtra(RhRecovery.PROFILE));assertFalse(p.getBoolean("audioEnabled"));assertTrue(p.getBoolean("swapButtons"));
    }
    @Test public void cancelDuringCleanupCannotLaunchLater(){
        recovery=make(Runnable::run);assertTrue(recovery.stageFailed(1,-1));idle();recovery.cancel();
        assertTrue(RhActions.hasStream());cleanup.accept(true);cleanup=null;idle();
        assertEquals(0,resolutions);assertNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity());
    }
    @Test public void fatalStartupReturnsHomeAndReleasesOwnershipOnlyAfterCleanup(){
        recovery=make(Runnable::run);recovery.handleStageFailure("Desktop",0,400);idle();
        assertTrue(activity.isFinishing());assertTrue(RhActions.hasStream());assertEquals(0,resolutions);
        assertNotNull(cleanup);cleanup.accept(true);cleanup=null;idle();
        assertFalse(RhActions.hasStream());assertEquals(0,resolutions);
        assertNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity());
    }
    @Test public void cancelAfterResolveCannotLeaveStatisticsOrLaunchAnActivity(){
        RhStatistics.configure(true);recovery=make(Runnable::run);
        assertTrue(recovery.stageFailed(1,-1));idle();cleanup.accept(true);cleanup=null;
        assertTrue(RhStatistics.state().optBoolean("recording"));recovery.cancel();idle();
        assertFalse(RhStatistics.state().optBoolean("recording"));
        assertNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity());
    }
    @Test public void firstFrameDisarmsTheWatchdogEvenWithoutStatistics(){
        recovery=make(Runnable::run);recovery.frame();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertEquals(0,resolutions);assertFalse(RhActions.hasStream());
    }
    @Test public void statisticsKeepTheInitialActionClockAcrossAttempts()throws Exception{
        RhStatistics.configure(true);long origin=System.currentTimeMillis()*1000-250_000;
        RhStatistics.beginAt(profile,origin);String first=RhStatistics.state().getString("latest");RhStatistics.finish();
        RhStatistics.beginAt(profile,origin);String second=RhStatistics.state().getString("latest");RhStatistics.finish();assertNotEquals(first,second);
        for(String path:new String[]{first,second}){
            java.nio.file.Path file=new java.io.File(path,"native.jsonl").toPath();long end=System.currentTimeMillis()+3000;String contents;
            do{Thread.sleep(20);contents=new String(java.nio.file.Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8);}while(!contents.contains("\"event\":\"trace_end\"")&&System.currentTimeMillis()<end);
            assertTrue(contents.contains("\"event\":\"trace_end\""));
            JSONObject action=null;for(String row:contents.split("\n")){JSONObject event=new JSONObject(row);if(event.optString("event").equals("connect_action"))action=event;}
            assertNotNull(action);assertEquals(origin,action.getLong("ts_us"));
        }
    }
    @Test public void queuedWorkerKeepsReservationUntilCancellationIsReaped(){
        Runnable[] queued=new Runnable[1];recovery=make(task->queued[0]=task);
        assertTrue(recovery.stageFailed(1,-1));idle();cleanup.accept(true);cleanup=null;
        recovery.cancel();assertTrue(RhActions.hasStream());queued[0].run();idle();
        assertTrue("Proxy cleanup must complete before the reservation is released",RhActions.hasStream());
        queued[0].run();idle();
        assertEquals(0,resolutions);assertFalse(RhActions.hasStream());assertNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity());
    }
    @Test public void fatalErrorOverridesAnEarlierRetry(){
        recovery=make(Runnable::run);assertTrue(recovery.stageFailed(1,-1));idle();
        assertFalse(recovery.stageFailed(0,401));idle();cleanup.accept(true);cleanup=null;idle();
        assertEquals(0,resolutions);assertNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity());
    }
    @Test public void failedCleanupStopsWithoutResolving(){
        recovery=make(Runnable::run);assertTrue(recovery.stageFailed(1,-1));idle();cleanup.accept(false);idle();
        assertTrue("Timed-out cleanup must still block a new session",RhActions.hasStream());
        cleanup.accept(true);cleanup=null;idle();
        assertEquals(0,resolutions);assertNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity());
    }
    @Test public void explicitDisconnectKeepsTheReservationUntilCleanupFinishes(){
        recovery=make(Runnable::run);recovery.background();assertTrue(RhActions.hasStream());
        cleanup.accept(false);assertTrue(RhActions.hasStream());
        cleanup.accept(true);cleanup=null;idle();assertFalse(RhActions.hasStream());assertEquals(0,resolutions);
    }
    @Test public void routeBurstAndRecoveryOriginAreBoundedAndTransient()throws Exception{
        RhRecoveryPolicy p=new RhRecoveryPolicy(new JSONObject(),1000);
        assertEquals(-1,p.failure(0,2000,"lan","host",false));assertEquals(0,p.excluded().length());
        assertEquals(0,p.failure(1,2000,"lan","host",true));assertEquals(1000,p.originUs);
        assertEquals(-1,p.failure(2,3000,"lan","host",true));
        RhRecoveryPolicy second=new RhRecoveryPolicy(p.next(),9000);assertEquals(2,second.attempt);assertEquals(1000,second.originUs);
        assertEquals(-1,second.failure(60_001,90_000,"relay","host",true));
        second.frame(61_000);second.frame(71_000);assertEquals(0,second.excluded().length());
        assertEquals(500,second.failure(72_000,100_000,"lan","host",true));assertEquals(100_000,second.originUs);
        assertEquals(1500,second.failure(73_000,101_000,"lan","host",true));
        assertEquals(3000,second.failure(74_000,102_000,"lan","host",true));
        assertEquals(-1,second.failure(75_000,103_000,"lan","host",true));
    }
    @Test public void tlsAndCertificateFailuresAreNeverNetworkFailures(){
        assertTrue(RhRecoveryPolicy.networkError(new SocketTimeoutException()));
        assertFalse(RhRecoveryPolicy.networkError(new javax.net.ssl.SSLHandshakeException("certificate mismatch")));
        assertFalse(RhRecoveryPolicy.networkError(new java.io.IOException(new java.security.cert.CertificateException())));
        assertFalse(RhRecoveryPolicy.networkError(new IllegalStateException("decoder failed")));
        assertFalse(RhRecoveryPolicy.networkStage("video stream establishment",0,-1));
        assertTrue(RhRecoveryPolicy.networkStage("video stream establishment",0,111));
        assertTrue(RhRecoveryPolicy.networkStage("name resolution",0,-2));
    }
    @Test public void stopBeforeStartDoesNotReleaseAnotherConnectionsPermit()throws Exception{
        Field field=NvConnection.class.getDeclaredField("connectionAllowed");field.setAccessible(true);
        Semaphore semaphore=(Semaphore)field.get(null);int permits=semaphore.availablePermits();
        NvConnection c=new NvConnection(activity,new ComputerDetails.AddressTuple("127.0.0.1",47989),47984,"test",new StreamConfiguration.Builder().build(),null,null);
        c.stop();c.stop();assertTrue(c.rhAwaitStopped(10));assertEquals(permits,semaphore.availablePermits());
    }
}
