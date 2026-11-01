package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Own native widgets and two saved fixture devices; no host or WebDAV operations. */
public final class RhFormWidgetsDeviceTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final String source="ui-form-source87",peer="ui-form-peer87";
    private Activity activity;
    private RhHome home;
    private File folder;
    private void main(Runnable action){instrument.runOnMainSync(action);instrument.waitForIdleSync();}
    private JSONObject state(){final JSONObject[] result={null};main(()->{try{result[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return result[0];}
    private void action(String id,JSONObject args){main(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});}
    private View root(){return ((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);}
    private View find(View view,String label,boolean button){
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(button&&view instanceof Button&&label.contentEquals(((Button)view).getText())||!button&&label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),label,button);if(result!=null)return result;}return null;
    }
    private View described(String label){final View[] result={null};main(()->result[0]=find(root(),label,false));assertNotNull(label,result[0]);return result[0];}
    private View field(String key)throws Exception{JSONArray catalog=RhSettings.catalog(activity);for(int i=0;i<catalog.length();i++){JSONObject row=catalog.getJSONObject(i);if(row.getString("key").equals(key))return described(row.getString("label"));}throw new AssertionError("Missing field "+key);}
    private Button button(String label){final Button[] result={null};main(()->result[0]=(Button)find(root(),label,true));assertNotNull(label,result[0]);return result[0];}
    private void click(String label){Button value=button(label);main(()->{assertTrue(label,value.isEnabled());value.performClick();});}
    private void selectDevice(String uuid)throws Exception{
        final View[] selector={null};main(()->selector[0]=find(root(),"切换远程设备",false));
        if(selector[0] instanceof Spinner){JSONArray devices=state().getJSONArray("devices");int position=-1;for(int i=0;i<devices.length();i++)if(devices.getJSONObject(i).getString("uuid").equals(uuid))position=i;assertTrue(position>=0);final int target=position;main(()->((Spinner)selector[0]).setSelection(target));}
        else{View row=described("选择设备 "+(uuid.equals(peer)?"UI peer":"UI source"));main(()->{row.requestRectangleOnScreen(new Rect(0,0,row.getWidth(),row.getHeight()),true);row.performClick();});}
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(!uuid.equals(state().getString("selected"))){assertTrue("Actual device selector did not switch",System.nanoTime()<until);Thread.sleep(20);}assertEquals("settings",state().getString("page"));
    }
    private String fingerprint(Context context)throws Exception{return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(RhShared.canonical(RhShared.read(context)).getBytes(java.nio.charset.StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP);}
    private void capture(String name)throws Exception{final Bitmap[] pixels={null};main(()->{View view=root();pixels[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(pixels[0]));});try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(pixels[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{pixels[0].recycle();}}
    @Test public void retiredNativeEditorsButtonsAndRowsCannotChangeTheReplacementDevice()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("formWidgetRun","");org.junit.Assume.assumeFalse("Explicit dedicated emulator run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhSessionFlowDeviceTest holder=new RhSessionFlowDeviceTest();holder.folder=folder;JSONArray cases=new JSONArray();
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(holder,run)){
            main(()->{try{JSONObject shared=RhShared.read(context);shared.getJSONArray("devices").put(RhActions.obj("uuid",source,"name","UI source","displayName","","address","192.0.2.86","addresses",new JSONArray()));shared.getJSONArray("devices").put(RhActions.obj("uuid",peer,"name","UI peer","displayName","","address","192.0.2.87","addresses",new JSONArray()));RhShared.apply(context,shared);}catch(Exception e){throw new RuntimeException(e);}});
            for(boolean dark:new boolean[]{false,true}){
                main(()->{try{RhShared.theme(context,dark);RhShared.capture(context,java.util.Collections.emptyList());}catch(Exception e){throw new RuntimeException(e);}});activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertNotNull(home);action("devices.filter",RhActions.obj("query","UI"));action("device.settings.open",RhActions.obj("uuid",source));EditText old=(EditText)field("bitrateMbps");Button oldSave=button("保存");final InputConnection[] retired={null};main(()->{old.requestFocus();retired[0]=old.onCreateInputConnection(new EditorInfo());});assertNotNull(retired[0]);int original=RhProfile.load(context,source).bitrateMbps;
                selectDevice(peer);EditText live=(EditText)field("bitrateMbps");assertNotSame(old,live);main(()->live.setText("22"));String saved=fingerprint(context);final boolean[] oldImeAccepted={false};main(()->{retired[0].setSelection(0,old.length());oldImeAccepted[0]=retired[0].commitText("64",1);oldSave.performClick();});assertEquals("settings",state().getString("page"));assertEquals(peer,state().getString("selected"));assertEquals("22",state().getJSONObject("settingsInputs").getString("bitrateMbps"));assertEquals(saved,fingerprint(context));capture("peer-after-retired-events-"+(dark?"dark":"light"));
                int wanted=dark?23:24;main(()->{live.requestFocus();InputConnection current=live.onCreateInputConnection(new EditorInfo());assertNotNull(current);assertTrue(current.setSelection(0,live.length()));assertTrue(current.commitText(Integer.toString(wanted),1));});assertEquals(Integer.toString(wanted),state().getJSONObject("settingsInputs").getString("bitrateMbps"));click("保存");assertEquals(wanted,RhProfile.load(context,peer).bitrateMbps);assertEquals(original,RhProfile.load(context,source).bitrateMbps);
                action("device.settings.open",RhActions.obj("uuid",source));action("settings.tab",RhActions.obj("tab","network"));CheckBox oldRoute=(CheckBox)described("连接路径：局域网");selectDevice(peer);String routes=state().getJSONObject("draft").getJSONArray("routeOrder").toString();main(oldRoute::performClick);assertEquals(routes,state().getJSONObject("draft").getJSONArray("routeOrder").toString());CheckBox currentRoute=(CheckBox)described("连接路径：局域网");main(currentRoute::performClick);assertNotEquals(routes,state().getJSONObject("draft").getJSONArray("routeOrder").toString());action("settings.cancel",RhActions.obj());
                action("device.settings.open",RhActions.obj("uuid",source));action("settings.tab",RhActions.obj("tab","video"));Spinner oldSelect=(Spinner)field("codec");AdapterView.OnItemSelectedListener callback=oldSelect.getOnItemSelectedListener();int other=(oldSelect.getSelectedItemPosition()+1)%oldSelect.getCount();selectDevice(peer);String codec=state().getJSONObject("draft").getString("codec");main(()->callback.onItemSelected(oldSelect,null,other,other));assertEquals(codec,state().getJSONObject("draft").getString("codec"));capture("peer-after-retired-selection-"+(dark?"dark":"light"));Spinner currentSelect=(Spinner)field("codec");main(()->currentSelect.setSelection(other));long selectionDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(codec.equals(state().getJSONObject("draft").getString("codec"))){assertTrue("Current codec selection did not apply",System.nanoTime()<selectionDeadline);Thread.sleep(20);}action("settings.cancel",RhActions.obj());
                action("devices.open",RhActions.obj());action("devices.filter",RhActions.obj("query","UI"));View oldRow=described("选择设备 UI source");action("device.select",RhActions.obj("uuid",peer));action("devices.filter",RhActions.obj("query","UI peer"));main(oldRow::performClick);assertEquals(peer,state().getString("selected"));assertEquals("UI peer",state().getString("deviceQuery"));action("device.select",RhActions.obj("uuid",source));action("devices.open",RhActions.obj());View currentRow=described("选择设备 UI peer");main(currentRow::performClick);assertEquals(peer,state().getString("selected"));
                cases.put(RhActions.obj("dark",dark,"oldInputConnectionCommitReturned",oldImeAccepted[0],"retiredImeIgnored",true,"retiredSaveIgnored",true,"retiredRouteIgnored",true,"retiredSelectionIgnored",true,"filteredRowIgnored",true,"currentImeWorked",true,"currentSaveWorked",true,"currentRouteWorked",true,"currentSelectWorked",true,"currentRowWorked",true,"sourceBitrate",original,"peerSavedBitrate",wanted));
            }
            RhShared.write(new File(folder,"form-widgets-result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"passed",true,"cases",cases,"nativeInputConnections",true,"hostOperations",false,"webdavOperations",false,"systemInputInjected",false));
        }
    }
}
