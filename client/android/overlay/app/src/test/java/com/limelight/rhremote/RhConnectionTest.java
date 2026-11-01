package com.limelight.rhremote;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhConnectionTest {
    @Test public void aCompletedSessionWithoutALocalStopFlagIsEndedRatherThanFailed(){RhConnection c=new RhConnection();c.begin("host","winserver");c.frame();c.observeIdle();assertEquals("ended",c.phase);assertEquals("已断开",c.title());assertEquals("",c.error);assertFalse(c.busy());}
    @Test public void disappearingBeforeTheFirstFrameRemainsARealFailure(){RhConnection c=new RhConnection();c.begin("host","winserver");c.finished(true,"");c.observeIdle();assertEquals("failed",c.phase);assertTrue(c.error.contains("远程画面"));}
    @Test public void cleanupAndGracefulCallbacksCannotEraseAnExplicitFailure(){RhConnection c=new RhConnection();c.begin("host","winserver");c.frame();c.fail("连接已断开 (-100)");c.gracefulEnd();c.observeIdle();assertEquals("failed",c.phase);assertEquals("连接已断开 (-100)",c.error);}
    @Test public void gracefulNativeTerminationDoesNotRequireTheLocalDisconnectButton(){RhConnection c=new RhConnection();c.begin("host","winserver");c.frame();c.gracefulEnd();assertEquals("ended",c.phase);assertEquals("",c.error);}
    @Test public void transport_ready_does_not_mean_a_remote_frame_was_received(){
        RhConnection c=new RhConnection();c.begin("host","winserver");c.stage(RhActions.obj("phase","starting"));c.finished(true,"");
        assertEquals("first-frame",c.phase);assertFalse(c.firstFrame);c.frame();assertTrue(c.firstFrame);assertEquals("connected",c.phase);
    }
    @Test public void explicit_cancel_ignores_late_frame_and_failure(){
        RhConnection c=new RhConnection();c.begin("host","winserver");c.stop();c.frame();c.fail("cancelled socket");assertEquals("cancelling",c.phase);
        c.finished(false,"cancelled socket");assertEquals("cancelled",c.phase);assertEquals("",c.error);assertFalse(c.busy());
    }
    @Test public void failed_attempt_retains_the_original_device_until_retry(){
        RhConnection c=new RhConnection();c.begin("host","winserver");c.stage(RhActions.obj("phase","identity"));c.finished(false,"certificate mismatch");
        assertEquals("failed",c.phase);assertEquals("certificate mismatch",c.error);assertEquals("host",c.uuid);assertFalse(c.busy());
        c.begin(c.uuid,c.name);assertEquals(2,c.attempt);assertEquals("",c.error);assertEquals("resolving",c.phase);
    }
    @Test public void admitted_failure_waits_for_cleanup_and_does_not_report_connected(){
        RhConnection c=new RhConnection();c.begin("host","winserver");c.stage(RhActions.obj("phase","error","error","cleanup pending"));
        assertEquals("cleaning",c.phase);assertTrue(c.busy());c.finished(false,"cleanup pending");assertEquals("failed",c.phase);assertFalse(c.busy());
    }
    @Test public void stopping_an_existing_session_is_distinct_from_startup_cancellation(){
        RhConnection c=new RhConnection();c.begin("host","winserver");c.frame();c.stop();c.finished(false,"");assertEquals("ended",c.phase);assertEquals("已断开",c.title());
    }
    @Test public void an_early_frame_is_not_overwritten_by_the_activity_handoff(){
        RhConnection c=new RhConnection();c.begin("host","winserver");c.frame();c.finished(true,"");assertEquals("connected",c.phase);assertTrue(c.firstFrame);
    }
    @Test public void retained_results_are_immutable_and_never_resume_live_work(){
        RhConnection source=new RhConnection();source.begin("host","winserver");assertNull(source.retainResult());source.fail("private fixture failure");RhConnection.Result saved=source.retainResult();assertNotNull(saved);
        source.begin("other","another host");source.fail("new failure");RhConnection restored=new RhConnection();assertTrue(restored.restoreResult(saved));assertEquals("host",restored.uuid);assertEquals("failed",restored.phase);assertEquals("private fixture failure",restored.error);assertEquals(1,restored.attempt);assertFalse(restored.busy());
        for(String phase:new String[]{"resolving","identity","pairing","starting","first-frame","connected","reconnecting","cancelling","cleaning","cancelled"}){source.phase=phase;assertNull(phase,source.retainResult());}
        source.phase="failed";source.stopping=true;assertNull(source.retainResult());assertFalse(new RhConnection().restoreResult(null));
    }
}
