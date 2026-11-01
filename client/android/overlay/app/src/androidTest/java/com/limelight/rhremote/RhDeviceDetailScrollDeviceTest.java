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

/** Real workspace selection and scrolling, without starting network or remote input. */
@RunWith(AndroidJUnit4.class)
public final class RhDeviceDetailScrollDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final JSONArray cases=new JSONArray();
    final String a="detail-scroll-a",b="detail-scroll-b";
    RhDeviceBrowser browser;ScrollView detail;

    Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    ComputerDetails device(String uuid){ComputerDetails d=new ComputerDetails();d.uuid=uuid;d.name=uuid.equals(a)?"设备 A":"设备 B";d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.163",47989);return d;}
    void settle()throws Exception{
        long deadline=SystemClock.uptimeMillis()+3000;boolean[] requested={true};
        // A GONE detail pane is intentionally not measured while the narrow list is shown.
        do{rig.instrument.waitForIdleSync();rig.main(()->requested[0]=rig.activity.findViewById(android.R.id.content).isLayoutRequested()||browser.isLayoutRequested()||(detail.isShown()&&detail.isLayoutRequested()));if(!requested[0])return;Thread.sleep(10);}while(SystemClock.uptimeMillis()<deadline);
        fail("Workspace layout did not settle");
    }
    int scrollY(){int[] value={-1};rig.main(()->value[0]=detail.getScrollY());return value[0];}
    void select(String uuid,boolean actualRow)throws Exception{
        if(!actualRow){rig.action("device.select",RhActions.obj("uuid",uuid));settle();return;}
        if(rig.state().getJSONObject("homeLayout").getJSONObject("browser").optString("mode").equals("detail"))rig.action("navigation.back",RhActions.obj());
        settle();View[] target={null};rig.main(()->{target[0]=(View)((Map<?,?>)field(browser,"rows")).get(uuid);assertNotNull(target[0]);assertTrue(target[0].isShown());target[0].requestRectangleOnScreen(new Rect(0,0,target[0].getWidth(),target[0].getHeight()),true);});
        settle();rig.main(()->{Rect rect=new Rect();assertTrue(target[0].getGlobalVisibleRect(rect));assertEquals(target[0].getHeight(),rect.height());assertTrue(target[0].performClick());});settle();
    }
    void scenario(String name,int width,int font,boolean dark)throws Exception{
        rig.main(()->{
            rig.context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
            RhShared.capture(rig.context,Arrays.asList(device(a),device(b)));rig.home=new RhHome(rig.activity,rig.actions);rig.home.resumed();rig.home.update(device(a));rig.home.update(device(b));
            browser=(RhDeviceBrowser)field(rig.home,"browser");detail=(ScrollView)field(browser,"detail");
        });
        for(boolean actualRow:new boolean[]{false,true}){
            select(a,actualRow);settle();rig.main(()->detail.scrollTo(0,detail.getChildAt(0).getHeight()));settle();int before=scrollY();assertTrue("Fixture must have scrollable details",before>0);
            rig.main(()->rig.home.update(device(a)));settle();int same=scrollY();assertEquals("Background refresh moved the current device",before,same);
            rig.capture(name+"-"+(actualRow?"row":"api")+"-a-bottom");select(b,actualRow);settle();int changed=scrollY();
            assertEquals(b,rig.state().getString("selected"));rig.capture(name+"-"+(actualRow?"row":"api")+"-b-selected");
            cases.put(RhActions.obj("name",name,"input",actualRow?"actual-row":"shared-action","before",before,"afterSameDeviceRefresh",same,"afterDifferentDeviceSelection",changed,"topAfterSelection",changed==0,"sameBrowser",browser==field(rig.home,"browser"),"widthDp",width,"fontScale",font,"dark",dark));
            RhShared.write(new File(rig.folder,"cases-progress.json"),RhActions.obj("cases",cases));
        }
    }
    @Test public void differentDeviceOpensItsOverviewButBackgroundRefreshKeepsPosition()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("deviceDetailScrollRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        rig.context=rig.instrument.getTargetContext();assertTrue(rig.context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());
        try{
            for(int width:new int[]{360,1008})for(int font:new int[]{1,2})for(boolean dark:new boolean[]{false,true}){
                rig.main(()->RhUiFixtureActivity.fontScale(font));rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*density),Math.round(640*density));});
                scenario((dark?"dark":"light")+"-"+width+"-font"+font,width,font,dark);
                rig.main(()->{rig.home.dispose();rig.home=null;rig.activity.finish();rig.activity=null;});rig.instrument.waitForIdleSync();
            }
        }finally{rig.main(()->{if(rig.home!=null)rig.home.dispose();if(rig.activity!=null)rig.activity.finish();RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();}
        boolean passed=true;for(int i=0;i<cases.length();i++)passed&=cases.getJSONObject(i).getBoolean("topAfterSelection");
        RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"package",rig.context.getPackageName(),"passed",passed,"cases",cases,"scope","Actual RhHome, native ScrollView, actual row/shared action; no pairing or streaming","oppoOperated",false,"osInputInjected",false));
        assertEquals(16,cases.length());for(int i=0;i<cases.length();i++){JSONObject row=cases.getJSONObject(i);assertTrue(row.toString(),row.getBoolean("sameBrowser"));assertEquals("A different device retained the previous device scroll: "+row,0,row.getInt("afterDifferentDeviceSelection"));}
    }
}
