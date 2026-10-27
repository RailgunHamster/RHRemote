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
        for(int frame=0;frame<2;frame++){CountDownLatch committed=new CountDownLatch(1);instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();decor.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);decor.invalidate();});assertTrue(committed.await(3,TimeUnit.SECONDS));}
        Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);File folder=instrument.getTargetContext().getExternalFilesDir("ui-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
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
