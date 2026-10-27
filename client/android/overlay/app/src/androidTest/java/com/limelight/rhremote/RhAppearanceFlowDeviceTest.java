package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Recreates the real PcView through its visible theme control, without starting a stream. */
@RunWith(AndroidJUnit4.class)
public class RhAppearanceFlowDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;
    void bind(Activity next)throws Exception{activity=next;Field field=next.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(next);}
    JSONObject state(){JSONObject[] value={null};instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return value[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View root,String label){if(root.getVisibility()!=View.VISIBLE)return null;if(root instanceof Button&&label.contentEquals(((Button)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}return null;}
    void click(String label){instrument.runOnMainSync(()->{View button=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,button);assertTrue(button.performClick());});instrument.waitForIdleSync();}
    void waitForRecreation(Activity old)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);Activity[] replacement={null};
        while(replacement[0]==null){instrument.runOnMainSync(()->{for(Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(candidate!=old&&candidate.getClass().getName().equals("com.limelight.PcView"))replacement[0]=candidate;});assertTrue("Theme did not recreate PcView",System.nanoTime()<deadline);if(replacement[0]==null)Thread.sleep(25);}
        bind(replacement[0]);instrument.waitForIdleSync();
    }
    void capture(String name)throws Exception{Thread.sleep(200);Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);File dir=activity.getExternalFilesDir("ui-evidence");assertTrue(dir.isDirectory()||dir.mkdirs());try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
    @Test public void themeChangesKeepTheCurrentSettingsTabSelectionAndSearch()throws Exception{
        Context context=instrument.getTargetContext();boolean originalDark=RhShared.dark(context);
        try{
            bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();
            assertTrue("Dedicated emulator needs its saved test device",state().getJSONArray("devices").length()>0);String uuid=state().getJSONArray("devices").getJSONObject(0).getString("uuid");
            action("devices.filter",RhActions.obj("query","win"));action("device.select",RhActions.obj("uuid",uuid));action("app.settings.tab",RhActions.obj("tab","appearance"));
            for(int round=0;round<2;round++){
                boolean before=RhShared.dark(context);Activity old=activity;click(before?"切换为浅色":"切换为深色");waitForRecreation(old);capture("appearance-"+(before?"light":"dark"));
                JSONObject state=state();assertEquals("Theme changed the current page","global-settings",state.getString("page"));assertEquals("appearance",state.getString("settingsTab"));assertEquals(uuid,state.getString("selected"));assertEquals("win",state.getString("deviceQuery"));assertEquals(!before,RhShared.dark(context));
                for(String[] tab:new String[][]{{"更新","updates"},{"配置同步","sync"},{"关于","about"},{"外观","appearance"}}){click(tab[0]);assertEquals(tab[1],state().getString("settingsTab"));}
                click("配置同步");instrument.runOnMainSync(()->{for(String label:new String[]{"立即同步","移除同步设置"}){Button button=(Button)find(activity.findViewById(android.R.id.content),label);assertNotNull(label,button);assertFalse(button.isEnabled());assertTrue(button.getTextColors().isStateful());}});
                action("sync.edit",RhActions.obj("key","url","value","invalid"));click("关于");assertFalse(state().isNull("navigationPrompt"));
                Field prompt=RhHome.class.getDeclaredField("navigationDialog");prompt.setAccessible(true);AlertDialog dialog=(AlertDialog)prompt.get(home);instrument.runOnMainSync(()->{for(int which:new int[]{AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_POSITIVE}){Button button=dialog.getButton(which);android.graphics.Rect visible=new android.graphics.Rect();assertTrue(button.getGlobalVisibleRect(visible));assertEquals(button.getWidth(),visible.width());assertEquals(button.getHeight(),visible.height());}});capture("appearance-dialog-"+(before?"light":"dark"));
                instrument.runOnMainSync(()->assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()));instrument.waitForIdleSync();assertTrue(state().isNull("navigationPrompt"));assertEquals("invalid",state().getJSONObject("syncDraft").getString("url"));action("sync.cancel",RhActions.obj());click("外观");
            }
            click("返回设备");assertEquals("devices",state().getString("page"));assertEquals(uuid,state().getString("selected"));assertEquals("win",state().getString("deviceQuery"));assertFalse(state().getJSONObject("connection").optBoolean("firstFrame"));
            RhShared.write(new File(activity.getExternalFilesDir("ui-evidence"),"appearance-flow.json"),RhActions.obj("roundTrips",2,"settingsTabPreserved",true,"selectionPreserved",true,"searchPreserved",true,"streamStarted",false,"width",activity.getWindow().getDecorView().getWidth(),"height",activity.getWindow().getDecorView().getHeight()));
        }finally{
            RhShared.theme(context,originalDark);if(activity!=null)instrument.runOnMainSync(()->activity.finish());
        }
    }
}
