package com.limelight.rhremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import com.limelight.Game;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Cancellation after Game.onCreate was admitted still waits for that Game to stop. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhAdmissionTest.Transport.class,RhRecoveryTest.NoNativeMoonBridge.class})
public class RhAdmissionGameHandoffTest {
    private Activity home,game;private RhProfile profile;private RhAdmission admission;private RhRecovery lifecycle;
    private Intent intent;private Consumer<Boolean> stopped;private int completions;
    private final ArrayDeque<Runnable> jobs=new ArrayDeque<>();
    private final RhActions.Target stream=new RhActions.Target(){public JSONObject state(){return RhActions.obj();}public JSONObject perform(String id,JSONObject args){return RhActions.obj();}};
    @Before public void setup()throws Exception{
        home=Robolectric.buildActivity(Activity.class).setup().get();RhStatistics.initialize(home);RhStatistics.configure(false);
        RhAdmissionTest.Transport.stops=0;RhAdmissionTest.Transport.fail=false;
        profile=RhProfile.fromJson("host",RhActions.obj("width",1920,"height",1080,"fps",30,"bitrateMbps",25,"codec","H.264","swapButtons",false,"trackpad",false,"statistics",false));
        intent=new Intent(home,Game.class).putExtra(Game.EXTRA_PC_UUID,"host");
        admission=make();admission.start(op->()->{try{op.handoff(()->RhAdmission.stamp(intent));}catch(Exception e){throw new IllegalStateException(e);}});jobs.removeFirst().run();idle();
        assertTrue(RhAdmission.allowGame(intent));
    }
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void drain(){for(int i=0;i<30;i++){idle();if(jobs.isEmpty())return;jobs.removeFirst().run();}fail("Cleanup did not settle");}
    private RhAdmission make(){return new RhAdmission(home,"host",jobs::add,new RhAdmission.Listener(){public void changed(RhAdmission op){}public void finished(RhAdmission op,boolean transferred,String message){completions++;}});}
    private void rejectNew(){try{make().start(op->()->{});fail("Claimed Game's resources were released early");}catch(IllegalStateException expected){}}
    private void initializeGame(){
        game=Robolectric.buildActivity(Activity.class).setup().get();game.setIntent(intent);
        lifecycle=new RhRecovery(game,profile,done->stopped=done,(i,p,f,op)->{throw new AssertionError("Unexpected recovery");},jobs::add);lifecycle.frame();idle();
    }
    @After public void cleanup(){
        RhActions.detachStream(stream);admission.cancel();if(lifecycle!=null)lifecycle.cancel();idle();if(stopped!=null)stopped.accept(true);drain();RhStatistics.finish();assertFalse(RhActions.hasStream());
    }
    @Test public void cancelAfterAdmissionWaitsForTheDelayedGamesActualCleanup(){
        admission.cancel();assertTrue(jobs.isEmpty());rejectNew();assertTrue(admission.state().optBoolean("cleanupPending"));
        initializeGame();assertNotNull(stopped);assertFalse(RhActions.attachStream(stream));stopped.accept(false);rejectNew();assertTrue(jobs.isEmpty());
        stopped.accept(true);stopped.accept(true);assertTrue(RhActions.hasStream());drain();assertFalse(RhActions.hasStream());assertEquals(1,completions);assertEquals(1,RhAdmissionTest.Transport.stops);
    }
    @Test public void initialGameBackgroundCancelsAdmissionWithoutStealingItsReservation(){
        initializeGame();assertNull(stopped);lifecycle.background();assertNotNull(stopped);assertTrue(RhActions.ownsAdmission(admission));assertNull(RhActions.recovery());rejectNew();
        stopped.accept(true);drain();assertFalse(RhActions.hasStream());assertEquals(1,completions);
    }
    @Test public void controllerHandoffClearsTheOldHomeReferenceAndDoesNotStopTheProxy(){
        initializeGame();assertTrue(RhActions.attachStream(stream));assertFalse(RhActions.ownsAdmission(admission));assertNull(ReflectionHelpers.getField(lifecycle,"initialAdmission"));
        assertTrue(RhAdmission.allowGame(new Intent(intent)));admission.cancel();assertEquals(0,RhAdmissionTest.Transport.stops);assertEquals(1,completions);
    }
    @Test public void cancelledClaimCannotReopenAnActivityAfterItsReservationWasReleased(){
        admission.cancel();initializeGame();stopped.accept(true);drain();assertFalse(RhActions.hasStream());
        org.robolectric.android.controller.ActivityController<Game> rejected=Robolectric.buildActivity(Game.class,intent);
        rejected.create().start().resume();assertTrue(rejected.get().isFinishing());rejected.get().onWindowFocusChanged(false);rejected.pause().stop().destroy();assertFalse(RhActions.hasStream());
    }
}
