package com.limelight.rhremote;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Events are dispatched to our attached Views, never to the OS input dispatcher. */
@RunWith(AndroidJUnit4.class)
public final class RhDeviceIntentDeviceTest {
    final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    Activity activity;Context context;File folder;Rig rig;final JSONArray cases=new JSONArray();
    final class Rig {
        final ComputerDetails first=device("a"),second=device("b");final RhDeviceBrowser browser;
        final List<JSONObject> sent=new ArrayList<>();
        Rig(boolean dark,String kind)throws Exception{
            context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).commit();
            browser=new RhDeviceBrowser(activity,(id,args)->{try{sent.add(RhActions.obj("action",id,"args",new JSONObject(args.toString())));}catch(JSONException e){throw new AssertionError(e);}});
            if(!kind.equals("refresh")&&!kind.equals("pair-roundtrip")){paired(first);paired(second);}
            activity.setContentView(browser);update(first.uuid);
        }
        ComputerDetails device(String uuid){ComputerDetails d=new ComputerDetails();d.uuid=uuid;d.name="PC "+uuid;d.state=ComputerDetails.State.ONLINE;d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.10",47989);return d;}
        void paired(ComputerDetails d){d.serverCert=com.limelight.binding.PlatformBinding.getCryptoProvider(context).getClientCertificate();d.pairState=PairingManager.PairState.PAIRED;}
        void update(String uuid){browser.update(Arrays.asList(first,second),uuid,"");browser.showDetail(true);}
        View control(String kind)throws Exception{
            if(kind.equals("row-mode")){Field f=RhDeviceBrowser.class.getDeclaredField("rows");f.setAccessible(true);return ((Map<String,? extends View>)f.get(browser)).get(first.uuid);}
            String label=kind.equals("refresh")?"检查连接":kind.equals("settings")?"连接设置":kind.equals("info")?"编辑资料":kind.equals("ordering")?"下移":kind.equals("pair-roundtrip")?"配对":"连接";
            Button found=find(browser,label);assertNotNull(label,found);return found;
        }
        void change(String kind,View control){
            switch(kind){
                case "refresh":paired(first);update(first.uuid);break;
                case "selection":case "settings":case "info":update(second.uuid);break;
                case "roundtrip":case "ordering":update(second.uuid);update(first.uuid);break;
                case "metadata":first.name="Updated PC a";update(first.uuid);break;
                case "parent":browser.setVisibility(View.GONE);browser.setVisibility(View.VISIBLE);break;
                case "disabled":control.setEnabled(false);control.setEnabled(true);break;
                case "row-mode":browser.setListOnly(true);break;
                case "pair-roundtrip":paired(first);update(first.uuid);first.serverCert=null;first.pairState=null;update(first.uuid);break;
                default:break;
            }
        }
    }
    Button find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
    void main(Runnable work){instrument.runOnMainSync(work);}
    void touch(View target,int action,long down){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,Math.min(target.getWidth()/2f,12f),Math.min(target.getHeight()/2f,12f),0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{assertTrue(target.dispatchTouchEvent(e));}finally{e.recycle();}}
    void key(View target,int action,long down,int repeat,boolean cancelled){KeyEvent event=new KeyEvent(down,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_ENTER,repeat,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,cancelled?KeyEvent.FLAG_CANCELED:0,InputDevice.SOURCE_KEYBOARD);target.dispatchKeyEvent(event);}
    void capture(String name)throws Exception{Bitmap image=Bitmap.createBitmap(rig.browser.getWidth(),rig.browser.getHeight(),Bitmap.Config.ARGB_8888);try{rig.browser.draw(new Canvas(image));try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}}finally{image.recycle();}}
    void scenario(boolean dark,String mode,String kind)throws Exception{
        Throwable[] error={null};main(()->{try{rig=new Rig(dark,kind);}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(kind,error[0]);instrument.waitForIdleSync();
        View[] target={null};main(()->{try{target[0]=rig.control(kind);assertTrue(target[0].isAttachedToWindow());target[0].requestRectangleOnScreen(new Rect(0,0,target[0].getWidth(),target[0].getHeight()),true);if(mode.equals("keyboard")){target[0].setFocusableInTouchMode(true);assertTrue(target[0].requestFocus());}}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(kind,error[0]);instrument.waitForIdleSync();
        main(()->assertTrue("The tested widget must be visible",target[0].getGlobalVisibleRect(new Rect())));
        long down=SystemClock.uptimeMillis();main(()->{
            if(mode.equals("touch"))touch(target[0],MotionEvent.ACTION_DOWN,down);else key(target[0],KeyEvent.ACTION_DOWN,down,0,false);
            if(!kind.equals("queued"))rig.change(kind,target[0]);
            if(kind.equals("cancel")&&mode.equals("touch"))touch(target[0],MotionEvent.ACTION_CANCEL,down);
            if(mode.equals("touch"))touch(target[0],MotionEvent.ACTION_UP,down);else{key(target[0],KeyEvent.ACTION_DOWN,down,1,false);key(target[0],KeyEvent.ACTION_UP,down,0,kind.equals("cancel"));}
            if(kind.equals("queued"))rig.update(rig.second.uuid);
        });instrument.waitForIdleSync();
        String name=(dark?"dark":"light")+"-"+mode+"-"+kind;main(()->{try{
            // Key-up activates synchronously. A subsequent change must preserve that
            // completed action, while a still-posted touch click must be cancelled.
            boolean completed=kind.equals("metadata")||kind.equals("queued")&&mode.equals("keyboard");
            assertEquals("Unexpected old action: "+name+" "+rig.sent,completed?1:0,rig.sent.size());
            if(completed){assertEquals("device.connect",rig.sent.get(0).getString("action"));assertEquals("a",rig.sent.get(0).getJSONObject("args").getString("uuid"));}
            JSONArray old=new JSONArray(rig.sent);rig.sent.clear();assertTrue(target[0].isEnabled());assertTrue(target[0].performClick());assertEquals(1,rig.sent.size());JSONObject fresh=rig.sent.get(0);
            String action=kind.equals("settings")||kind.equals("row-mode")?"device.settings.open":kind.equals("info")?"device.info.open":kind.equals("ordering")?"device.move":kind.equals("pair-roundtrip")?"device.pair":"device.connect";
            String uuid=Arrays.asList("selection","settings","info","queued").contains(kind)?"b":"a";
            assertEquals(action,fresh.getString("action"));assertEquals(uuid,fresh.getJSONObject("args").getString("uuid"));if(kind.equals("ordering"))assertEquals("down",fresh.getJSONObject("args").getString("direction"));
            cases.put(RhActions.obj("name",name,"old",old,"fresh",fresh,"attached",target[0].isAttachedToWindow()));
            if(mode.equals("touch")&&Arrays.asList("refresh","selection","metadata","ordering").contains(kind))capture(name);
        }catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(name,error[0]);
    }
    @Test public void touchKeyboardAndQueuedActionsKeepTheirOriginalIntent()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("deviceIntentRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        context=instrument.getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhShared.clientId(context);RhShared.read(context);
        activity=instrument.startActivitySync(new Intent().setComponent(new ComponentName(context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrument.waitForIdleSync();
        try{for(boolean dark:new boolean[]{false,true})for(String mode:new String[]{"touch","keyboard"})for(String kind:new String[]{"refresh","selection","roundtrip","metadata","settings","info","parent","disabled","row-mode","pair-roundtrip","ordering","queued","cancel"})scenario(dark,mode,kind);}
        finally{main(()->activity.finish());instrument.waitForIdleSync();}
        RhShared.write(new File(folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"package",context.getPackageName(),"cases",cases,"images",8,"hostOperations",false,"normalClientOperated",false,"systemInputInjected",false,"scope","Attached native Android Views; direct owned MotionEvent/KeyEvent dispatch and API action recording; no remote session"));
        assertEquals(52,cases.length());
    }
}
