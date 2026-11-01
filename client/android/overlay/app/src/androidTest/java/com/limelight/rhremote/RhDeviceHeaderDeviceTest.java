package com.limelight.rhremote;

import android.app.*;
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

/** Attached workspace navigation, scroll and unsaved choices. No remote or system input. */
@RunWith(AndroidJUnit4.class)
public final class RhDeviceHeaderDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final JSONArray cases=new JSONArray();
    final String a="pinned-header-a",b="pinned-header-b";
    RhDeviceBrowser browser;ScrollView detail;
    Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    ComputerDetails device(String uuid){ComputerDetails d=new ComputerDetails();d.uuid=uuid;d.name=uuid.equals(a)?"winserver":"Claw";d.manualAddress=new ComputerDetails.AddressTuple(uuid.equals(a)?"192.0.2.182":"192.0.2.183",47989);return d;}
    void settle()throws Exception{
        long until=SystemClock.uptimeMillis()+3000;boolean[] requested={true};
        do{rig.instrument.waitForIdleSync();rig.main(()->requested[0]=rig.activity.findViewById(android.R.id.content).isLayoutRequested()||(browser.isShown()&&browser.isLayoutRequested())||(detail.isShown()&&detail.isLayoutRequested()));if(!requested[0])return;Thread.sleep(10);}while(SystemClock.uptimeMillis()<until);
        fail("Workspace layout did not settle");
    }
    Rect location(View v){int[] p=new int[2];v.getLocationInWindow(p);return new Rect(p[0],p[1],p[0]+v.getWidth(),p[1]+v.getHeight());}
    boolean full(View v){Rect r=new Rect();return v.isShown()&&v.getGlobalVisibleRect(r)&&r.width()==v.getWidth()&&r.height()==v.getHeight();}
    int scroll(){int[] y={-1};rig.main(()->y[0]=detail.getScrollY());return y[0];}
    void click(View view){rig.main(()->{assertTrue("Hidden control",full(view));assertTrue(view.isEnabled());assertTrue(view.performClick());});rig.instrument.waitForIdleSync();}
    Button navigationChoice(int which)throws Exception{Button[] button={null};rig.main(()->{AlertDialog dialog=(AlertDialog)field(rig.home,"navigationDialog");assertNotNull(dialog);assertTrue(dialog.isShowing());button[0]=dialog.getButton(which);});return button[0];}
    void action(String id,JSONObject args)throws Exception{rig.action(id,args);settle();}
    void settings()throws Exception{View[] view={null};rig.main(()->view[0]=(View)field(browser,"settings"));click(view[0]);settle();assertEquals("settings",rig.state().getString("page"));}
    void scenario(String name,boolean dark,int width,int height,int font)throws Exception{
        rig.main(()->{
            rig.context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
            RhShared.capture(rig.context,Arrays.asList(device(a),device(b)));rig.home=new RhHome(rig.activity,rig.actions);rig.home.update(device(a));rig.home.update(device(b));
            browser=(RhDeviceBrowser)field(rig.home,"browser");detail=(ScrollView)field(browser,"detail");
        });
        action("device.select",RhActions.obj("uuid",a));JSONObject row=RhActions.obj("name",name,"widthDp",width,"heightDp",height,"font",font,"dark",dark);
        Rect[] titleTop={null},settingsTop={null};
        rig.main(()->{titleTop[0]=location((View)field(browser,"title"));settingsTop[0]=location((View)field(browser,"settings"));});rig.capture(name+"-top");
        rig.main(()->detail.scrollTo(0,detail.getChildAt(0).getHeight()));settle();int bottom=scroll();assertTrue("Need scrolling content",bottom>0);
        rig.main(()->{
            View title=(View)field(browser,"title"),settings=(View)field(browser,"settings");
            row.put("titleFixed",titleTop[0].equals(location(title)));row.put("settingsFixed",settingsTop[0].equals(location(settings)));row.put("navigationVisible",full(title)&&full(settings));row.put("bodyHeightDp",detail.getHeight()/rig.activity.getResources().getDisplayMetrics().density);row.put("scrollY",detail.getScrollY());row.put("scrollbarWidth",detail.getVerticalScrollbarWidth());
        });rig.capture(name+"-bottom");
        rig.main(()->rig.home.update(device(a)));settle();assertEquals("Same-device refresh moved the body",bottom,scroll());row.put("sameDeviceRefreshRetainsScroll",true);
        boolean pinned=row.getBoolean("titleFixed")&&row.getBoolean("settingsFixed")&&row.getBoolean("navigationVisible");
        if(pinned){
            assertTrue("Short landscape body is unusable: "+row,row.getDouble("bodyHeightDp")>=96);
            settings();assertEquals(a,rig.state().getString("selected"));rig.capture(name+"-settings");
            int initial=rig.state().getJSONObject("draft").getInt("volume"),changed=initial==51?52:51;
            action("settings.set",RhActions.obj("key","volume","value",changed));assertTrue(rig.state().getBoolean("settingsDirty"));
            action("device.select",RhActions.obj("uuid",b));assertFalse(rig.state().isNull("navigationPrompt"));assertEquals(a,rig.state().getString("selected"));
            click(navigationChoice(AlertDialog.BUTTON_NEUTRAL));settle();assertEquals(changed,rig.state().getJSONObject("draft").getInt("volume"));assertEquals(a,rig.state().getString("selected"));
            action("device.select",RhActions.obj("uuid",b));click(navigationChoice(AlertDialog.BUTTON_NEGATIVE));settle();assertEquals(b,rig.state().getString("selected"));assertEquals(initial,RhProfile.load(rig.context,a).volume);
            action("device.select",RhActions.obj("uuid",a));settings();action("settings.set",RhActions.obj("key","volume","value",changed));
            action("device.select",RhActions.obj("uuid",b));click(navigationChoice(AlertDialog.BUTTON_POSITIVE));settle();assertEquals(b,rig.state().getString("selected"));assertEquals(changed,RhProfile.load(rig.context,a).volume);
            action("device.select",RhActions.obj("uuid",a));assertEquals(0,scroll());
            if(browser.isDetailOpen()){View[] back={null};rig.main(()->back[0]=(View)field(browser,"back"));click(back[0]);settle();assertEquals("list",rig.state().getJSONObject("homeLayout").getJSONObject("browser").getString("mode"));action("device.select",RhActions.obj("uuid",a));}
            row.put("settingsFromBottom",true);row.put("dirtyStayDiscardSave",true);row.put("newSelectionStartsAtTop",true);rig.capture(name+"-returned");
        }
        cases.put(row);RhShared.write(new File(rig.folder,"cases-progress.json"),RhActions.obj("cases",cases));
    }
    @Test public void navigationRemainsVisibleAndDraftChoicesUseTheSelectedDevice()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("deviceHeaderRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        rig.context=rig.instrument.getTargetContext();assertEquals("com.rhremote.client.uicheck172",rig.context.getPackageName());assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());
        try{
            for(int[] size:new int[][]{{360,640},{640,360},{1008,640}})for(int font:new int[]{1,2})for(boolean dark:new boolean[]{false,true}){
                rig.main(()->RhUiFixtureActivity.fontScale(font));rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(size[0]*density),Math.round(size[1]*density));});
                scenario((dark?"dark":"light")+"-"+size[0]+"x"+size[1]+"-font"+font,dark,size[0],size[1],font);
                rig.main(()->{rig.home.dispose();rig.home=null;rig.activity.finish();rig.activity=null;});rig.instrument.waitForIdleSync();
            }
        }finally{rig.main(()->{if(rig.home!=null)rig.home.dispose();if(rig.activity!=null)rig.activity.finish();RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();}
        RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("version",rig.context.getPackageManager().getPackageInfo(rig.context.getPackageName(),0).versionName,"cases",cases,"scope","Attached own RhHome; own View.draw and own button.performClick; no OS screenshot/input, service or remote operations"));
        assertEquals(12,cases.length());for(int i=0;i<cases.length();i++){JSONObject r=cases.getJSONObject(i);assertTrue(r.toString(),r.getBoolean("titleFixed")&&r.getBoolean("settingsFixed")&&r.getBoolean("navigationVisible"));assertTrue(r.toString(),r.optBoolean("dirtyStayDiscardSave"));}
    }
}
