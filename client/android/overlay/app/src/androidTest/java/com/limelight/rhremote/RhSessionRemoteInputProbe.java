package com.limelight.rhremote;

import android.app.AlertDialog;
import android.graphics.Rect;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.*;
import static org.junit.Assert.*;

/** Opt-in real input test. A separate controller guards and observes one owned remote window. */
final class RhSessionRemoteInputProbe {
    interface Operation { void run(JSONObject ready)throws Exception; }
    final RhSessionFlowDeviceTest test;
    final File bridge;
    final String run,probeRun;
    final int repeats;
    final JSONArray steps=new JSONArray();
    int sequence;
    boolean secureRequested,escaped;
    private RhSessionRemoteInputProbe(RhSessionFlowDeviceTest test)throws Exception {
        this.test=test;
        assertTrue("Dedicated emulator required",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        assertEquals("192.168.0.104",InstrumentationRegistry.getArguments().getString("sessionHost"));
        assertFalse(test.stream().getBoolean("audioEnabled"));
        run=InstrumentationRegistry.getArguments().getString("sessionRun","");
        probeRun=InstrumentationRegistry.getArguments().getString("remoteInputRun","");
        assertTrue("Explicit owned probe identity required",probeRun.matches("session70-[a-f0-9]{12}"));
        repeats=Integer.parseInt(InstrumentationRegistry.getArguments().getString("remoteInputRepeats","1"));assertTrue(repeats>=1&&repeats<=5);
        bridge=new File(test.folder,"remote-input");assertFalse(bridge.exists());assertTrue(bridge.mkdir());
    }
    private JSONObject await(String id,String phase)throws Exception {
        File file=new File(bridge,"ack-"+sequence+".json");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            if(file.isFile()&&file.length()>0&&file.length()<=32768){
                JSONObject value;
                try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] bytes=new byte[2048];int count;
                    while((count=in.read(bytes))!=-1){out.write(bytes,0,count);assertTrue(out.size()<=32768);}
                    value=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                }catch(IOException|JSONException incomplete){Thread.sleep(40);continue;}
                assertEquals(run,value.getString("run"));assertEquals(probeRun,value.getString("probeRun"));
                assertEquals(sequence,value.getInt("sequence"));assertEquals(id,value.getString("id"));
                assertFalse("Remote guard rejected "+id+": "+value.optString("error"),value.optString("phase").equals("failed"));
                if(value.optString("phase").equals(phase))return value;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("Remote "+phase+" confirmation deadline: "+id);
    }
    private void publish(String id,String phase)throws Exception {
        RhShared.write(new File(bridge,"request.json"),RhActions.obj("run",run,"probeRun",probeRun,"sequence",sequence,"id",id,"phase",phase));
    }
    private void step(String id,Operation operation)throws Exception {
        sequence++;publish(id,"prepare");JSONObject ready=await(id,"ready");
        RhStatistics.event("ui_remote_input_requested",-1,RhActions.obj("sequence",sequence,"id",id));
        operation.run(ready);RhStatistics.event("ui_remote_input_accepted",-1,RhActions.obj("sequence",sequence,"id",id));publish(id,"sent");JSONObject verified=await(id,"verified");
        assertTrue("Remote result was not verified",verified.getBoolean("received"));steps.put(verified);
        RhStatistics.event("ui_remote_input_verified",-1,RhActions.obj("sequence",sequence,"id",id));
        RhShared.write(new File(test.folder,"session-remote-input-progress.json"),RhActions.obj("steps",steps));
    }
    private void visibleButton(String label){
        View[] button={null};test.instrument.runOnMainSync(()->{button[0]=test.namedControl(label);button[0].requestRectangleOnScreen(new Rect(0,0,button[0].getWidth(),button[0].getHeight()),true);});
        test.instrument.waitForIdleSync();test.instrument.runOnMainSync(()->{Rect visible=new Rect();assertTrue(label,button[0].getGlobalVisibleRect(visible));assertEquals(label,button[0].getHeight(),visible.height());});test.click(label);
    }
    private void shortcut(String id)throws Exception {
        test.click("操作");visibleButton("快捷键");
        JSONArray catalog=RhShortcuts.catalog(test.game);int found=-1;
        for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("id").equals(id))found=i;
        assertTrue("Shortcut catalog row missing",found>=0);final int index=found;
        AlertDialog dialog=test.menuDialogOf(test.game);ListView list=dialog.getListView();
        test.instrument.runOnMainSync(()->list.setSelection(index));test.instrument.waitForIdleSync();
        test.instrument.runOnMainSync(()->{
            View row=list.getChildAt(index-list.getFirstVisiblePosition());assertNotNull("Shortcut row is not visible",row);
            assertTrue(row instanceof TextView);assertEquals(catalog.optJSONObject(index).optString("label"),((TextView)row).getText().toString());
            Rect visible=new Rect();assertTrue(row.getGlobalVisibleRect(visible));assertEquals(row.getHeight(),visible.height());
            assertTrue(list.performItemClick(row,index,list.getAdapter().getItemId(index)));
        });
        test.instrument.waitForIdleSync();
        test.waitFor(()->!test.stream().optBoolean("shortcutBusy"),10,"Shortcut worker completion");
        JSONObject result=test.stream().getJSONObject("lastShortcut");assertEquals(id,result.getString("shortcut"));assertTrue(result.toString(),result.getBoolean("accepted"));
        assertTrue(test.stream().getJSONObject("controlAction").getString("error").isEmpty());
    }
    private void activate(JSONObject ready)throws Exception {
        JSONObject surface=test.stream().getJSONObject("surface");
        assertEquals(1,test.stream().getJSONObject("zoom").getDouble("scale"),.001);
        double x=ready.getDouble("xFraction"),y=ready.getDouble("yFraction");assertTrue(x>0&&x<1&&y>0&&y<1);
        test.action("input.gesture",RhActions.obj("gesture","tap","x",x*surface.getInt("width"),"y",y*surface.getInt("height")));
    }
    private void floatingButton(String id)throws Exception{
        test.instrument.runOnMainSync(()->{View button=test.game.findViewById(android.R.id.content).findViewWithTag("floating-key:"+id);assertNotNull(id,button);assertTrue(id,button.isShown());assertTrue(id,button.isEnabled());Rect visible=new Rect();assertTrue(id,button.getGlobalVisibleRect(visible));assertEquals(button.getWidth(),visible.width());assertEquals(button.getHeight(),visible.height());assertTrue(button.performClick());});
        test.instrument.waitForIdleSync();test.waitFor(()->!test.stream().optBoolean("shortcutBusy"),10,"Floating key completion");JSONObject result=test.stream().getJSONObject("lastShortcut");assertEquals(id,result.getString("shortcut"));assertTrue(result.toString(),result.getBoolean("accepted"));
    }
    private void activateThroughView(JSONObject ready)throws Exception{
        test.instrument.runOnMainSync(()->{View surface=test.game.findViewById(com.limelight.R.id.surfaceView);int[] origin=new int[2];surface.getLocationInWindow(origin);float x=origin[0]+(float)ready.optDouble("xFraction")*surface.getWidth(),y=origin[1]+(float)ready.optDouble("yFraction")*surface.getHeight();View layer=test.game.findViewById(android.R.id.content).findViewWithTag("floating-keyboard");int[] offset=new int[2];layer.getLocationInWindow(offset);for(int i=0;i<((android.view.ViewGroup)layer).getChildCount();i++){View key=((android.view.ViewGroup)layer).getChildAt(i);if(key.getVisibility()!=View.VISIBLE)continue;Rect r=new Rect();key.getHitRect(r);assertFalse("Guarded tap overlaps a floating key",r.contains((int)x-offset[0],(int)y-offset[1]));}
            long now=android.os.SystemClock.uptimeMillis();for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}){android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now+action,action,x,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{assertTrue(test.game.dispatchTouchEvent(event));}finally{event.recycle();}}
        });test.instrument.waitForIdleSync();
    }
    private void floating()throws Exception{
        test.click("操作");final JSONObject[] toggleState={RhActions.obj()};
        try{test.waitFor(()->{final boolean[] ready={false};test.instrument.runOnMainSync(()->{View toggle=test.game.findViewById(android.R.id.content).findViewWithTag("stream.keyboard.floating.set");assertNotNull("Floating keyboard switch",toggle);toggle.requestRectangleOnScreen(new Rect(0,0,toggle.getWidth(),toggle.getHeight()),true);Rect visible=new Rect();boolean seen=toggle.getGlobalVisibleRect(visible);toggleState[0]=RhActions.obj("shown",toggle.isShown(),"enabled",toggle.isEnabled(),"width",toggle.getWidth(),"height",toggle.getHeight(),"visibleWidth",visible.width(),"visibleHeight",visible.height());ready[0]=toggle.isEnabled()&&seen&&toggle.getWidth()>0&&toggle.getHeight()>0&&toggle.getWidth()==visible.width()&&toggle.getHeight()==visible.height();});return ready[0];},4,"Floating keyboard switch layout "+toggleState[0]);}
        finally{RhShared.write(new File(test.folder,"session-floating-switch.json"),RhActions.obj("switch",toggleState[0],"stream",test.stream()));}
        // CompoundButton toggles and calls OnCheckedChangeListener even when the
        // inherited performClick returns false (there is no OnClickListener).
        // Verify the resulting production state rather than that return value.
        test.instrument.runOnMainSync(()->{View toggle=test.game.findViewById(android.R.id.content).findViewWithTag("stream.keyboard.floating.set");toggle.performClick();});test.instrument.waitForIdleSync();
        assertTrue(test.stream().getBoolean("floatingKeyboard"));assertFalse(test.stream().getBoolean("controls"));assertTrue(test.stream().getJSONObject("floatingKeyboardLayout").getBoolean("visible"));
        test.capture("session-floating-keyboard");test.captureVideoUi("session-floating-keyboard-composite");RhShared.write(new File(test.folder,"session-floating-layout.json"),test.stream().getJSONObject("floatingKeyboardLayout"));
        step("activate",this::activateThroughView);
        for(String id:new String[]{"tab","shift-tab","arrow-up","arrow-left","arrow-down","arrow-right","enter","backspace","delete","escape"})step(id,ready->floatingButton(id));
        test.click("控制");test.action("stream.keyboard.floating.set",RhActions.obj("enabled",false));assertFalse(test.stream().getBoolean("floatingKeyboard"));assertFalse(test.stream().getJSONObject("floatingKeyboardLayout").getBoolean("visible"));test.click("控制");
        step("alt-f4",ready->shortcut("alt-f4"));
        RhShared.write(new File(test.folder,"session-remote-input.json"),RhActions.obj("run",run,"probeRun",probeRun,"steps",steps,"remoteResultsVerified",true,"floatingKeyboard",true,"actualControlCallbacks",true,"productionInputTransport",true,"systemInputUsedToDriveClient",false,"audioEnabled",false));
    }
    private void scrollSwitch(boolean enabled)throws Exception{
        if(!test.stream().optBoolean("controls"))test.click("控制");test.click("操作");
        test.instrument.runOnMainSync(()->{View view=test.game.findViewById(android.R.id.content).findViewWithTag("stream.scroll.set");assertNotNull(view);view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);});test.instrument.waitForIdleSync();
        test.instrument.runOnMainSync(()->{android.widget.Switch view=test.game.findViewById(android.R.id.content).findViewWithTag("stream.scroll.set");assertTrue(view.isEnabled());Rect r=new Rect();assertTrue(view.getGlobalVisibleRect(r));assertEquals(view.getHeight(),r.height());assertEquals(view.getWidth(),r.width());if(view.isChecked()!=enabled)view.performClick();assertEquals(enabled,view.isChecked());});test.instrument.waitForIdleSync();assertEquals(enabled,test.stream().getBoolean("scrollMode"));test.capture(enabled?"session-scroll-on":"session-scroll-off");test.click("收起");
    }
    private void touch(JSONObject ready,float dx,float dy)throws Exception{
        assertEquals(1,test.stream().getJSONObject("zoom").getDouble("scale"),.001);assertFalse(test.stream().getBoolean("controls"));assertFalse(test.stream().getBoolean("floatingKeyboard"));
        test.instrument.runOnMainSync(()->{View surface=test.game.findViewById(com.limelight.R.id.surfaceView);int[] origin=new int[2];surface.getLocationInWindow(origin);float x=origin[0]+(float)ready.optDouble("xFraction")*surface.getWidth(),y=origin[1]+(float)ready.optDouble("yFraction")*surface.getHeight();float mx=dx*surface.getWidth(),my=dy*surface.getHeight();long now=android.os.SystemClock.uptimeMillis();for(int i=0;i<(dx==0&&dy==0?2:10);i++){int last=dx==0&&dy==0?1:9,action=i==0?android.view.MotionEvent.ACTION_DOWN:i==last?android.view.MotionEvent.ACTION_UP:android.view.MotionEvent.ACTION_MOVE;float fraction=(float)i/last;android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now+i*16,action,x+mx*fraction,y+my*fraction,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{assertTrue(test.game.dispatchTouchEvent(event));}finally{event.recycle();}}});test.instrument.waitForIdleSync();
    }
    private void scroll()throws Exception{
        if(test.stream().optBoolean("controls"))test.click("收起");step("activate",ready->touch(ready,0,0));scrollSwitch(true);test.captureVideoUi("session-scroll-composite");
        step("middle-tap",ready->touch(ready,0,0));
        for(String direction:new String[]{"up","down","left","right"})step("swipe-"+direction,ready->{float amount=(float)ready.optDouble("distanceFraction");assertTrue(amount>0&&amount<.2);touch(ready,direction.equals("left")?-amount:direction.equals("right")?amount:0,direction.equals("up")?-amount:direction.equals("down")?amount:0);});
        scrollSwitch(false);step("normal-tap",ready->touch(ready,0,0));
        if(InstrumentationRegistry.getArguments().getString("remoteRotatePortrait","false").equals("true"))step("rotate-portrait",ready->{
            android.app.Activity original=test.game;String trace=RhStatistics.state().getString("latest");long marker=RhStatistics.now();RhStatistics.event("ui_rotation_requested");
            test.waitFor(()->{boolean[] portrait={false};test.instrument.runOnMainSync(()->{View decor=test.game.getWindow().getDecorView();portrait[0]=decor.getHeight()>decor.getWidth()&&decor.getWidth()>0;});return portrait[0];},12,"Actual portrait Game viewport after rotation");assertSame("Rotation recreated the stream Activity",original,test.resumedGame());assertTrue(test.stream().getBoolean("connected"));assertEquals(trace,RhStatistics.state().getString("latest"));
            File file=new File(trace,"native.jsonl"),root=new File(test.game.getFilesDir(),"statistics");assertTrue(file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator));int rendered=0;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(rendered<3){rendered=0;try(BufferedReader reader=new BufferedReader(new FileReader(file))){String line;while((line=reader.readLine())!=null)try{JSONObject row=new JSONObject(line);if(row.optString("event").equals("render_submit")&&row.optLong("ts_us")>marker)rendered++;}catch(JSONException incomplete){}}assertTrue("No new rendered submissions after viewport rotation",System.nanoTime()<until);if(rendered<3)Thread.sleep(50);}
            JSONObject pixels=test.videoPixels("session-after-rotation-video");assertTrue(pixels.toString(),pixels.getBoolean("visibleContent"));RhStatistics.event("ui_rotation_verified");test.captureVideoUi("session-after-rotation-composite");RhShared.write(new File(test.folder,"session-hot-rotation.json"),RhActions.obj("portrait",true,"sameActivity",true,"sameTrace",true,"renderSubmissionsAfterRotation",rendered,"videoPixels",pixels,"reconnected",false,"systemInputInjected",false));
        });
        test.click("控制");boolean includeFloating=InstrumentationRegistry.getArguments().getString("remoteFloatingAfterScroll","false").equals("true");if(includeFloating)floating();else step("alt-f4",ready->shortcut("alt-f4"));
        RhShared.write(new File(test.folder,"session-remote-input.json"),RhActions.obj("run",run,"probeRun",probeRun,"steps",steps,"remoteResultsVerified",true,"scrollMode",true,"floatingKeyboard",includeFloating,"actualControlCallbacks",true,"applicationTouchDispatch",true,"productionInputTransport",true,"systemInputUsedToDriveClient",false,"audioEnabled",false));
    }
    private void run()throws Exception {
        try{
            if(InstrumentationRegistry.getArguments().getString("remoteScrollOnly","false").equals("true")){scroll();return;}
            if(InstrumentationRegistry.getArguments().getString("remoteFloatingOnly","false").equals("true")){floating();return;}
            step("activate",this::activate);
            for(int repeat=0;repeat<repeats;repeat++)for(String id:new String[]{"ctrl-alt-left","ctrl-alt-right","ctrl-alt-up","ctrl-alt-down","ctrl-c","ctrl-a","ctrl-s","ctrl-f"})step(id,ready->shortcut(id));
            test.click("屏幕");
            for(String direction:new String[]{"right","left"})step("window-move-"+direction,ready->{
                visibleButton(direction.equals("right")?"窗口移到右屏":"窗口移到左屏");
                assertTrue(test.stream().getJSONObject("controlAction").getString("error").isEmpty());
            });
            test.click("操作");
            step("ctrl-alt-delete",ready->{secureRequested=true;visibleButton("Ctrl+Alt+Delete");test.waitFor(()->!test.stream().optBoolean("shortcutBusy"),15,"Secure attention completion");assertTrue(test.stream().getJSONObject("lastShortcut").getBoolean("accepted"));});
            step("escape",ready->shortcut("escape"));escaped=true;
            step("activate-after-sas",this::activate);
            step("alt-f4",ready->shortcut("alt-f4"));
            assertTrue(test.stream().getBoolean("connected"));assertTrue(test.stream().getBoolean("firstFrame"));
            assertFalse(test.stream().getBoolean("audioEnabled"));
            RhShared.write(new File(test.folder,"session-remote-input.json"),RhActions.obj("run",run,"probeRun",probeRun,"repeats",repeats,"steps",steps,"remoteResultsVerified",true,"actualControlCallbacks",true,"productionInputTransport",true,"systemInputUsedToDriveClient",false,"audioEnabled",false,"physicalKeyboardTested",false));
        }finally{
            // If the assertion or external observer failed while secure attention
            // was open, return through the same production remote Escape action.
            if(secureRequested&&!escaped&&RhActions.hasStream())test.action("stream.shortcut",RhActions.obj("shortcut","escape"));
            publish("complete","finished");
        }
    }
    static void run(RhSessionFlowDeviceTest test)throws Exception {new RhSessionRemoteInputProbe(test).run();}
}
