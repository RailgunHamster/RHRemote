package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exports actual Views and explicit visual fixtures; does not verify business workflows. */
@RunWith(AndroidJUnit4.class)
public class RhVisualCatalogDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    Activity activity;RhHome home;File folder;JSONArray pages=new JSONArray();
    Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    void call(String method)throws Exception{Method value=RhHome.class.getDeclaredMethod(method);value.setAccessible(true);value.invoke(home);}
    JSONObject state(){JSONObject[] value={null};instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception failure){throw new RuntimeException(failure);}});return value[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);if(!result.optBoolean("ok"))throw new IllegalStateException(result.optString("error"));});instrument.waitForIdleSync();}
    void open(Context context)throws Exception{activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();home=(RhHome)field(activity,"rhHome");}
    void capture(String name)throws Exception{capture(name,null,false);}
    void capture(String name,AlertDialog dialog,boolean fixture)throws Exception{
        instrument.waitForIdleSync();Bitmap[] bitmap={null};JSONObject[] focus={null};instrument.runOnMainSync(()->{View view=dialog==null?activity.getWindow().getDecorView():dialog.getWindow().getDecorView();View focused=view.findFocus();focus[0]=RhActions.obj("class",focused==null?"":focused.getClass().getName(),"defaultHighlight",focused!=null&&focused.getDefaultFocusHighlightEnabled());view.jumpDrawablesToCurrentState();bitmap[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap[0]));});
        File file=new File(folder,name+".png");if(file.exists())throw new IOException("Existing visual must not be replaced");try(OutputStream out=new FileOutputStream(file)){if(!bitmap[0].compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG export failed");}finally{bitmap[0].recycle();}
        pages.put(RhActions.obj("name",name,"page",state().optString("page"),"theme",RhShared.dark(activity)?"dark":"light","fixture",fixture,"focus",focus[0],"file",file.getName()));
    }
    void scene(String name,String uuid,String phase,boolean pair)throws Exception{
        RhConnection model=(RhConnection)field(home,"connection");
        instrument.runOnMainSync(()->{model.begin(uuid,"winserver");model.operation=pair?"pair":"connect";model.phase=phase;model.error=phase.equals("failed")?"连接超时，请检查设备地址。":"";try{call("showConnection");}catch(Exception failure){throw new RuntimeException(failure);}});capture(name,null,true);
        instrument.runOnMainSync(()->{model.phase="idle";model.uuid="";model.error="";model.operation="connect";});action("devices.open",RhActions.obj());
    }
    void panels(String theme)throws Exception{
        FrameLayout[] root={null};RhStreamPanel[] panel={null};
        JSONObject physical=RhActions.obj("id","physical","name","LG FULL HD","width",1920,"height",1080,"available",true),virtual=RhActions.obj("id","virtual","name","","virtual",true,"width",2520,"height",1680,"available",true);
        instrument.runOnMainSync(()->{root[0]=new FrameLayout(activity);root[0].setBackgroundColor(Color.parseColor(RhShared.dark(activity)?"#111826":"#edf0f6"));ImageView desktop=new ImageView(activity);desktop.setImageResource(com.limelight.R.drawable.rh_monitor);desktop.setAlpha(.13f);FrameLayout.LayoutParams logo=new FrameLayout.LayoutParams(150,150,Gravity.CENTER);root[0].addView(desktop,logo);activity.setContentView(root[0]);panel[0]=new RhStreamPanel(activity,root[0],(id,args)->{});panel[0].setDeviceName("winserver");panel[0].setExpanded(true);panel[0].update(false,false,false,40,RhActions.obj("displays",new JSONArray().put(physical).put(virtual),"selected",physical),false,"");panel[0].updateControls(true,false,1.5f,false,"","","");});instrument.waitForIdleSync();
        for(String tab:new String[]{"input","displays","audio"}){instrument.runOnMainSync(()->panel[0].setTab(tab));capture("panel-"+tab+"-"+theme,null,true);}
        instrument.runOnMainSync(()->{panel[0].setTab("input");panel[0].updateScrollMode(true);});capture("panel-scroll-mode-"+theme,null,true);instrument.runOnMainSync(()->panel[0].updateScrollMode(false));
        instrument.runOnMainSync(()->{panel[0].setTab("displays");panel[0].updateControls(true,false,1,false,"displays","屏幕切换失败","所选显示器暂不可用，请刷新后重试。");});capture("panel-error-"+theme,null,true);instrument.runOnMainSync(()->panel[0].dispose());
    }
    void dialogs(String theme)throws Exception{
        String[] labels=new String[RhShortcuts.catalog(activity).length()];JSONArray shortcuts=RhShortcuts.catalog(activity);for(int i=0;i<labels.length;i++)labels[i]=shortcuts.getJSONObject(i).getString("label");
        AlertDialog[] dialog={null};instrument.runOnMainSync(()->{dialog[0]=new AlertDialog.Builder(activity,RhUiStyle.dialogTheme(activity)).setTitle("远程快捷键").setAdapter(new ArrayAdapter<>(new android.view.ContextThemeWrapper(activity,RhUiStyle.dialogTheme(activity)),android.R.layout.simple_list_item_1,labels),null).setNegativeButton("关闭",null).create();dialog[0].show();});capture("shortcuts-"+theme,dialog[0],true);instrument.runOnMainSync(()->dialog[0].dismiss());
        instrument.runOnMainSync(()->{dialog[0]=new AlertDialog.Builder(activity,RhUiStyle.dialogTheme(activity)).setTitle("断开连接？").setPositiveButton("断开",null).setNegativeButton("继续连接",null).create();dialog[0].show();});capture("disconnect-"+theme,dialog[0],true);instrument.runOnMainSync(()->dialog[0].dismiss());
    }
    @Test public void exportVisualCatalogOnly()throws Exception{
        assertTrue("Visual export is restricted to the dedicated emulator",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));String run=InstrumentationRegistry.getArguments().getString("visualRun","");assertTrue("Fresh explicit visualRun is required",run.matches("[a-zA-Z0-9_-]{1,64}"));Context context=instrument.getTargetContext();File shared=new File(context.getFilesDir(),"rhremote-shared.json");byte[] before=Files.readAllBytes(shared.toPath());JSONObject original=RhShared.read(context);boolean dark=RhShared.dark(context);android.content.SharedPreferences global=context.getSharedPreferences("rhremote.global",0);boolean hadCheck=global.contains("lastUpdateCheck");long check=global.getLong("lastUpdateCheck",0);global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();folder=new File(context.getExternalFilesDir("ui-evidence"),"visual-"+run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        try{
            for(boolean target:new boolean[]{false,true}){
                RhShared.theme(context,target);open(context);String theme=target?"dark":"light";JSONObject current=state();if(current.getJSONObject("sync").optBoolean("automatic"))throw new IllegalStateException("Visual export must not start automatic sync");String uuid="C3448560-C539-6209-F595-88C1E8D2769B";boolean found=false;JSONArray devices=current.getJSONArray("devices");for(int i=0;i<devices.length();i++)if(devices.getJSONObject(i).optString("uuid").equals(uuid))found=true;if(!found)uuid=devices.getJSONObject(0).getString("uuid");
                if(InstrumentationRegistry.getArguments().getString("visualScene","").equals("profile-advanced")){action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.tab",RhActions.obj("tab","advanced"));capture("profile-advanced-"+theme);instrument.runOnMainSync(()->activity.finish());instrument.waitForIdleSync();continue;}
                action("devices.open",RhActions.obj());capture("devices-list-"+theme);action("device.select",RhActions.obj("uuid",uuid));capture("devices-"+theme);action("devices.filter",RhActions.obj("query","no-such-device"));capture("search-empty-"+theme);action("devices.filter",RhActions.obj("query",""));
                action("device.settings.open",RhActions.obj("uuid",uuid));for(String tab:new String[]{"video","audio","input","network","advanced"}){action("settings.tab",RhActions.obj("tab",tab));capture("profile-"+tab+"-"+theme);}action("settings.cancel",RhActions.obj());
                for(String tab:new String[]{"updates","sync","appearance","about"}){action("app.settings.tab",RhActions.obj("tab",tab));capture("application-"+tab+"-"+theme);}action("devices.open",RhActions.obj());
                action("device.info.open",RhActions.obj("uuid",uuid));capture("device-info-"+theme);action("device.info.cancel",RhActions.obj());action("device.add.open",RhActions.obj());capture("device-add-"+theme);action("device.add.cancel",RhActions.obj());
                action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.input",RhActions.obj("key","fps","value","25"));action("devices.open",RhActions.obj());AlertDialog prompt=(AlertDialog)field(home,"navigationDialog");if(prompt!=null){capture("unsaved-"+theme,prompt,true);action("navigation.resolve",RhActions.obj("choice","discard"));}else action("settings.cancel",RhActions.obj());
                scene("connection-waiting-"+theme,uuid,"first-frame",false);scene("connection-error-"+theme,uuid,"failed",false);scene("pair-result-"+theme,uuid,"paired",true);
                dialogs(theme);panels(theme);instrument.runOnMainSync(()->activity.finish());instrument.waitForIdleSync();
            }
            RhShared.write(new File(folder,"catalog.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"visualOnly",true,"functionalVerification",false,"remoteSessionStarted",false,"syncStarted",false,"updateStarted",false,"systemInputInjected",false,"osScreenshots",false,"pages",pages));
        }finally{if(activity!=null)instrument.runOnMainSync(()->activity.finish());instrument.waitForIdleSync();RhShared.apply(context,original);try(FileOutputStream out=new FileOutputStream(shared)){out.write(before);}RhShared.theme(context,dark);android.content.SharedPreferences.Editor restore=global.edit();if(hadCheck)restore.putLong("lastUpdateCheck",check);else restore.remove("lastUpdateCheck");restore.commit();}
    }
}
