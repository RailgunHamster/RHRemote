package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses real native views on the dedicated emulator; no remote session or pairing is started. */
@RunWith(AndroidJUnit4.class)
public class RhFormFlowDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;
    JSONObject state(){JSONObject[] value=new JSONObject[1];instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return value[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject r=RhActions.dispatch(id,args);assertTrue(r.toString(),r.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View v,String text,boolean editor){if(v.getVisibility()!=View.VISIBLE)return null;if(editor&&v instanceof EditText&&text.contentEquals(v.getContentDescription())||!editor&&v instanceof Button&&text.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=find(((ViewGroup)v).getChildAt(i),text,editor);if(child!=null)return child;}return null;}
    View control(String text,boolean editor){View v=find(activity.findViewById(android.R.id.content),text,editor);assertNotNull(text,v);return v;}
    void visibleOutsideScroll(View v){Rect rect=new Rect();assertTrue(v.getGlobalVisibleRect(rect));assertEquals(v.getHeight(),rect.height());for(android.view.ViewParent p=v.getParent();p instanceof View;p=p.getParent())assertFalse("Save bar must not scroll",p instanceof ScrollView);}
    @Test public void nativeFormsKeepPartialInputProtectNavigationAndKeepSaveReachable()throws Exception{
        activity=instrument.startActivitySync(new Intent().setClassName(instrument.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();
        Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);
        String uuid=state().getJSONArray("devices").getJSONObject(0).getString("uuid");action("device.settings.open",RhActions.obj("uuid",uuid));
        JSONArray fields=RhSettings.catalog(activity);String label="";for(int i=0;i<fields.length();i++)if(fields.getJSONObject(i).getString("key").equals("width"))label=fields.getJSONObject(i).getString("label");final String widthLabel=label;
        instrument.runOnMainSync(()->{EditText edit=(EditText)control(widthLabel,true);edit.requestFocus();edit.setText("");((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(edit,InputMethodManager.SHOW_IMPLICIT);});instrument.waitForIdleSync();
        assertEquals("",state().getJSONObject("settingsInputs").getString("width"));instrument.runOnMainSync(()->{visibleOutsideScroll(control("保存",false));visibleOutsideScroll(control("概览",false));control("保存",false).performClick();});instrument.waitForIdleSync();assertEquals("width",state().getString("settingsErrorField"));
        action("settings.tab",RhActions.obj("tab","audio"));action("settings.tab",RhActions.obj("tab","video"));assertEquals("",state().getJSONObject("settingsInputs").getString("width"));
        action("app.settings.tab",RhActions.obj("tab","sync"));assertEquals("unsaved-device",state().getJSONObject("navigationPrompt").getString("kind"));action("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("settings",state().getString("page"));
        action("app.settings.tab",RhActions.obj("tab","sync"));action("navigation.resolve",RhActions.obj("choice","discard"));
        instrument.runOnMainSync(()->{((EditText)control("WebDAV 目录地址（HTTP / HTTPS）",true)).setText("invalid");control("保存",false).performClick();});instrument.waitForIdleSync();assertTrue(state().getBoolean("syncDirty"));assertFalse(state().getString("syncError").isEmpty());assertEquals("invalid",state().getJSONObject("syncDraft").getString("url"));
        action("app.settings.tab",RhActions.obj("tab","updates"));assertEquals("unsaved-sync",state().getJSONObject("navigationPrompt").getString("kind"));action("navigation.resolve",RhActions.obj("choice","stay"));
        instrument.runOnMainSync(()->((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0));
        long backDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);boolean[] keyboard={true};do{instrument.runOnMainSync(()->{androidx.core.view.WindowInsetsCompat insets=androidx.core.view.ViewCompat.getRootWindowInsets(activity.findViewById(android.R.id.content));keyboard[0]=!activity.hasWindowFocus()||insets!=null&&insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());});assertTrue("Keyboard did not close",System.nanoTime()<backDeadline);if(keyboard[0])Thread.sleep(25);}while(keyboard[0]);
        assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));backDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(state().isNull("navigationPrompt")){assertTrue("System Back: page="+state().optString("page")+", dirty="+state().optBoolean("syncDirty")+", focus="+activity.hasWindowFocus(),System.nanoTime()<backDeadline);Thread.sleep(25);}assertEquals("unsaved-sync",state().getJSONObject("navigationPrompt").getString("kind"));action("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("invalid",state().getJSONObject("syncDraft").getString("url"));action("sync.cancel",RhActions.obj());
        instrument.runOnMainSync(()->{visibleOutsideScroll(control("保存",false));((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);});action("devices.open",RhActions.obj());
    }
}
