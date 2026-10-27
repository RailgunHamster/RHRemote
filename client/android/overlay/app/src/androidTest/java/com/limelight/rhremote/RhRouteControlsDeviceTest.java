package com.limelight.rhremote;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.CheckBox;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native touch and draft preservation on the dedicated emulator; never starts a stream or sync. */
@RunWith(AndroidJUnit4.class)
public class RhRouteControlsDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    Activity activity;RhHome home;int taps;
    JSONObject state(){JSONObject[] result=new JSONObject[1];instrument.runOnMainSync(()->{try{result[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return result[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View view,String description){if(view.getVisibility()!=View.VISIBLE)return null;if(view.getContentDescription()!=null&&description.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),description);if(result!=null)return result;}return null;}
    View control(String description){View result=find(activity.findViewById(android.R.id.content),description);assertNotNull(description,result);return result;}
    void enabled(String description,boolean expected){instrument.runOnMainSync(()->assertEquals(description,expected,control(description).isEnabled()));}
    void checked(String description,boolean expected){instrument.runOnMainSync(()->assertEquals(description,expected,((CheckBox)control(description)).isChecked()));}
    void tap(String description){
        instrument.runOnMainSync(()->{View view=control(description);view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);});instrument.waitForIdleSync();
        float[] point=new float[2];instrument.runOnMainSync(()->{View view=control(description);Rect visible=new Rect();assertTrue(description+" is not visible",view.getGlobalVisibleRect(visible));assertEquals(description+" clipped vertically",view.getHeight(),visible.height());point[0]=visible.exactCenterX();point[1]=visible.exactCenterY();});
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,point[0],point[1],0),up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,point[0],point[1],0);
        try{instrument.sendPointerSync(down);instrument.sendPointerSync(up);}finally{down.recycle();up.recycle();}instrument.waitForIdleSync();taps++;
    }
    File directory(){File result=new File(activity.getExternalFilesDir(null),"ui-evidence");assertTrue(result.isDirectory()||result.mkdirs());return result;}
    String prefix(){return InstrumentationRegistry.getArguments().getString("evidencePrefix","routes");}
    void screenshot(String name)throws Exception{Bitmap bitmap=instrument.getUiAutomation().takeScreenshot();assertNotNull(bitmap);try(FileOutputStream out=new FileOutputStream(new File(directory(),prefix()+"-"+name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}}
    @Test public void nativeRouteChoicesReorderPreventEmptyConfigurationAndCancelWithoutSaving()throws Exception{
        assertTrue("Dedicated emulator only",Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));
        activity=instrument.startActivitySync(new Intent().setClassName(instrument.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();
        Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);
        String uuid=state().getJSONArray("devices").getJSONObject(0).getString("uuid"),before=RhShared.canonical(RhShared.read(activity));
        try{
            action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.set",RhActions.obj("key","routeOrder","value",new JSONArray().put("lan").put("relay")));action("settings.tab",RhActions.obj("tab","network"));
            enabled("上移局域网",false);enabled("下移局域网",true);enabled("下移自建中继",false);checked("连接路径：局域网",true);checked("连接路径：公网直连",false);
            tap("下移局域网");assertEquals("relay",state().getJSONObject("draft").getJSONArray("routeOrder").getString(0));enabled("上移自建中继",false);enabled("下移局域网",false);screenshot("reordered");
            tap("连接路径：局域网");assertEquals(1,state().getJSONObject("draft").getJSONArray("routeOrder").length());checked("连接路径：自建中继",true);enabled("连接路径：自建中继",false);enabled("上移自建中继",false);enabled("下移自建中继",false);
            tap("连接路径：自建中继");assertEquals(1,state().getJSONObject("draft").getJSONArray("routeOrder").length());checked("连接路径：自建中继",true);screenshot("last-path");
            tap("连接路径：公网直连");assertEquals(2,state().getJSONObject("draft").getJSONArray("routeOrder").length());enabled("连接路径：自建中继",true);checked("连接路径：公网直连",true);screenshot("added");
            action("settings.cancel",RhActions.obj());assertEquals(before,RhShared.canonical(RhShared.read(activity)));assertEquals("devices",state().getString("page"));
            JSONObject proof=RhActions.obj("phase","cancelled_without_saving","touchCount",taps,"reordered",true,"lastPathProtected",true,"configurationPreserved",true,"uuid",uuid);
            try(FileOutputStream out=new FileOutputStream(new File(directory(),prefix()+"-proof.json"))){out.write(proof.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }finally{if(state().optString("page").equals("settings"))action("settings.cancel",RhActions.obj());assertEquals(before,RhShared.canonical(RhShared.read(activity)));}
    }
}
