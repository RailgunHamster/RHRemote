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
    public static class KeyProbeGame extends Game {
        final java.util.List<String> remoteKeys=new java.util.ArrayList<>();
        @Override public boolean handleKeyDown(KeyEvent event){remoteKeys.add("down:"+event.getKeyCode());return true;}
        @Override public boolean handleKeyUp(KeyEvent event){remoteKeys.add("up:"+event.getKeyCode());return true;}
    }
    Game game;RhStreamControls controls;
    void create(boolean dark){
        game=Robolectric.buildActivity(KeyProbeGame.class).get();game.setIntent(new Intent().putExtra(Game.EXTRA_PC_NAME,"Fixture workstation").putExtra(Game.EXTRA_PC_UUID,"fixture"));
        RhShared.theme(game,dark);FrameLayout root=new FrameLayout(game);TextView stats=new TextView(game);stats.setId(R.id.performanceOverlay);root.addView(stats);View surface=new View(game);surface.setId(R.id.surfaceView);surface.setFocusableInTouchMode(true);root.addView(surface);game.setContentView(root);assertTrue(surface.requestFocusFromTouch());
        RhProfile profile=RhProfile.load(game,"fixture");RhTouchController touch=new RhTouchController(new RhTouchController.TouchSink(){public void button(byte b,boolean down){throw new AssertionError("No remote input expected");}public void position(short x,short y,short w,short h){throw new AssertionError();}public void relative(short x,short y){throw new AssertionError();}public void scroll(short value){throw new AssertionError();}public void horizontalScroll(short value){throw new AssertionError();}},surface,profile);
        controls=RhStreamControls.install(game,touch,new PreferenceConfiguration(),profile,null);assertNotNull(controls);controls.markConnected();controls.markFirstFrame();Shadows.shadowOf(Looper.getMainLooper()).idle();
        try{Field owner=Game.class.getDeclaredField("rhControls");owner.setAccessible(true);owner.set(game,controls);}catch(Exception failure){throw new AssertionError(failure);}
        layout();
    }
    AlertDialog dialog(String field)throws Exception{Field value=RhStreamControls.class.getDeclaredField(field);value.setAccessible(true);return (AlertDialog)value.get(controls);}
    @After public void cleanup(){if(controls!=null)controls.dispose();}
    View find(View view,String title){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof Button&&title.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),title);if(found!=null)return found;}return null;}
    View button(String title){View view=find(game.findViewById(android.R.id.content),title);assertNotNull(title,view);return view;}
    void layout(){View root=game.findViewById(android.R.id.content);for(int i=0;i<3;i++){root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,720);}}
    boolean key(int action,int code,int modifiers){long time=android.os.SystemClock.uptimeMillis();return game.dispatchKeyEvent(new KeyEvent(time,time,action,code,0,modifiers,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));}
    void toggle()throws Exception{controls.perform("stream.controls.toggle",RhActions.obj());layout();}
    RhStreamPanel panel()throws Exception{Field field=RhStreamControls.class.getDeclaredField("panel");field.setAccessible(true);return (RhStreamPanel)field.get(controls);}
    org.json.JSONObject screens(){org.json.JSONArray values=new org.json.JSONArray().put(RhActions.obj("id","physical","name","LG","width",1920,"height",1080,"available",true)).put(RhActions.obj("id","virtual","virtual",true,"width",2520,"height",1680,"available",true));return RhActions.obj("displays",values,"selected",values.optJSONObject(0));}
    void screens(RhStreamPanel panel,org.json.JSONObject data,boolean busy){panel.update(false,false,false,100,data,busy,"");panel.updateControls(true,false,1,busy,"displays",busy?"正在读取屏幕…":"屏幕列表已刷新","");layout();}
    @Test public void refreshingAFocusedMonitorKeepsNavigationLocalAndRestoresItsRow()throws Exception{
        create(false);toggle();RhStreamPanel panel=panel();panel.setTab("displays");org.json.JSONObject data=screens();screens(panel,data,false);assertTrue(button("LG · 1920×1080  ✓").requestFocusFromTouch());
        screens(panel,data,true);assertTrue("Busy refresh lost local focus",button("收起").hasFocus());screens(panel,data,false);assertTrue("Refresh lost monitor identity",button("LG · 1920×1080  ✓").hasFocus());press(KeyEvent.KEYCODE_A,0);assertTrue(((KeyProbeGame)game).remoteKeys.isEmpty());
    }
    @Test public void reorderingMonitorRowsKeepsTheirIdentityAndMissingRowsStayLocal()throws Exception{
        create(false);toggle();RhStreamPanel panel=panel();panel.setTab("displays");org.json.JSONObject data=screens();screens(panel,data,false);assertTrue(button("虚拟屏 · 2520×1680").requestFocusFromTouch());
        org.json.JSONArray values=data.getJSONArray("displays");org.json.JSONObject reordered=RhActions.obj("displays",new org.json.JSONArray().put(values.getJSONObject(1)).put(values.getJSONObject(0)),"selected",values.getJSONObject(0));screens(panel,reordered,false);assertTrue("Focused monitor was replaced by its row position",button("虚拟屏 · 2520×1680").hasFocus());
        screens(panel,RhActions.obj("displays",new org.json.JSONArray().put(values.getJSONObject(0)),"selected",values.getJSONObject(0)),false);assertTrue("Missing monitor returned focus to the remote surface",button("收起").hasFocus());
    }
    @Test public void refreshCompletionDoesNotStealFocusFromAnotherCategoryOrTheVideo()throws Exception{
        create(false);toggle();RhStreamPanel panel=panel();panel.setTab("displays");org.json.JSONObject data=screens();screens(panel,data,false);assertTrue(button("LG · 1920×1080  ✓").requestFocusFromTouch());screens(panel,data,true);assertTrue(button("声音").requestFocusFromTouch());panel.setTab("audio");screens(panel,data,false);assertTrue(button("声音").hasFocus());
        assertTrue(game.findViewById(R.id.surfaceView).requestFocusFromTouch());screens(panel,data,true);screens(panel,data,false);assertTrue("A background refresh stole video focus",game.findViewById(R.id.surfaceView).hasFocus());
    }
    @Test public void refreshCompletionAfterCollapseDoesNotRestoreAHiddenMonitor()throws Exception{
        create(false);toggle();RhStreamPanel panel=panel();panel.setTab("displays");org.json.JSONObject data=screens();screens(panel,data,false);assertTrue(button("LG · 1920×1080  ✓").requestFocusFromTouch());screens(panel,data,true);toggle();screens(panel,data,false);assertTrue(game.findViewById(R.id.surfaceView).hasFocus());assertFalse(controls.state().getBoolean("controls"));
    }
    @Test public void disablingAFocusedInputOperationKeepsTypingLocal()throws Exception{
        create(false);toggle();assertTrue(button("统计：关").requestFocusFromTouch());panel().updateControls(true,false,1,true,"input","正在发送快捷键…","");layout();assertTrue(button("收起").hasFocus());press(KeyEvent.KEYCODE_A,0);assertTrue(((KeyProbeGame)game).remoteKeys.isEmpty());assertFalse(button("统计：关").isEnabled());
    }
    @Test public void changingCategoryFromAFocusedBodyControlDoesNotReturnKeysToTheVideo()throws Exception{
        create(false);toggle();assertTrue(button("统计：关").requestFocusFromTouch());panel().setTab("audio");layout();assertTrue("Hidden page kept or lost focus",button("声音").hasFocus());press(KeyEvent.KEYCODE_A,0);assertTrue(((KeyProbeGame)game).remoteKeys.isEmpty());
    }
    @Test public void videoTransformUpdatesTheOpenPanelWithoutAnotherControlAction()throws Exception{
        create(false);toggle();Field field=RhStreamControls.class.getDeclaredField("touch");field.setAccessible(true);RhTouchController touch=(RhTouchController)field.get(controls);touch.inject(RhActions.obj("gesture","pinch","factor",1.5));
        assertEquals(1.5,controls.state().getJSONObject("zoom").getDouble("scale"),.001);assertNotNull("Live zoom label is stale",findText(game.findViewById(android.R.id.content),"缩放 150%"));touch.resetZoom();assertNotNull(findText(game.findViewById(android.R.id.content),"缩放 100%"));
    }
    @Test public void aHiddenZoomLabelDoesNotPublishViewChangesUntilShown()throws Exception{
        create(false);Field field=RhStreamControls.class.getDeclaredField("touch");field.setAccessible(true);RhTouchController touch=(RhTouchController)field.get(controls);TextView label=(TextView)findText(game.findViewById(android.R.id.content),"缩放 100%");assertNotNull(label);int[] changes={0};label.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changes[0]++;}public void afterTextChanged(android.text.Editable value){}});
        touch.inject(RhActions.obj("gesture","pinch","factor",1.5));assertEquals(0,changes[0]);toggle();assertEquals("缩放 150%",label.getText().toString());assertEquals(1,changes[0]);panel().setTab("audio");touch.inject(RhActions.obj("gesture","pinch","factor",1.2));assertEquals(1,changes[0]);panel().setTab("input");assertEquals("缩放 180%",label.getText().toString());assertEquals(2,changes[0]);controls.dispose();touch.resetZoom();assertEquals("Disposed controls retained the transform callback",2,changes[0]);
    }
    View findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    void press(int code,int modifiers){key(KeyEvent.ACTION_DOWN,code,modifiers);key(KeyEvent.ACTION_UP,code,modifiers);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void scrollModeSwitchAndApiStayInSyncAndPanExitsTheMode()throws Exception{create(false);toggle();Button control=(Button)button("滚动模式");control.performClick();assertTrue(controls.state().getBoolean("scrollMode"));controls.perform("stream.scroll.set",RhActions.obj("enabled",true));assertTrue(controls.state().getBoolean("scrollMode"));controls.perform("stream.pan.toggle",RhActions.obj());assertFalse(controls.state().getBoolean("scrollMode"));assertTrue(controls.state().getBoolean("pan"));assertFalse(((android.widget.Switch)control).isChecked());controls.perform("stream.scroll.set",RhActions.obj("enabled",true));assertFalse(controls.state().getBoolean("pan"));assertTrue(((android.widget.Switch)control).isChecked());}
    @Test public void tabAndDirectionNavigationStayInsideLocalControlsAndEnterUsesTheirAction()throws Exception{
        create(false);toggle();assertTrue(button("操作").requestFocusFromTouch());
        press(KeyEvent.KEYCODE_TAB,0);assertTrue("Tab should focus the next category",button("屏幕").hasFocus());
        press(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON);assertTrue(button("操作").hasFocus());
        press(KeyEvent.KEYCODE_DPAD_RIGHT,0);assertTrue("Right should focus the adjacent category",button("屏幕").hasFocus());
        assertTrue(button("统计：关").requestFocusFromTouch());press(KeyEvent.KEYCODE_ENTER,0);assertTrue(controls.state().getBoolean("showStats"));
        press(KeyEvent.KEYCODE_A,0);assertEquals("Keys owned by controls must not reach the remote keyboard",java.util.Collections.emptyList(),((KeyProbeGame)game).remoteKeys);
    }
    @Test public void openingControlsTakesLocalFocusAndCollapsingRestoresTheVideo()throws Exception{
        create(false);assertTrue("Initial video focus",game.findViewById(R.id.surfaceView).hasFocus());toggle();assertTrue("Opening focuses the handle",button("收起").hasFocus());press(KeyEvent.KEYCODE_TAB,0);assertTrue("Tab focuses Disconnect",button("断开").hasFocus());toggle();assertTrue("Closing restores video focus",game.findViewById(R.id.surfaceView).hasFocus());assertTrue("No remote calls: "+((KeyProbeGame)game).remoteKeys,((KeyProbeGame)game).remoteKeys.isEmpty());
    }
    @Test public void aLocalKeyReleaseRemainsLocalAfterThePanelCloses()throws Exception{
        create(false);toggle();assertTrue(button("操作").requestFocusFromTouch());assertTrue(key(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_TAB,0));
        toggle();FrameLayout root=game.findViewById(android.R.id.content);View remote=new View(game);remote.setFocusableInTouchMode(true);root.addView(remote);assertTrue(remote.requestFocusFromTouch());
        assertTrue(key(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_TAB,0));assertEquals(java.util.Collections.emptyList(),((KeyProbeGame)game).remoteKeys);
        press(KeyEvent.KEYCODE_TAB,0);assertEquals(java.util.Arrays.asList("down:"+KeyEvent.KEYCODE_TAB,"up:"+KeyEvent.KEYCODE_TAB),((KeyProbeGame)game).remoteKeys);
    }
    @Test public void aRemoteModifierReleaseStillReachesTheRemoteAfterLocalFocusChanges()throws Exception{
        create(false);FrameLayout root=game.findViewById(android.R.id.content);View remote=new View(game);remote.setFocusableInTouchMode(true);root.addView(remote);assertTrue(remote.requestFocusFromTouch());key(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.META_CTRL_ON);
        toggle();assertTrue(button("操作").requestFocusFromTouch());key(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_CTRL_LEFT,0);
        assertEquals(java.util.Arrays.asList("down:"+KeyEvent.KEYCODE_CTRL_LEFT,"up:"+KeyEvent.KEYCODE_CTRL_LEFT),((KeyProbeGame)game).remoteKeys);
    }
    @Test public void tabWrapsAndSkipsDisabledAndHiddenControls()throws Exception{
        create(false);toggle();assertTrue(button("收起").requestFocusFromTouch());press(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON);assertTrue(button("统计：关").hasFocus());
        button("统计：关").setEnabled(false);assertTrue(button("收起").requestFocusFromTouch());press(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON);assertTrue(button("还原缩放").hasFocus());press(KeyEvent.KEYCODE_TAB,0);assertTrue(button("收起").hasFocus());assertTrue(((KeyProbeGame)game).remoteKeys.isEmpty());
    }
    @Test public void escapeCollapsesControlsWithoutOpeningExitOrSendingARemoteEscape()throws Exception{
        create(false);toggle();assertTrue(button("操作").requestFocusFromTouch());assertTrue(key(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ESCAPE,0));assertTrue(controls.state().getBoolean("controls"));assertTrue(key(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ESCAPE,0));assertFalse(controls.state().getBoolean("controls"));assertFalse(controls.state().getBoolean("exitPrompt"));assertTrue(((KeyProbeGame)game).remoteKeys.isEmpty());
    }
    @Test public void cancelledOrRefocusedEnterDoesNotActivateAReplacementButton()throws Exception{
        create(false);toggle();assertTrue(button("统计：关").requestFocusFromTouch());key(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0);long time=android.os.SystemClock.uptimeMillis();assertTrue(game.dispatchKeyEvent(new KeyEvent(time,time,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_CANCELED,InputDevice.SOURCE_KEYBOARD)));assertFalse(controls.state().getBoolean("showStats"));
        key(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0);assertTrue("Replacement focus",button("直接点击").requestFocusFromTouch());key(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0);assertFalse("Old stats button activated",controls.state().getBoolean("showStats"));assertFalse("Replacement mode button activated",controls.state().getBoolean("trackpad"));assertTrue("No remote calls: "+((KeyProbeGame)game).remoteKeys,((KeyProbeGame)game).remoteKeys.isEmpty());
    }
    @Test public void gamepadAndMouseBackKeepTheirUpstreamRoute()throws Exception{
        create(false);toggle();assertTrue(button("操作").requestFocusFromTouch());long time=android.os.SystemClock.uptimeMillis();
        for(int source:new int[]{InputDevice.SOURCE_MOUSE,InputDevice.SOURCE_GAMEPAD})for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP})game.dispatchKeyEvent(new KeyEvent(time,time,action,source==InputDevice.SOURCE_MOUSE?KeyEvent.KEYCODE_BACK:KeyEvent.KEYCODE_BUTTON_A,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,source));
        assertEquals(java.util.Arrays.asList("down:"+KeyEvent.KEYCODE_BACK,"up:"+KeyEvent.KEYCODE_BACK,"down:"+KeyEvent.KEYCODE_BUTTON_A,"up:"+KeyEvent.KEYCODE_BUTTON_A),((KeyProbeGame)game).remoteKeys);assertTrue(controls.state().getBoolean("controls"));assertFalse(controls.state().getBoolean("exitPrompt"));
    }
    @Test public void shortcutRowsUseTheSamePaletteAsTheirDialogInBothAppThemes()throws Exception{
        for(boolean dark:new boolean[]{false,true}){
            create(dark);controls.perform("stream.shortcuts.open",RhActions.obj());AlertDialog menu=dialog("menuDialog");ListView list=menu.getListView();assertTrue(list.getAdapter().getCount()>0);
            TextView row=(TextView)list.getAdapter().getView(0,null,list);int expected=android.graphics.Color.parseColor(dark?"#e1e8f6":"#34415b");
            assertEquals("Shortcut rows inherited the Game theme instead of the dialog theme",expected,row.getCurrentTextColor());
            controls.dispose();controls=null;
        }
    }
    @Test public void backClosesTheMenuThenThePanelAndTheExitDecisionCanKeepTheSession()throws Exception{
        create(false);toggle();assertTrue(controls.state().getBoolean("controls"));controls.perform("stream.shortcuts.open",RhActions.obj());
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
            create(dark);toggle();controls.perform("stream.shortcuts.open",RhActions.obj());AlertDialog menu=dialog("menuDialog");
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
