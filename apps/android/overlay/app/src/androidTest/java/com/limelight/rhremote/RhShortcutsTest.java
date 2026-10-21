package com.limelight.rhremote;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RhShortcutsTest {
    @Test public void releasesEveryModifierAfterTransportFailure()throws Exception {
        List<Integer> releases=new ArrayList<>();
        try {RhShortcuts.send(RhShortcuts.find(InstrumentationRegistry.getInstrumentation().getTargetContext(),"ctrl-alt-left"),(key,action,mods)->{int vk=key&255;if(action==3&&vk==37)throw new IllegalStateException("transport failed");if(action==4)releases.add(vk);});fail("must report the send failure");}catch(IllegalStateException expected){}
        assertEquals(Arrays.asList(37,18,17),releases);
    }
    @Test public void secureAttentionNeverFallsBackToOrdinaryKeyboard()throws Exception {
        try {RhShortcuts.send(RhShortcuts.find(InstrumentationRegistry.getInstrumentation().getTargetContext(),"ctrl-alt-delete"),(key,action,mods)->fail("must use the secure host operation"));fail("must reject key injection");}catch(IllegalArgumentException expected){}
    }
}
