package com.limelight.rhremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Debug;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.R;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real native Views only: no Activity, host, input injection, or persisted configuration. */
@RunWith(AndroidJUnit4.class)
public final class RhPanelLifetimeDeviceTest {
    private Context context(Context base,boolean dark,float scale){
        Configuration configuration=new Configuration(base.getResources().getConfiguration());configuration.densityDpi=160;configuration.fontScale=scale;
        return new ContextThemeWrapper(base.createConfigurationContext(configuration),R.style.RhTheme){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                SharedPreferences prefs=super.getSharedPreferences(name,mode);
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(method.getName().equals("edit"))throw new AssertionError("The panel fixture must not persist preferences");
                    if(name.equals("rhremote.global")&&method.getName().equals("getBoolean")&&args[0].equals("dark"))return dark;
                    try{return method.invoke(prefs,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                });
            }
        };
    }
    private int views(View view){int count=1;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=views(((ViewGroup)view).getChildAt(i));return count;}
    private SeekBar volume(View view){if(view instanceof SeekBar)return (SeekBar)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){SeekBar result=volume(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}return null;}
    private TextView text(View view,String value){if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView result=text(((ViewGroup)view).getChildAt(i),value);if(result!=null)return result;}return null;}
    private void layout(FrameLayout root,int width,int height){for(int i=0;i<3;i++){root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    private void capture(File folder,String name,FrameLayout root)throws Exception{
        File path=new File(folder,name+".png");assertFalse(path.exists());Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        try{root.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(path)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}finally{bitmap.recycle();}
    }
    private JSONObject sample(Context base,File folder,boolean dark,float scale,int width,int height,int trial,boolean expectLazy)throws Exception{
        Context themed=context(base,dark,scale);FrameLayout root=new FrameLayout(themed);root.setBackgroundColor(Color.rgb(15,23,42));
        List<String> calls=new ArrayList<>();RhStreamPanel[] target=new RhStreamPanel[1];
        long start=SystemClock.elapsedRealtimeNanos(),cpu=Debug.threadCpuTimeNanos();
        RhStreamPanel panel=new RhStreamPanel(themed,root,(id,args)->{calls.add(id);if(id.equals("stream.controls.toggle"))target[0].setExpanded(!target[0].isExpanded());});target[0]=panel;
        long constructNs=SystemClock.elapsedRealtimeNanos()-start,constructCpuNs=Debug.threadCpuTimeNanos()-cpu;
        String label=(dark?"dark":"light")+"-"+width+"-font"+(int)scale;
        try{
            int constructedViews=views(root);boolean hasHiddenSlider=volume(root)!=null;
            if(expectLazy){assertEquals("Only the root and control handle belong on the collapsed path",2,constructedViews);assertFalse(hasHiddenSlider);}
            start=SystemClock.elapsedRealtimeNanos();cpu=Debug.threadCpuTimeNanos();
            JSONObject monitor=RhActions.obj("id","fixture-display","name","Fixture Screen","available",true,"width",1920,"height",1080);
            panel.setDeviceName("Fixture Device");panel.update(false,false,true,37,RhActions.obj("displays",new JSONArray().put(monitor),"selected",monitor),false,"");
            panel.updateControls(true,true,1.5f,false,"input","状态已更新","");panel.updateScrollMode(true);panel.updateFloatingKeyboard(true);panel.updateNetworkWarning("网络提示","网络提示");
            long bindNs=SystemClock.elapsedRealtimeNanos()-start,bindCpuNs=Debug.threadCpuTimeNanos()-cpu;
            assertFalse(panel.isExpanded());if(expectLazy)assertEquals(constructedViews,views(root));assertTrue(calls.isEmpty());
            layout(root,width,height);if(trial==0)capture(folder,label+"-collapsed",root);
            start=SystemClock.elapsedRealtimeNanos();cpu=Debug.threadCpuTimeNanos();assertTrue(panel.controlHandle().performClick());
            long openNs=SystemClock.elapsedRealtimeNanos()-start,openCpuNs=Debug.threadCpuTimeNanos()-cpu;
            assertTrue(panel.isExpanded());layout(root,width,height);assertEquals(1,calls.size());assertEquals("stream.controls.toggle",calls.get(0));
            assertNotNull(text(root,"Fixture Device"));assertNotNull(text(root,"统计：开"));
            assertTrue(((Switch)root.findViewWithTag("stream.scroll.set")).isChecked());assertTrue(((Switch)root.findViewWithTag("stream.keyboard.floating.set")).isChecked());
            if(trial==0)capture(folder,label+"-input",root);
            panel.setTab("displays");layout(root,width,height);assertNotNull(text(root,"缩放 150%"));assertNotNull(text(root,"Fixture Screen · 1920×1080"));if(trial==0)capture(folder,label+"-displays",root);
            panel.setTab("audio");layout(root,width,height);SeekBar slider=volume(root);assertNotNull(slider);assertEquals(37,slider.getProgress());assertTrue(slider.isEnabled());assertNotNull(text(root,"声音：开"));if(trial==0)capture(folder,label+"-audio",root);
            int builtViews=views(root);calls.clear();panel.setExpanded(false);panel.setTab("audio");panel.update(false,true,false,21,RhActions.obj("displays",new JSONArray().put(monitor),"selected",monitor),false,"");panel.updateControls(true,false,2,true,"audio","正在处理…","");panel.updateScrollMode(false);panel.updateFloatingKeyboard(false);
            start=SystemClock.elapsedRealtimeNanos();panel.setExpanded(true);long reopenNs=SystemClock.elapsedRealtimeNanos()-start;
            layout(root,width,height);assertSame(slider,volume(root));assertEquals(builtViews,views(root));assertEquals(21,slider.getProgress());assertFalse(slider.isEnabled());assertTrue(calls.isEmpty());
            return RhActions.obj("case",label,"trial",trial,"constructNs",constructNs,"constructCpuNs",constructCpuNs,"hiddenBindNs",bindNs,"hiddenBindCpuNs",bindCpuNs,"firstOpenNs",openNs,"firstOpenCpuNs",openCpuNs,"reopenNs",reopenNs,"constructedViews",constructedViews,"builtViews",builtViews,"hiddenSlider",hasHiddenSlider,"stateRetained",true,"callbacksSuppressedDuringBinding",true);
        }finally{panel.dispose();assertEquals("Disposed panel retained a child",0,root.getChildCount());}
    }
    @Test public void collapsedStartupFirstOpenAndReopenRetainCurrentState()throws Exception{
        android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Context base=instrument.getTargetContext();
        String run=InstrumentationRegistry.getArguments().getString("panelRun","");assertTrue(run.matches("[a-zA-Z0-9_-]{1,64}"));boolean lazy=InstrumentationRegistry.getArguments().getString("expectLazy","false").equals("true");
        File folder=new File(base.getExternalFilesDir("ui-evidence"),"panel-"+run);assertFalse("Use a fresh evidence directory",folder.exists());assertTrue(folder.mkdirs());JSONArray samples=new JSONArray();
        for(boolean dark:new boolean[]{false,true})for(float scale:new float[]{1,2})for(int[] size:new int[][]{{360,800},{1008,672}})for(int trial=0;trial<3;trial++){
            final int iteration=trial;Throwable[] failure=new Throwable[1];instrument.runOnMainSync(()->{try{samples.put(sample(base,folder,dark,scale,size[0],size[1],iteration,lazy));}catch(Throwable error){failure[0]=error;}});
            if(failure[0]!=null)throw new AssertionError("Panel lifetime failed",failure[0]);
            RhShared.write(new File(folder,"panel-lifetime.json"),RhActions.obj("samples",samples,"expectedLazy",lazy,"remoteHostUsed",false,"persistedConfigurationChanged",false,"osScreenshots",false,"systemInputInjected",false));
        }
        assertEquals(24,samples.length());
    }
}
