package com.limelight.rhremote;

import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Switch;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Actual connected controls; only local session state changes, never remote input. */
final class RhSessionControlOriginProbe {
    private final RhSessionFlowDeviceTest test;
    private final JSONArray steps=new JSONArray();
    private RhSessionControlOriginProbe(RhSessionFlowDeviceTest test){this.test=test;}
    static void run(RhSessionFlowDeviceTest test)throws Exception{new RhSessionControlOriginProbe(test).verify();}
    private View tagged(String id){View[] result={null};test.instrument.runOnMainSync(()->{result[0]=test.game.findViewById(android.R.id.content).findViewWithTag(id);assertNotNull(id,result[0]);});return result[0];}
    private void record(String label)throws Exception{
        JSONObject value=test.stream();assertTrue(value.getBoolean("connected"));assertTrue(value.getBoolean("firstFrame"));assertFalse("UI validation must keep sound disabled",value.getBoolean("audioEnabled"));
        JSONObject detail=RhActions.obj("step",label,"controls",value.getBoolean("controls"),"tab",value.getString("controlsTab"),"trackpad",value.getBoolean("trackpad"),"scrollMode",value.getBoolean("scrollMode"),"showStats",value.getBoolean("showStats"),"volume",value.getInt("volume"),"floatingKeyboard",value.getBoolean("floatingKeyboard"));steps.put(detail);
        RhShared.write(new File(test.folder,"session-current-control-steps.json"),RhActions.obj("steps",steps,"remoteInputSent",false));
    }
    private void click(View source){
        test.instrument.runOnMainSync(()->source.requestRectangleOnScreen(new Rect(0,0,source.getWidth(),source.getHeight()),true));test.instrument.waitForIdleSync();
        test.instrument.runOnMainSync(()->{Rect area=new Rect();assertTrue("Current control is visible",source.isShown()&&source.getGlobalVisibleRect(area));assertEquals("Current control clipped horizontally",source.getWidth(),area.width());assertEquals("Current control clipped vertically",source.getHeight(),area.height());assertTrue("Current control is enabled",source.isEnabled());source.performClick();});test.instrument.waitForIdleSync();
    }
    private void unchanged(JSONObject before,String label)throws Exception{
        JSONObject after=test.stream();for(String key:new String[]{"connected","firstFrame","trackpad","showStats","scrollMode","pan","volume","audioEnabled","controls","controlsTab","floatingKeyboard"})assertEquals(label+" changed "+key,before.get(key),after.get(key));record(label);
    }
    private void volume(SeekBar slider,int key){test.instrument.runOnMainSync(()->slider.onKeyDown(key,new KeyEvent(KeyEvent.ACTION_DOWN,key)));test.instrument.waitForIdleSync();}
    private void verify()throws Exception{
        assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));assertTrue(test.stream().getBoolean("controls"));assertFalse(test.stream().getBoolean("floatingKeyboard"));assertFalse(test.stream().getBoolean("pan"));
        String trace=RhStatistics.state().getString("latest");JSONObject original=test.stream();test.click("操作");
        View mode=tagged("stream.mode.toggle"),stats=tagged("stream.stats.toggle");Switch scroll=(Switch)tagged("stream.scroll.set"),floating=(Switch)tagged("stream.keyboard.floating.set");
        click(mode);assertEquals(!original.getBoolean("trackpad"),test.stream().getBoolean("trackpad"));record("current-mode");click(mode);assertEquals(original.getBoolean("trackpad"),test.stream().getBoolean("trackpad"));
        click(stats);assertEquals(!original.getBoolean("showStats"),test.stream().getBoolean("showStats"));record("current-statistics");click(stats);assertEquals(original.getBoolean("showStats"),test.stream().getBoolean("showStats"));
        click(scroll);assertEquals(!original.getBoolean("scrollMode"),test.stream().getBoolean("scrollMode"));record("current-scroll");click(scroll);assertEquals(original.getBoolean("scrollMode"),test.stream().getBoolean("scrollMode"));
        test.click("声音");JSONObject before=test.stream();test.instrument.runOnMainSync(()->{assertFalse(mode.isShown());assertFalse(scroll.isShown());mode.performClick();stats.performClick();scroll.performClick();});test.instrument.waitForIdleSync();unchanged(before,"hidden-input-callbacks");
        SeekBar slider=(SeekBar)tagged("stream.audio.volume");int savedVolume=before.getInt("volume"),volumeKey=savedVolume>=5?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT;int[] increment={0};test.instrument.runOnMainSync(()->{increment[0]=slider.getKeyProgressIncrement();slider.setKeyProgressIncrement(5);assertTrue(slider.isShown());});
        try{
            volume(slider,volumeKey);assertEquals(savedVolume>=5?savedVolume-5:savedVolume+5,test.stream().getInt("volume"));record("current-volume");
            test.instrument.runOnMainSync(()->{android.os.Bundle value=new android.os.Bundle();value.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,savedVolume);assertTrue(slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),value));});test.instrument.waitForIdleSync();assertEquals(savedVolume,test.stream().getInt("volume"));record("current-volume-restored");
            test.click("操作");before=test.stream();test.instrument.runOnMainSync(()->assertFalse(slider.isShown()));volume(slider,volumeKey);unchanged(before,"hidden-volume-callback");
            JSONObject handle=before.getJSONObject("controlsLayout").getJSONObject("handle");test.click("收起");before=test.stream();test.instrument.runOnMainSync(()->{mode.performClick();stats.performClick();scroll.performClick();});volume(slider,volumeKey);unchanged(before,"collapsed-control-callbacks");
            assertEquals("Control handle moved when collapsed",handle.toString(),test.stream().getJSONObject("controlsLayout").getJSONObject("handle").toString());test.click("控制");record("controls-restored");boolean currentScroll=test.stream().optBoolean("scrollMode");test.instrument.runOnMainSync(()->assertEquals(currentScroll,scroll.isChecked()));
            test.click("声音");test.instrument.runOnMainSync(()->assertEquals("Restored volume model and slider disagree",savedVolume,slider.getProgress()));record("restored-volume-model");
            for(String label:new String[]{"操作","屏幕","声音"}){test.click(label);test.waitFor(()->!test.stream().optBoolean("displayBusy"),20,"Idle header controls");test.instrument.runOnMainSync(()->{Rect area=new Rect();assertTrue("Floating header switch not visible on "+label,floating.getGlobalVisibleRect(area));assertEquals(floating.getWidth(),area.width());assertEquals(floating.getHeight(),area.height());assertTrue(floating.isEnabled());});record("header-reachable-"+label);}
            click(floating);test.waitFor(()->test.stream().optJSONObject("floatingKeyboardLayout").optBoolean("visible"),3,"Actual floating keyboard after header action");assertFalse(test.stream().getBoolean("controls"));JSONArray keys=test.stream().getJSONObject("floatingKeyboardLayout").getJSONArray("keys");int visible=0;for(int i=0;i<keys.length();i++)if(keys.getJSONObject(i).getBoolean("visible"))visible++;assertEquals(10,visible);record("floating-ten-keys");test.captureVideoUi("session-current-floating-keyboard");
            test.click("控制");assertFalse(test.stream().getJSONObject("floatingKeyboardLayout").getBoolean("visible"));click(floating);assertFalse(test.stream().getBoolean("floatingKeyboard"));assertFalse(test.stream().getBoolean("controls"));test.click("控制");test.click("操作");record("floating-disabled-controls-restored");test.capture("session-current-controls");
        }finally{test.instrument.runOnMainSync(()->slider.setKeyProgressIncrement(increment[0]));}
        JSONObject finalState=test.stream();for(String key:new String[]{"trackpad","showStats","scrollMode","pan","volume","audioEnabled","floatingKeyboard"})assertEquals("Local setting not restored: "+key,original.get(key),finalState.get(key));assertEquals(trace,RhStatistics.state().getString("latest"));assertSame(test.game,test.resumedGame());assertFalse(test.game.isDestroyed());
        RhShared.write(new File(test.folder,"session-current-controls.json"),RhActions.obj("passed",true,"version",com.limelight.BuildConfig.VERSION_NAME,"steps",steps.length(),"actualAttachedGame",true,"sameGame",true,"sameTrace",true,"actualControlCallbacks",true,"hiddenCallbacksRejected",true,"currentCallbacksAccepted",true,"floatingVisibleKeys",10,"settingsRestored",true,"audioEnabled",false,"remoteInputSent",false,"systemInputInjected",false));
    }
}
