package com.limelight.rhremote;

import android.app.AlertDialog;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.nio.file.Files;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The real Home decisions and dialog key dispatch; no OS or remote input. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public final class RhHomeDialogKeysTest {
    private final RhAppearanceFlowTest fixture=new RhAppearanceFlowTest();
    @Before public void setup(){fixture.create(false);}
    @After public void cleanup(){fixture.cleanup();}
    private AlertDialog dialog(String name)throws Exception{Field field=RhHome.class.getDeclaredField(name);field.setAccessible(true);return (AlertDialog)field.get(fixture.home);}
    private AlertDialog decision()throws Exception{fixture.home.perform("device.settings.open",RhActions.obj("uuid","host"));fixture.home.perform("settings.input",RhActions.obj("key","bitrateMbps","value","37"));fixture.home.perform("app.settings.tab",RhActions.obj("tab","about"));return dialog("navigationDialog");}
    private boolean key(AlertDialog dialog,int action,int code,int flags){long now=android.os.SystemClock.uptimeMillis();KeyEvent event=new KeyEvent(now,now,action,code,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,flags,InputDevice.SOURCE_KEYBOARD);return code==KeyEvent.KEYCODE_ENTER?dialog.getCurrentFocus().dispatchKeyEvent(event):dialog.dispatchKeyEvent(event);}
    private void press(AlertDialog dialog,int code){assertTrue(key(dialog,KeyEvent.ACTION_DOWN,code,0));assertTrue(key(dialog,KeyEvent.ACTION_UP,code,0));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    @Test public void escapeContinuesEditingAndKeepsTheDraft()throws Exception{
        AlertDialog popup=decision();String saved=RhShared.canonical(RhShared.read(fixture.activity));press(popup,KeyEvent.KEYCODE_ESCAPE);assertTrue(fixture.home.state().isNull("navigationPrompt"));assertFalse(popup.isShowing());assertEquals("settings",fixture.home.state().getString("page"));assertEquals("37",fixture.home.state().getJSONObject("settingsInputs").getString("bitrateMbps"));assertEquals(saved,RhShared.canonical(RhShared.read(fixture.activity)));
    }
    @Test public void enterStartsAtTheNonDestructiveChoice()throws Exception{
        AlertDialog popup=decision();assertTrue("Continue editing should be the initial keyboard choice",popup.getButton(AlertDialog.BUTTON_NEUTRAL).hasFocus());press(popup,KeyEvent.KEYCODE_ENTER);assertTrue(fixture.home.state().isNull("navigationPrompt"));assertTrue(fixture.home.state().getBoolean("settingsDirty"));assertEquals("settings",fixture.home.state().getString("page"));
    }
    @Test public void canceledOrUnpairedEscapeCannotResolveTheCurrentOrReplacementDecision()throws Exception{
        AlertDialog old=decision();assertTrue(key(old,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ESCAPE,0));assertTrue(old.isShowing());assertTrue(key(old,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ESCAPE,0));assertTrue(key(old,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ESCAPE,KeyEvent.FLAG_CANCELED));assertTrue(old.isShowing());
        assertTrue(key(old,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ESCAPE,0));fixture.home.perform("navigation.resolve",RhActions.obj("choice","stay"));fixture.home.perform("app.settings.tab",RhActions.obj("tab","about"));AlertDialog current=dialog("navigationDialog");assertNotSame(old,current);assertTrue(key(current,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ESCAPE,0));assertTrue(current.isShowing());key(old,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ESCAPE,0);assertTrue(current.isShowing());press(current,KeyEvent.KEYCODE_ESCAPE);assertFalse(current.isShowing());
    }
    @Test public void escapeCancelsSyncRemovalWithoutChangingTheSavedAccount()throws Exception{
        org.json.JSONObject account=RhActions.obj("url","http://example.test/dav/","username","fixture-user","ciphertext","fixture-placeholder-not-decrypted","automatic",false,"intervalMinutes",15);java.io.File file=new java.io.File(fixture.activity.getFilesDir(),"webdav-local.json");RhShared.write(file,account);Field local=RhSync.class.getDeclaredField("local");local.setAccessible(true);local.set(RhSyncWork.shared(fixture.activity).sync,account);byte[] saved=Files.readAllBytes(file.toPath());
        fixture.home.perform("app.settings.tab",RhActions.obj("tab","sync"));fixture.home.perform("sync.clear",RhActions.obj());AlertDialog popup=dialog("syncClearDialog");press(popup,KeyEvent.KEYCODE_ESCAPE);assertTrue(fixture.home.state().isNull("syncClearPrompt"));assertFalse(popup.isShowing());assertArrayEquals(saved,Files.readAllBytes(file.toPath()));
    }
}
