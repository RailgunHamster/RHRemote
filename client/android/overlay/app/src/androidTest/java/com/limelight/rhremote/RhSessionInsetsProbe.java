package com.limelight.rhremote;

import android.graphics.Rect;
import android.view.*;
import android.widget.Switch;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Opt-in checks on the real connected Game; never clicks a remote key or injects OS input. */
final class RhSessionInsetsProbe {
    private final RhSessionFlowDeviceTest test;
    private final List<JSONObject> samples=new ArrayList<>();
    private RhSessionInsetsProbe(RhSessionFlowDeviceTest test){this.test=test;}
    static void run(RhSessionFlowDeviceTest test)throws Exception{new RhSessionInsetsProbe(test).verify();}
    private String emulatorShell(String command)throws Exception{
        assertTrue("Shell fixture is restricted to the dedicated emulator",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(test.instrument.getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int count;while((count=in.read(buffer))!=-1){assertTrue("Oversized display fixture response",out.size()+count<=4096);out.write(buffer,0,count);}return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private JSONObject sample(){
        JSONObject[] result={null};test.instrument.runOnMainSync(()->{
            View root=test.game.findViewById(android.R.id.content);WindowInsetsCompat window=ViewCompat.getRootWindowInsets(root);
            assertNotNull("Real attached window insets",window);Insets safe=window.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());Insets bars=window.getInsets(WindowInsetsCompat.Type.systemBars());
            JSONObject state=RhActions.activeState();int[] origin=new int[2];root.getLocationInWindow(origin);
            result[0]=RhActions.obj("rootWidth",root.getWidth(),"rootHeight",root.getHeight(),"rootX",origin[0],"rootY",origin[1],"density",root.getResources().getDisplayMetrics().density,"fontScale",root.getResources().getConfiguration().fontScale,"safe",RhActions.obj("left",safe.left,"top",safe.top,"right",safe.right,"bottom",safe.bottom),"bars",RhActions.obj("left",bars.left,"top",bars.top,"right",bars.right,"bottom",bars.bottom),"keyboard",state.optJSONObject("floatingKeyboardLayout"),"controls",state.optJSONObject("controlsLayout"),"connected",state.optBoolean("connected"),"firstFrame",state.optBoolean("firstFrame"));
        });return result[0];
    }
    private void bars(boolean show)throws Exception{
        test.instrument.runOnMainSync(()->{
            WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(test.game.getWindow(),test.game.getWindow().getDecorView());
            if(show)controller.show(WindowInsetsCompat.Type.systemBars());else controller.hide(WindowInsetsCompat.Type.systemBars());
        });
        test.waitFor(()->{JSONObject bars=sample().optJSONObject("bars");int total=bars.optInt("left")+bars.optInt("top")+bars.optInt("right")+bars.optInt("bottom");return show?total>0:total==0;},3,"Actual system bar transition");
        // Let the actual WindowInsets dispatch and its next layout settle, without
        // calling a control update that could hide a missing insets listener.
        Thread.sleep(120);test.instrument.waitForIdleSync();
    }
    private void check(String label)throws Exception{
        JSONObject value=sample();value.put("label",label);samples.add(value);
        RhShared.write(new File(test.folder,"session-window-insets-samples.json"),RhActions.obj("samples",new JSONArray(samples),"sameGame",true,"remoteInputSent",false,"systemInputInjected",false));
        assertTrue(value.getBoolean("connected"));assertTrue(value.getBoolean("firstFrame"));
        JSONObject safe=value.getJSONObject("safe"),keyboard=value.getJSONObject("keyboard");int width=value.getInt("rootWidth"),height=value.getInt("rootHeight");float density=(float)value.getDouble("density");int gap=Math.round(16*density);
        assertEquals("Overlay root must cover the window for these inset coordinates",0,value.getInt("rootX"));assertEquals(0,value.getInt("rootY"));assertTrue(keyboard.getBoolean("visible"));
        JSONArray keys=keyboard.getJSONArray("keys");List<Rect> occupied=new ArrayList<>();int visible=0;
        for(int i=0;i<keys.length();i++){
            JSONObject key=keys.getJSONObject(i);if(!key.getBoolean("visible"))continue;visible++;
            Rect area=new Rect(key.getInt("x"),key.getInt("y"),key.getInt("x")+key.getInt("width"),key.getInt("y")+key.getInt("height"));
            String description=label+"/"+key.getString("id")+": "+area+", safe="+safe;
            assertTrue(description,area.left>=safe.getInt("left")+gap&&area.top>=safe.getInt("top")+gap&&area.right<=width-safe.getInt("right")-gap&&area.bottom<=height-safe.getInt("bottom")-gap);
            for(Rect previous:occupied)assertFalse(description,Rect.intersects(previous,area));occupied.add(area);
        }
        assertEquals("All ten keys fit the real emulator viewport",10,visible);
        JSONObject handle=value.getJSONObject("controls").getJSONObject("handle");assertTrue("Control handle under a system bar",handle.getInt("y")>=safe.getInt("top")+Math.round(8*density));
        test.captureVideoUi("session-insets-"+label);
    }
    private JSONObject header(){
        JSONObject[] result={null};test.instrument.runOnMainSync(()->{
            View root=test.game.findViewById(android.R.id.content);Switch toggle=root.findViewWithTag("stream.keyboard.floating.set");assertNotNull(toggle);
            Rect visible=new Rect();boolean global=toggle.getGlobalVisibleRect(visible);JSONArray parents=new JSONArray();
            for(View current=toggle;current!=null;){Rect bounds=new Rect();current.getHitRect(bounds);parents.put(RhActions.obj("class",current.getClass().getSimpleName(),"width",current.getWidth(),"height",current.getHeight(),"bounds",bounds.toShortString(),"visibility",current.getVisibility(),"shown",current.isShown(),"attached",current.isAttachedToWindow(),"layoutRequested",current.isLayoutRequested()));ViewParent parent=current.getParent();current=parent instanceof View?(View)parent:null;}
            result[0]=RhActions.obj("globalVisible",global,"width",toggle.getWidth(),"height",toggle.getHeight(),"visibleWidth",visible.width(),"visibleHeight",visible.height(),"ready",global&&toggle.getWidth()>0&&toggle.getHeight()>0&&toggle.getWidth()==visible.width()&&toggle.getHeight()==visible.height(),"parents",parents,"controls",RhActions.activeState().optBoolean("controls"));
        });return result[0];
    }
    private void verify()throws Exception{
        assertTrue("Dedicated emulator only",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        assertFalse(test.stream().optBoolean("audioEnabled"));String trace=RhStatistics.state().getString("latest");
        int[] requested={0};test.instrument.runOnMainSync(()->requested[0]=test.game.getRequestedOrientation());
        String originalSize=emulatorShell("wm size");java.util.regex.Matcher override=java.util.regex.Pattern.compile("Override size: (\\d+)x(\\d+)").matcher(originalSize);
        String restoreSize=override.find()?override.group(1)+"x"+override.group(2):"reset";
        JSONObject originalViewport=sample();int shorter=Math.min(originalViewport.getInt("rootWidth"),originalViewport.getInt("rootHeight")),longer=Math.max(originalViewport.getInt("rootWidth"),originalViewport.getInt("rootHeight"));assertTrue(shorter>=600&&longer<=4096);
        boolean wasEnabled=test.stream().optBoolean("floatingKeyboard");
        try{
            if(!test.stream().optBoolean("controls"))test.click("控制");
            if(!wasEnabled){
                JSONObject[] header={null};try{test.waitFor(()->{header[0]=header();return header[0].optBoolean("ready");},3,"Actual header layout before clicking");}finally{RhShared.write(new File(test.folder,"session-insets-header.json"),header[0]==null?RhActions.obj():header[0]);}
                test.instrument.runOnMainSync(()->{Switch toggle=test.game.findViewById(android.R.id.content).findViewWithTag("stream.keyboard.floating.set");assertFalse(toggle.isChecked());toggle.performClick();assertTrue("Actual checked state after the native Switch action",toggle.isChecked());});test.instrument.waitForIdleSync();
            }
            else test.action("stream.controls.toggle",RhActions.obj());
            test.waitFor(()->test.stream().optJSONObject("floatingKeyboardLayout").optBoolean("visible"),3,"Floating keys after the real header switch");
            for(boolean landscape:new boolean[]{true,false}){
                // Game follows FULL_USER and intentionally restores that policy
                // after configuration changes. Change the emulator's real viewport
                // instead of fighting the application's orientation policy.
                emulatorShell("wm size "+(landscape?longer+"x"+shorter:shorter+"x"+longer));
                test.waitFor(()->{JSONObject size=sample();return(size.optInt("rootWidth")>size.optInt("rootHeight"))==landscape;},8,"Same Game orientation");
                String orientation=landscape?"landscape":"portrait";
                bars(false);JSONObject hidden=sample();check(orientation+"-hidden");
                bars(true);JSONObject shown=sample();
                assertEquals("Showing bars must not resize this edge-to-edge viewport",hidden.getInt("rootWidth"),shown.getInt("rootWidth"));assertEquals(hidden.getInt("rootHeight"),shown.getInt("rootHeight"));
                check(orientation+"-shown");bars(false);check(orientation+"-hidden-again");
                assertEquals("Insets test unexpectedly replaced the session",trace,RhStatistics.state().getString("latest"));
            }
            RhShared.write(new File(test.folder,"session-window-insets.json"),RhActions.obj("passed",true,"sameGame",true,"sameTrace",true,"samples",samples.size(),"realWindowInsets",true,"orientationFixture","emulator display override; physical sensor not tested","remoteInputSent",false,"systemInputInjected",false));
        }finally{
            emulatorShell("wm size "+restoreSize);RhShared.write(new File(test.folder,"session-window-size-restored.json"),RhActions.obj("before",originalSize,"after",emulatorShell("wm size")));
            if(RhActions.hasStream()){
                test.action("stream.keyboard.floating.set",RhActions.obj("enabled",wasEnabled));
                test.instrument.runOnMainSync(()->{test.game.setRequestedOrientation(requested[0]);WindowCompat.getInsetsController(test.game.getWindow(),test.game.getWindow().getDecorView()).hide(WindowInsetsCompat.Type.systemBars());});
                test.waitFor(()->{JSONObject current=sample();return current.optInt("rootWidth")==originalViewport.optInt("rootWidth")&&current.optInt("rootHeight")==originalViewport.optInt("rootHeight");},8,"Original emulator viewport restored");test.instrument.waitForIdleSync();if(!test.stream().optBoolean("controls"))test.click("控制");
            }
        }
    }
}
