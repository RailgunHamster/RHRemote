package com.limelight.rhremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.R;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Production layout and widgets drawn on their own Canvas; no Activity, host or OS input. */
@RunWith(AndroidJUnit4.class)
public final class RhSessionWarningDeviceTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final JSONArray cases=new JSONArray();
    private File folder;
    private Context fixture(Context original,boolean dark,float scale){
        Configuration config=new Configuration(original.getResources().getConfiguration());config.densityDpi=160;config.fontScale=scale;
        return new ContextThemeWrapper(original.createConfigurationContext(config),R.style.RhTheme){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                SharedPreferences saved=super.getSharedPreferences(name,mode);
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(method.getName().equals("edit"))throw new AssertionError("Fixture must not persist preferences");
                    if(name.equals("rhremote.global")&&method.getName().equals("getBoolean")&&args[0].equals("dark"))return dark;
                    try{return method.invoke(saved,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
            }
        };
    }
    private void layout(FrameLayout root,int width,int height){for(int i=0;i<4;i++){root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    private void capture(FrameLayout root,String name)throws Exception{Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);try{root.draw(new Canvas(bitmap));try(FileOutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}}finally{bitmap.recycle();}}
    private void sample(Context original,int width,int height,boolean dark,float scale,Insets safe)throws Exception{
        String name=(dark?"dark":"light")+"-"+width+"x"+height+"-font"+Math.round(scale*100)+(safe.top==0?"":"-insets");Context context=fixture(original,dark,scale);FrameLayout root=new FrameLayout(context);LayoutInflater.from(context).inflate(R.layout.activity_game,root,true);root.setBackgroundColor(dark?0xff192030:0xffe4eaf4);
        RhSessionWarning warning=root.findViewById(R.id.notificationOverlay);assertNotNull("Actual Game XML must use the new warning",warning);
        RhStreamPanel panel=new RhStreamPanel(context,root,(action,args)->{throw new AssertionError("No action expected in layout fixture");});
        try{
            panel.update(false,false,false,100,RhActions.obj(),false,"");panel.updateControls(true,false,1,false,"","","");warning.bind(panel.controlHandle());warning.setChanged(()->{panel.updateNetworkWarning(warning.message(),warning.summary());panel.updateFeedback("","","");});
            WindowInsetsCompat insets=new WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),safe).build();ViewCompat.dispatchApplyWindowInsets(panel.controlHandle(),insets);ViewCompat.dispatchApplyWindowInsets(warning,insets);
            warning.setText(R.string.rh_connection_slow);warning.setVisibility(View.VISIBLE);layout(root,width,height);
            Rect area=new Rect(),handle=new Rect();warning.getHitRect(area);panel.controlHandle().getHitRect(handle);
            assertEquals(name,View.VISIBLE,warning.getVisibility());assertTrue(name+" banner overlaps control handle",area.top>=handle.bottom+8);assertTrue(name+" left inset",area.left>=safe.left+16);assertTrue(name+" right inset",area.right<=width-safe.right-16);assertTrue(name+" bottom inset",area.bottom<=height-safe.bottom-16);
            assertFalse(warning.isClickable());assertFalse(warning.isFocusable());assertTrue(warning.getLineCount()>0);assertEquals(warning.getText().length(),warning.getLayout().getLineEnd(warning.getLineCount()-1));assertTrue(name+" clipped text height",warning.getLayout().getHeight()+warning.getCompoundPaddingTop()+warning.getCompoundPaddingBottom()<=warning.getHeight());assertTrue("Status marker",panel.controlHandle().getContentDescription().toString().contains(warning.message()));
            for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}){android.view.MotionEvent event=android.view.MotionEvent.obtain(0,0,action,warning.getWidth()/2f,warning.getHeight()/2f,0);try{assertFalse("Warning must pass touch to the session",warning.dispatchTouchEvent(event));}finally{event.recycle();}}
            capture(root,name+"-banner");panel.setExpanded(true);warning.cover("controls");layout(root,width,height);assertEquals(View.GONE,warning.getVisibility());capture(root,name+"-panel");
            View details=root.findViewWithTag("stream.menu.open");assertNotNull(details);Rect detailArea=new Rect();details.getDrawingRect(detailArea);root.offsetDescendantRectToMyCoords(details,detailArea);assertTrue(name+" details clipped: "+detailArea,detailArea.left>=safe.left&&detailArea.right<=width-safe.right&&detailArea.top>=safe.top&&detailArea.bottom<=height-safe.bottom);
            panel.setExpanded(false);warning.cover("");layout(root,width,height);assertEquals(View.VISIBLE,warning.getVisibility());
            warning.setVisibility(View.GONE);layout(root,width,height);assertFalse(warning.active());assertEquals("控制",panel.controlHandle().getContentDescription().toString());
            warning.setVisibility(View.VISIBLE);layout(root,width,100);assertEquals("viewport-small",warning.state().getString("suppressed"));assertEquals(View.GONE,warning.getVisibility());layout(root,width,height);assertEquals(View.VISIBLE,warning.getVisibility());
            cases.put(RhActions.obj("name",name,"width",width,"height",height,"dark",dark,"fontScale",scale,"safeTop",safe.top,"banner",RhActions.obj("x",area.left,"y",area.top,"width",area.width(),"height",area.height()),"passed",true));
        }finally{warning.dispose();panel.dispose();root.removeAllViews();}
    }
    @Test public void warningsFitAndYieldToSessionControls()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("sessionWarningRun","");org.junit.Assume.assumeFalse("Explicit owned run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,70}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        try{for(boolean dark:new boolean[]{false,true})for(float scale:new float[]{1,1.3f,2})for(int[] size:new int[][]{{840,560},{360,800},{640,360}})for(Insets safe:new Insets[]{Insets.NONE,Insets.of(24,36,32,24)}){Throwable[] error={null};instrument.runOnMainSync(()->{try{sample(context,size[0],size[1],dark,scale,safe);}catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new AssertionError("Native connection warning fixture failed",error[0]);}}
        finally{RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",cases.length()==36,"cases",cases,"actualGameXml",true,"actualSessionController",false,"attachedWindow",false,"streamStarted",false,"hostInputSent",false,"preferencesWritten",false,"systemInputInjected",false));}
        assertEquals(36,cases.length());
    }
}
