package com.limelight.rhremote;

import android.app.*;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.lang.reflect.Field;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual PcView lookup, cancellation and database commit, on the isolated UI emulator. */
@RunWith(AndroidJUnit4.class)
public class RhAddFlowDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;
    JSONObject state(){JSONObject[] value=new JSONObject[1];instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return value[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject response=RhActions.dispatch(id,args);assertTrue(response.toString(),response.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View v,String label){if(v.getVisibility()!=View.VISIBLE)return null;if(v instanceof EditText&&label.contentEquals(v.getContentDescription())||v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=find(((ViewGroup)v).getChildAt(i),label);if(child!=null)return child;}return null;}
    View control(String label){View v=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,v);return v;}
    void type(String address){instrument.runOnMainSync(()->((EditText)control("设备地址")).setText(address));instrument.waitForIdleSync();}
    void capture(String name)throws Exception{
        if(!InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true"))return;
        instrument.waitForIdleSync();android.graphics.Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull("Native UI capture",image);java.io.File folder=instrument.getTargetContext().getExternalFilesDir("ui-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());try(java.io.OutputStream output=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}finally{image.recycle();}
    }
    void complete()throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(!state().getJSONObject("add").getString("phase").equals("editing")){assertTrue("Add operation did not complete",System.nanoTime()<deadline);Thread.sleep(25);}instrument.waitForIdleSync();}
    boolean keyboardVisible(){boolean[] visible={false};instrument.runOnMainSync(()->{WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(activity.findViewById(android.R.id.content));visible[0]=insets!=null&&insets.isVisible(WindowInsetsCompat.Type.ime());});return visible[0];}
    void systemBackToDevices()throws Exception{
        String previousPage=state().getString("page");if(keyboardVisible()){assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(keyboardVisible()){assertTrue("Back did not dismiss the keyboard",System.nanoTime()<deadline);Thread.sleep(25);}assertEquals(previousPage,state().getString("page"));}
        assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(!state().getString("page").equals("devices")){assertTrue("Back did not return to devices",System.nanoTime()<deadline);Thread.sleep(25);}assertFalse(activity.isFinishing());
    }
    @Test public void lookupFailureCancellationAndAcceptanceUseTheActualNativeViews()throws Exception{
        activity=instrument.startActivitySync(new Intent().setClassName(instrument.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);
        Field binder=activity.getClass().getDeclaredField("managerBinder");binder.setAccessible(true);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(binder.get(activity)==null){assertTrue(System.nanoTime()<deadline);Thread.sleep(20);}
        String before=RhShared.canonical(RhShared.read(activity));action("device.add.open",RhActions.obj());type("bad/path");instrument.runOnMainSync(()->control("添加").performClick());assertEquals("add",state().getString("page"));assertFalse(state().getJSONObject("add").getString("error").isEmpty());capture("add-invalid");
        type("198.51.100.1");instrument.runOnMainSync(()->{control("添加").performClick();try{assertEquals("probing",home.state().getJSONObject("add").getString("phase"));}catch(Exception e){throw new RuntimeException(e);}assertFalse(control("设备地址").isEnabled());assertFalse(RhActions.dispatch("device.add.submit",RhActions.obj()).optBoolean("ok"));control("取消").performClick();});complete();assertEquals("devices",state().getString("page"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
        action("device.add.open",RhActions.obj());type("127.0.0.1");action("device.add.submit",RhActions.obj());complete();assertEquals("add",state().getString("page"));assertEquals("127.0.0.1",state().getJSONObject("add").getString("address"));assertFalse(state().getJSONObject("add").getString("error").isEmpty());
        systemBackToDevices();action("device.add.open",RhActions.obj());
        String success=InstrumentationRegistry.getArguments().getString("addSuccessAddress");assertNotNull("Pass a real isolated test host address",success);type(success);action("device.add.submit",RhActions.obj());complete();JSONObject s=state();assertEquals(s.getJSONObject("add").optString("error"),"devices",s.getString("page"));assertFalse(s.getString("selected").isEmpty());assertFalse(RhActions.hasStream());capture("add-success");
        JSONArray devices=RhShared.read(activity).getJSONArray("devices");boolean selectedSaved=false;for(int i=0;i<devices.length();i++)if(devices.getJSONObject(i).getString("uuid").equals(s.getString("selected")))selectedSaved=true;assertTrue(selectedSaved);action("app.settings.tab",RhActions.obj("tab","updates"));systemBackToDevices();
    }
}
