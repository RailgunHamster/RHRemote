package com.limelight.rhremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real native controls and real HTTP cancellation/timeout against a test-owned loopback listener.
 * This does not replace real host pairing, decoded video, or WAN recovery acceptance. */
@RunWith(AndroidJUnit4.class)
public final class RhConnectionFlowDeviceTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    Activity activity;RhHome home;
    JSONObject state()throws Exception{JSONObject[] result={null};instrument.runOnMainSync(()->{try{result[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return result[0];}
    JSONObject action(String id)throws Exception{JSONObject[] result={null};instrument.runOnMainSync(()->result[0]=RhActions.dispatch(id,RhActions.obj()));instrument.waitForIdleSync();return result[0];}
    View find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(label.equals(view.getContentDescription())||view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    void click(String label){instrument.runOnMainSync(()->{View view=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,view);assertTrue(label,view.isEnabled());view.performClick();});instrument.waitForIdleSync();}
    void waitPhase(String phase)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!state().getJSONObject("connection").getString("phase").equals(phase)){assertTrue("Expected "+phase+", got "+state().getJSONObject("connection"),System.nanoTime()<end);Thread.sleep(25);}instrument.waitForIdleSync();}
    void capture(String name)throws Exception{if(!InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true"))return;instrument.waitForIdleSync();android.graphics.Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);java.io.File folder=instrument.getTargetContext().getExternalFilesDir("ui-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}}
    void footerVisible(String label){instrument.runOnMainSync(()->{View view=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,view);android.graphics.Rect visible=new android.graphics.Rect();assertTrue(label,view.getGlobalVisibleRect(visible));assertEquals(label,view.getHeight(),visible.height());assertEquals(label,view.getWidth(),visible.width());assertTrue(label,view.getHeight()>=Math.round(48*activity.getResources().getDisplayMetrics().density));});}
    @Test public void nativeConnectionFailureRetryCancelAndReturnKeepTheOriginalContext()throws Exception{
        List<Socket> held=Collections.synchronizedList(new ArrayList<>());
        try(ServerSocket listener=new ServerSocket(47989,4,InetAddress.getByName("127.0.0.1"))){
            Thread responder=new Thread(()->{try{while(!listener.isClosed())held.add(listener.accept());}catch(java.io.IOException stopped){}});responder.start();
            activity=instrument.startActivitySync(new Intent().setClassName(instrument.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);
            JSONObject original=RhShared.read(activity);assertFalse(RhActions.hasStream());
            try{
                ComputerDetails host=new ComputerDetails();host.uuid="ui-connection-loopback";host.name="连接流程验收";host.manualAddress=new ComputerDetails.AddressTuple("127.0.0.1",47989);host.activeAddress=host.manualAddress;host.state=ComputerDetails.State.ONLINE;
                RhProfile profile=RhProfile.load(activity,host.uuid);profile.routeOrder=new ArrayList<>(Arrays.asList("lan"));RhShared.saveProfile(activity,host,profile,"");String baseline=RhShared.canonical(RhShared.read(activity));
                instrument.runOnMainSync(()->{home.update(host);assertTrue(RhActions.dispatch("devices.filter",RhActions.obj("query",host.name)).optBoolean("ok"));assertTrue(RhActions.dispatch("device.select",RhActions.obj("uuid",host.uuid)).optBoolean("ok"));});instrument.waitForIdleSync();
                click("配对");assertEquals("connection",state().getString("page"));assertFalse(action("app.settings.open").optBoolean("ok"));
                waitPhase("failed");assertFalse(RhActions.hasStream());assertEquals(host.uuid,state().getString("selected"));assertFalse(state().getJSONObject("connection").getString("error").isEmpty());
                footerVisible("重试连接");footerVisible("返回设备");capture("connection-failed");assertEquals(baseline,RhShared.canonical(RhShared.read(activity)));
                click("重试连接");assertEquals(2,state().getJSONObject("connection").getInt("attempt"));footerVisible("取消连接");click("取消连接");waitPhase("cancelled");
                assertEquals("devices",state().getString("page"));assertEquals(host.uuid,state().getString("selected"));assertEquals(host.name,state().getString("deviceQuery"));assertEquals(baseline,RhShared.canonical(RhShared.read(activity)));
                // A second real failure exercises the explicit return button as well as cancellation.
                click("配对");waitPhase("failed");click("返回设备");assertEquals("devices",state().getString("page"));assertEquals(host.uuid,state().getString("selected"));assertEquals(host.name,state().getString("deviceQuery"));assertEquals("",state().getString("status"));
            }finally{
                if(RhActions.hasStream())action("stream.disconnect");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(RhActions.hasStream()&&System.nanoTime()<end)Thread.sleep(25);assertFalse("Connection fixture retained a transport",RhActions.hasStream());
                RhShared.write(new java.io.File(activity.getFilesDir(),"rhremote-shared.json"),original);assertEquals(RhShared.canonical(original),RhShared.canonical(RhShared.read(activity)));instrument.runOnMainSync(()->activity.finish());
            }
        }finally{for(Socket socket:held)socket.close();}
    }
}
