package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native form ownership only; no connection, WebDAV transfer or OS input. */
@RunWith(AndroidJUnit4.class)
public final class RhFormOwnerDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;File folder;final String uuid="ui-form-owner83";
    void main(Runnable action){instrument.runOnMainSync(action);instrument.waitForIdleSync();}
    JSONObject state(){JSONObject[] result={null};main(()->{try{result[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return result[0];}
    void action(String id,JSONObject args){main(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});}
    void bind(Activity value)throws Exception{activity=value;Field field=value.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(value);assertNotNull(home);}
    Activity resumed(){Activity[] value={null};main(()->{for(Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(candidate.getClass().getName().equals("com.limelight.PcView"))value[0]=candidate;});return value[0];}
    View find(View view,String text,boolean editor){if(view.getVisibility()!=View.VISIBLE)return null;if(editor&&view instanceof EditText&&text.contentEquals(view.getContentDescription()))return view;if(!editor&&view instanceof Button&&text.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),text,editor);if(found!=null)return found;}return null;}
    void click(String text){main(()->{View view=find(activity.findViewById(android.R.id.content),text,false);assertNotNull(text,view);assertTrue(view.isEnabled());view.performClick();});}
    EditText bitrate()throws Exception{String name="";JSONArray fields=RhSettings.catalog(activity);for(int i=0;i<fields.length();i++)if(fields.getJSONObject(i).getString("key").equals("bitrateMbps"))name=fields.getJSONObject(i).getString("label");final String label=name;EditText[] result={null};main(()->result[0]=(EditText)find(activity.findViewById(android.R.id.content),label,true));assertNotNull(result[0]);return result[0];}
    AlertDialog prompt()throws Exception{Field field=RhHome.class.getDeclaredField("navigationDialog");field.setAccessible(true);AlertDialog value=(AlertDialog)field.get(home);assertNotNull(value);assertTrue(value.isShowing());return value;}
    void choose(AlertDialog dialog,int button){main(()->dialog.getButton(button).performClick());}
    void capture(AlertDialog dialog,String name)throws Exception{Bitmap[] frame={null};main(()->{View decor=dialog.getWindow().getDecorView();frame[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(frame[0]));});try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(frame[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{frame[0].recycle();}}
    @Test public void currentDecisionsAndEditorsSurviveOldCallbacksAndActivityRecreation()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("formOwnerRun","");org.junit.Assume.assumeFalse("Explicit dedicated run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),"form-owner-"+run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhSessionFlowDeviceTest snapshotOwner=new RhSessionFlowDeviceTest();snapshotOwner.folder=folder;
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(snapshotOwner,run)){
            main(()->{try{JSONObject fixture=RhShared.read(context);fixture.getJSONArray("devices").put(RhActions.obj("uuid",uuid,"name","UI form fixture","displayName","","address","192.0.2.83","addresses",new JSONArray()));RhShared.apply(context,fixture);RhShared.theme(context,false);}catch(Exception e){throw new RuntimeException(e);}});bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();main(()->{ComputerDetails d=new ComputerDetails();d.uuid=uuid;d.name="UI form fixture";d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.83",47989);home.update(d);});
            action("device.settings.open",RhActions.obj("uuid",uuid));EditText original=bitrate();int saved=RhProfile.load(context,uuid).bitrateMbps;main(()->original.setText("37"));click("概览");AlertDialog old=prompt();choose(old,AlertDialog.BUTTON_NEUTRAL);assertEquals("37",state().getJSONObject("settingsInputs").getString("bitrateMbps"));
            action("app.settings.tab",RhActions.obj("tab","about"));AlertDialog current=prompt();assertNotSame(old,current);choose(old,AlertDialog.BUTTON_POSITIVE);main(old::cancel);assertTrue(current.isShowing());assertEquals("settings",state().getString("page"));assertEquals("37",state().getJSONObject("settingsInputs").getString("bitrateMbps"));assertEquals(saved,RhProfile.load(context,uuid).bitrateMbps);capture(current,"current-save-decision");choose(current,AlertDialog.BUTTON_NEGATIVE);assertEquals("about",state().getString("settingsTab"));
            action("device.settings.open",RhActions.obj("uuid",uuid));EditText retired=bitrate();Activity previous=activity;RhHome previousHome=home;main(previous::recreate);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);Activity next;
            while((next=resumed())==null||next==previous){assertTrue("Replacement PcView missing",System.nanoTime()<end);Thread.sleep(30);}bind(next);assertNotSame(previousHome,home);assertFalse(RhActions.ownsHome(previousHome));assertTrue(RhActions.ownsHome(home));EditText live=bitrate();main(()->live.setText("22"));main(()->retired.setText("64"));assertEquals("22",state().getJSONObject("settingsInputs").getString("bitrateMbps"));main(()->live.setText("24"));assertEquals("24",state().getJSONObject("settingsInputs").getString("bitrateMbps"));
            action("app.settings.tab",RhActions.obj("tab","about"));AlertDialog afterRecreate=prompt();choose(old,AlertDialog.BUTTON_POSITIVE);main(old::cancel);assertTrue(afterRecreate.isShowing());assertEquals(saved,RhProfile.load(context,uuid).bitrateMbps);choose(afterRecreate,AlertDialog.BUTTON_POSITIVE);assertEquals("about",state().getString("settingsTab"));assertEquals(24,RhProfile.load(context,uuid).bitrateMbps);
            RhShared.write(new File(folder,"form-owner-result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"oldDialogIgnored",true,"draftRetained",true,"currentDiscardWorked",true,"actualActivityRecreated",true,"oldEditorIgnored",true,"currentEditorWorked",true,"currentSavePersisted",true,"webdavChanged",false,"networkTransfer",false,"systemInputInjected",false));
        }
    }
}
