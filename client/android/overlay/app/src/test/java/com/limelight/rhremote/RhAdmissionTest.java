package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.Game;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhAdmissionTest.Transport.class,RhAdmissionTest.Network.class,RhRecoveryTest.NoNativeMoonBridge.class})
public class RhAdmissionTest {
    @Implements(value=RhActions.class,isInAndroidSdk=false)
    public static class Controls {
        @Implementation protected static void serve(){} // No JVM HTTP listener; real dispatcher is exercised.
    }
    @Implements(value=RhTunnel.class,isInAndroidSdk=false)
    public static class Transport {
        static int stops;static boolean fail;
        @Implementation protected static void stop(){stops++;if(fail)throw new IllegalStateException("Injected cleanup failure");}
    }
    @Implements(value=RhNetwork.class,isInAndroidSdk=false)
    public static class Network {
        @Implementation protected static ComputerDetails resolve(Context c,ComputerDetails source,RhProfile p,JSONObject options,RhCancellation operation)throws Exception{operation.checkpoint();return source;}
        @Implementation protected static ComputerDetails refreshConnectionDetails(Context c,ComputerDetails source,RhCancellation operation)throws Exception{operation.checkpoint();return source;}
    }
    Activity activity;RhAdmission operation;RhHome home;
    ArrayDeque<Runnable> queued=new ArrayDeque<>();
    AtomicInteger launches=new AtomicInteger(),finishes=new AtomicInteger(),pairCancels=new AtomicInteger();
    boolean transferred;String result;CountDownLatch release;
    RhActions.Target stream=new RhActions.Target(){public JSONObject state(){return RhActions.obj();}public JSONObject perform(String id,JSONObject args){return RhActions.obj();}};
    @Before public void setup(){
        Transport.stops=0;Transport.fail=false;
        activity=Robolectric.buildActivity(Activity.class).setup().get();RhStatistics.initialize(activity);RhStatistics.configure(false);
    }
    @After public void cleanup()throws Exception{
        if(release!=null)release.countDown();Transport.fail=false;
        RhAdmission pending=RhActions.admission();
        if(pending!=null){pending.cancel();if(pending.state().optBoolean("pairing"))pending.pairFinished("test cleanup");}
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do{while(!queued.isEmpty())queued.removeFirst().run();idle();if(!RhActions.ownsAdmission(pending))break;Thread.sleep(10);}while(System.nanoTime()<end);
        RhActions.detachStream(stream);RhStatistics.finish();assertFalse("Admission reservation leaked",RhActions.hasStream());
    }
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private RhAdmission make(){
        return new RhAdmission(activity,"host",queued::add,new RhAdmission.Listener(){
            public void changed(RhAdmission current){}
            public void finished(RhAdmission current,boolean handoff,String message){finishes.incrementAndGet();transferred=handoff;result=message;}
        });
    }
    private void run(){queued.removeFirst().run();idle();}
    private void rejectNew(){
        try{make().start(op->()->{});fail("Previous task must keep its reservation");}catch(IllegalStateException expected){}
    }
    @Test public void queuedCancellationSkipsTheWorkAndWaitsForCleanup(){
        AtomicInteger work=new AtomicInteger();operation=make();operation.start(op->{work.incrementAndGet();return launches::incrementAndGet;});
        operation.cancel();assertTrue(RhActions.hasStream());rejectNew();run();
        assertEquals(0,work.get());assertEquals(0,launches.get());assertTrue(RhActions.hasStream());assertEquals(0,Transport.stops);
        run();assertFalse(RhActions.hasStream());assertEquals(1,Transport.stops);assertEquals("已取消连接",result);
    }
    @Test public void lateSuccessCannotLaunchAndCannotReleaseTheReservationEarly(){
        operation=make();operation.start(op->launches::incrementAndGet);queued.removeFirst().run();
        operation.cancel();rejectNew();idle();assertEquals(0,launches.get());assertTrue(RhActions.hasStream());
        run();assertEquals(1,finishes.get());assertEquals(0,launches.get());
    }
    @Test public void cancellationCallbacksAreScopedAndCancelBeforeRegistrationIsImmediate()throws Exception{
        operation=make();operation.start(op->launches::incrementAndGet);
        AtomicInteger aborted=new AtomicInteger();RhAdmission.Watch closed=operation.watch(()->fail("Closed request was cancelled"));closed.close();
        operation.watch(aborted::incrementAndGet);operation.cancel();operation.cancel();
        operation.watch(aborted::incrementAndGet).close();assertEquals(2,aborted.get());
        try{operation.checkpoint();fail();}catch(IOException expected){}run();run();
    }
    @Test public void linkageFailureAlsoCleansAndReleasesTheReservation(){
        operation=make();operation.start(op->{throw new UnsatisfiedLinkError("library unavailable");});run();
        assertTrue(RhActions.hasStream());run();assertEquals(1,finishes.get());assertTrue(result,result.contains("library unavailable"));
    }
    @Test public void handoffKeepsTheReservationUntilTheStreamAttaches()throws Exception{
        Intent intent=new Intent().putExtra(Game.EXTRA_PC_UUID,"host");operation=make();operation.start(op->()->{try{op.handoff(()->{RhAdmission.stamp(intent);launches.incrementAndGet();});}catch(IOException e){throw new IllegalStateException(e);}});run();
        assertEquals(1,launches.get());assertEquals("starting",operation.state().getString("phase"));rejectNew();
        Intent admitted=new Intent(intent);assertTrue(RhAdmission.allowGame(admitted));
        assertFalse("Only one Game may claim an admission",RhAdmission.allowGame(new Intent(intent)));
        RhActions.attachStream(stream);assertTrue(transferred);assertEquals(1,finishes.get());assertEquals(0,Transport.stops);
        assertTrue(RhAdmission.allowGame(new Intent(admitted)));assertFalse(RhAdmission.allowGame(new Intent(intent)));
    }
    @Test public void cancellingBetweenLaunchAndActivityCreationRejectsTheOldIntent()throws Exception{
        Intent intent=new Intent().putExtra(Game.EXTRA_PC_UUID,"host");operation=make();operation.start(op->()->{try{op.handoff(()->RhAdmission.stamp(intent));}catch(IOException e){throw new IllegalStateException(e);}});run();
        operation.cancel();assertFalse(RhAdmission.allowGame(new Intent(intent)));rejectNew();run();
        RhAdmission next=make();next.start(op->launches::incrementAndGet);assertFalse(RhAdmission.allowGame(new Intent(intent)));
        next.cancel();run();run();assertEquals(0,launches.get());
    }
    @Test public void pairingCancellationWaitsForTheActualPairingWorker(){
        operation=make();operation.start(op->()->{try{op.pair(()->{},pairCancels::incrementAndGet);}catch(IOException e){throw new IllegalStateException(e);}});run();
        operation.cancel();operation.cancel();assertEquals(1,pairCancels.get());assertEquals(0,queued.size());rejectNew();
        operation.pairFinished("配对完成");assertTrue(RhActions.hasStream());run();assertEquals("已取消连接",result);
    }
    @Test public void successfulPairingReleasesOnlyAfterProxyCleanup(){
        operation=make();operation.start(op->()->{try{op.pair(()->{},()->{});}catch(IOException e){throw new IllegalStateException(e);}});run();
        operation.pairFinished("配对完成");assertTrue(RhActions.hasStream());run();assertEquals("配对完成",result);assertEquals(1,Transport.stops);
    }
    @Test public void cleanupFailureHoldsTheReservationAndExplicitRetryCanReapIt()throws Exception{
        operation=make();operation.start(op->launches::incrementAndGet);operation.cancel();run();Transport.fail=true;run();
        assertTrue(RhActions.hasStream());assertEquals("error",operation.state().getString("phase"));assertEquals(0,finishes.get());rejectNew();
        Transport.fail=false;operation.cancel();run();assertFalse(RhActions.hasStream());assertEquals(1,finishes.get());
    }
    @Test public void finishingActivityCannotRunALateContinuation(){
        operation=make();operation.start(op->launches::incrementAndGet);queued.removeFirst().run();activity.finish();idle();run();assertEquals(0,launches.get());
    }
    @Test public void missingStreamWindowTimesOutAndCleansTheAdmission(){
        operation=make();operation.start(op->()->{try{op.handoff(()->{});}catch(IOException e){throw new IllegalStateException(e);}});run();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(16));assertTrue(RhActions.hasStream());run();assertTrue(result,result.contains("未能启动"));
    }
    @Test public void rejectedGameLifecycleCannotStopTheCurrentAdmissionOrTouchUninitializedInput(){
        operation=make();operation.start(op->launches::incrementAndGet);
        org.robolectric.android.controller.ActivityController<Game> rejected=Robolectric.buildActivity(Game.class,new Intent(activity,Game.class).putExtra(Game.EXTRA_PC_UUID,"other-host"));
        rejected.create().start().resume();assertTrue(rejected.get().isFinishing());
        rejected.get().onWindowFocusChanged(false);rejected.pause().stop().destroy();
        assertTrue(RhActions.ownsAdmission(operation));assertEquals(0,Transport.stops);operation.cancel();run();run();
    }
    private ComputerDetails device(){
        ComputerDetails d=new ComputerDetails();d.uuid="host";d.name="PC";d.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);d.activeAddress=d.manualAddress;
        d.state=ComputerDetails.State.ONLINE;d.pairState=PairingManager.PairState.PAIRED;d.serverCert=new AndroidCryptoProvider(activity).getClientCertificate();return d;
    }
    private RhHome.Actions callbacks(CountDownLatch entered){return new RhHome.Actions(){
        public void connect(ComputerDetails d){launches.incrementAndGet();}public void add(String address){}public void advanced(){}
        public void pair(ComputerDetails d,String pin){}public void cancelPair(){pairCancels.incrementAndGet();}public void refresh(){}
        public Runnable prepareConnect(ComputerDetails d,RhAdmission op)throws Exception{
            try(RhAdmission.Watch request=op.watch(()->{})){entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IOException("test gate timed out");return ()->launches.incrementAndGet();}
        }
    };}
    private Button button(View view,String text){
        if(view instanceof Button&&((Button)view).getText().toString().equals(text))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button b=button(((ViewGroup)view).getChildAt(i),text);if(b!=null)return b;}
        return null;
    }
    private void awaitIdle()throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do{idle();if(!RhActions.hasStream())return;Thread.sleep(10);}while(System.nanoTime()<end);fail("Admission did not finish");
    }
    @Test public void homeCancelButtonAndApiUseTheSameReservationWhileAppListIsPending()throws Exception{
        release=new CountDownLatch(1);CountDownLatch entered=new CountDownLatch(1);home=new RhHome(activity,callbacks(entered));home.update(device());
        assertTrue(RhActions.dispatch("device.connect",RhActions.obj("uuid","host")).toString(),RhActions.hasStream());
        assertTrue(entered.await(3,TimeUnit.SECONDS));Button cancel=button(activity.getWindow().getDecorView(),"取消连接");assertNotNull(cancel);cancel.performClick();
        assertTrue(home.state().getBoolean("networkBusy"));assertTrue(home.state().getJSONObject("admission").getBoolean("cancelled"));
        assertFalse(RhActions.dispatch("device.connect",RhActions.obj("uuid","host")).getBoolean("ok"));
        assertTrue(RhActions.dispatch("stream.disconnect",RhActions.obj()).getBoolean("ok"));
        release.countDown();awaitIdle();assertEquals(0,launches.get());assertFalse(home.state().getBoolean("networkBusy"));assertEquals(1,Transport.stops);
    }
    @Test public void homePairCancelApiWaitsForPairCompletion()throws Exception{
        release=new CountDownLatch(0);home=new RhHome(activity,callbacks(new CountDownLatch(0)));home.update(device());
        assertTrue(RhActions.dispatch("device.pair",RhActions.obj("uuid","host","pin","1234")).getBoolean("ok"));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);do{idle();if(home.state().getBoolean("pairing"))break;Thread.sleep(10);}while(System.nanoTime()<end);
        assertTrue(home.state().getBoolean("pairing"));assertTrue(RhActions.dispatch("pair.cancel",RhActions.obj()).getBoolean("ok"));assertEquals(1,pairCancels.get());
        assertTrue(RhActions.hasStream());home.pairFinished(false,"已取消配对");awaitIdle();assertEquals(0,launches.get());
    }
    @Test public void busySyncAndUpdateWorkersCannotQueueTheConnectionBehindDownloads()throws Exception{
        java.util.concurrent.ExecutorService background=org.robolectric.util.ReflectionHelpers.getStaticField(RhHome.class,"NETWORK");
        CountDownLatch busy=new CountDownLatch(2),finishBackground=new CountDownLatch(1);
        java.util.ArrayList<java.util.concurrent.Future<?>> blocked=new java.util.ArrayList<>();
        try{
            for(int i=0;i<2;i++)blocked.add(background.submit(()->{busy.countDown();try{finishBackground.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            assertTrue(busy.await(2,TimeUnit.SECONDS));release=new CountDownLatch(1);CountDownLatch entered=new CountDownLatch(1);
            home=new RhHome(activity,callbacks(entered));home.update(device());
            assertTrue(RhActions.dispatch("device.connect",RhActions.obj("uuid","host")).getBoolean("ok"));
            assertTrue("Connection was queued behind unrelated downloads",entered.await(2,TimeUnit.SECONDS));
            home.dispose();release.countDown();awaitIdle();assertEquals(0,launches.get());
        }finally{finishBackground.countDown();for(java.util.concurrent.Future<?> task:blocked)task.get(2,TimeUnit.SECONDS);}
    }
}
