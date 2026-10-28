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
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;FrameLayout root;RhStreamPanel panel;File evidence;
    final List<String> actions=new ArrayList<>();JSONObject lastArgs;
    View find(View v,String label){if(v.getVisibility()!=View.VISIBLE)return null;if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    View button(String label){View v=find(root,label);assertNotNull(label,v);return v;}
    void click(String label){instrument.runOnMainSync(()->button(label).performClick());instrument.waitForIdleSync();}
    void fixed(String label){instrument.runOnMainSync(()->{View view=button(label);Rect visible=new Rect();assertTrue(label,view.getGlobalVisibleRect(visible));assertEquals(label,view.getWidth(),visible.width());assertEquals(label,view.getHeight(),visible.height());for(ViewParent parent=view.getParent();parent instanceof View;parent=parent.getParent())assertFalse(parent instanceof ScrollView);});}
    void capture(String name)throws Exception{if(!InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true"))return;Bitmap[] image={null};instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image[0]));});File file=new File(evidence,name+".png");assertFalse("Use a fresh evidence name",file.exists());try(OutputStream out=new FileOutputStream(file)){assertTrue(image[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{image[0].recycle();}}
    @Test public void nativeGroupsRetainTheirHandleAndDisconnectAndRenderLongListsAndErrors()throws Exception{
        assertTrue("Dedicated emulator only",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));String run=InstrumentationRegistry.getArguments().getString("panelRun","");assertTrue("A fresh panelRun is required",run.matches("[A-Za-z0-9_-]{1,80}"));evidence=new File(instrument.getTargetContext().getExternalFilesDir("ui-evidence"),"panel-"+run);assertFalse(evidence.exists());assertTrue(evidence.mkdirs());
        activity=instrument.startActivitySync(new Intent().setClassName(instrument.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);RhHome home=(RhHome)field.get(activity);assertFalse(RhActions.hasStream());
        JSONArray monitors=new JSONArray();for(int i=0;i<12;i++)monitors.put(RhActions.obj("id","display-"+i,"name","显示器 "+i+" · 长名称布局检查","width",1920,"height",1080,"available",i!=1,"virtual",i==11));JSONObject display=RhActions.obj("selected",monitors.getJSONObject(0),"displays",monitors);
        Context context=instrument.getTargetContext();boolean originalDark=RhShared.dark(context);
        try{for(boolean dark:new boolean[]{false,true}){
            RhShared.theme(context,dark);actions.clear();String theme=dark?"dark":"light";
            instrument.runOnMainSync(()->{root=new FrameLayout(activity);root.setBackgroundColor(Color.rgb(40,48,65));activity.setContentView(root);panel=new RhStreamPanel(activity,root,(id,args)->{actions.add(id);lastArgs=args;if(id.equals("stream.controls.toggle"))panel.setExpanded(!panel.isExpanded());if(id.equals("stream.controls.tab"))panel.setTab(args.optString("tab"));});panel.setDeviceName("winserver · 会话布局检查");panel.update(false,false,false,100,display,false,"");panel.updateControls(true,false,1,false,"","","");});instrument.waitForIdleSync();
            click("控制");fixed("收起");fixed("断开");JSONObject[] handle={null};instrument.runOnMainSync(()->handle[0]=panel.layoutState().optJSONObject("handle"));capture("panel-fixture-input-"+theme);
            for(String label:new String[]{"屏幕","声音","操作"}){click(label);fixed("收起");fixed("断开");assertEquals("stream.controls.tab",actions.get(actions.size()-1));}
            click("屏幕");instrument.runOnMainSync(()->{panel.update(false,false,false,100,display,true,"","stream.display.select");panel.updateControls(true,false,1,true,"displays","正在切换屏幕…","");try{Field status=RhStreamPanel.class.getDeclaredField("displayStatus");status.setAccessible(true);assertEquals("正在切换屏幕…",((TextView)status.get(panel)).getText().toString());}catch(ReflectiveOperationException invalid){throw new AssertionError(invalid);}assertFalse(button("刷新屏幕").isEnabled());});instrument.waitForIdleSync();fixed("断开");capture("panel-fixture-switching-"+theme);
            instrument.runOnMainSync(()->{panel.update(false,false,false,100,display,false,"屏幕切换失败");panel.updateControls(true,false,1,false,"displays","","屏幕切换失败。".repeat(50));assertFalse(button("显示器 1 · 长名称布局检查 · 1920×1080  暂不可用").isEnabled());assertTrue(button("显示器 0 · 长名称布局检查 · 1920×1080  ✓").isEnabled());});instrument.waitForIdleSync();fixed("详情");fixed("断开");capture("panel-fixture-displays-error-"+theme);click("详情");assertEquals("stream.menu.open",actions.get(actions.size()-1));assertEquals("feedback",lastArgs.getString("menu"));
            instrument.runOnMainSync(()->panel.updateControls(true,false,1,true,"displays","正在读取屏幕…",""));instrument.waitForIdleSync();assertFalse(button("自动选屏").isEnabled());fixed("断开");click("声音");instrument.runOnMainSync(()->panel.updateControls(true,false,1,false,"displays","屏幕列表已刷新",""));instrument.waitForIdleSync();capture("panel-fixture-audio-"+theme);click("收起");instrument.runOnMainSync(()->assertEquals(handle[0].toString(),panel.layoutState().optJSONObject("handle").toString()));fixed("控制");
            RhShared.write(new File(evidence,"panel-fixture-flow-"+theme+".json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"sdk",android.os.Build.VERSION.SDK_INT,"width",root.getWidth(),"height",root.getHeight(),"fontScale",activity.getResources().getConfiguration().fontScale,"theme",theme,"fixedHandle",true,"fixedDisconnect",true,"unavailableScreenDisabled",true,"pendingCommandDisabled",true,"switchingStatus",true,"previousMonitorRetained",true,"streamStarted",false,"scope","native layout and emitted arguments"));instrument.runOnMainSync(()->panel.dispose());panel=null;
        }}finally{RhShared.theme(context,originalDark);instrument.runOnMainSync(()->{if(panel!=null)panel.dispose();home.rebind();activity.finish();});}
    }
}
