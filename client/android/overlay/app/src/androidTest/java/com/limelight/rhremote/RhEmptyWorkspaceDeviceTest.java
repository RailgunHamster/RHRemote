package com.limelight.rhremote;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** User-visible recovery controls; no sockets, native discovery, pairing or remote input. */
@RunWith(AndroidJUnit4.class)
public final class RhEmptyWorkspaceDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();final JSONArray cases=new JSONArray();String runId;
    ComputerDetails device(String id){ComputerDetails d=new ComputerDetails();d.uuid=id;d.name=id.endsWith("alpha")?"Fixture Alpha":"Fixture Beta";d.manualAddress=new ComputerDetails.AddressTuple(id.endsWith("alpha")?"192.0.2.1":"192.0.2.2",47989);d.state=ComputerDetails.State.OFFLINE;return d;}
    View find(View v,String text){if(!v.isShown())return null;if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    Button button(String text){Button[] button={null};rig.main(()->{button[0]=(Button)find(rig.activity.findViewById(android.R.id.content),text);assertNotNull(text,button[0]);});return button[0];}
    void click(Button button,String mode)throws Exception{rig.visible(button);rig.main(()->{assertTrue(button.isEnabled());long now=SystemClock.uptimeMillis();rig.down(button,mode,now);rig.up(button,mode,now);});rig.instrument.waitForIdleSync();}
    void capture(String name,JSONArray stages)throws Exception{rig.instrument.waitForIdleSync();rig.capture(name);JSONObject state=rig.state();stages.put(RhActions.obj("image",name+".png","page",state.getString("page"),"query",state.getString("deviceQuery"),"selected",state.getString("selected"),"deviceCount",state.getJSONArray("devices").length(),"settingsDirty",state.getBoolean("settingsDirty")));}
    void scenario(boolean dark,int width,int font)throws Exception{
        String name=(dark?"dark":"light")+"-"+width+"-font"+font,mode=width==360?"touch":"keyboard",prefix=runId+"-"+name+"-";JSONArray stages=new JSONArray();
        rig.main(()->{if(rig.home!=null)rig.home.dispose();JSONObject empty=RhShared.read(rig.context);empty.put("devices",new JSONArray()).put("dark",dark);RhShared.apply(rig.context,empty);rig.context.getSharedPreferences("rhremote.global",0).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();rig.home=new RhHome(rig.activity,rig.actions);rig.home.resumed();});rig.instrument.waitForIdleSync();assertEquals(dark,RhShared.dark(rig.context));
        rig.action("devices.filter",RhActions.obj("query","leftover search"));Button add=button("添加电脑");rig.visible(add);rig.main(()->assertNull(find(rig.activity.findViewById(android.R.id.content),"清除搜索")));capture(name+"-first-use",stages);click(add,mode);assertEquals("add",rig.state().getString("page"));capture(name+"-add",stages);rig.action("device.add.cancel",RhActions.obj());
        ComputerDetails alpha=device(prefix+"alpha"),beta=device(prefix+"beta");rig.main(()->{RhShared.capture(rig.context,Arrays.asList(alpha,beta));rig.home.update(alpha);rig.home.update(beta);});rig.action("device.select",RhActions.obj("uuid",alpha.uuid));rig.action("devices.open",RhActions.obj());String saved=RhShared.canonical(RhShared.read(rig.context));
        String query="no matching device";rig.action("devices.filter",RhActions.obj("query",query));Button clear=button("清除搜索");rig.visible(clear);capture(name+"-no-results",stages);
        long held=SystemClock.uptimeMillis();rig.main(()->rig.down(clear,mode,held));rig.action("devices.filter",RhActions.obj("query","new missing device"));rig.main(()->rig.up(clear,mode,held));rig.instrument.waitForIdleSync();assertEquals("new missing device",rig.state().getString("deviceQuery"));
        rig.main(()->{JSONObject rejected=RhActions.dispatch("devices.filter.clear",RhActions.obj("expectedQuery",query));assertFalse(rejected.toString(),rejected.optBoolean("ok"));});click(button("清除搜索"),mode);assertEquals("",rig.state().getString("deviceQuery"));assertEquals(alpha.uuid,rig.state().getString("selected"));assertEquals(saved,RhShared.canonical(RhShared.read(rig.context)));capture(name+"-cleared",stages);
        rig.action("devices.filter",RhActions.obj("query",query));final Button hidden=button("清除搜索");rig.action("devices.filter",RhActions.obj("query","Fixture"));rig.main(()->hidden.performClick());assertEquals("Fixture",rig.state().getString("deviceQuery"));assertEquals(2,rig.state().getJSONArray("devices").length());assertFalse(rig.state().getJSONArray("devices").getJSONObject(0).getBoolean("online"));capture(name+"-offline-devices",stages);
        if(width>700){rig.action("device.settings.open",RhActions.obj("uuid",alpha.uuid));rig.action("settings.input",RhActions.obj("key","volume","value","77"));rig.action("devices.filter",RhActions.obj("query",query));click(button("清除搜索"),mode);assertEquals("settings",rig.state().getString("page"));assertTrue(rig.state().getBoolean("settingsDirty"));assertEquals("77",rig.state().getJSONObject("settingsInputs").getString("volume"));assertEquals(saved,RhShared.canonical(RhShared.read(rig.context)));capture(name+"-draft-preserved",stages);rig.action("settings.cancel",RhActions.obj());}
        assertFalse(RhActions.hasStream());cases.put(RhActions.obj("name",name,"widthDp",width,"fontScale",font,"actualDark",RhShared.dark(rig.context),"dark",dark,"mode",mode,"stages",stages,"staleClearRejected",true,"hiddenClearRejected",true,"selectionAndProfilesPreserved",true));
    }
    @Test public void firstUseAndNoResultsOfferDifferentActionsAndPreserveContext()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("emptyWorkspaceRun","");org.junit.Assume.assumeFalse("Dedicated opt-in required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,50}"));runId=run;rig.context=rig.instrument.getTargetContext();assertTrue(rig.context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());
        try{for(int width:new int[]{360,1008})for(int font:new int[]{1,2}){
            rig.main(()->RhUiFixtureActivity.fontScale(font));rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));rig.main(()->{assertEquals((float)font,rig.activity.getResources().getConfiguration().fontScale,0f);float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*density),Math.round((width==360?560:640)*density));});rig.instrument.waitForIdleSync();for(boolean dark:new boolean[]{false,true})scenario(dark,width,font);rig.main(()->{rig.home.dispose();rig.home=null;rig.activity.finish();});rig.instrument.waitForIdleSync();
        }}finally{rig.main(()->{if(rig.home!=null)rig.home.dispose();if(rig.activity!=null)rig.activity.finish();RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();}
        assertEquals(8,cases.length());RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("scope","Actual Home controls with owned View touch/key events; fixture devices and app-scoped font settings; no network or remote host","cases",cases,"oppoOperated",false,"osScreenshots",false));
    }
}
