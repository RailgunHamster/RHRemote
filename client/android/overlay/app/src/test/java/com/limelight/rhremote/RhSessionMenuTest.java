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
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhSessionMenuTest.Keyboard.class})
public class RhSessionMenuTest {
    @org.robolectric.annotation.Implements(value=com.limelight.nvstream.jni.MoonBridge.class,isInAndroidSdk=false)
    public static class Keyboard {
        static final java.util.List<Integer> events=new java.util.ArrayList<>();
        static final java.util.List<String> packets=new java.util.ArrayList<>();
        static int rejectKey;
        @org.robolectric.annotation.Implementation protected static void __staticInitializer__(){}
        @org.robolectric.annotation.Implementation protected static int rhSendKeyboardInputChecked(short key,byte direction,byte modifiers,byte flags){events.add((int)direction);packets.add((key&255)+":"+direction);return direction==3&&(key&255)==rejectKey?-2:0;}
    }
    public static class KeyProbeGame extends Game {
        final java.util.List<String> remoteKeys=new java.util.ArrayList<>();
        @Override public boolean handleKeyDown(KeyEvent event){remoteKeys.add("down:"+event.getKeyCode());return true;}
        @Override public boolean handleKeyUp(KeyEvent event){remoteKeys.add("up:"+event.getKeyCode());return true;}
    }
    Game game;RhStreamControls controls;
    void create(boolean dark){
        create(dark,false);
    }
    void create(boolean dark,boolean withWarning){
        Keyboard.rejectKey=0;
        Keyboard.packets.clear();
        game=Robolectric.buildActivity(KeyProbeGame.class).get();game.setIntent(new Intent().putExtra(Game.EXTRA_PC_NAME,"Fixture workstation").putExtra(Game.EXTRA_PC_UUID,"fixture"));
        RhShared.theme(game,dark);FrameLayout root=new FrameLayout(game);TextView stats=new TextView(game);stats.setId(R.id.performanceOverlay);root.addView(stats);View surface=new View(game);surface.setId(R.id.surfaceView);surface.setFocusableInTouchMode(true);root.addView(surface);if(withWarning){RhSessionWarning warning=new RhSessionWarning(game);warning.setId(R.id.notificationOverlay);warning.setVisibility(View.GONE);root.addView(warning,new FrameLayout.LayoutParams(-2,-2));}game.setContentView(root);assertTrue(surface.requestFocusFromTouch());
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
    @Test public void callbacksFromHiddenSessionControlsDoNotChangeTheCurrentSession()throws Exception{
        create(false);toggle();View oldMode=button("直接点击"),oldScroll=button("滚动模式"),oldStats=button("统计：关");
        controls.perform("stream.controls.tab",RhActions.obj("tab","audio"));layout();
        oldMode.performClick();oldScroll.performClick();oldStats.performClick();
        assertFalse("Hidden input button changed pointer mode",controls.state().getBoolean("trackpad"));
        assertFalse("Hidden input switch changed scrolling",controls.state().getBoolean("scrollMode"));
        assertFalse("Hidden input button changed statistics",controls.state().getBoolean("showStats"));
        View oldAudio=game.findViewById(android.R.id.content).findViewWithTag("stream.audio.toggle");assertNotNull(oldAudio);boolean before=controls.state().getBoolean("audioEnabled");toggle();oldAudio.performClick();
        assertEquals("Collapsed panel changed audio",before,controls.state().getBoolean("audioEnabled"));
        toggle();controls.perform("stream.controls.tab",RhActions.obj("tab","input"));layout();oldMode.performClick();assertTrue("Current input button stopped working",controls.state().getBoolean("trackpad"));
        oldScroll.performClick();assertTrue(controls.state().getBoolean("scrollMode"));oldStats.performClick();assertTrue(controls.state().getBoolean("showStats"));
    }
    RhStreamPanel panel()throws Exception{Field field=RhStreamControls.class.getDeclaredField("panel");field.setAccessible(true);return (RhStreamPanel)field.get(controls);}
    @Test public void aFailedDisplaySwitchKeepsThePreviousMonitorSelectedAndCanRetry()throws Exception{
        create(false);toggle();Keyboard.events.clear();RhProfile profile=RhProfile.load(game,"fixture");
        org.json.JSONObject old=RhActions.obj("id","old","name","原屏幕","available",true,"width",1920,"height",1080,"captureIndex",1),bad=RhActions.obj("id","bad","name","不可切换屏幕","available",true,"width",1920,"height",1080,"captureIndex",13);
        RhDisplay display=new RhDisplay(profile,(path,body)->RhActions.obj("result",RhActions.obj("selected",body.optString("displayId").equals("bad")?bad:old,"displays",new org.json.JSONArray().put(old).put(bad))));display.select("old",false);
        Field current=RhStreamControls.class.getDeclaredField("display");current.setAccessible(true);current.set(controls,display);controls.perform("stream.controls.tab",RhActions.obj("tab","displays"));
        controls.perform("stream.display.select",RhActions.obj("displayId","bad"));long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(controls.state().optBoolean("displayBusy")){assertTrue("Display operation did not finish",System.nanoTime()<until);Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idle();}layout();
        assertFalse(controls.state().getString("displayError").isEmpty());assertEquals("old",display.state().getJSONObject("selected").getString("id"));assertNotNull(button("原屏幕 · 1920×1080  ✓"));assertTrue(button("不可切换屏幕 · 1920×1080").isEnabled());assertTrue(button("断开").isEnabled());
        assertTrue(Keyboard.events.isEmpty());controls.perform("stream.display.select",RhActions.obj("displayId","old"));until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(controls.state().optBoolean("displayBusy")){assertTrue(System.nanoTime()<until);Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idle();}assertEquals("",controls.state().getString("displayError"));assertEquals(8,Keyboard.events.size());
    }
    @Test public void aPendingDisplayCandidateCannotReplaceTheCurrentScreenOrSendInputAfterDisposal()throws Exception{
        create(false);toggle();Keyboard.events.clear();java.util.concurrent.CountDownLatch reached=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Thread> worker=new java.util.concurrent.atomic.AtomicReference<>();
        org.json.JSONObject old=RhActions.obj("id","old","name","原屏幕","available",true,"width",1920,"height",1080,"captureIndex",1),next=RhActions.obj("id","next","name","下一屏幕","available",true,"width",1920,"height",1080,"captureIndex",2);
        RhDisplay display=new RhDisplay(RhProfile.load(game,"fixture"),(path,body)->{boolean pending=body.optString("displayId").equals("next");if(pending){worker.set(Thread.currentThread());reached.countDown();assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));}return RhActions.obj("result",RhActions.obj("selected",pending?next:old,"displays",new org.json.JSONArray().put(old).put(next)));});display.select("old",false);Field current=RhStreamControls.class.getDeclaredField("display");current.setAccessible(true);current.set(controls,display);controls.perform("stream.controls.tab",RhActions.obj("tab","displays"));
        try{controls.perform("stream.display.select",RhActions.obj("displayId","next"));assertTrue(reached.await(2,java.util.concurrent.TimeUnit.SECONDS));layout();assertEquals("old",display.state().getJSONObject("selected").getString("id"));assertNotNull(findText(game.findViewById(android.R.id.content),"正在切换屏幕…"));assertFalse(button("刷新屏幕").isEnabled());assertTrue(button("断开").isEnabled());controls.dispose();}
        finally{release.countDown();}
        assertNotNull(worker.get());worker.get().join(3000);assertFalse("Prepared result has not reached its late callback",worker.get().isAlive());Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(Keyboard.events.isEmpty());assertEquals("old",display.state().getJSONObject("selected").getString("id"));
    }
    @Test public void transportRejectionCannotReportAShortcutAsSentOrCommitANewMonitor()throws Exception{
        create(false);Keyboard.events.clear();controls.perform("stream.shortcut",RhActions.obj("shortcut","ctrl-alt-right"));assertTrue(controls.state().getJSONObject("lastShortcut").optBoolean("accepted"));Keyboard.events.clear();Keyboard.rejectKey=37;
        try{controls.perform("stream.shortcut",RhActions.obj("shortcut","ctrl-alt-left"));fail("Transport error was discarded");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("-2"));}
        assertEquals("快捷键发送失败",controls.state().getJSONObject("controlAction").getString("message"));assertEquals("ctrl-alt-left",controls.state().getJSONObject("lastShortcut").getString("shortcut"));assertFalse(controls.state().getJSONObject("lastShortcut").optBoolean("accepted"));assertEquals(java.util.Arrays.asList(3,3,3,4,4,4),Keyboard.events);
        RhDisplay display=new RhDisplay(RhProfile.load(game,"fixture"),(path,body)->RhActions.obj("result",RhActions.obj("selected",RhActions.obj("id",body.optString("displayId"),"captureIndex",2))));display.select("old",false);Keyboard.rejectKey=113;
        try{display.select("next",true);fail("Rejected input committed selection");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("-2"));}assertEquals("old",display.state().getJSONObject("selected").getString("id"));
    }
    @Test public void pendingScreenChangeRejectsDisabledControlActionsButKeepsLocalNavigation()throws Exception{
        create(false);toggle();java.util.concurrent.CountDownLatch reached=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Thread> worker=new java.util.concurrent.atomic.AtomicReference<>();
        org.json.JSONObject old=RhActions.obj("id","old","captureIndex",1),next=RhActions.obj("id","next","captureIndex",2);
        RhDisplay display=new RhDisplay(RhProfile.load(game,"fixture"),(path,body)->{if(body.optString("displayId").equals("next")){worker.set(Thread.currentThread());reached.countDown();assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));}return RhActions.obj("result",RhActions.obj("selected",body.optString("displayId").equals("next")?next:old));});display.select("old",false);
        Field field=RhStreamControls.class.getDeclaredField("display");field.setAccessible(true);field.set(controls,display);
        try{
            controls.perform("stream.display.select",RhActions.obj("displayId","next"));assertTrue(reached.await(2,java.util.concurrent.TimeUnit.SECONDS));controls.perform("stream.controls.tab",RhActions.obj("tab","input"));layout();org.json.JSONObject before=controls.state();
            for(String title:new String[]{"直接点击","键盘","统计：关","悬浮键盘","滚动模式"})assertFalse(title,button(title).isEnabled());
            controls.perform("stream.controls.tab",RhActions.obj("tab","displays"));layout();for(String title:new String[]{"移动画面","还原缩放"})assertFalse(title,button(title).isEnabled());controls.perform("stream.controls.tab",RhActions.obj("tab","input"));layout();
            for(String id:new String[]{"stream.mode.toggle","stream.keyboard.toggle","stream.pan.toggle","stream.zoom.reset","stream.keyboard.floating.set","stream.scroll.set"}){
                try{controls.perform(id,RhActions.obj("enabled",true));fail("Disabled control accepted: "+id);}catch(IllegalStateException expected){assertEquals("上一项会话操作尚未完成",expected.getMessage());}
            }
            org.json.JSONObject after=controls.state();for(String key:new String[]{"trackpad","pan","scrollMode","floatingKeyboard","keyboardPending","keyboardTargetVisible"})assertEquals(key,before.get(key),after.get(key));assertEquals(before.getJSONObject("zoom").toString(),after.getJSONObject("zoom").toString());assertTrue(after.getJSONObject("controlAction").getBoolean("busy"));
            controls.perform("stream.controls.tab",RhActions.obj("tab","audio"));layout();assertEquals("audio",controls.state().getString("controlsTab"));assertTrue(button("收起").isEnabled());assertTrue(button("断开").isEnabled());
        }finally{controls.dispose();release.countDown();if(worker.get()!=null){worker.get().join(3000);assertFalse(worker.get().isAlive());}Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
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
        create(false);toggle();panel().setTab("displays");Field field=RhStreamControls.class.getDeclaredField("touch");field.setAccessible(true);RhTouchController touch=(RhTouchController)field.get(controls);touch.inject(RhActions.obj("gesture","pinch","factor",1.5));
        assertEquals(1.5,controls.state().getJSONObject("zoom").getDouble("scale"),.001);assertNotNull("Live zoom label is stale",findText(game.findViewById(android.R.id.content),"缩放 150%"));touch.resetZoom();assertNotNull(findText(game.findViewById(android.R.id.content),"缩放 100%"));
    }
    @Test public void aHiddenZoomLabelDoesNotPublishViewChangesUntilShown()throws Exception{
        create(false);Field field=RhStreamControls.class.getDeclaredField("touch");field.setAccessible(true);RhTouchController touch=(RhTouchController)field.get(controls);
        assertTrue(controls.state().getJSONObject("controlsLayout").getBoolean("created"));assertFalse(controls.state().getBoolean("controls"));
        TextView label=(TextView)findText(game.findViewById(android.R.id.content),"缩放 100%");assertNotNull(label);int[] changes={0};label.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changes[0]++;}public void afterTextChanged(android.text.Editable value){}});
        touch.inject(RhActions.obj("gesture","pinch","factor",1.5));assertEquals("Hidden zoom must not rewrite the prepared label",0,changes[0]);toggle();assertSame(label,findText(game.findViewById(android.R.id.content),"缩放 100%"));
        assertEquals(0,changes[0]);panel().setTab("displays");assertEquals("缩放 150%",label.getText().toString());assertEquals(1,changes[0]);panel().setTab("audio");touch.inject(RhActions.obj("gesture","pinch","factor",1.2));assertEquals(1,changes[0]);panel().setTab("displays");assertEquals("缩放 180%",label.getText().toString());assertEquals(2,changes[0]);controls.dispose();touch.resetZoom();assertEquals("Disposed controls retained the transform callback",2,changes[0]);
    }
    @Test public void screenTransformFeedbackStaysWithItsControlsAndClearsAfterANewGesture()throws Exception{
        create(false);toggle();panel().setTab("displays");
        controls.perform("stream.pan.toggle",RhActions.obj());assertEquals("displays",controls.state().getJSONObject("controlAction").getString("group"));
        controls.perform("stream.pan.toggle",RhActions.obj());controls.perform("stream.zoom.reset",RhActions.obj());
        assertEquals("displays",controls.state().getJSONObject("controlAction").getString("group"));assertNotNull(findText(game.findViewById(android.R.id.content),"缩放已还原"));
        controls.perform("input.gesture",RhActions.obj("gesture","pinch","factor",1.25));
        assertEquals("",controls.state().getJSONObject("controlAction").getString("message"));assertNull(findText(game.findViewById(android.R.id.content),"缩放已还原"));assertNotNull(findText(game.findViewById(android.R.id.content),"缩放 125%"));
    }
    View findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    void press(int code,int modifiers){key(KeyEvent.ACTION_DOWN,code,modifiers);key(KeyEvent.ACTION_UP,code,modifiers);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void scrollModeSwitchAndApiStayInSyncAndPanExitsTheMode()throws Exception{create(false);toggle();Button control=(Button)button("滚动模式");control.performClick();assertTrue(controls.state().getBoolean("scrollMode"));controls.perform("stream.scroll.set",RhActions.obj("enabled",true));assertTrue(controls.state().getBoolean("scrollMode"));controls.perform("stream.pan.toggle",RhActions.obj());assertFalse(controls.state().getBoolean("scrollMode"));assertTrue(controls.state().getBoolean("pan"));assertFalse(((android.widget.Switch)control).isChecked());controls.perform("stream.scroll.set",RhActions.obj("enabled",true));assertFalse(controls.state().getBoolean("pan"));assertTrue(((android.widget.Switch)control).isChecked());}
    @Test public void sessionSwitchEnterUpdatesTheSameModelAndNeverReachesTheRemoteKeyboard()throws Exception{
        for(boolean dark:new boolean[]{false,true}){
            create(dark);toggle();Switch scroll=(Switch)button("滚动模式");assertTrue(scroll.isFocusable());assertTrue(scroll.isClickable());assertTrue(scroll.requestFocusFromTouch());
            press(KeyEvent.KEYCODE_ENTER,0);assertTrue(scroll.isChecked());assertTrue(controls.state().getBoolean("scrollMode"));
            press(KeyEvent.KEYCODE_ENTER,0);assertFalse(scroll.isChecked());assertFalse(controls.state().getBoolean("scrollMode"));
            controls.perform("stream.scroll.set",RhActions.obj("enabled",true));assertTrue(scroll.isChecked());assertTrue(scroll.hasFocus());
            assertTrue("Local switch keys reached the remote",((KeyProbeGame)game).remoteKeys.isEmpty());controls.dispose();controls=null;
        }
    }
    @Test public void tabAndDirectionNavigationStayInsideLocalControlsAndEnterUsesTheirAction()throws Exception{
        create(false);toggle();assertTrue(button("操作").requestFocusFromTouch());
        press(KeyEvent.KEYCODE_TAB,0);assertTrue("Tab should focus the next category",button("屏幕").hasFocus());
        press(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON);assertTrue(button("操作").hasFocus());
        press(KeyEvent.KEYCODE_DPAD_RIGHT,0);assertTrue("Right should focus the adjacent category",button("屏幕").hasFocus());
        assertTrue(button("统计：关").requestFocusFromTouch());press(KeyEvent.KEYCODE_ENTER,0);assertTrue(controls.state().getBoolean("showStats"));
        press(KeyEvent.KEYCODE_A,0);assertEquals("Keys owned by controls must not reach the remote keyboard",java.util.Collections.emptyList(),((KeyProbeGame)game).remoteKeys);
    }
    @Test public void openingControlsTakesLocalFocusAndCollapsingRestoresTheVideo()throws Exception{
        create(false);assertTrue("Initial video focus",game.findViewById(R.id.surfaceView).hasFocus());toggle();assertTrue("Opening focuses the handle",button("收起").hasFocus());press(KeyEvent.KEYCODE_TAB,0);assertTrue("Tab focuses the first header control",button("悬浮键盘").hasFocus());press(KeyEvent.KEYCODE_TAB,0);assertTrue("Tab continues to Disconnect",button("断开").hasFocus());toggle();assertTrue("Closing restores video focus",game.findViewById(R.id.surfaceView).hasFocus());assertTrue("No remote calls: "+((KeyProbeGame)game).remoteKeys,((KeyProbeGame)game).remoteKeys.isEmpty());
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
        button("统计：关").setEnabled(false);assertTrue(button("收起").requestFocusFromTouch());press(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON);assertTrue(button("点击与滚动").hasFocus());press(KeyEvent.KEYCODE_TAB,0);assertTrue(button("收起").hasFocus());
        button("点击与滚动").setEnabled(false);press(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON);assertTrue(button("滚动模式").hasFocus());press(KeyEvent.KEYCODE_TAB,0);assertTrue(button("收起").hasFocus());
        button("滚动模式").setEnabled(false);press(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON);assertTrue(button("显示键盘浮钮").hasFocus());button("显示键盘浮钮").setEnabled(false);assertTrue(button("收起").requestFocusFromTouch());press(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON);assertTrue(button("简化键盘").hasFocus());button("简化键盘").setEnabled(false);assertTrue(button("收起").requestFocusFromTouch());press(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON);assertTrue(button("Ctrl+Alt+Delete").hasFocus());assertFalse(game.findViewById(android.R.id.content).findViewWithTag("stream.zoom.reset").isShown());press(KeyEvent.KEYCODE_TAB,0);assertTrue(button("收起").hasFocus());
        button("悬浮键盘").setEnabled(false);press(KeyEvent.KEYCODE_TAB,0);assertTrue("Disabled header switch is skipped",button("断开").hasFocus());assertTrue(((KeyProbeGame)game).remoteKeys.isEmpty());
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
    @Test public void callbacksFromAnOlderControllerCannotOperateItsReplacementBeforeDisposal()throws Exception{
        create(false);toggle();View oldMode=button("直接点击");java.util.List<String> received=new java.util.ArrayList<>();RhActions.Target replacement=new RhActions.Target(){public org.json.JSONObject perform(String id,org.json.JSONObject args){received.add(id);return RhActions.obj("accepted",true);}public org.json.JSONObject state(){return RhActions.obj("connected",true,"firstFrame",true);}};
        assertTrue(RhActions.attachStream(replacement));
        try{oldMode.performClick();assertTrue("Old controller reached the replacement: "+received,received.isEmpty());RhActions.click("stream.controls.toggle",RhActions.obj());assertEquals(java.util.Collections.singletonList("stream.controls.toggle"),received);}
        finally{RhActions.detachStream(replacement);}
    }
    @Test public void clickScrollSwitchAndCatalogActionUseTheRealSessionController()throws Exception{
        create(false);toggle();assertTrue(RhActions.dispatch("stream.click-scroll.set",RhActions.obj("enabled",true)).getBoolean("ok"));
        assertTrue(controls.state().getBoolean("clickScrollMode"));assertFalse(controls.state().getBoolean("scrollMode"));assertFalse(controls.state().getBoolean("trackpad"));
        android.widget.Switch choice=game.findViewById(android.R.id.content).findViewWithTag("stream.click-scroll.set");assertNotNull(choice);assertTrue(choice.isChecked());
        choice.requestRectangleOnScreen(new android.graphics.Rect(0,0,choice.getWidth(),choice.getHeight()),true);layout();choice.performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(controls.state().getBoolean("clickScrollMode"));assertFalse(choice.isChecked());assertEquals("stream.click-scroll.set",controls.state().getJSONObject("controlAction").getString("action"));
        assertTrue(RhActions.dispatch("stream.click-scroll.set",RhActions.obj("enabled",true)).getBoolean("ok"));
        assertTrue(RhActions.dispatch("stream.scroll.set",RhActions.obj("enabled",true)).getBoolean("ok"));assertFalse(controls.state().getBoolean("clickScrollMode"));assertTrue(controls.state().getBoolean("scrollMode"));assertFalse(choice.isChecked());
    }
    @Test public void clickScrollCannotChangeDuringAnotherOperationOrBeforeConnection()throws Exception{
        create(false);Field busy=RhStreamControls.class.getDeclaredField("displayBusy");busy.setAccessible(true);busy.setBoolean(controls,true);
        assertFalse(RhActions.dispatch("stream.click-scroll.set",RhActions.obj("enabled",true)).getBoolean("ok"));assertFalse(controls.state().getBoolean("clickScrollMode"));
        busy.setBoolean(controls,false);Field connected=RhStreamControls.class.getDeclaredField("connected");connected.setAccessible(true);connected.setBoolean(controls,false);
        assertFalse(RhActions.dispatch("stream.click-scroll.set",RhActions.obj("enabled",true)).getBoolean("ok"));assertFalse(controls.state().getBoolean("clickScrollMode"));
    }
    @Test public void completeKeyboardUsesTheSameActionDispatcherAndOneShotModifiers()throws Exception{
        create(false);assertTrue(RhActions.dispatch("stream.keyboard.press",RhActions.obj("key","ctrl-left")).getBoolean("ok"));assertTrue(Keyboard.packets.isEmpty());
        assertEquals("ctrl",controls.state().getJSONArray("keyboardModifiers").getString(0));
        assertTrue(RhActions.dispatch("stream.keyboard.press",RhActions.obj("key","c")).getBoolean("ok"));
        assertEquals(java.util.Arrays.asList("17:3","67:3","67:4","17:4"),Keyboard.packets);assertEquals(0,controls.state().getJSONArray("keyboardModifiers").length());
        org.json.JSONArray catalog=RhKeyboard.catalog(game);assertEquals(108,catalog.length());
        for(int i=0;i<catalog.length();i++){org.json.JSONObject key=catalog.getJSONObject(i);String id=key.getString("id");Keyboard.packets.clear();assertTrue(id,RhActions.dispatch("stream.keyboard.press",RhActions.obj("key",id)).getBoolean("ok"));if(!key.optString("modifier").isEmpty()){assertTrue(Keyboard.packets.isEmpty());RhActions.dispatch("stream.keyboard.press",RhActions.obj("key",id));}else{int code=key.getInt("code");assertEquals(id,java.util.Arrays.asList(code+":3",code+":4"),Keyboard.packets);}}
        assertFalse(RhActions.dispatch("stream.keyboard.press",RhActions.obj("key","not-a-key")).getBoolean("ok"));
        org.json.JSONObject sas=RhKeyboard.command(RhKeyboard.find(game,"delete"),java.util.Arrays.asList("ctrl","alt"));assertTrue(sas.getBoolean("secureAttention"));
    }
    @Test public void rejectedKeyboardChordStillReleasesEveryAttemptedKeyAndClearsSelection()throws Exception{
        create(false);RhActions.dispatch("stream.keyboard.press",RhActions.obj("key","ctrl-left"));Keyboard.rejectKey=67;
        assertFalse(RhActions.dispatch("stream.keyboard.press",RhActions.obj("key","c")).getBoolean("ok"));assertEquals(java.util.Arrays.asList("17:3","67:3","67:4","17:4"),Keyboard.packets);assertEquals(0,controls.state().getJSONArray("keyboardModifiers").length());assertFalse(controls.state().getJSONObject("controlAction").optString("error").isEmpty());
    }
    @Test public void keyboardFloatingButtonOptionUsesItsControlAndSurvivesSessionRecreation()throws Exception{
        create(false);toggle();android.widget.Switch choice=game.findViewById(android.R.id.content).findViewWithTag("stream.keyboard.button.set");assertNotNull(choice);assertTrue(choice.isChecked());choice.performClick();assertFalse(controls.state().getBoolean("keyboardButtonVisible"));assertFalse(choice.isChecked());controls.dispose();controls=null;create(false);assertFalse(controls.state().getBoolean("keyboardButtonVisible"));
        assertTrue(RhActions.dispatch("stream.keyboard.button.set",RhActions.obj("enabled",true)).getBoolean("ok"));assertTrue(controls.state().getBoolean("keyboardButtonVisible"));
    }

    @Test public void simplifiedLayoutAndStandaloneWinUseTheSameSessionActions()throws Exception{
        create(false);assertTrue(RhActions.dispatch("stream.keyboard.layout.set",RhActions.obj("layout","simple")).getBoolean("ok"));assertEquals("simple",controls.state().getString("keyboardLayout"));
        assertTrue(RhActions.dispatch("stream.keyboard.press",RhActions.obj("key","meta-left","tap",true)).getBoolean("ok"));assertEquals(java.util.Arrays.asList("91:3","91:4"),Keyboard.packets);assertEquals(0,controls.state().getJSONArray("keyboardModifiers").length());
        assertFalse(RhActions.dispatch("stream.keyboard.layout.set",RhActions.obj("layout","invalid")).getBoolean("ok"));assertEquals("simple",controls.state().getString("keyboardLayout"));controls.dispose();controls=null;create(false);assertEquals("simple",controls.state().getString("keyboardLayout"));RhActions.dispatch("stream.keyboard.layout.set",RhActions.obj("layout","full"));
    }

}
