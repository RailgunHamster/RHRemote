package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real home-window IME and View callbacks. No host lookup, update download or system input. */
@RunWith(AndroidJUnit4.class)
public final class RhHomeImeDeviceTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Activity activity;private RhHome home;private final Set<WindowInsetsAnimationCompat> animations=new HashSet<>();
    private View find(View view,String value){if(!view.isShown())return null;if(value.equals(view.getTag())||view instanceof Button&&value.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;}
    private void visible(View view){android.graphics.Rect rect=new android.graphics.Rect();assertTrue(view.getGlobalVisibleRect(rect));assertEquals(view.getWidth(),rect.width());assertEquals(view.getHeight(),rect.height());}
    private void click(String value){instrument.runOnMainSync(()->{View view=find(activity.getWindow().getDecorView(),value);assertNotNull(value,view);assertTrue(value,view.isEnabled());visible(view);assertTrue(value,view.performClick());});instrument.waitForIdleSync();}
    private JSONObject state(){JSONObject[] result={null};instrument.runOnMainSync(()->{try{result[0]=home.state();}catch(Exception error){throw new RuntimeException(error);}});return result[0];}
    private JSONObject keyboard(){JSONObject[] result={null};instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(decor);result[0]=RhActions.obj("known",insets!=null,"visible",insets!=null&&insets.isVisible(WindowInsetsCompat.Type.ime()),"animations",animations.size(),"windowFocus",activity.hasWindowFocus());});return result[0];}
    private void waitKeyboard(boolean visible)throws Exception{long end=SystemClock.uptimeMillis()+5000;int stable=0;while(stable<3){JSONObject value=keyboard();stable=value.optBoolean("known")&&value.optBoolean("visible")==visible&&value.optInt("animations")==0?stable+1:0;assertTrue("IME did not settle to "+visible+": "+value,SystemClock.uptimeMillis()<end);Thread.sleep(25);}instrument.waitForIdleSync();}
    @Test public void cancellingAnEditorDoesNotLeaveTheKeyboardOnTheUpdatePage()throws Exception{
        assertTrue("Dedicated emulator required",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Context context=instrument.getTargetContext();String run=InstrumentationRegistry.getArguments().getString("caseId","");assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));File evidence=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(evidence.exists());assertTrue(evidence.mkdirs());
        JSONObject beforeShared=RhShared.read(context);String shared=RhShared.canonical(beforeShared);boolean originalDark=RhShared.dark(context);String theme=InstrumentationRegistry.getArguments().getString("theme",originalDark?"dark":"light");assertTrue(theme.equals("light")||theme.equals("dark"));RhShared.theme(context,theme.equals("dark"));SharedPreferences global=context.getSharedPreferences("rhremote.global",0);boolean hadCheck=global.contains("lastUpdateCheck");long lastCheck=global.getLong("lastUpdateCheck",0);global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
        try{
            activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field owner=activity.getClass().getDeclaredField("rhHome");owner.setAccessible(true);home=(RhHome)owner.get(activity);
            instrument.runOnMainSync(()->ViewCompat.setWindowInsetsAnimationCallback(activity.getWindow().getDecorView(),new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){@Override public void onPrepare(WindowInsetsAnimationCompat animation){if((animation.getTypeMask()&WindowInsetsCompat.Type.ime())!=0)animations.add(animation);}@Override public WindowInsetsCompat onProgress(WindowInsetsCompat insets,List<WindowInsetsAnimationCompat> running){return insets;}@Override public void onEnd(WindowInsetsAnimationCompat animation){animations.remove(animation);}}));
            click("nav:device.add.open");assertEquals("add",state().optString("page"));Field editor=RhHome.class.getDeclaredField("addEditor");editor.setAccessible(true);EditText input=(EditText)editor.get(home);
            instrument.runOnMainSync(()->{input.setText("ime-navigation.invalid");assertTrue(input.requestFocusFromTouch());input.post(()->((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,0));});waitKeyboard(true);
            instrument.runOnMainSync(()->input.setText("bad address !"));waitKeyboard(true);click("添加");assertEquals("add",state().optString("page"));assertFalse(state().getJSONObject("add").optString("error").isEmpty());assertEquals("bad address !",state().getJSONObject("add").getString("address"));waitKeyboard(true);
            RhShared.write(new File(evidence,"before-cancel.json"),keyboard());click("取消");assertEquals("devices",state().optString("page"));
            click("nav:app.settings.open");assertEquals("updates",state().optString("settingsTab"));
            RhShared.write(new File(evidence,"after-navigation.json"),RhActions.obj("keyboard",keyboard(),"homeLayout",state().optJSONObject("homeLayout")));waitKeyboard(false);
            android.graphics.Bitmap[] image={null};instrument.runOnMainSync(()->{View primary=activity.getWindow().getDecorView().findViewWithTag("updates.primary");assertNotNull(primary);visible(primary);assertTrue(primary.isEnabled());View decor=activity.getWindow().getDecorView();image[0]=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);decor.draw(new android.graphics.Canvas(image[0]));});try(java.io.OutputStream out=new java.io.FileOutputStream(new File(evidence,"updates-after-navigation.png"))){assertTrue(image[0].compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{image[0].recycle();}
            assertFalse(state().optBoolean("updatesBusy"));JSONObject expectedShared=new JSONObject(beforeShared.toString());expectedShared.put("dark",theme.equals("dark"));assertEquals(RhShared.canonical(expectedShared),RhShared.canonical(RhShared.read(context)));
            RhShared.write(new File(evidence,"result.json"),RhActions.obj("passed",true,"keyboardHidden",true,"samePageTypingKeptKeyboard",true,"invalidSubmitKeptInputAndKeyboard",true,"updatesIdle",true,"sharedUnchanged",true,"systemInputInjected",false,"updateInstalled",false));
        }finally{
            if(activity!=null){instrument.runOnMainSync(()->{((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);ViewCompat.setWindowInsetsAnimationCallback(activity.getWindow().getDecorView(),null);activity.finish();});long end=SystemClock.uptimeMillis()+3000;while(!activity.isDestroyed()){assertTrue("Home Activity cleanup",SystemClock.uptimeMillis()<end);Thread.sleep(25);}}instrument.waitForIdleSync();RhShared.apply(context,beforeShared);RhShared.theme(context,originalDark);if(hadCheck)global.edit().putLong("lastUpdateCheck",lastCheck).commit();else global.edit().remove("lastUpdateCheck").commit();assertEquals(originalDark,RhShared.dark(context));assertEquals(shared,RhShared.canonical(RhShared.read(context)));
        }
    }
}
