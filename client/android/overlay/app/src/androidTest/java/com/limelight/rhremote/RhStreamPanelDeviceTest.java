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
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Layout and emitted arguments only; real streaming is tested by RhSessionFlowDeviceTest. */
@RunWith(AndroidJUnit4.class)
public class RhStreamPanelDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;FrameLayout root;RhStreamPanel panel;
    final List<String> actions=new ArrayList<>();JSONObject lastArgs;
    View find(View v,String label){if(v.getVisibility()!=View.VISIBLE)return null;if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    View button(String label){View v=find(root,label);assertNotNull(label,v);return v;}
    void click(String label){instrument.runOnMainSync(()->button(label).performClick());instrument.waitForIdleSync();}
    void fixed(String label){instrument.runOnMainSync(()->{View view=button(label);Rect visible=new Rect();assertTrue(label,view.getGlobalVisibleRect(visible));assertEquals(label,view.getWidth(),visible.width());assertEquals(label,view.getHeight(),visible.height());for(ViewParent parent=view.getParent();parent instanceof View;parent=parent.getParent())assertFalse(parent instanceof ScrollView);});}
    void capture(String name)throws Exception{if(!InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true"))return;for(int frame=0;frame<2;frame++){CountDownLatch done=new CountDownLatch(1);instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();decor.getViewTreeObserver().registerFrameCommitCallback(done::countDown);decor.invalidate();});assertTrue(done.await(3,TimeUnit.SECONDS));}Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);File folder=activity.getExternalFilesDir("ui-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
    @Test public void nativeGroupsRetainTheirHandleAndDisconnectAndRenderLongListsAndErrors()throws Exception{
        activity=instrument.startActivitySync(new Intent().setClassName(instrument.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);RhHome home=(RhHome)field.get(activity);assertFalse(RhActions.hasStream());
        JSONArray monitors=new JSONArray();for(int i=0;i<12;i++)monitors.put(RhActions.obj("id","display-"+i,"name","显示器 "+i+" · 长名称布局检查","width",1920,"height",1080,"available",i!=1,"virtual",i==11));JSONObject display=RhActions.obj("selected",monitors.getJSONObject(0),"displays",monitors);
        try{
            instrument.runOnMainSync(()->{root=new FrameLayout(activity);root.setBackgroundColor(Color.rgb(40,48,65));activity.setContentView(root);panel=new RhStreamPanel(activity,root,(id,args)->{actions.add(id);lastArgs=args;if(id.equals("stream.controls.toggle"))panel.setExpanded(!panel.isExpanded());if(id.equals("stream.controls.tab"))panel.setTab(args.optString("tab"));});panel.setDeviceName("winserver · 会话布局检查");panel.update(false,false,false,100,display,false,"");panel.updateControls(true,false,1,false,"","","");});instrument.waitForIdleSync();
            click("控制");fixed("收起");fixed("断开");JSONObject[] handle={null};instrument.runOnMainSync(()->handle[0]=panel.layoutState().optJSONObject("handle"));capture("panel-fixture-input");
            for(String label:new String[]{"屏幕","声音","操作"}){click(label);fixed("收起");fixed("断开");assertEquals("stream.controls.tab",actions.get(actions.size()-1));}
            click("屏幕");instrument.runOnMainSync(()->{panel.updateControls(true,false,1,false,"displays","","屏幕切换失败。".repeat(50));assertFalse(button("显示器 1 · 长名称布局检查 · 1920×1080  暂不可用").isEnabled());});instrument.waitForIdleSync();fixed("详情");fixed("断开");capture("panel-fixture-displays-error");click("详情");assertEquals("stream.menu.open",actions.get(actions.size()-1));assertEquals("feedback",lastArgs.getString("menu"));
            instrument.runOnMainSync(()->panel.updateControls(true,false,1,true,"displays","正在读取屏幕…",""));instrument.waitForIdleSync();assertFalse(button("自动选屏").isEnabled());fixed("断开");click("声音");instrument.runOnMainSync(()->panel.updateControls(true,false,1,false,"displays","屏幕列表已刷新",""));instrument.waitForIdleSync();capture("panel-fixture-audio");click("收起");instrument.runOnMainSync(()->assertEquals(handle[0].toString(),panel.layoutState().optJSONObject("handle").toString()));fixed("控制");
            File folder=activity.getExternalFilesDir("ui-evidence");RhShared.write(new File(folder,"panel-fixture-flow.json"),RhActions.obj("sdk",android.os.Build.VERSION.SDK_INT,"width",root.getWidth(),"height",root.getHeight(),"fontScale",activity.getResources().getConfiguration().fontScale,"fixedHandle",true,"fixedDisconnect",true,"unavailableScreenDisabled",true,"pendingCommandDisabled",true,"streamStarted",false,"scope","native layout and emitted arguments"));
        }finally{instrument.runOnMainSync(()->{if(panel!=null)panel.dispose();home.rebind();});}
    }
}
