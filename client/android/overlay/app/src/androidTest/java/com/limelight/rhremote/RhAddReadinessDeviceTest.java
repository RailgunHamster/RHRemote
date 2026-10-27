package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Delays only this Activity's binder reference. Never stops the service or injects OS input. */
@RunWith(AndroidJUnit4.class)
public class RhAddReadinessDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;File folder;Field binder;Object originalBinder;
    JSONObject state(){JSONObject[] result={null};instrument.runOnMainSync(()->{try{result[0]=home.state();}catch(Exception error){throw new RuntimeException(error);}});return result[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    void waitFor(java.util.function.BooleanSupplier condition,int seconds,String label)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!condition.getAsBoolean()){assertTrue(label+": "+snapshot(),System.nanoTime()<end);Thread.sleep(20);}instrument.waitForIdleSync();}
    JSONObject snapshot(){JSONObject value=state();return RhActions.obj("page",value.optString("page"),"add",value.optJSONObject("add"),"selected",value.optString("selected"),"status",value.optString("status"));}
    void setBinder(Object value){instrument.runOnMainSync(()->{try{binder.set(activity,value);}catch(Exception error){throw new RuntimeException(error);}});}
    View find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof EditText&&label.contentEquals(view.getContentDescription())||view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View child=find(((ViewGroup)view).getChildAt(i),label);if(child!=null)return child;}return null;}
    void click(String label){instrument.runOnMainSync(()->{View control=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,control);assertTrue(control.isEnabled());control.performClick();});instrument.waitForIdleSync();}
    void visibleControl(String label){instrument.runOnMainSync(()->{View control=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,control);Rect bounds=new Rect();assertTrue(label,control.getGlobalVisibleRect(bounds));assertEquals(label,control.getWidth(),bounds.width());assertEquals(label,control.getHeight(),bounds.height());});}
    void capture(String name)throws Exception{Bitmap[] image={null};instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image[0]));});try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image[0].compress(Bitmap.CompressFormat.PNG,100,out);}finally{image[0].recycle();}}
    @Test public void aSingleAddWaitsForTheBackendAndCancelRetiresThePendingRequest()throws Exception{
        String address=InstrumentationRegistry.getArguments().getString("readinessHost","");org.junit.Assume.assumeFalse("Explicit existing real host is required",address.isEmpty());Context context=instrument.getTargetContext();assertFalse(RhActions.hasStream());String run=InstrumentationRegistry.getArguments().getString("readinessRun","");assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));folder=new File(context.getExternalFilesDir("ui-evidence"),"add-readiness-"+run);assertFalse(folder.exists());assertTrue(folder.mkdirs());JSONObject original=RhShared.read(context);String before=RhShared.canonical(original);JSONObject report=RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"activityBinderDelayed",true,"serviceStopped",false,"systemInputInjected",false,"osScreenshots",false);
        try{
            String theme=InstrumentationRegistry.getArguments().getString("readinessTheme","light");assertTrue(theme.equals("light")||theme.equals("dark"));RhShared.theme(context,theme.equals("dark"));report.put("theme",theme);
            activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);binder=activity.getClass().getDeclaredField("managerBinder");binder.setAccessible(true);report.put("binderVolatile",java.lang.reflect.Modifier.isVolatile(binder.getModifiers()));assertTrue("Backend publication must be visible across threads",java.lang.reflect.Modifier.isVolatile(binder.getModifiers()));waitFor(()->{try{return binder.get(activity)!=null;}catch(Exception error){throw new RuntimeException(error);}},8,"Initial backend binding");originalBinder=binder.get(activity);
            String uuid=InstrumentationRegistry.getArguments().getString("readinessUuid","C3448560-C539-6209-F595-88C1E8D2769B");boolean known=false;JSONArray devices=original.getJSONArray("devices");for(int i=0;i<devices.length();i++)if(uuid.equals(devices.getJSONObject(i).optString("uuid")))known=true;assertTrue("Use an already stored test host",known);
            action("device.add.open",RhActions.obj());action("device.add.input",RhActions.obj("address",address));setBinder(null);click("添加");waitFor(()->state().optJSONObject("add").optString("phase").equals("preparing")||!state().optJSONObject("add").optString("error").isEmpty(),3,"Wait for preparation or explicit failure");
            JSONObject preparing=snapshot();report.put("preparing",preparing);RhShared.write(new File(folder,"readiness-flow.json"),report);capture("readiness-preparing");assertEquals("A cold backend must not require another add click","preparing",preparing.getJSONObject("add").getString("phase"));assertEquals(address,preparing.getJSONObject("add").getString("address"));assertEquals("",preparing.getJSONObject("add").getString("error"));
            instrument.runOnMainSync(()->{assertFalse(find(activity.findViewById(android.R.id.content),"设备地址").isEnabled());assertFalse(RhActions.dispatch("device.add.submit",RhActions.obj()).optBoolean("ok"));});visibleControl("取消");visibleControl("正在准备…");setBinder(originalBinder);waitFor(()->state().optString("page").equals("devices"),15,"Original request completes after backend ready");assertEquals(uuid,state().optString("selected"));report.put("success",snapshot()).put("singleSubmitCompleted",true);capture("readiness-success");String saved=RhShared.canonical(RhShared.read(context));
            action("device.add.open",RhActions.obj());action("device.add.input",RhActions.obj("address",address));setBinder(null);click("添加");waitFor(()->state().optJSONObject("add").optString("phase").equals("preparing"),3,"Second preparation");Field operationField=RhHome.class.getDeclaredField("addOperation");operationField.setAccessible(true);RhDeviceAdd pending=(RhDeviceAdd)operationField.get(home);click("取消");setBinder(originalBinder);waitFor(pending::isFinished,3,"Cancelled preparation finishes");assertEquals("devices",state().optString("page"));assertEquals(saved,RhShared.canonical(RhShared.read(context)));report.put("cancelledBeforeReady",true).put("lateReadyDidNotSave",true);capture("readiness-cancelled");
            action("device.add.open",RhActions.obj());action("device.add.input",RhActions.obj("address",address));setBinder(null);click("添加");waitFor(()->state().optJSONObject("add").optString("phase").equals("editing")&&!state().optJSONObject("add").optString("error").isEmpty(),8,"Bounded preparation timeout");JSONObject timeout=snapshot();report.put("timeout",timeout);assertEquals(address,timeout.getJSONObject("add").getString("address"));assertTrue(timeout.getJSONObject("add").getString("error").contains("尚未准备好"));assertEquals(saved,RhShared.canonical(RhShared.read(context)));visibleControl("取消");visibleControl("添加");capture("readiness-timeout");setBinder(originalBinder);click("添加");waitFor(()->state().optString("page").equals("devices"),15,"Explicit retry after preparation timeout");assertEquals(uuid,state().optString("selected"));report.put("explicitRetrySucceeded",true);capture("readiness-retried");
        }catch(Exception|AssertionError failure){report.put("failure",failure.toString()).put("failureState",home==null?JSONObject.NULL:snapshot());throw failure;}finally{
            try{if(binder!=null&&originalBinder!=null)setBinder(originalBinder);if(home!=null&&state().optString("page").equals("add"))action("device.add.cancel",RhActions.obj());}
            finally{RhShared.apply(context,original);assertEquals(before,RhShared.canonical(RhShared.read(context)));report.put("preferencesRestored",true);RhShared.write(new File(folder,"readiness-flow.json"),report);if(activity!=null)instrument.runOnMainSync(activity::finish);}
        }
    }
}
