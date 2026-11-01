package com.limelight.rhremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.ui.StreamView;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual owned windows and system IME; fixtures never create a Game or media connection. */
@RunWith(AndroidJUnit4.class)
public class RhPlatformWindowDeviceTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    private FrameLayout root;
    private StreamView surface;
    private RhSoftKeyboard keyboard;
    private TrackedBackend backend;
    private JSONObject completion;
    private JSONObject lastObservation;
    private boolean cancelledCompletion;
    private File folder;

    private String installedVersion(){
        try{Context context=instrument.getTargetContext();return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}
        catch(Exception failure){throw new RuntimeException(failure);}
    }

    private static final class TrackedBackend implements RhSoftKeyboard.Backend {
        final RhSoftKeyboard.WindowBackend window;
        final List<Boolean> requests=new ArrayList<>();
        boolean holdAnimation;
        TrackedBackend(RhSoftKeyboard.WindowBackend window){this.window=window;}
        public Boolean visible(){return window.visible();}
        public boolean settled(){return !holdAnimation&&window.settled();}
        public void request(boolean show){requests.add(show);window.request(show);}
        public void dispose(){window.dispose();}
    }

    private String prepare(String part)throws Exception{
        assertTrue("Dedicated emulator required",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        String run=InstrumentationRegistry.getArguments().getString("windowRun","");
        assertTrue("Fresh explicit windowRun required",run.matches("[a-zA-Z0-9_-]{1,40}"));
        assertFalse("Window fixtures must not start during a stream",RhActions.hasStream());
        folder=new File(instrument.getTargetContext().getExternalFilesDir("ui-evidence"),"window-"+run+"/"+part);
        assertFalse("Existing evidence must not be replaced",folder.exists());assertTrue(folder.mkdirs());
        return "window-"+run+"-"+part;
    }

    private void open(boolean dark)throws Exception{
        Context context=instrument.getTargetContext();RhShared.theme(context,dark);
        activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrument.waitForIdleSync();
        instrument.runOnMainSync(()->{
            try{Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);((RhHome)field.get(activity)).dispose();}
            catch(Exception failure){throw new RuntimeException(failure);}
            root=new FrameLayout(activity);surface=new StreamView(activity);surface.setId(com.limelight.R.id.surfaceView);
            surface.setFocusable(true);surface.setFocusableInTouchMode(true);
            root.addView(surface,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(root);surface.requestFocusFromTouch();
        });
        await(()->{JSONObject state=read();return state.optBoolean("windowFocus")&&state.optBoolean("attached")&&state.optBoolean("insetsKnown")&&state.optInt("width")>0&&state.optInt("height")>0;},5,"Owned window focus and layout");
    }

    private JSONObject read(){
        JSONObject[] state={null};instrument.runOnMainSync(()->{
            WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
            state[0]=RhActions.obj("visible",insets!=null&&insets.isVisible(WindowInsetsCompat.Type.ime()),"imeBottom",insets==null?-1:insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,"insetsKnown",insets!=null,"width",root.getWidth(),"height",root.getHeight(),
                "windowFocus",activity.hasWindowFocus(),"attached",surface.isAttachedToWindow(),"streamFocus",surface.hasFocus(),"streamTextEditor",surface.onCheckIsTextEditor(),
                "pending",keyboard!=null&&keyboard.pending(),"error",keyboard==null?"":keyboard.error(),"completion",completion==null?JSONObject.NULL:completion,
                "requests",backend==null?new JSONArray():new JSONArray(backend.requests),"cancelledCompletion",cancelledCompletion,"hostStream",RhActions.hasStream());
        });return state[0];
    }

    private void await(BooleanSupplier condition,int seconds,String message)throws Exception{
        long until=SystemClock.uptimeMillis()+seconds*1000L;
        while(!condition.getAsBoolean()){if(SystemClock.uptimeMillis()>=until)fail(message+": "+read());Thread.sleep(40);}
    }

    private void request(boolean show,boolean hold){
        instrument.runOnMainSync(()->{
            backend.holdAnimation=hold;completion=null;long begin=SystemClock.uptimeMillis();
            keyboard.request(show,(visible,error)->completion=RhActions.obj("visible",visible,"error",error,"elapsedMs",SystemClock.uptimeMillis()-begin));
        });
    }

    private JSONObject confirm(boolean show)throws Exception{
        await(()->read().optBoolean("visible")==show,6,"Actual IME visibility");
        JSONObject observed=read();lastObservation=observed;if(show)assertTrue("IME must occupy actual window insets",observed.getInt("imeBottom")>0);
        await(()->!read().optBoolean("pending"),6,"Keyboard completion");
        JSONObject result=read();assertFalse(result.getBoolean("hostStream"));
        JSONObject done=result.getJSONObject("completion");assertEquals(show,done.getBoolean("visible"));assertEquals("",done.getString("error"));
        result.put("observedBeforeCompletion",observed);return result;
    }

    private void closeWindow()throws Exception{
        if(activity==null)return;
        instrument.runOnMainSync(()->{if(keyboard!=null){keyboard.dispose();keyboard=null;}});
        instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();
        long until=SystemClock.uptimeMillis()+5000;while(!activity.isDestroyed()){assertTrue("Owned fixture did not retire",SystemClock.uptimeMillis()<until);Thread.sleep(20);}
        activity=null;backend=null;completion=null;
    }

    @Test public void realWindowImeConfirmsObservedStateAndCancelsPostedShows()throws Exception{
        String run=prepare("ime");RhSessionFlowDeviceTest owner=new RhSessionFlowDeviceTest();owner.folder=folder;
        JSONArray cases=new JSONArray();boolean passed=false;
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(owner,run)){
            try{
                for(boolean dark:new boolean[]{false,true}){
                    open(dark);instrument.runOnMainSync(()->{backend=new TrackedBackend(new RhSoftKeyboard.WindowBackend(activity.getWindow(),surface));keyboard=new RhSoftKeyboard(backend,new Handler(Looper.getMainLooper()));});
                    assertFalse("Fixture must start with its IME hidden",read().getBoolean("visible"));
                    request(true,true);JSONObject shown=confirm(true);
                    assertEquals("A completed show must not be cancelled",1,shown.getJSONArray("requests").length());assertTrue(shown.getJSONArray("requests").getBoolean(0));
                    request(false,true);JSONObject hidden=confirm(false);
                    request(true,false);JSONObject ordinaryShown=confirm(true);request(false,false);JSONObject ordinaryHidden=confirm(false);
                    instrument.runOnMainSync(()->{backend.holdAnimation=false;completion=null;cancelledCompletion=false;keyboard.request(true,(v,e)->cancelledCompletion=true);keyboard.request(false,(v,e)->completion=RhActions.obj("visible",v,"error",e));});
                    JSONObject cancelled=confirm(false);Thread.sleep(700);JSONObject afterCancel=read();assertFalse("Cancelled posted show reopened the keyboard",afterCancel.getBoolean("visible"));assertFalse("Cancelled show callback ran",afterCancel.getBoolean("cancelledCompletion"));
                    cases.put(RhActions.obj("dark",dark,"lateAnimationControlled",true,"shown",shown,"hidden",hidden,"ordinaryShown",ordinaryShown,"ordinaryHidden",ordinaryHidden,"cancelled",cancelled));
                    closeWindow();
                }
                passed=true;
            }finally{JSONObject last=activity==null?RhActions.obj():read();closeWindow();RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",passed,"version",installedVersion(),"cases",cases,"lastObservation",lastObservation==null?JSONObject.NULL:lastObservation,"lastState",last,"actualWindow",true,"actualSystemIme",true,"lateAnimationControlled",true,"gameSession",false,"hostConnectionStarted",false,"remoteInputSent",false,"systemInputInjected",false,"osScreenshots",false));}
        }
    }

    private JSONObject measuredHandle(RhStreamPanel panel)throws Exception{
        JSONObject[] measured={null};
        await(()->{
            boolean[] stable={false};instrument.runOnMainSync(()->{
                View handle=panel.controlHandle();ViewGroup.LayoutParams requested=handle.getLayoutParams();
                stable[0]=!root.isLayoutRequested()&&!handle.isLayoutRequested()&&handle.getWidth()>0&&handle.getHeight()>0
                    &&requested.width==handle.getWidth()&&requested.height==handle.getHeight();
                measured[0]=panel.layoutState().optJSONObject("handle");
            });return stable[0];
        },5,"Actual handle layout did not settle");
        return measured[0];
    }

    private void captureOwnedRoot(String name)throws Exception{
        android.graphics.Bitmap[] pixels={null};instrument.runOnMainSync(()->{pixels[0]=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(pixels[0]));});
        try(java.io.OutputStream out=new java.io.FileOutputStream(new File(folder,name+".png"))){assertTrue(pixels[0].compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{pixels[0].recycle();}
    }
    private void revealOwnedControl(View view)throws Exception{
        instrument.runOnMainSync(()->view.requestRectangleOnScreen(new android.graphics.Rect(0,0,view.getWidth(),view.getHeight()),true));
        await(()->{boolean[] visible={false};instrument.runOnMainSync(()->{android.graphics.Rect rect=new android.graphics.Rect();visible[0]=view.getGlobalVisibleRect(rect)&&rect.width()==view.getWidth()&&rect.height()==view.getHeight();});return visible[0];},5,"Screen transform control must be fully reachable");
    }
    @Test public void screenTransformsAreReachableOnlyInTheirOwnPage()throws Exception{
        String run=prepare("screen");RhSessionFlowDeviceTest owner=new RhSessionFlowDeviceTest();owner.folder=folder;
        JSONArray cases=new JSONArray();boolean passed=false;
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(owner,run)){
            try{
                for(boolean dark:new boolean[]{false,true}){
                    open(dark);RhStreamPanel[] panel={null};View[] pan={null},reset={null};List<String> actions=new ArrayList<>();
                    try{
                        instrument.runOnMainSync(()->{panel[0]=new RhStreamPanel(activity,root,(id,args)->actions.add(id));panel[0].setExpanded(true);panel[0].updateControls(true,false,1,false,"","","");pan[0]=root.findViewWithTag("stream.pan.toggle");reset[0]=root.findViewWithTag("stream.zoom.reset");assertNotNull(pan[0]);assertNotNull(reset[0]);assertFalse(pan[0].isShown());assertFalse(reset[0].isShown());pan[0].performClick();reset[0].performClick();assertTrue(actions.isEmpty());});
                        measuredHandle(panel[0]);captureOwnedRoot(dark?"dark-input":"light-input");
                        instrument.runOnMainSync(()->{panel[0].updateZoom(1.5f);panel[0].setTab("displays");});measuredHandle(panel[0]);
                        revealOwnedControl(pan[0]);revealOwnedControl(reset[0]);captureOwnedRoot(dark?"dark-screen-scale":"light-screen-scale");
                        instrument.runOnMainSync(()->{assertTrue(pan[0].isEnabled());assertTrue(reset[0].isEnabled());pan[0].performClick();reset[0].performClick();assertEquals(java.util.Arrays.asList("stream.pan.toggle","stream.zoom.reset"),actions);panel[0].setTab("audio");pan[0].performClick();reset[0].performClick();assertEquals(2,actions.size());panel[0].setTab("displays");panel[0].updateControls(true,false,1.5f,true,"displays","正在切换屏幕…","");assertFalse(pan[0].isEnabled());assertFalse(reset[0].isEnabled());pan[0].performClick();reset[0].performClick();assertEquals(2,actions.size());});
                        cases.put(RhActions.obj("dark",dark,"version",installedVersion(),"fontScale",activity.getResources().getConfiguration().fontScale,"actualWindow",true,"fullyReachable",true,"actualCallbacks",actions.size(),"hiddenAndBusyRejected",true,"connectedStateIsFixture",true));
                    }finally{instrument.runOnMainSync(()->{if(panel[0]!=null)panel[0].dispose();});closeWindow();}
                }
                passed=true;
            }finally{closeWindow();RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",passed,"version",installedVersion(),"cases",cases,"actualWindow",true,"gameSession",false,"hostConnectionStarted",false,"remoteInputSent",false,"systemInputInjected",false,"osScreenshots",false));}
        }
    }

    @Test public void warningAndCollapseKeepTheActualWindowTouchTarget()throws Exception{
        String run=prepare("handle");RhSessionFlowDeviceTest owner=new RhSessionFlowDeviceTest();owner.folder=folder;
        JSONArray cases=new JSONArray();boolean passed=false;
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(owner,run)){
            try{
                for(boolean dark:new boolean[]{false,true}){
                    open(dark);RhStreamPanel[] panel={null};JSONObject[] result={null};
                    try{
                        instrument.runOnMainSync(()->{panel[0]=new RhStreamPanel(activity,root,(id,args)->{});panel[0].setExpanded(true);panel[0].updateNetworkWarning("布局测试警告","此处仅核对按钮位置");});
                        JSONObject warning=measuredHandle(panel[0]);
                        instrument.runOnMainSync(()->{panel[0].setExpanded(false);panel[0].updateNetworkWarning("","");});JSONObject collapsed=measuredHandle(panel[0]);
                        instrument.runOnMainSync(()->panel[0].setExpanded(true));JSONObject expanded=measuredHandle(panel[0]);
                        result[0]=RhActions.obj("dark",dark,"warning",warning,"collapsed",collapsed,"expanded",expanded,"controlledWarning",true);cases.put(result[0]);
                        assertEquals("Clearing a warning must not move the collapsed target",warning.toString(),collapsed.toString());
                        assertEquals("Both captions use the same target",warning.toString(),expanded.toString());
                        assertTrue(warning.optInt("width")>0&&warning.optInt("height")>0);
                        assertFalse(RhActions.hasStream());
                    }
                    finally{instrument.runOnMainSync(()->{if(panel[0]!=null)panel[0].dispose();});closeWindow();}
                }
                passed=true;
            }finally{closeWindow();RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",passed,"version",installedVersion(),"cases",cases,"actualWindow",true,"controlledWarning",true,"gameSession",false,"hostConnectionStarted",false,"remoteInputSent",false,"systemInputInjected",false,"osScreenshots",false));}
        }
    }
}
