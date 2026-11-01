package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual home/update widgets on an isolated emulator app. All updater I/O is intercepted. */
@RunWith(AndroidJUnit4.class)
public final class RhUpdateIntentDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    Activity activity;Context context;RhHome home;RhUpdates updates;File folder;
    final AtomicInteger requests=new AtomicInteger();final JSONArray results=new JSONArray();
    final RhHome.Actions actions=new RhHome.Actions(){public void connect(com.limelight.nvstream.http.ComputerDetails d){throw new AssertionError("No remote sessions");}public void add(String a){}public void advanced(){}public void pair(com.limelight.nvstream.http.ComputerDetails d,String pin){throw new AssertionError("No pairing");}public void cancelPair(){}public void refresh(){}};
    Object field(Object object,String name)throws Exception{Field value=object.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(object);}
    void set(Object object,String name,Object value)throws Exception{Field target=object.getClass().getDeclaredField(name);target.setAccessible(true);target.set(object,value);}
    void main(Work work){Throwable[] failure={null};instrument.runOnMainSync(()->{try{work.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    interface Work{void run()throws Exception;}
    void refresh()throws Exception{Method value=RhHome.class.getDeclaredMethod("refreshUpdateControls");value.setAccessible(true);value.invoke(home);}
    JSONObject candidate(String hash){return RhActions.obj("version","9.0.0","versionCode",999999,"packageName",context.getPackageName(),"tag","v9.0.0","fileName","RHRemote-test.apk","size",16,"sha256",hash);}
    String hash(char c){char[] text=new char[64];java.util.Arrays.fill(text,c);return new String(text);}
    void candidate(JSONObject value)throws Exception{set(updates,"available",value);set(updates,"ready",false);set(updates,"phase",value==null?"idle":"available");refresh();}
    void await()throws Exception{long end=SystemClock.uptimeMillis()+5000;while(true){instrument.waitForIdleSync();boolean[] busy={false};main(()->busy[0]=home.state().optBoolean("updatesBusy"));if(!busy[0])return;if(SystemClock.uptimeMillis()>end)fail("Fixture update worker did not finish");Thread.sleep(10);}}
    void touch(View target,int action,long down){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,target.getWidth()/2f,target.getHeight()/2f,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{assertTrue(target.dispatchTouchEvent(event));}finally{event.recycle();}}
    void key(View target,int action,long down){target.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_ENTER,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));}
    void capture(String name)throws Exception{main(()->{View root=activity.findViewById(android.R.id.content);Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);try{root.draw(new Canvas(bitmap));try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}finally{bitmap.recycle();}});}
    void scenario(boolean dark,String mode,String kind)throws Exception{
        requests.set(0);main(()->{
            if(home!=null)home.dispose();context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
            home=new RhHome(activity,actions);home.resumed();updates=(RhUpdates)field(home,"updates");
            set(updates,"connections",(RhUpdates.Connections)address->{requests.incrementAndGet();throw new IOException("Isolated native updater fixture; no network request");});
            home.perform("app.settings.tab",RhActions.obj("tab","updates"));candidate(kind.equals("check-to-install")?null:candidate(hash('a')));
        });instrument.waitForIdleSync();Button[] target={null};long[] operation={0};
        main(()->{target[0]=(Button)activity.findViewById(android.R.id.content).findViewWithTag("updates.primary");assertNotNull(target[0]);assertTrue(target[0] instanceof RhBoundAction.Button);assertTrue(target[0].isEnabled());assertTrue(target[0].isAttachedToWindow());target[0].requestRectangleOnScreen(new Rect(0,0,target[0].getWidth(),target[0].getHeight()),true);if(mode.equals("keyboard")){target[0].setFocusableInTouchMode(true);assertTrue(target[0].requestFocus());}operation[0]=home.state().getJSONObject("updates").getLong("operationId");});instrument.waitForIdleSync();
        boolean[] visible={false};long layoutDeadline=SystemClock.uptimeMillis()+2000;
        do{main(()->visible[0]=target[0].getGlobalVisibleRect(new Rect()));if(visible[0])break;Thread.sleep(16);}while(SystemClock.uptimeMillis()<layoutDeadline);
        if(!visible[0]){capture("not-visible-"+mode+"-"+kind);StringBuilder geometry=new StringBuilder();main(()->{View view=target[0];while(view!=null){geometry.append(view.getClass().getSimpleName()).append(':').append(view.getWidth()).append('x').append(view.getHeight()).append(" x=").append(view.getLeft()).append(" y=").append(view.getTop()).append(" scroll=").append(view.getScrollX()).append(',').append(view.getScrollY()).append(" visibility=").append(view.getVisibility()).append(';');view=view.getParent() instanceof View?(View)view.getParent():null;}});fail(geometry.toString());}
        main(()->{Rect visibleBounds=new Rect();assertTrue(target[0].getGlobalVisibleRect(visibleBounds));assertEquals("Primary button clipped horizontally",target[0].getWidth(),visibleBounds.width());assertEquals("Primary button clipped vertically",target[0].getHeight(),visibleBounds.height());});
        long down=SystemClock.uptimeMillis();main(()->{
            if(mode.equals("touch"))touch(target[0],MotionEvent.ACTION_DOWN,down);else key(target[0],KeyEvent.ACTION_DOWN,down);
            if(kind.equals("queued"))touch(target[0],MotionEvent.ACTION_UP,down);
            if(kind.equals("metadata")){JSONObject value=candidate(hash('a'));value.put("notes","Only notes changed");candidate(value);}
            else{candidate(candidate(hash('b')));if(kind.equals("roundtrip"))candidate(candidate(hash('a')));}
            if(!kind.equals("queued")){if(mode.equals("touch"))touch(target[0],MotionEvent.ACTION_UP,down);else key(target[0],KeyEvent.ACTION_UP,down);}
        });await();int oldRequests=requests.get();boolean completed=kind.equals("metadata");assertEquals("Unexpected old gesture I/O: "+kind,completed,oldRequests>0);
        main(()->assertEquals(operation[0]+(completed?1:0),home.state().getJSONObject("updates").getLong("operationId")));
        requests.set(0);main(()->{Button fresh=(Button)activity.findViewById(android.R.id.content).findViewWithTag("updates.primary");assertTrue(fresh.isEnabled());assertTrue(fresh.performClick());});await();assertTrue("Fresh explicit click was swallowed",requests.get()>0);
        String name=(dark?"dark":"light")+"-"+mode+"-"+kind;main(()->{assertEquals(operation[0]+(completed?2:1),home.state().getJSONObject("updates").getLong("operationId"));assertFalse(RhActions.hasStream());results.put(RhActions.obj("name",name,"oldFixtureRequests",oldRequests,"freshFixtureRequests",requests.get(),"workerFinished",true,"updates",home.state().getJSONObject("updates")));});capture(name);
    }
    @Test public void actualUpdatePageRejectsOldPressesAndAcceptsOneFreshOperation()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("updateIntentRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));context=instrument.getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        activity=instrument.startActivitySync(new Intent().setComponent(new ComponentName(context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrument.waitForIdleSync();
        try{for(boolean dark:new boolean[]{false,true})for(String mode:new String[]{"touch","keyboard"})for(String kind:new String[]{"artifact","roundtrip","check-to-install","metadata","queued"}){if(kind.equals("queued")&&mode.equals("keyboard"))continue;scenario(dark,mode,kind);}}
        finally{main(()->{if(home!=null)home.dispose();activity.finish();});instrument.waitForIdleSync();}
        try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"result.json")),"UTF-8")){out.write(RhActions.obj("scope","Actual RhHome update controls; direct View events; intercepted I/O; no installer or remote session","cases",results).toString(2));}assertEquals(18,results.length());
    }
}
