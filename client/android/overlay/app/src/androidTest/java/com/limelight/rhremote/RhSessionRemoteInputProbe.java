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
    private void run()throws Exception {
        try{
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
