package com.limelight.rhremote;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import com.limelight.Game;
import java.io.File;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.*;
import static org.junit.Assert.*;

/** Retains actual old Views across an owned session recovery; sends no host input. */
final class RhSessionRetiredControlsProbe {
    private final Activity previous;
    private final RhStreamControls previousControls;
    private final Map<String,View> retired=new LinkedHashMap<>();
    private RhSessionRetiredControlsProbe(RhSessionFlowDeviceTest test)throws Exception{
        previous=test.game;previousControls=controls(previous);
        test.instrument.runOnMainSync(()->{for(String id:new String[]{"stream.disconnect","stream.mode.toggle","stream.scroll.set","stream.keyboard.floating.set","stream.controls.toggle"}){
            View view=previous.findViewById(android.R.id.content).findViewWithTag(id);assertNotNull(id,view);retired.put(id,view);
        }});
    }
    private static RhStreamControls controls(Activity owner)throws Exception{Field field=Game.class.getDeclaredField("rhControls");field.setAccessible(true);return (RhStreamControls)field.get(owner);}
    static RhSessionRetiredControlsProbe capture(RhSessionFlowDeviceTest test)throws Exception{return new RhSessionRetiredControlsProbe(test);}
    void verify(RhSessionFlowDeviceTest test)throws Exception{
        assertNotSame(previous,test.game);RhStreamControls current=controls(test.game);assertNotSame(previousControls,current);assertTrue(RhActions.ownsStream(current));assertFalse(RhActions.ownsStream(previousControls));
        test.click("控制");JSONObject before=test.stream();JSONArray attempted=new JSONArray();
        test.instrument.runOnMainSync(()->{for(Map.Entry<String,View> entry:retired.entrySet()){entry.getValue().performClick();attempted.put(entry.getKey());}((Game)previous).toggleKeyboard();});test.instrument.waitForIdleSync();
        JSONObject after=test.stream();for(String key:new String[]{"connected","firstFrame","controls","controlsTab","trackpad","pan","scrollMode","floatingKeyboard","keyboard","keyboardPending","keyboardTargetVisible","volume","audioEnabled","exitPrompt","streamMenu"})assertEquals("Retired callback changed "+key,before.get(key),after.get(key));
        test.instrument.runOnMainSync(()->{View live=test.game.findViewById(android.R.id.content).findViewWithTag("stream.mode.toggle");assertTrue(live instanceof Button);assertTrue(live.isEnabled());Rect visible=new Rect();assertTrue(live.getGlobalVisibleRect(visible));assertEquals(live.getWidth(),visible.width());assertEquals(live.getHeight(),visible.height());live.performClick();});test.instrument.waitForIdleSync();assertEquals(!before.getBoolean("trackpad"),test.stream().getBoolean("trackpad"));
        test.instrument.runOnMainSync(()->test.game.findViewById(android.R.id.content).findViewWithTag("stream.mode.toggle").performClick());test.instrument.waitForIdleSync();assertEquals(before.getBoolean("trackpad"),test.stream().getBoolean("trackpad"));
        Field disposed=RhStreamControls.class.getDeclaredField("disposed");disposed.setAccessible(true);
        RhShared.write(new File(test.folder,"session-retired-controls.json"),RhActions.obj("actualOldViews",true,"attempted",attempted,"oldGameGestureAttempted",true,"oldControllerDisposed",disposed.getBoolean(previousControls),"oldActivityDestroyed",previous.isDestroyed(),"oldOwnerRejected",true,"replacementStateUnchanged",true,"currentButtonChangedMode",true,"currentModeRestored",true,"hostInputSent",false,"systemInputInjected",false));
        test.capture("session-retired-controls-current");test.click("收起");
    }
}
