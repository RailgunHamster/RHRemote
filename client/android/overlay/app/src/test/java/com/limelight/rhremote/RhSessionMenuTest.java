package com.limelight.rhremote;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.limelight.Game;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The production session controller and dialogs, without a native connection or OS input. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows=RhAdmissionTest.Controls.class)
public class RhSessionMenuTest {
    Game game;RhStreamControls controls;
    void create(boolean dark){
        game=Robolectric.buildActivity(Game.class).get();game.setIntent(new Intent().putExtra(Game.EXTRA_PC_NAME,"Fixture workstation").putExtra(Game.EXTRA_PC_UUID,"fixture"));
        RhShared.theme(game,dark);FrameLayout root=new FrameLayout(game);TextView stats=new TextView(game);stats.setId(R.id.performanceOverlay);root.addView(stats);View surface=new View(game);root.addView(surface);game.setContentView(root);
        RhProfile profile=RhProfile.load(game,"fixture");RhTouchController touch=new RhTouchController(new RhTouchController.TouchSink(){public void button(byte b,boolean down){throw new AssertionError("No remote input expected");}public void position(short x,short y,short w,short h){throw new AssertionError();}public void relative(short x,short y){throw new AssertionError();}public void scroll(short value){throw new AssertionError();}},surface,profile);
        controls=RhStreamControls.install(game,touch,new PreferenceConfiguration(),profile,null);assertNotNull(controls);controls.markConnected();controls.markFirstFrame();Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    AlertDialog dialog(String field)throws Exception{Field value=RhStreamControls.class.getDeclaredField(field);value.setAccessible(true);return (AlertDialog)value.get(controls);}
    @After public void cleanup(){if(controls!=null)controls.dispose();}
    @Test public void shortcutRowsUseTheSamePaletteAsTheirDialogInBothAppThemes()throws Exception{
        for(boolean dark:new boolean[]{false,true}){
            create(dark);controls.perform("stream.shortcuts.open",RhActions.obj());AlertDialog menu=dialog("menuDialog");ListView list=menu.getListView();assertTrue(list.getAdapter().getCount()>0);
            TextView row=(TextView)list.getAdapter().getView(0,null,list);int expected=android.graphics.Color.parseColor(dark?"#e1e8f6":"#34415b");
            assertEquals("Shortcut rows inherited the Game theme instead of the dialog theme",expected,row.getCurrentTextColor());
            controls.dispose();controls=null;
        }
    }
    @Test public void backClosesTheMenuThenThePanelAndTheExitDecisionCanKeepTheSession()throws Exception{
        create(false);controls.perform("stream.controls.toggle",RhActions.obj());assertTrue(controls.state().getBoolean("controls"));controls.perform("stream.shortcuts.open",RhActions.obj());
        assertTrue(controls.goBack());assertEquals("",controls.state().getString("streamMenu"));assertTrue(controls.state().getBoolean("controls"));
        assertTrue(controls.goBack());assertFalse(controls.state().getBoolean("controls"));assertTrue(controls.state().getBoolean("connected"));
        assertTrue(controls.goBack());assertTrue(controls.state().getBoolean("exitPrompt"));AlertDialog exit=dialog("exitDialog");exit.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(controls.state().getBoolean("exitPrompt"));assertTrue(controls.state().getBoolean("connected"));assertTrue(controls.state().getBoolean("firstFrame"));assertFalse(game.isFinishing());
    }
    void escape(AlertDialog popup){
        long time=android.os.SystemClock.uptimeMillis();
        for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP})popup.dispatchKeyEvent(new KeyEvent(time,time+action,action,KeyEvent.KEYCODE_ESCAPE,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void escapeClosesOnlyTheLocalMenuAndPreservesTheExpandedSession()throws Exception{
        for(boolean dark:new boolean[]{false,true}){
            create(dark);controls.perform("stream.controls.toggle",RhActions.obj());controls.perform("stream.shortcuts.open",RhActions.obj());AlertDialog menu=dialog("menuDialog");
            escape(menu);assertFalse("Escape must dismiss shortcuts",menu.isShowing());assertEquals("",controls.state().getString("streamMenu"));assertTrue(controls.state().getBoolean("controls"));assertTrue(controls.state().getBoolean("connected"));assertFalse(game.isFinishing());controls.dispose();controls=null;
        }
    }
    @Test public void escapeOnTheDisconnectDecisionKeepsTheStreamConnected()throws Exception{
        for(boolean dark:new boolean[]{false,true}){
            create(dark);controls.perform("stream.exit.request",RhActions.obj());AlertDialog exit=dialog("exitDialog");escape(exit);
            assertFalse("Escape must cancel the decision",exit.isShowing());assertFalse(controls.state().getBoolean("exitPrompt"));assertTrue(controls.state().getBoolean("connected"));assertTrue(controls.state().getBoolean("firstFrame"));assertFalse(game.isFinishing());controls.dispose();controls=null;
        }
    }
    @Test public void escapeRepeatsAndCancelledReleaseKeepTheDialogUntilANormalRelease()throws Exception{
        create(false);controls.perform("stream.shortcuts.open",RhActions.obj());AlertDialog menu=dialog("menuDialog");long time=android.os.SystemClock.uptimeMillis();
        for(int repeat:new int[]{0,2}){assertTrue(menu.dispatchKeyEvent(new KeyEvent(time,time+repeat,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ESCAPE,repeat,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD)));assertTrue("Do not close on key down",menu.isShowing());}
        assertTrue(menu.dispatchKeyEvent(new KeyEvent(time,time+3,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ESCAPE,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_CANCELED,InputDevice.SOURCE_KEYBOARD)));Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(menu.isShowing());assertEquals("shortcuts",controls.state().getString("streamMenu"));escape(menu);assertFalse(menu.isShowing());assertTrue(controls.state().getBoolean("connected"));
    }
    @Test public void lateCallbacksFromReplacedDialogsCannotResolveTheCurrentDialog()throws Exception{
        create(false);controls.perform("stream.shortcuts.open",RhActions.obj());AlertDialog oldMenu=dialog("menuDialog");controls.perform("stream.menu.close",RhActions.obj());controls.perform("stream.shortcuts.open",RhActions.obj());AlertDialog currentMenu=dialog("menuDialog");
        escape(oldMenu);assertTrue("Old Escape closed the new menu",currentMenu.isShowing());oldMenu.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();oldMenu.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(currentMenu.isShowing());assertEquals("shortcuts",controls.state().getString("streamMenu"));escape(currentMenu);
        controls.perform("stream.exit.request",RhActions.obj());AlertDialog oldExit=dialog("exitDialog");controls.perform("stream.exit.resolve",RhActions.obj("choice","stay"));controls.perform("stream.exit.request",RhActions.obj());AlertDialog currentExit=dialog("exitDialog");
        escape(oldExit);assertTrue("Old Escape resolved the new exit decision",currentExit.isShowing());oldExit.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();oldExit.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(currentExit.isShowing());assertTrue(controls.state().getBoolean("exitPrompt"));currentExit.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(controls.state().getBoolean("exitPrompt"));assertTrue(controls.state().getBoolean("connected"));assertFalse(game.isFinishing());
    }
}
