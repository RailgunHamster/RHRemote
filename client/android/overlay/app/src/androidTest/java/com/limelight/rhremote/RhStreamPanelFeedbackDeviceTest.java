package com.limelight.rhremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native feedback binding, own View.draw only; no Activity, host operation or OS input. */
@RunWith(AndroidJUnit4.class)
public final class RhStreamPanelFeedbackDeviceTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private static TextView text(View view,String label){
        if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=text(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private static void layout(FrameLayout root){for(int i=0;i<4;i++){root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,640,800);}}
    private static void capture(FrameLayout root,File folder,String name)throws Exception{
        layout(root);Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        File file=new File(folder,name+".png");assertFalse(file.exists());
        try(FileOutputStream out=new FileOutputStream(file)){root.draw(new Canvas(bitmap));assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
    @Test public void latestFeedbackFollowsItsCategoryAndClearedResultsStayCleared()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("feedbackRun","");org.junit.Assume.assumeFalse("Explicit owned run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context base=instrument.getTargetContext();
        File folder=new File(base.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());JSONArray cases=new JSONArray();
        // Reuse the existing no-write fixture context, including its theme/font overrides.
        Method fixture=RhFloatingLayoutDeviceTest.class.getDeclaredMethod("fixtureContext",Context.class,boolean.class,float.class);fixture.setAccessible(true);
        for(boolean dark:new boolean[]{false,true})for(float font:new float[]{1,2}){
            Throwable[] failure={null};instrument.runOnMainSync(()->{RhStreamPanel panel=null;FrameLayout root=null;try{
                Context context=(Context)fixture.invoke(new RhFloatingLayoutDeviceTest(),base,dark,font);root=new FrameLayout(context);root.setBackgroundColor(dark?0xff192030:0xffe4eaf4);List<String> actions=new ArrayList<>();
                panel=new RhStreamPanel(context,root,(id,args)->actions.add(id));panel.updateControls(true,false,1,false,"","","");panel.setExpanded(true);layout(root);String name=(dark?"dark":"light")+"-font"+(int)(font*100),label="音量设置失败";
                panel.setTab("input");panel.updateFeedback("audio",label,"unavailable");boolean hiddenInitially=text(root,label)==null;
                panel.setTab("audio");boolean deliveredToOwnCategory=text(root,label)!=null;capture(root,folder,name+"-audio-arrival");
                panel.updateFeedback("audio",label,"unavailable");panel.setTab("displays");boolean hiddenInOtherCategory=text(root,label)==null;capture(root,folder,name+"-displays");
                panel.setTab("audio");boolean survivesRevisit=text(root,label)!=null;capture(root,folder,name+"-audio-revisit");
                panel.updateFeedback("audio","","");panel.setTab("input");panel.setTab("audio");boolean staysCleared=text(root,label)==null;capture(root,folder,name+"-cleared");
                JSONObject result=RhActions.obj("dark",dark,"fontScale",font,"hiddenInitially",hiddenInitially,"deliveredToOwnCategory",deliveredToOwnCategory,"hiddenInOtherCategory",hiddenInOtherCategory,"survivesRevisit",survivesRevisit,"staysCleared",staysCleared,"actionsEmitted",actions.size(),"passed",hiddenInitially&&deliveredToOwnCategory&&hiddenInOtherCategory&&survivesRevisit&&staysCleared&&actions.isEmpty());cases.put(result);
            }catch(Throwable error){failure[0]=error;}finally{if(panel!=null)panel.dispose();if(root!=null)root.removeAllViews();}});if(failure[0]!=null)throw new AssertionError("Native feedback fixture failed",failure[0]);
        }
        boolean passed=true;for(int i=0;i<cases.length();i++)passed&=cases.getJSONObject(i).getBoolean("passed");
        RhShared.write(new File(folder,"result.json"),RhActions.obj("version",base.getPackageManager().getPackageInfo(base.getPackageName(),0).versionName,"passed",passed,"cases",cases,"hostOperations",false,"systemInputInjected",false,"preferencesWritten",false,"rendering","own native View.draw without attached Activity"));
        assertTrue("Feedback did not follow its category: "+cases,passed);
    }
}
