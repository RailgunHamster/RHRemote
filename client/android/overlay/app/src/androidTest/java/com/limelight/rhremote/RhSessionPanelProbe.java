package com.limelight.rhremote;

import android.os.SystemClock;
import android.view.View;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.InputDevice;
import android.graphics.Rect;
import android.widget.SeekBar;
import android.widget.Switch;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Opt-in first-control-open observation during an owned real session; no remote input. */
final class RhSessionPanelProbe {
    private static boolean body(RhSessionFlowDeviceTest test){
        boolean[] value={false};test.instrument.runOnMainSync(()->value[0]=test.game.findViewById(android.R.id.content).findViewWithTag("stream.audio.volume")!=null);return value[0];
    }
    private static JSONObject open(RhSessionFlowDeviceTest test)throws Exception{
        View[] root={null};AtomicLong started=new AtomicLong(),drawn=new AtomicLong(-1);CountDownLatch draw=new CountDownLatch(1);
        android.view.ViewTreeObserver.OnDrawListener listener=()->{View toggle=root[0].findViewWithTag("stream.scroll.set");long begin=started.get();if(begin>0&&toggle!=null&&toggle.isShown()&&drawn.compareAndSet(-1,SystemClock.elapsedRealtime()-begin))draw.countDown();};
        test.instrument.runOnMainSync(()->{root[0]=test.game.findViewById(android.R.id.content);root[0].getViewTreeObserver().addOnDrawListener(listener);});
        try{
            started.set(SystemClock.elapsedRealtime());test.action("stream.controls.toggle",RhActions.obj());long idle=SystemClock.elapsedRealtime()-started.get();
            assertTrue("Expanded controls did not reach the root draw callback",draw.await(3,TimeUnit.SECONDS));
            return RhActions.obj("dispatchAndIdleMs",idle,"requestToRootDrawMs",drawn.get(),"rootDrawObserved",true,"presentTimestampMeasured",false,"scope","own root OnDraw after the scroll switch is shown; excludes compositor/pixel-present proof");
        }finally{test.instrument.runOnMainSync(()->root[0].getViewTreeObserver().removeOnDrawListener(listener));}
    }
    private static JSONObject switchInput(RhSessionFlowDeviceTest test)throws Exception{
        boolean original=test.stream().getBoolean("scrollMode");Switch[] target={null};
        test.instrument.runOnMainSync(()->{target[0]=test.game.findViewById(android.R.id.content).findViewWithTag("stream.scroll.set");assertNotNull(target[0]);assertTrue(target[0].isAttachedToWindow());assertTrue(target[0].isEnabled());target[0].requestRectangleOnScreen(new Rect(0,0,target[0].getWidth(),target[0].getHeight()),true);});test.instrument.waitForIdleSync();
        try{
            test.instrument.runOnMainSync(()->{Switch toggle=target[0];Rect visible=new Rect();assertTrue(toggle.getGlobalVisibleRect(visible));assertEquals(toggle.getWidth(),visible.width());assertEquals(toggle.getHeight(),visible.height());long time=SystemClock.uptimeMillis();for(int kind:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(time,time+(kind==MotionEvent.ACTION_UP?100:0),kind,toggle.getWidth()/3f,toggle.getHeight()/2f,0);try{assertTrue(toggle.dispatchTouchEvent(event));}finally{event.recycle();}}});
            test.instrument.waitForIdleSync();assertEquals("Attached label touch did not update the model",!original,test.stream().getBoolean("scrollMode"));
            test.instrument.runOnMainSync(()->{assertTrue(target[0].requestFocusFromTouch());long time=SystemClock.uptimeMillis();for(int kind:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP})assertTrue(test.game.dispatchKeyEvent(new KeyEvent(time,time+kind,kind,KeyEvent.KEYCODE_ENTER,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD)));});
            test.instrument.waitForIdleSync();assertEquals("Local Enter did not restore the model",original,test.stream().getBoolean("scrollMode"));
            return RhActions.obj("attachedToWindow",true,"labelTouchAfterIdle",true,"localEnter",true,"originalScrollModeRestored",true,"systemInputInjected",false,"remoteInputRequested",false);
        }finally{if(test.stream().getBoolean("scrollMode")!=original)test.action("stream.scroll.set",RhActions.obj("enabled",original));}
    }
    static JSONObject run(RhSessionFlowDeviceTest test)throws Exception{
        JSONObject before=test.stream();boolean beforeBody=body(test);assertFalse(before.getBoolean("controls"));
        String expected=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("sessionPanelExpected","");
        if(expected.equals("lazy")){assertFalse("Hidden panel body was created before the first open",beforeBody);assertTrue(before.getJSONObject("controlsLayout").has("created"));assertFalse(before.getJSONObject("controlsLayout").getBoolean("created"));}
        else if(expected.equals("eager"))assertTrue("The fixed baseline did not contain its hidden body",beforeBody);
        else throw new AssertionError("Explicit panel lifetime expectation is required");
        JSONObject firstObservation=open(test);long firstOpenMs=firstObservation.getLong("dispatchAndIdleMs");
        JSONObject opened=test.stream();assertTrue(opened.getBoolean("controls"));assertTrue(body(test));
        int[] slider={-1};test.instrument.runOnMainSync(()->{View view=test.game.findViewById(android.R.id.content).findViewWithTag("stream.audio.volume");assertTrue(view instanceof SeekBar);slider[0]=((SeekBar)view).getProgress();});assertEquals(opened.getInt("volume"),slider[0]);
        test.capture("session-first-panel");test.action("stream.controls.toggle",RhActions.obj());assertFalse(test.stream().getBoolean("controls"));
        JSONObject repeatObservation=open(test);long reopenMs=repeatObservation.getLong("dispatchAndIdleMs");assertTrue(test.stream().getBoolean("controls"));JSONObject switchInteractions=switchInput(test);test.action("stream.controls.toggle",RhActions.obj());
        android.content.Context context=test.instrument.getTargetContext();String runtimeVersion=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;
        JSONObject result=RhActions.obj("version",runtimeVersion,"expected",expected,"bodyBeforeOpen",beforeBody,"beforeLayout",before.getJSONObject("controlsLayout"),"openedLayout",opened.getJSONObject("controlsLayout"),"firstOpenDispatchAndIdleMs",firstOpenMs,"reopenDispatchAndIdleMs",reopenMs,"firstOpenDrawing",firstObservation,"reopenDrawing",repeatObservation,"sliderMatchesModel",true,"switchInteractions",switchInteractions,"returnedCollapsed",true,"remoteInputSent",false,"audioPlayback",false,"measurementScope","action dispatch plus instrumentation UI idle; root drawing reported separately; neither is exact pixel-present latency");
        RhShared.write(new File(test.folder,"session-panel-lifetime.json"),result);return result;
    }
}
