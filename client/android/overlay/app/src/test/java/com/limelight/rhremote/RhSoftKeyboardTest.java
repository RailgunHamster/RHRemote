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
        Boolean visible=false;boolean reject,settled=true;Runnable changed;
        final List<Boolean> requests=new ArrayList<>();
        public Boolean visible(){return visible;}
        public boolean settled(){return settled;}
        public void setChanged(Runnable changed){this.changed=changed;}
        void observe(Boolean value){visible=value;if(changed!=null)changed.run();}
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
    @Test public void dismissingFromTheImeRefreshesControlsAndTheNextToggleCanShowAgain(){
        Backend backend=new Backend();RhSoftKeyboard keyboard=keyboard(backend);List<Boolean> changes=new ArrayList<>(),completed=new ArrayList<>();
        keyboard.setChanged(()->changes.add(keyboard.visible()));
        keyboard.request(true,(visible,error)->completed.add(visible));backend.observe(true);advance(0);
        assertEquals(Collections.singletonList(true),completed);assertFalse(keyboard.pending());
        // The user hides the IME itself; no application action or state read occurs.
        backend.observe(false);advance(0);
        assertEquals(Collections.singletonList(false),changes);assertFalse(keyboard.target());assertFalse(keyboard.visible());
        assertEquals("Observing dismissal must not send another hide request",Collections.singletonList(true),backend.requests);
        backend.observe(false);backend.observe(null);advance(0);assertEquals(1,changes.size());
        keyboard.request(!keyboard.visible(),(visible,error)->completed.add(visible));backend.observe(true);advance(0);
        assertEquals(Arrays.asList(true,true),backend.requests);assertEquals(Arrays.asList(true,true),completed);assertTrue(keyboard.visible());keyboard.dispose();
    }
    @Test public void disposalUnsubscribesAndCancelsAnExternalVisibilityNotification(){
        Backend backend=new Backend();RhSoftKeyboard keyboard=keyboard(backend);keyboard.setChanged(()->fail("Disposed UI must not refresh"));
        Runnable stale=backend.changed;backend.observe(true);keyboard.dispose();stale.run();advance(0);
        assertNull(backend.changed);assertFalse(keyboard.visible());assertTrue(backend.requests.isEmpty());
    }
    @Test @Config(sdk=35) public void systemInsetsKeepTheOuterMobileKeyboardButtonInSyncWithoutAnAppAction(){
        android.app.Activity activity=Robolectric.buildActivity(android.app.Activity.class).setup().get();activity.setTheme(com.limelight.R.style.RhTheme);
        android.widget.FrameLayout root=new android.widget.FrameLayout(activity);android.view.View surface=new android.view.View(activity);root.addView(surface);activity.setContentView(root);
        activity.getSharedPreferences("rhremote.global",0).edit().remove("hideKeyboardButton").commit();
        RhFloatingKeyboard floating=new RhFloatingKeyboard(activity,root,(id,args)->fail("No UI action needed for an external IME change"));
        RhSoftKeyboard keyboard=keyboard(new RhSoftKeyboard.WindowBackend(activity.getWindow(),surface));keyboard.setChanged(()->floating.update(true,false,keyboard.visible()));floating.update(true,false,keyboard.visible());
        advance(0);
        android.widget.Button button=root.findViewWithTag("floating-ime-toggle");assertEquals("手机键盘",button.getText().toString());
        int[] parentCalls={0};androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{parentCalls[0]++;return insets;});
        try{
            for(boolean shown:new boolean[]{true,false,true,false}){
                androidx.core.view.WindowInsetsCompat insets=new androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.of(0,0,0,shown?320:0)).setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(),shown).build();
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,insets);assertEquals("Dispatched system visibility",shown,keyboard.visible());advance(0);
                assertEquals(shown?"收起键盘":"手机键盘",button.getText().toString());assertEquals(shown?"关闭手机键盘":"打开手机键盘",button.getContentDescription().toString());assertEquals(shown,button.isSelected());assertEquals(shown,keyboard.target());assertFalse(keyboard.pending());
            }
            assertEquals("Keep the owner's inset listener",4,parentCalls[0]);
        }finally{keyboard.dispose();floating.dispose();activity.finish();}
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
    @Test public void aVisibleKeyboardAtTheDeadlineIsNotReportedMissingOrHiddenByItsOwner(){
        Backend backend=new Backend();backend.visible=true;backend.settled=false;
        RhSoftKeyboard keyboard=keyboard(backend);List<String> completed=new ArrayList<>();
        keyboard.request(true,(visible,error)->completed.add(visible+":"+error));advance(2100);
        assertEquals("The platform has already shown the requested keyboard",Collections.singletonList("true:"),completed);
        assertEquals("Do not undo a successful show while its animation callback is late",Collections.singletonList(true),backend.requests);
        assertFalse(keyboard.pending());assertEquals("",keyboard.error());
    }
    @Test public void aHiddenKeyboardAtTheDeadlineIsNotReportedStillOpen(){
        Backend backend=new Backend();backend.visible=false;backend.settled=false;
        RhSoftKeyboard keyboard=keyboard(backend);List<String> completed=new ArrayList<>();
        keyboard.request(false,(visible,error)->completed.add(visible+":"+error));advance(2100);
        assertEquals(Collections.singletonList("false:"),completed);
        assertEquals(Collections.singletonList(false),backend.requests);assertFalse(keyboard.pending());
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
    @Test @Config(sdk=35) public void showingImeCannotPanTheStreamOrItsControlHandleOffscreen(){
        android.app.Activity activity=Robolectric.buildActivity(android.app.Activity.class).setup().get();
        android.view.Window window=activity.getWindow();android.view.View surface=new android.view.View(activity);surface.setFocusableInTouchMode(true);activity.setContentView(surface);
        int state=android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        window.setSoftInputMode(state|android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        RhSoftKeyboard.WindowBackend backend=new RhSoftKeyboard.WindowBackend(window,surface);backend.request(true);
        assertEquals(state|android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,window.getAttributes().softInputMode);
        backend.request(false);backend.request(true);assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,window.getAttributes().softInputMode&android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        backend.dispose();assertEquals("Restore the owner's adjustment flags at session disposal",state|android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,window.getAttributes().softInputMode);activity.finish();
    }
    @Test public void legacyAndroidUsesResizeForCompatibleImeInsetsInsteadOfPan(){
        android.app.Activity activity=Robolectric.buildActivity(android.app.Activity.class).setup().get();android.view.View surface=new android.view.View(activity);surface.setFocusableInTouchMode(true);activity.setContentView(surface);
        RhSoftKeyboard.WindowBackend backend=new RhSoftKeyboard.WindowBackend(activity.getWindow(),surface);backend.request(true);
        assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,activity.getWindow().getAttributes().softInputMode&android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);backend.dispose();activity.finish();
    }
}
