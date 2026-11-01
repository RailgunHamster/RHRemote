package com.limelight.rhremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
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

/** Real production Views; intentionally retained callbacks, never a host input sink. */
@RunWith(AndroidJUnit4.class)
public final class RhSessionViewOriginDeviceTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final JSONArray cases=new JSONArray(),issues=new JSONArray();
    private final List<String> calls=new ArrayList<>();
    private FrameLayout root;private File folder;private JSONArray steps;
    private Context fixture(Context original,boolean dark,float scale){
        Configuration configuration=new Configuration(original.getResources().getConfiguration());configuration.densityDpi=160;configuration.fontScale=scale;
        return new ContextThemeWrapper(original.createConfigurationContext(configuration),R.style.RhTheme){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                SharedPreferences saved=super.getSharedPreferences(name,mode);
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(method.getName().equals("edit"))throw new AssertionError("A view fixture must not persist preferences");
                    if(name.equals("rhremote.global")&&method.getName().equals("getBoolean")&&args[0].equals("dark"))return dark;
                    try{return method.invoke(saved,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
            }
        };
    }
    private void layout(int width,int height){for(int i=0;i<4;i++){root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    private View named(View view,String name){
        if(view instanceof TextView&&name.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=named(((ViewGroup)view).getChildAt(i),name);if(result!=null)return result;}return null;
    }
    private void send(String id,JSONObject arguments){calls.add(id+":"+arguments.toString());}
    private void attempt(String test,String step,Runnable callback,int expected){
        calls.clear();callback.run();steps.put(RhActions.obj("step",step,"expectedCallbacks",expected,"actualCallbacks",calls.size(),"actions",new JSONArray(calls)));
        if(calls.size()!=expected)issues.put(RhActions.obj("case",test,"step",step,"expected",expected,"actual",calls.size()));
    }
    private void capture(String name)throws Exception{
        File file=new File(folder,name+".png");assertFalse(file.exists());Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        try{root.draw(new Canvas(bitmap));try(FileOutputStream output=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}}finally{bitmap.recycle();}
    }
    private void sample(Context original,int width,int height,boolean dark,float scale)throws Exception{
        String name=(dark?"dark":"light")+"-"+width+"x"+height+"-font"+Math.round(scale*100);steps=new JSONArray();Context context=fixture(original,dark,scale);root=new FrameLayout(context);root.setBackgroundColor(dark?0xff192030:0xffe4eaf4);
        RhFloatingKeyboard keyboard=new RhFloatingKeyboard(context,root,this::send);RhStreamPanel panel=new RhStreamPanel(context,root,this::send);
        try{
            keyboard.setEnabled(true);keyboard.update(true,false);panel.update(false,false,false,100,RhActions.obj(),false,"");panel.updateControls(true,false,1,false,"","","");layout(width,height);
            Button backspace=root.findViewWithTag("floating-key:backspace"),tab=root.findViewWithTag("floating-key:tab");assertEquals(View.VISIBLE,backspace.getVisibility());
            attempt(name,"current-floating-key",backspace::performClick,1);
            layout(320,240);assertEquals(View.GONE,backspace.getVisibility());attempt(name,"key-hidden-by-resize",backspace::performClick,0);
            layout(width,height);assertEquals(View.VISIBLE,backspace.getVisibility());attempt(name,"key-restored-after-resize",backspace::performClick,1);
            View layer=root.findViewWithTag("floating-keyboard");root.removeView(layer);attempt(name,"detached-key-layer",tab::performClick,0);root.addView(layer,new FrameLayout.LayoutParams(-1,-1));layout(width,height);attempt(name,"reattached-key-layer",tab::performClick,1);
            root.setVisibility(View.INVISIBLE);attempt(name,"hidden-owner-key",tab::performClick,0);root.setVisibility(View.VISIBLE);
            keyboard.update(false,false);attempt(name,"busy-key",tab::performClick,0);keyboard.update(true,true);attempt(name,"obscured-key",tab::performClick,0);
            panel.setExpanded(true);panel.setTab("input");layout(width,height);
            View mode=named(root,"直接点击"),stats=named(root,"统计：关");Switch scrolling=root.findViewWithTag("stream.scroll.set");assertNotNull(mode);assertNotNull(stats);
            attempt(name,"current-mode",mode::performClick,1);attempt(name,"current-scroll",scrolling::performClick,1);panel.updateScrollMode(false);
            panel.setTab("audio");layout(width,height);attempt(name,"hidden-input-mode",mode::performClick,0);attempt(name,"hidden-input-stats",stats::performClick,0);attempt(name,"hidden-input-scroll",scrolling::performClick,0);
            View audio=named(root,"声音：关");assertNotNull(audio);SeekBar volume=root.findViewWithTag("stream.audio.volume");assertNotNull(volume);
            attempt(name,"current-audio",audio::performClick,1);attempt(name,"current-volume",()->volume.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT)),1);
            panel.setExpanded(false);layout(width,height);attempt(name,"collapsed-audio",audio::performClick,0);attempt(name,"collapsed-volume",()->volume.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT)),0);
            Button handle=(Button)named(root,"控制");assertNotNull(handle);attempt(name,"collapsed-live-handle",handle::performClick,1);
            panel.setExpanded(true);panel.setTab("input");panel.updateControls(true,false,1,false,"","","");layout(width,height);attempt(name,"restored-mode",mode::performClick,1);
            root.setVisibility(View.INVISIBLE);attempt(name,"hidden-owner-mode",mode::performClick,0);root.setVisibility(View.VISIBLE);
            View ownedPanel=mode;while(ownedPanel.getParent()!=root){assertTrue(ownedPanel.getParent() instanceof View);ownedPanel=(View)ownedPanel.getParent();}
            ViewGroup.LayoutParams panelParams=ownedPanel.getLayoutParams();root.removeView(ownedPanel);attempt(name,"detached-panel-mode",mode::performClick,0);root.addView(ownedPanel,panelParams);layout(width,height);attempt(name,"reattached-panel-mode",mode::performClick,1);
            panel.updateControls(true,false,1,true,"input","正在发送快捷键…","");layout(width,height);attempt(name,"disabled-mode",mode::performClick,0);attempt(name,"disabled-scroll",scrolling::performClick,0);
            panel.updateControls(true,false,1,false,"","","");layout(width,height);
            if(scale==1&&width==840)capture(name+"-current");
            panel.dispose();attempt(name,"disposed-mode",mode::performClick,0);keyboard.dispose();attempt(name,"disposed-key",tab::performClick,0);
            cases.put(RhActions.obj("name",name,"width",width,"height",height,"dark",dark,"fontScale",scale,"steps",steps));
        }finally{panel.dispose();keyboard.dispose();root.removeAllViews();}
    }
    @Test public void callbacksRemainWithTheCurrentVisibleSessionControls()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("sessionViewOriginRun","");org.junit.Assume.assumeFalse("Explicit owned run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,70}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        try{
            for(boolean dark:new boolean[]{false,true})for(float scale:new float[]{1,1.3f,2})for(int[] size:new int[][]{{840,560},{360,800},{640,360}}){Throwable[] error={null};instrument.runOnMainSync(()->{try{sample(context,size[0],size[1],dark,scale);}catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new AssertionError("Native session widget fixture failed",error[0]);}
        }finally{RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",issues.length()==0&&cases.length()==18,"actualNativeViews",true,"actualSessionController",false,"attachedWindow",false,"cases",cases,"issues",issues,"hostInputSent",false,"streamStarted",false,"preferencesWritten",false,"systemInputInjected",false));}
        assertEquals("Retained hidden widget callbacks reached the action sink: "+issues,0,issues.length());
    }
}
