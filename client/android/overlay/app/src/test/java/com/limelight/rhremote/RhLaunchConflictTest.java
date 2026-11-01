package com.limelight.rhremote;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RhLaunchConflictTest {
    @Test public void knownHostRepliesExplainDifferentRetryDecisions(){
        assertEquals(RhLaunchConflict.INITIAL_DISPLAY_BUSY,RhLaunchConflict.from(409,"Another launch or stream is using this host; disconnect it before choosing an initial display"));
        assertTrue(RhLaunchConflict.INITIAL_DISPLAY_BUSY.message.contains("初始屏幕"));
        assertEquals(RhLaunchConflict.PENDING_CHANGED,RhLaunchConflict.from(409,"Launch request no longer owns the pending slot"));
        assertEquals(RhLaunchConflict.DUPLICATE,RhLaunchConflict.from(409,"Launch ownership admission rejected"));
        for(RhLaunchConflict value:RhLaunchConflict.values())if(value!=RhLaunchConflict.UNKNOWN)assertTrue(value.message.contains("409"));
    }
    @Test public void unknownAndUntrustedReplyTextNeverBecomesStoredUserFeedback(){
        assertEquals(RhLaunchConflict.UNKNOWN,RhLaunchConflict.from(409,"https://private.test/?rhremoteLaunchToken="+"a".repeat(64)));
        assertEquals(RhLaunchConflict.UNKNOWN,RhLaunchConflict.from(409,"Launch ownership admission rejected extra private details"));
        assertEquals(RhLaunchConflict.UNKNOWN,RhLaunchConflict.from(401,"Launch ownership admission rejected"));
        assertEquals(RhLaunchConflict.UNKNOWN,RhLaunchConflict.from(409,null));assertEquals("",RhLaunchConflict.UNKNOWN.message);
    }
}
