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

/** Real PcView recreation and current widgets; no connection, DAV request or system input. */
@RunWith(AndroidJUnit4.class)
public final class RhDraftRecreationDeviceTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final String uuid="ui-draft-recreation116",query="UI recreation fixture";
    private final JSONArray cases=new JSONArray();
    private Activity activity;private RhHome home;private File folder;private int recreations;
    private void main(Runnable action){instrument.runOnMainSync(action);instrument.waitForIdleSync();}
    private JSONObject state(){JSONObject[] result={null};main(()->{try{result[0]=home.state();}catch(Exception error){throw new RuntimeException(error);}});return result[0];}
    private void action(String id,JSONObject args){main(()->assertTrue(id,RhActions.dispatch(id,args).optBoolean("ok")));}
    private void bind(Activity value)throws Exception{activity=value;Field field=value.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(value);assertNotNull(home);}
    private View find(View view,String text,boolean editor){if(view.getVisibility()!=View.VISIBLE)return null;if(editor&&view instanceof EditText&&text.contentEquals(view.getContentDescription())||!editor&&(view instanceof Button&&text.contentEquals(((Button)view).getText())||text.startsWith("nav:")&&text.equals(view.getTag())))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),text,editor);if(result!=null)return result;}return null;}
    private EditText editor(String label){EditText[] value={null};main(()->value[0]=(EditText)find(activity.findViewById(android.R.id.content),label,true));assertNotNull(label,value[0]);return value[0];}
    private void type(EditText view,String value){main(()->view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true));main(()->{Rect area=new Rect();assertTrue(view.getGlobalVisibleRect(area));assertEquals(view.getHeight(),area.height());view.setText(value);});}
    private void click(String label){main(()->{View view=find(activity.findViewById(android.R.id.content),label,false);assertNotNull(label,view);assertTrue(view.isEnabled());Rect area=new Rect();assertTrue(label,view.getGlobalVisibleRect(area));assertEquals(label,view.getWidth(),area.width());assertEquals(label,view.getHeight(),area.height());view.performClick();});}
    private AlertDialog prompt()throws Exception{Field field=RhHome.class.getDeclaredField("navigationDialog");field.setAccessible(true);AlertDialog dialog=(AlertDialog)field.get(home);assertNotNull(dialog);assertTrue(dialog.isShowing());return dialog;}
    private void choose(int button)throws Exception{AlertDialog dialog=prompt();main(()->dialog.getButton(button).performClick());}
    private Activity resumed(){Activity[] result={null};main(()->{for(Activity item:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(item.getClass().getName().equals("com.limelight.PcView"))result[0]=item;});return result[0];}
    private void recreate()throws Exception{Activity old=activity;RhHome retired=home;main(old::recreate);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);Activity current;while((current=resumed())==null||current==old){assertTrue("Replacement PcView missing",System.nanoTime()<until);Thread.sleep(25);}bind(current);assertNotSame(retired,home);assertFalse(RhActions.ownsHome(retired));assertTrue(RhActions.ownsHome(home));assertEquals(uuid,state().getString("selected"));assertEquals(query,state().getString("deviceQuery"));recreations++;}
    private String label(String key)throws Exception{JSONArray fields=RhSettings.catalog(activity);for(int i=0;i<fields.length();i++)if(fields.getJSONObject(i).getString("key").equals(key))return fields.getJSONObject(i).getString("label");throw new AssertionError(key);}
    private void capture(String name)throws Exception{Bitmap[] image={null};main(()->{View decor=activity.getWindow().getDecorView();image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image[0]));});try(OutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image[0].compress(Bitmap.CompressFormat.PNG,100,output));}finally{image[0].recycle();}}
    private void finish()throws Exception{if(activity==null)return;Activity old=activity;main(old::finish);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!old.isDestroyed()){assertTrue(System.nanoTime()<until);Thread.sleep(20);}activity=null;}
    @Test public void unsubmittedFormsAndTheirDecisionsSurviveActualActivityRecreation()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("draftRecreationRun","");org.junit.Assume.assumeFalse("Explicit isolated run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,64}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhSessionFlowDeviceTest owner=new RhSessionFlowDeviceTest();owner.folder=folder;
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(owner,run)){
            main(()->{try{JSONObject shared=RhShared.read(context);shared.getJSONArray("devices").put(RhActions.obj("uuid",uuid,"name",query,"displayName","","address","192.0.2.116","addresses",new JSONArray()));RhShared.apply(context,shared);}catch(Exception error){throw new RuntimeException(error);}});
            for(boolean dark:new boolean[]{false,true}){
                RhShared.theme(context,dark);bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();assertFalse("No automatic DAV requests in this fixture",state().getJSONObject("sync").optBoolean("automatic"));main(()->{ComputerDetails device=new ComputerDetails();device.uuid=uuid;device.name=query;device.manualAddress=new ComputerDetails.AddressTuple("192.0.2.116",47989);home.update(device);});String theme=dark?"dark":"light";action("devices.filter",RhActions.obj("query",query));action("device.select",RhActions.obj("uuid",uuid));
                action("device.settings.open",RhActions.obj("uuid",uuid));String width=label("width"),bitrate=label("bitrateMbps");int savedWidth=RhProfile.load(context,uuid).width,changed=RhProfile.load(context,uuid).bitrateMbps==37?38:37;EditText oldEditor=editor(bitrate);type(oldEditor,Integer.toString(changed));type(editor(width),"");click("保存");assertFalse(state().getString("settingsError").isEmpty());click("nav:app.settings.open");AlertDialog oldPrompt=prompt();recreate();
                assertEquals("",state().getJSONObject("settingsInputs").getString("width"));assertEquals(Integer.toString(changed),state().getJSONObject("settingsInputs").getString("bitrateMbps"));assertTrue(state().getBoolean("settingsDirty"));AlertDialog current=prompt();main(()->{oldEditor.setText("64");oldPrompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();});assertTrue(current.isShowing());assertEquals(Integer.toString(changed),state().getJSONObject("settingsInputs").getString("bitrateMbps"));choose(AlertDialog.BUTTON_NEUTRAL);capture("profile-recreated-"+theme);type(editor(width),Integer.toString(savedWidth));click("保存");assertEquals("devices",state().getString("page"));assertEquals(changed,RhProfile.load(context,uuid).bitrateMbps);assertEquals(uuid,state().getString("selected"));assertEquals(query,state().getString("deviceQuery"));cases.put(RhActions.obj("form","profile","theme",theme,"invalidInputRetained",true,"oldCallbacksRejected",true,"decisionRestored",true,"currentSaveWorked",true));
                action("device.info.open",RhActions.obj("uuid",uuid));type(editor("设备名称"),"草稿设备 "+theme);click("nav:devices.open");recreate();assertEquals("草稿设备 "+theme,state().getJSONObject("infoDraft").getString("displayName"));assertTrue(state().getBoolean("infoDirty"));choose(AlertDialog.BUTTON_NEGATIVE);assertEquals("",RhShared.alias(context,uuid));cases.put(RhActions.obj("form","info","theme",theme,"draftRetained",true,"decisionRestored",true,"discardWorked",true));
                action("app.settings.tab",RhActions.obj("tab","sync"));type(editor("WebDAV 目录地址（HTTP / HTTPS）"),"invalid-private-fixture");type(editor("用户名"),"fixture-user");type(editor("密码"),"fixture-only-password116");click("保存");String syncFailure=state().getString("syncError");assertFalse(syncFailure.isEmpty());click("nav:devices.open");recreate();assertEquals(syncFailure,state().getString("syncError"));assertEquals("invalid-private-fixture",state().getJSONObject("syncDraft").getString("url"));assertEquals("fixture-user",state().getJSONObject("syncDraft").getString("username"));assertEquals("fixture-only-password116",editor("密码").getText().toString());assertFalse(state().getJSONObject("syncDraft").has("password"));choose(AlertDialog.BUTTON_NEUTRAL);assertTrue(editor("密码").getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod);capture("sync-recreated-"+theme);click("取消");assertFalse(state().getBoolean("syncDirty"));click("nav:devices.open");assertEquals(uuid,state().getString("selected"));assertEquals(query,state().getString("deviceQuery"));cases.put(RhActions.obj("form","sync","theme",theme,"draftRetained",true,"passwordMasked",true,"passwordNotExposedByState",true,"decisionRestored",true,"cancelWorked",true));finish();
            }
            assertEquals(6,recreations);RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",cases.length()==6,"version",com.limelight.BuildConfig.VERSION_NAME,"fontScale",context.getResources().getConfiguration().fontScale,"cases",cases,"actualPcView",true,"actualActivityRecreations",recreations,"normalDavRequestSent",false,"normalDavCredentialsExported",false,"streamStarted",false,"systemInputInjected",false,"osScreenshots",false));
        }
    }
}
