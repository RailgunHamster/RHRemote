package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native device metadata controls; uses the dedicated emulator and never starts a stream. */
@RunWith(AndroidJUnit4.class)
public class RhDeviceInfoFlowDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;private volatile int imeAnimations;private boolean observingIme;
    JSONObject state(){JSONObject[] value=new JSONObject[1];instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return value[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View v,String label){if(v.getVisibility()!=View.VISIBLE)return null;if(v instanceof EditText&&label.contentEquals(v.getContentDescription())||v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    View control(String label){View result=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,result);return result;}
    void type(String value){instrument.runOnMainSync(()->((EditText)control("设备名称")).setText(value));instrument.waitForIdleSync();}
    void click(String label){instrument.runOnMainSync(()->control(label).performClick());instrument.waitForIdleSync();}
    void capture(String name)throws Exception{if(!InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true"))return;instrument.waitForIdleSync();android.graphics.Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);java.io.File folder=instrument.getTargetContext().getExternalFilesDir("ui-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}}
    void observeIme(){
        if(observingIme)return;observingIme=true;instrument.runOnMainSync(()->androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(activity.getWindow().getDecorView(),new androidx.core.view.WindowInsetsAnimationCompat.Callback(androidx.core.view.WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){
            @Override public void onPrepare(androidx.core.view.WindowInsetsAnimationCompat animation){if((animation.getTypeMask()&androidx.core.view.WindowInsetsCompat.Type.ime())!=0)imeAnimations++;}
            @Override public androidx.core.view.WindowInsetsCompat onProgress(androidx.core.view.WindowInsetsCompat insets,java.util.List<androidx.core.view.WindowInsetsAnimationCompat> running){return insets;}
            @Override public void onEnd(androidx.core.view.WindowInsetsAnimationCompat animation){if((animation.getTypeMask()&androidx.core.view.WindowInsetsCompat.Type.ime())!=0)imeAnimations=Math.max(0,imeAnimations-1);}
        }));
    }
    void waitIme(boolean visible)throws Exception{
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);String previous="";int stable=0;
        while(stable<3){String[] sample={""};boolean[] ready={false};instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();androidx.core.view.WindowInsetsCompat insets=androidx.core.view.ViewCompat.getRootWindowInsets(decor);View done=control("完成");int[] position=new int[2];done.getLocationOnScreen(position);int keyboard=insets==null?0:insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
            ready[0]=activity.hasWindowFocus()&&insets!=null&&insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==visible&&imeAnimations==0&&(!visible||position[1]+done.getHeight()<=decor.getHeight()-keyboard);
            sample[0]=position[1]+":"+done.getHeight()+":"+keyboard+":"+decor.getHeight();});
            if(ready[0]&&sample[0].equals(previous))stable++;else stable=0;previous=sample[0];assertTrue("IME layout did not settle: "+visible+", "+previous+", animations="+imeAnimations,System.nanoTime()<deadline);if(stable<3)Thread.sleep(25);
        }instrument.waitForIdleSync();
    }
    void closeKeyboard()throws Exception{
        observeIme();instrument.runOnMainSync(()->((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0));waitIme(false);
    }
    void verifyKeyboardFooter()throws Exception{
        observeIme();
        long focusDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(!activity.hasWindowFocus()){assertTrue("The identity page did not receive focus",System.nanoTime()<focusDeadline);Thread.sleep(25);}
        instrument.runOnMainSync(()->{EditText editor=(EditText)control("设备名称");editor.requestFocus();((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(editor,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
        waitIme(true);
        instrument.runOnMainSync(()->{View done=control("完成");android.graphics.Rect visible=new android.graphics.Rect();assertTrue(done.getGlobalVisibleRect(visible));assertEquals("Done must remain above the keyboard",done.getHeight(),visible.height());});capture("device-info-keyboard");closeKeyboard();
    }
    @Test public void nativeIdentityEditingKeepsConnectionProfilesAndProtectsUnsavedNavigation()throws Exception {
        activity=instrument.startActivitySync(new Intent().setClassName(instrument.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);
        JSONArray devices=state().getJSONArray("devices");String uuid="";for(int i=0;i<devices.length();i++)if(devices.getJSONObject(i).getString("name").equalsIgnoreCase("winserver"))uuid=devices.getJSONObject(i).getString("uuid");assertFalse("Configure winserver in the UI emulator",uuid.isEmpty());
        String original=RhShared.alias(activity,uuid);String profiles=RhShared.canonical(RhShared.read(activity).getJSONObject("profiles"));action("devices.filter",RhActions.obj("query","winserver"));action("device.select",RhActions.obj("uuid",uuid));
        click("编辑资料");assertEquals("device-info",state().getString("page"));type("资料验收");verifyKeyboardFooter();capture("device-info-editor");click("完成");assertEquals("资料验收",RhShared.alias(activity,uuid));assertEquals("devices",state().getString("page"));assertEquals("winserver",state().getString("deviceQuery"));assertEquals(uuid,state().getString("selected"));assertEquals(profiles,RhShared.canonical(RhShared.read(activity).getJSONObject("profiles")));
        action("device.info.open",RhActions.obj("uuid",uuid));type("bad\nname");click("完成");assertEquals("device-info",state().getString("page"));assertEquals("bad\nname",state().getJSONObject("infoDraft").getString("displayName"));assertFalse(state().getJSONObject("infoDraft").getString("error").isEmpty());capture("device-info-error");click("取消");assertEquals("资料验收",RhShared.alias(activity,uuid));
        action("device.info.open",RhActions.obj("uuid",uuid));click("恢复原名");assertEquals("",state().getJSONObject("infoDraft").getString("displayName"));assertEquals("资料验收",RhShared.alias(activity,uuid));closeKeyboard();assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(state().isNull("navigationPrompt")){assertTrue("Back bypassed device identity changes",System.nanoTime()<deadline);Thread.sleep(25);}assertEquals("unsaved-info",state().getJSONObject("navigationPrompt").getString("kind"));
        Field dialog=RhHome.class.getDeclaredField("navigationDialog");dialog.setAccessible(true);instrument.runOnMainSync(()->{try{((AlertDialog)dialog.get(home)).getButton(DialogInterface.BUTTON_NEUTRAL).performClick();}catch(Exception e){throw new RuntimeException(e);}});instrument.waitForIdleSync();assertTrue(state().isNull("navigationPrompt"));assertEquals("",state().getJSONObject("infoDraft").getString("displayName"));click("完成");assertEquals("",RhShared.alias(activity,uuid));
        action("device.info.open",RhActions.obj("uuid",uuid));type(original);click("完成");assertEquals(original,RhShared.alias(activity,uuid));assertEquals(profiles,RhShared.canonical(RhShared.read(activity).getJSONObject("profiles")));assertFalse(RhActions.hasStream());
    }
}
