package com.limelight.rhremote;

import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class RhSoftKeyboardTest {
    static final class Backend implements RhSoftKeyboard.Backend {
        Boolean visible=false;boolean reject,settled=true;
        final List<Boolean> requests=new ArrayList<>();
        public Boolean visible(){return visible;}
        public boolean settled(){return settled;}
        public void request(boolean show){requests.add(show);if(reject)throw new IllegalStateException("internal framework detail");}
    }
    private RhSoftKeyboard keyboard(RhSoftKeyboard.Backend backend){return new RhSoftKeyboard(backend,new Handler(Looper.getMainLooper()));}
    private void advance(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    @Test public void anAcceptedShowDoesNotPretendTheKeyboardIsVisible(){
        Backend backend=new Backend();RhSoftKeyboard keyboard=keyboard(backend);List<String> completed=new ArrayList<>();
        keyboard.request(true,(visible,error)->completed.add(visible+":"+error));advance(0);
        assertTrue(keyboard.pending());assertFalse(keyboard.visible());assertTrue(completed.isEmpty());
        backend.visible=true;advance(50);assertEquals(Collections.singletonList("true:"),completed);assertFalse(keyboard.pending());assertTrue(keyboard.visible());
    }
    @Test public void cancellingAPendingShowCannotRunItsOldCompletion(){
        Backend backend=new Backend();RhSoftKeyboard keyboard=keyboard(backend);List<String> completed=new ArrayList<>();
        keyboard.request(true,(visible,error)->completed.add("old"));keyboard.request(false,(visible,error)->completed.add("hide:"+visible));advance(3000);
        assertEquals(Arrays.asList(true,false),backend.requests);assertEquals(Collections.singletonList("hide:false"),completed);assertFalse(keyboard.pending());assertFalse(keyboard.visible());
    }
    @Test public void failedShowReportsAVisibleErrorAndCanBeRetried(){
        Backend backend=new Backend();RhSoftKeyboard keyboard=keyboard(backend);List<String> completed=new ArrayList<>();
        keyboard.request(true,(visible,error)->completed.add(error));advance(2100);
        assertFalse(keyboard.pending());assertFalse(keyboard.visible());assertEquals("键盘未打开",keyboard.error());assertEquals(Collections.singletonList("键盘未打开"),completed);
        assertEquals("Cancel an unobserved show before reporting timeout",Arrays.asList(true,false),backend.requests);
        backend.visible=true;keyboard.request(true,(visible,error)->completed.add(error));advance(0);
        assertEquals("",keyboard.error());assertTrue(keyboard.visible());assertEquals(Arrays.asList("键盘未打开",""),completed);
    }
    @Test public void unavailableInsetsCannotConfirmTheRequestedState(){
        Backend backend=new Backend();backend.visible=null;RhSoftKeyboard keyboard=keyboard(backend);List<Boolean> completed=new ArrayList<>();
        keyboard.request(true,(visible,error)->completed.add(visible));advance(2100);
        assertFalse(keyboard.visible());assertEquals(Collections.singletonList(false),completed);assertEquals("键盘未打开",keyboard.error());
    }
    @Test public void targetVisibilityDuringAnimationDoesNotCompleteTheRequest(){
        Backend backend=new Backend();backend.visible=true;backend.settled=false;RhSoftKeyboard keyboard=keyboard(backend);List<Boolean> completed=new ArrayList<>();
        keyboard.request(true,(visible,error)->completed.add(visible));advance(100);assertTrue(keyboard.pending());assertTrue(completed.isEmpty());
        backend.settled=true;advance(50);assertFalse(keyboard.pending());assertEquals(Collections.singletonList(true),completed);
    }
    @Test public void disposalCancelsCallbacksAndClosesOnlyAnOwnedRequest(){
        Backend backend=new Backend();RhSoftKeyboard keyboard=keyboard(backend);List<Boolean> completed=new ArrayList<>();
        keyboard.request(true,(visible,error)->completed.add(visible));keyboard.dispose();advance(3000);
        assertEquals(Arrays.asList(true,false),backend.requests);assertTrue(completed.isEmpty());assertFalse(keyboard.pending());
        Backend idle=new Backend();keyboard(idle).dispose();assertTrue(idle.requests.isEmpty());
        Backend external=new Backend();external.visible=true;RhSoftKeyboard observer=keyboard(external);assertTrue(observer.visible());observer.dispose();assertTrue("Do not hide an IME this controller never requested",external.requests.isEmpty());
    }
    @Test public void frameworkFailureDoesNotExposeInternalExceptionText(){
        Backend backend=new Backend();backend.reject=true;RhSoftKeyboard keyboard=keyboard(backend);List<String> completed=new ArrayList<>();
        keyboard.request(true,(visible,error)->completed.add(error));advance(0);
        assertFalse(keyboard.pending());assertFalse(keyboard.visible());assertEquals(Collections.singletonList("键盘未打开"),completed);
    }
    @Test public void windowBackendReplacesAndCancelsItsPostedShow(){
        android.app.Activity activity=Robolectric.buildActivity(android.app.Activity.class).setup().get();
        List<Runnable> posted=new ArrayList<>();
        android.view.View surface=new android.view.View(activity){
            @Override public boolean post(Runnable command){posted.add(command);return true;}
            @Override public boolean removeCallbacks(Runnable command){return posted.remove(command);}
        };
        surface.setFocusableInTouchMode(true);activity.setContentView(surface);
        RhSoftKeyboard.WindowBackend backend=new RhSoftKeyboard.WindowBackend(activity.getWindow(),surface);
        backend.request(true);assertEquals(1,posted.size());Runnable first=posted.get(0);
        backend.request(true);assertEquals(1,posted.size());assertNotSame(first,posted.get(0));first.run();assertFalse("Old callback must not clear the replacement",backend.settled());
        backend.request(false);assertTrue(posted.isEmpty());
        RhSoftKeyboard keyboard=keyboard(backend);keyboard.request(true,(visible,error)->fail("Disposed callback"));assertEquals(1,posted.size());
        keyboard.dispose();advance(3000);assertTrue(posted.isEmpty());activity.finish();
    }
}
