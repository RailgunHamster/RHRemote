package com.limelight.rhremote;

import android.content.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual category buttons, field editing, saving and responsive layout in an owned Activity. */
@RunWith(AndroidJUnit4.class)
public final class RhSettingsViewportDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final JSONArray pages=new JSONArray(),flows=new JSONArray();final String uuid="settings-viewport-183";
    Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    void settle()throws Exception{
        long until=SystemClock.uptimeMillis()+3000;boolean[] pending={true};
        do{rig.instrument.waitForIdleSync();rig.main(()->pending[0]=rig.activity.findViewById(android.R.id.content).isLayoutRequested());if(!pending[0])return;Thread.sleep(10);}while(SystemClock.uptimeMillis()<until);fail("Active root did not settle");
    }
    boolean full(View v){Rect rect=new Rect();return v!=null&&v.getWidth()>0&&v.getHeight()>0&&v.isShown()&&v.getGlobalVisibleRect(rect)&&rect.width()==v.getWidth()&&rect.height()==v.getHeight();}
    View member(String name)throws Exception{View[] v={null};rig.main(()->v[0]=(View)field(rig.home,name));return v[0];}
    void reveal(View v)throws Exception{rig.main(()->v.requestRectangleOnScreen(new Rect(0,0,v.getWidth(),v.getHeight()),true));settle();}
    void click(View v)throws Exception{rig.main(()->{assertTrue("Control clipped",full(v));assertTrue(v.isEnabled());assertTrue(v.performClick());});settle();}
    void action(String id,JSONObject args)throws Exception{rig.action(id,args);settle();}
    void tab(String action,String id)throws Exception{View header=member("formHeader");View v=header.findViewWithTag("category:"+action+":"+id);assertNotNull(id,v);reveal(v);click(v);}
    EditText firstEditor(View root){if(root instanceof EditText&&root.isEnabled())return (EditText)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){EditText found=firstEditor(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    boolean snapshot(String name,String page,String tab)throws Exception{
        JSONObject row=RhActions.obj("name",name,"page",page,"tab",tab);boolean[] good={false};
        rig.main(()->{ScrollView body=(ScrollView)field(rig.home,"scroll");View footer=(View)field(rig.home,"formFooter");View primary=page.equals("settings")?(View)field(rig.home,"formSave"):tab.equals("updates")?(View)field(rig.home,"updatePrimary"):null;
            float density=rig.activity.getResources().getDisplayMetrics().density;double height=body.getHeight()/density;boolean primaryVisible=primary==null||full(primary);
            good[0]=height>=96&&primaryVisible;row.put("bodyHeightDp",height);row.put("footerHeightDp",footer.isShown()?footer.getHeight()/density:0);row.put("primaryFullyVisible",primaryVisible);row.put("usable",good[0]);row.put("railVisible",((View)field(rig.home,"sidebar")).isShown());row.put("bottomBarVisible",((View)field(rig.home,"bar")).isShown());
        });
        if(good[0]){
            EditText[] editor={null};rig.main(()->editor[0]=firstEditor((View)field(rig.home,"content")));
            if(editor[0]!=null){reveal(editor[0]);rig.main(()->assertTrue("Input clipped: "+name,full(editor[0])));row.put("firstEditorFullyVisible",true);}
        }
        pages.put(row);rig.capture(name);RhShared.write(new File(rig.folder,"pages-progress.json"),RhActions.obj("pages",pages,"flows",flows));return good[0];
    }
    void scenario(String name,int width,int height,int font,boolean dark)throws Exception{
        rig.main(()->{rig.context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();ComputerDetails d=new ComputerDetails();d.uuid=uuid;d.name="winserver";d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.183",47989);RhShared.capture(rig.context,Collections.singletonList(d));rig.home=new RhHome(rig.activity,rig.actions);rig.home.update(d);});
        action("device.settings.open",RhActions.obj("uuid",uuid));boolean all=true;
        for(String category:new String[]{"video","audio","input","network","advanced"}){tab("settings.tab",category);all&=snapshot(name+"-"+category,"settings",category);}
        if(all){
            tab("settings.tab","audio");EditText[] editor={null};rig.main(()->editor[0]=(EditText)((Map<?,?>)field(rig.home,"numberEditors")).get("volume"));assertNotNull(editor[0]);reveal(editor[0]);int previous=rig.state().getJSONObject("draft").getInt("volume"),changed=previous==51?52:51;
            rig.main(()->{assertTrue(full(editor[0]));editor[0].setText(Integer.toString(changed));});settle();assertTrue(rig.state().getBoolean("settingsDirty"));String draft=rig.state().getJSONObject("draft").toString();
            for(int resized:new int[]{800,360,640}){rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(resized*density),Math.round(640*density));});settle();assertEquals(draft,rig.state().getJSONObject("draft").toString());assertEquals("audio",rig.state().getString("profileTab"));rig.main(()->assertSame("Resize replaced an active editor",editor[0],((Map<?,?>)field(rig.home,"numberEditors")).get("volume")));assertTrue(snapshot(name+"-resize"+resized,"settings","audio"));}
            rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*density),Math.round(height*density));});settle();click(member("formSave"));assertEquals(changed,RhProfile.load(rig.context,uuid).volume);
            flows.put(RhActions.obj("name",name,"actualEditAndSave",true,"sameEditorAndDraftAfterResizes",true,"savedVolume",changed));
        }else action("settings.cancel",RhActions.obj());
        action("app.settings.open",RhActions.obj());for(String category:new String[]{"updates","sync","appearance","about"}){tab("app.settings.tab",category);snapshot(name+"-"+category,"global-settings",category);}
        // The lower rail destinations remain reachable even when the content is taller than the window.
        rig.main(()->{View rail=(View)field(rig.home,"sidebar");if(rail.isShown()){View settings=rail.findViewWithTag("nav:app.settings.open");settings.requestRectangleOnScreen(new Rect(0,0,settings.getWidth(),settings.getHeight()),true);}});settle();
        rig.main(()->{View rail=(View)field(rig.home,"sidebar");if(rail.isShown())assertTrue(full(rail.findViewWithTag("nav:app.settings.open")));});
    }
    @Test public void allSettingsPagesKeepAViewportAndRealEditSaveWorksAcrossSizes()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("settingsViewportRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));rig.context=rig.instrument.getTargetContext();assertEquals("com.rhremote.client.uicheck172",rig.context.getPackageName());assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());
        try{for(int[] size:new int[][]{{360,640},{640,360}})for(int font:new int[]{1,2})for(boolean dark:new boolean[]{false,true}){
            rig.main(()->RhUiFixtureActivity.fontScale(font));rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(size[0]*density),Math.round(size[1]*density));});
            scenario((dark?"dark":"light")+"-"+size[0]+"x"+size[1]+"-font"+font,size[0],size[1],font,dark);rig.main(()->{rig.home.dispose();rig.home=null;rig.activity.finish();rig.activity=null;});rig.instrument.waitForIdleSync();
        }}finally{rig.main(()->{if(rig.home!=null)rig.home.dispose();if(rig.activity!=null)rig.activity.finish();RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();}
        RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("version",rig.context.getPackageManager().getPackageInfo(rig.context.getPackageName(),0).versionName,"pages",pages,"flows",flows,"scope","Own attached RhHome, category buttons, real EditText/TextWatcher and save; View.draw only; no OS input, remote hosts or WebDAV"));
        assertEquals(72+3*flows.length(),pages.length());for(int i=0;i<pages.length();i++)assertTrue(pages.getJSONObject(i).toString(),pages.getJSONObject(i).getBoolean("usable"));assertEquals(8,flows.length());
    }
}
