package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real native controls on the isolated emulator. No pairing, stream or installer is started. */
@RunWith(AndroidJUnit4.class)
public class RhProfileCopyFlowDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;
    JSONObject state(){JSONObject[] out={null};instrument.runOnMainSync(()->{try{out[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return out[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(label.equals(view.getTag())||label.equals(view.getContentDescription())||view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    View control(String label){View result=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,result);return result;}
    void click(String label){instrument.runOnMainSync(()->{View view=control(label);assertTrue(view.isEnabled());view.performClick();});instrument.waitForIdleSync();}
    void footer(String label){instrument.runOnMainSync(()->{View button=control(label);Rect visible=new Rect();assertTrue(button.getGlobalVisibleRect(visible));assertEquals(button.getHeight(),visible.height());assertEquals(button.getWidth(),visible.width());for(android.view.ViewParent parent=button.getParent();parent instanceof View;parent=parent.getParent())assertFalse(parent instanceof ScrollView);});}
    void capture(String name)throws Exception{
        if(!InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true"))return;
        instrument.waitForIdleSync();Bitmap[] image={null};instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image[0]));});File folder=instrument.getTargetContext().getExternalFilesDir("ui-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{image[0].recycle();}
    }
    void recreateTheme()throws Exception{
        Activity old=activity;boolean before=RhShared.dark(activity);action("appearance.toggle",RhActions.obj());Activity[] next={null};long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(next[0]==null){instrument.runOnMainSync(()->{for(Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(candidate!=old&&candidate.getClass().getName().equals("com.limelight.PcView"))next[0]=candidate;});assertTrue("PcView did not recreate",System.nanoTime()<end);if(next[0]==null)Thread.sleep(20);}
        activity=next[0];Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);instrument.waitForIdleSync();assertEquals(!before,RhShared.dark(activity));
    }
    @Test public void themeRecreationRetainsTheInspectedSnapshotAndAnUnavailableSourceReturnsToSettings()throws Exception{
        Context context=instrument.getTargetContext();boolean originalDark=RhShared.dark(context);activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertFalse(RhActions.hasStream());
        JSONObject original=RhShared.read(context);String uuid=state().getJSONArray("devices").getJSONObject(0).getString("uuid"),client="ui-copy-theme-1234";JSONObject fixture=new JSONObject(original.toString());RhProfile source=RhProfile.load(context,uuid);source.width=1600;source.height=900;source.bitrateMbps=35;
        fixture.getJSONObject("clients").put(client,RhActions.obj("name","平板配置（UI 验证）","platform","android"));fixture.getJSONObject("profiles").put(client,RhActions.obj(uuid,source.clientJson()));RhShared.apply(context,fixture);
        try{
            action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.tab",RhActions.obj("tab","advanced"));click("从其他客户端复制配置");click("copy-source:"+client);String snapshot=RhShared.canonical(state().getJSONObject("profileCopy"));
            JSONObject changed=RhShared.read(context);changed.getJSONObject("profiles").getJSONObject(client).getJSONObject(uuid).put("width",1920).put("bitrateMbps",55);RhShared.apply(context,changed);String changedCanonical=saved(context);
            for(int round=0;round<2;round++){recreateTheme();assertEquals("profile-copy",state().getString("page"));assertEquals(snapshot,RhShared.canonical(state().getJSONObject("profileCopy")));assertFalse(state().getBoolean("settingsDirty"));footer("复制到草稿");footer("取消");capture("copy-theme-"+(RhShared.dark(context)?"dark":"light"));}
            click("复制到草稿");assertEquals(1600,state().getJSONObject("draft").getInt("width"));assertEquals(35,state().getJSONObject("draft").getInt("bitrateMbps"));assertTrue(state().getBoolean("settingsDirty"));assertEquals(changedCanonical,saved(context));footer("保存");capture("copy-theme-applied-draft");click("取消");assertEquals(changedCanonical,saved(context));
            action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.tab",RhActions.obj("tab","advanced"));click("从其他客户端复制配置");click("copy-source:"+client);assertEquals(1920,state().getJSONObject("profileCopy").getJSONObject("profile").getInt("width"));assertEquals(55,state().getJSONObject("profileCopy").getJSONObject("profile").getInt("bitrateMbps"));capture("copy-theme-reopened");
            JSONObject removed=RhShared.read(context);removed.getJSONObject("profiles").remove(client);removed.getJSONObject("clients").remove(client);RhShared.apply(context,removed);recreateTheme();assertEquals("settings",state().getString("page"));assertEquals("advanced",state().getString("profileTab"));assertTrue(state().isNull("profileCopy"));assertEquals("来源配置不可用",state().getString("profileCopyNotice"));assertFalse(state().getBoolean("settingsDirty"));footer("保存");footer("取消");capture("copy-theme-source-unavailable");assertFalse(RhActions.hasStream());
            RhShared.write(new File(context.getExternalFilesDir("ui-evidence"),"copy-theme-flow.json"),RhActions.obj("realRecreations",3,"snapshotPreservedAcrossSourceChange",true,"applyUsedDisplayedValues",true,"freshPreviewReadUpdatedSource",true,"unavailableSourceReturnedToCategory",true,"savedCurrentProfileChanged",false,"streamStarted",false));
        }finally{
            instrument.runOnMainSync(()->{try{if(home.state().optString("page").equals("profile-copy"))home.perform("settings.copy.cancel",RhActions.obj());if(!home.state().isNull("draft"))home.perform("settings.cancel",RhActions.obj());activity.finish();}catch(Exception e){throw new RuntimeException(e);}});instrument.waitForIdleSync();RhShared.theme(context,originalDark);RhShared.apply(context,original);assertEquals(RhShared.canonical(original),saved(context));
        }
    }
    String saved(Context context)throws Exception{return RhShared.canonical(RhShared.read(context));}
    @Test public void inspectCancelApplySaveAndDiscardUseRealControlsWithoutChangingHostIdentity()throws Exception{
        Context context=instrument.getTargetContext();activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertFalse(RhActions.hasStream());
        JSONObject original=RhShared.read(context);String originalCanonical=RhShared.canonical(original);String uuid=state().getJSONArray("devices").getJSONObject(0).getString("uuid"),client="ui-copy-source-1234";JSONObject fixture=new JSONObject(original.toString());RhProfile source=RhProfile.load(context,uuid);source.width=1280;source.height=720;source.bitrateMbps=42;source.audioEnabled=false;
        fixture.getJSONObject("clients").put(client,RhActions.obj("name","OPPO 平板（UI 验证）","platform","android"));fixture.getJSONObject("profiles").put(client,RhActions.obj(uuid,source.clientJson()));RhShared.apply(context,fixture);String before=saved(context);
        try{
            action("device.settings.open",RhActions.obj("uuid",uuid));String widthLabel="";JSONArray catalog=RhSettings.catalog(activity);for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("key").equals("width"))widthLabel=catalog.getJSONObject(i).getString("label");final String label=widthLabel;
            instrument.runOnMainSync(()->((EditText)control(label)).setText(""));click("保存");assertEquals("width",state().optString("settingsErrorField"));String raw=state().getJSONObject("settingsInputs").toString(),error=state().getString("settingsError");
            action("settings.tab",RhActions.obj("tab","advanced"));click("从其他客户端复制配置");click("copy-source:"+client);assertEquals("profile-copy",state().optString("page"));assertEquals("OPPO 平板（UI 验证） · 1234",state().getJSONObject("profileCopy").getString("sourceName"));footer("复制到草稿");footer("取消");capture("copy-preview");assertEquals(before,saved(context));
            click("取消");assertEquals("settings",state().optString("page"));assertEquals(raw,state().getJSONObject("settingsInputs").toString());assertEquals(error,state().getString("settingsError"));assertEquals("width",state().getString("settingsErrorField"));assertEquals(before,saved(context));
            click("copy-source:"+client);click("复制到草稿");assertEquals("settings",state().optString("page"));assertEquals(1280,state().getJSONObject("draft").getInt("width"));assertEquals("",state().getString("settingsErrorField"));assertEquals("",state().getString("settingsError"));assertEquals(0,state().getJSONObject("settingsInputs").length());assertEquals(before,saved(context));footer("保存");capture("copy-applied");click("保存");assertEquals("devices",state().optString("page"));assertEquals(1280,RhProfile.load(context,uuid).width);assertEquals(42,RhProfile.load(context,uuid).bitrateMbps);
            JSONObject persisted=RhShared.read(context);assertEquals(RhShared.canonical(fixture.getJSONArray("devices")),RhShared.canonical(persisted.getJSONArray("devices")));assertEquals(RhShared.canonical(fixture.getJSONObject("profiles").getJSONObject(client)),RhShared.canonical(persisted.getJSONObject("profiles").getJSONObject(client)));String savedCopy=saved(context);
            action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.input",RhActions.obj("key","fps","value","24"));action("settings.tab",RhActions.obj("tab","advanced"));click("从其他客户端复制配置");click("copy-source:"+client);action("app.settings.tab",RhActions.obj("tab","about"));assertEquals("unsaved-device",state().getJSONObject("navigationPrompt").optString("kind"));action("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("profile-copy",state().optString("page"));click("取消");click("取消");assertEquals(savedCopy,saved(context));
            File folder=context.getExternalFilesDir("ui-evidence");RhShared.write(new File(folder,"copy-flow.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"sdk",android.os.Build.VERSION.SDK_INT,"width",activity.getResources().getDisplayMetrics().widthPixels,"height",activity.getResources().getDisplayMetrics().heightPixels,"previewCancelPreservedInput",true,"applyClearedOldError",true,"explicitSaveVerified",true,"hostMetadataPreserved",true,"discardPreservedSavedProfile",true,"streamStarted",false));
        }finally{
            instrument.runOnMainSync(()->{try{if(home.state().optString("page").equals("profile-copy"))home.perform("settings.copy.cancel",RhActions.obj());if(!home.state().isNull("draft"))home.perform("settings.cancel",RhActions.obj());}catch(Exception e){throw new RuntimeException(e);}});RhShared.apply(context,original);assertEquals(originalCanonical,saved(context));
        }
    }
}
