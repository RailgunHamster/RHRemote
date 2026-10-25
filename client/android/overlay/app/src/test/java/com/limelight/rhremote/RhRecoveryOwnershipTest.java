package com.limelight.rhremote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Looper;
import com.limelight.Game;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.nvstream.http.ComputerDetails;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real reservations and Game admission; native cleanup is explicitly acknowledged. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhAdmissionTest.Transport.class,RhRecoveryTest.NoNativeMoonBridge.class})
public class RhRecoveryOwnershipTest {
    public static class NoGame extends Activity {
        @Override public void startActivity(Intent intent){throw new ActivityNotFoundException("test unavailable Game");}
    }
    private Activity activity;private RhProfile profile;private RhRecovery recovery,successor;
    private final ArrayDeque<Runnable> jobs=new ArrayDeque<>();
    private Consumer<Boolean> originalCleanup,successorCleanup;private int resolutions;
    private final RhActions.Target stream=new RhActions.Target(){public JSONObject state(){return RhActions.obj("connected",true);}public JSONObject perform(String id,JSONObject args){return RhActions.obj();}};
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();initializeActivity();
        RhStatistics.initialize(activity);RhStatistics.configure(false);RhActions.initialize(activity,null);
        RhAdmissionTest.Transport.stops=0;RhAdmissionTest.Transport.fail=false;
        profile=RhProfile.fromJson("host",RhActions.obj("width",1920,"height",1080,"fps",30,"bitrateMbps",25,"codec","H.264","audioEnabled",false,"swapButtons",true,"trackpad",false,"statistics",false));
        ReflectionHelpers.setStaticField(RhNetwork.class,"last",RhActions.obj("route","lan","selectedAddress","192.168.0.104"));
    }
    private void initializeActivity(){activity.setIntent(new Intent(activity,Game.class).putExtra(Game.EXTRA_PC_UUID,"host").putExtra(Game.EXTRA_HOST,"192.168.0.104").putExtra(Game.EXTRA_PORT,47989));}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void run(){jobs.removeFirst().run();idle();}
    private void drain(){for(int i=0;i<30;i++){idle();if(jobs.isEmpty())return;jobs.removeFirst().run();}fail("Cleanup did not settle");}
    private RhAdmission admission(){return new RhAdmission(activity,"another-host",jobs::add,new RhAdmission.Listener(){public void changed(RhAdmission op){}public void finished(RhAdmission op,boolean handoff,String message){}});}
    private void rejectNew(){try{admission().start(op->()->{});fail("New admission stole the recovery transport");}catch(IllegalStateException expected){}}
    private void start(){
        recovery=new RhRecovery(activity,profile,done->originalCleanup=done,(intent,p,failed,operation)->{
            operation.checkpoint();resolutions++;ComputerDetails d=new ComputerDetails();d.activeAddress=new ComputerDetails.AddressTuple("127.77.0.2",48989);return d;
        },jobs::add);
        assertTrue(recovery.stageFailed(1,111));idle();assertNotNull(originalCleanup);
    }
    private Intent launch(){start();originalCleanup.accept(true);run();Intent next=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(next);return next;}
    private void initializeSuccessor(Intent intent){
        Activity next=Robolectric.buildActivity(Activity.class).setup().get();next.setIntent(intent);
        successor=new RhRecovery(next,profile,done->successorCleanup=done,(i,p,f,op)->{throw new AssertionError("Successor must not start another route");},jobs::add);
        successor.frame();idle();
    }
    @After public void cleanup(){
        RhAdmissionTest.Transport.fail=false;RhActions.detachStream(stream);
        if(successor!=null)successor.cancel();if(recovery!=null)recovery.cancel();
        if(originalCleanup!=null)originalCleanup.accept(true);idle();if(successorCleanup!=null)successorCleanup.accept(true);
        RhAdmission pending=RhActions.admission();if(pending!=null)pending.cancel();drain();RhStatistics.finish();
        assertFalse("Session ownership leaked",RhActions.hasStream());
    }
    @Test public void reservationTransfersOnlyAfterOneGameClaimsAndItsControllerAttaches(){
        Intent next=launch();assertTrue(RhActions.ownsRecovery(recovery));rejectNew();assertFalse(RhActions.attachStream(stream));
        Intent claimed=new Intent(next);assertTrue(RhRecovery.allowGame(claimed));assertFalse(RhRecovery.allowGame(new Intent(next)));rejectNew();
        assertTrue(RhActions.attachStream(stream));assertFalse(RhActions.ownsRecovery(recovery));assertTrue(RhActions.hasStream());
        recovery.cancel();recovery.background();assertTrue(RhActions.hasStream());assertEquals(0,RhAdmissionTest.Transport.stops);
        assertTrue(RhRecovery.allowGame(new Intent(claimed)));assertFalse(RhRecovery.allowGame(new Intent(next)));
    }
    @Test public void cancelBetweenLaunchAndCreationRejectsTheOldWindowAndWaitsForCleanup(){
        Intent next=launch();assertTrue(RhActions.dispatch("stream.disconnect",RhActions.obj()).optBoolean("ok"));
        assertTrue(RhActions.hasStream());rejectNew();assertFalse(RhRecovery.allowGame(new Intent(next)));run();assertFalse(RhActions.hasStream());
        RhAdmission newer=admission();newer.start(op->()->{});
        org.robolectric.android.controller.ActivityController<Game> rejected=Robolectric.buildActivity(Game.class,next);
        rejected.create().start().resume();assertTrue(rejected.get().isFinishing());rejected.get().onWindowFocusChanged(false);rejected.pause().stop().destroy();
        assertTrue(RhActions.ownsAdmission(newer));assertEquals(1,RhAdmissionTest.Transport.stops);newer.cancel();drain();
    }
    @Test public void cancelAfterGameClaimWaitsForThatGamesRealCleanup(){
        Intent next=launch();assertTrue(RhRecovery.allowGame(next));recovery.cancel();assertTrue(jobs.isEmpty());rejectNew();
        initializeSuccessor(next);assertNotNull(successorCleanup);assertFalse(RhActions.attachStream(stream));
        // A duplicate acknowledgement from the old Game must not clear the new Game's lease.
        originalCleanup.accept(true);successorCleanup.accept(false);assertTrue(RhActions.hasStream());assertTrue(jobs.isEmpty());rejectNew();
        successorCleanup.accept(true);assertTrue(RhActions.hasStream());run();assertFalse(RhActions.hasStream());assertEquals(1,RhAdmissionTest.Transport.stops);
    }
    @Test public void handoffTimeoutRejectsALateIntentAfterTransportCleanup(){
        Intent next=launch();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertTrue(RhActions.hasStream());assertTrue(recovery.state().optString("error").contains("接管"));assertFalse(RhRecovery.allowGame(next));run();assertFalse(RhActions.hasStream());
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("接管"));
    }
    @Test public void staleGameLifecycleIsRejectedEvenWhenNoNewAdmissionExists(){
        Intent next=launch();recovery.cancel();run();assertFalse(RhActions.hasStream());
        org.robolectric.android.controller.ActivityController<Game> rejected=Robolectric.buildActivity(Game.class,next);
        rejected.create().start().resume();assertTrue(rejected.get().isFinishing());rejected.get().onWindowFocusChanged(false);rejected.pause().stop().destroy();
        assertFalse(RhActions.hasStream());assertEquals(1,RhAdmissionTest.Transport.stops);
    }
    @Test public void successfulHandoffDoesNotRetainTheOldGameForTheWholeSession(){
        Intent next=launch();assertTrue(RhRecovery.allowGame(next));initializeSuccessor(next);
        assertTrue(RhActions.attachStream(stream));assertNull(ReflectionHelpers.getField(successor,"predecessor"));successor.background();
        // Background now stops the new session using its own reservation.
        assertTrue(RhActions.ownsRecovery(successor));assertNotNull(successorCleanup);successorCleanup.accept(true);drain();
    }
    @Test public void timeoutAfterGameClaimCannotPretendThatNewGameHasStopped(){
        Intent next=launch();assertTrue(RhRecovery.allowGame(next));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertTrue(jobs.isEmpty());rejectNew();initializeSuccessor(next);assertNotNull(successorCleanup);
        successorCleanup.accept(true);run();assertFalse(RhActions.hasStream());
    }
    @Test public void oldGamesBackgroundDoesNotCancelItsSuccessor(){
        Intent next=launch();recovery.background();assertTrue(jobs.isEmpty());assertEquals("starting",recovery.state().optString("phase"));
        assertTrue(RhRecovery.allowGame(next));assertTrue(RhActions.attachStream(stream));assertEquals(0,RhAdmissionTest.Transport.stops);
    }
    @Test public void delayedUnreservedOldGameCannotStealANewActivitiesStream(){
        recovery=new RhRecovery(activity,profile,done->{throw new AssertionError("Old Game must not stop the new session");},(i,p,f,op)->{throw new AssertionError("No route resolution expected");},jobs::add);
        Activity next=Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(RhActions.attachStream(stream,next));RhActions.clearConnectionError();
        recovery.background();assertFalse(RhActions.ownsRecovery(recovery));assertTrue(RhActions.hasStream());assertTrue(jobs.isEmpty());
        assertTrue(recovery.terminated(MoonBridge.ML_ERROR_PROTECTED_CONTENT));idle();
        assertTrue(activity.isFinishing());assertTrue(RhActions.hasStream());assertEquals("",RhActions.lastConnectionError());
    }
    @Test public void wrongHostAndUnmarkedGameCannotClaimARecovery(){
        Intent next=launch();assertFalse(RhRecovery.allowGame(new Intent(next).putExtra(Game.EXTRA_PC_UUID,"wrong-host")));
        assertFalse(RhRecovery.allowGame(new Intent().putExtra(Game.EXTRA_PC_UUID,"host")));assertFalse(RhActions.attachStream(stream));
        assertTrue(RhRecovery.allowGame(next));assertTrue(RhActions.attachStream(stream));
    }
    @Test public void duplicateOriginalCleanupCannotLaunchAnotherRoute(){
        Intent next=launch();originalCleanup.accept(true);originalCleanup.accept(false);idle();
        assertEquals(1,resolutions);assertTrue(jobs.isEmpty());assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertTrue(RhRecovery.allowGame(next));assertTrue(RhActions.attachStream(stream));
    }
    @Test public void launchFailureCleansTheTunnelBeforeReleasingTheReservation(){
        activity=Robolectric.buildActivity(NoGame.class).setup().get();initializeActivity();start();originalCleanup.accept(true);run();
        assertTrue(recovery.state().optString("error").contains("未能启动"));assertTrue(RhActions.hasStream());rejectNew();run();assertFalse(RhActions.hasStream());
    }
    @Test public void failedTunnelCleanupKeepsItsLeaseAndCanBeRetried(){
        Intent next=launch();recovery.cancel();RhAdmissionTest.Transport.fail=true;run();
        assertTrue(RhActions.hasStream());rejectNew();assertFalse(RhRecovery.allowGame(next));
        RhAdmissionTest.Transport.fail=false;recovery.cancel();run();assertFalse(RhActions.hasStream());
    }
}
