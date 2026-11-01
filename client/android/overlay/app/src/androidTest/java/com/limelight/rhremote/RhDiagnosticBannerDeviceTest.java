package com.limelight.rhremote;

import android.content.*;
import android.os.StrictMode;
import android.os.strictmode.Violation;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native disk-read observation with live diagnostic ownership and actual cancellation. */
@RunWith(AndroidJUnit4.class)
public final class RhDiagnosticBannerDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final JSONArray observations=new JSONArray();
    final List<JSONObject> hidden=new ArrayList<>(),visible=new ArrayList<>(),controls=new ArrayList<>();
    Method refresh;

    JSONObject observe(String name,boolean shown,boolean positiveControl)throws Exception {
        List<Violation> violations=Collections.synchronizedList(new ArrayList<>());
        StrictMode.ThreadPolicy[] previous={null};
        try {rig.main(()->{
            View banner=rig.activity.findViewById(android.R.id.content).findViewWithTag("diagnostic.banner");
            assertEquals(name,shown?View.VISIBLE:View.GONE,banner.getVisibility());
            previous[0]=StrictMode.getThreadPolicy();
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectDiskReads()
                .penaltyListener(Runnable::run,violations::add).build());
            if(positiveControl)RhShared.read(rig.context);else refresh.invoke(rig.home);
        });
        // Android delivers main-thread violations after the current message. Keep the
        // listener installed until then; restoring it inline loses every callback.
        rig.instrument.waitForIdleSync();
        }finally {rig.main(()->{if(previous[0]!=null)StrictMode.setThreadPolicy(previous[0]);});rig.instrument.waitForIdleSync();}
        JSONArray stacks=new JSONArray();int sharedReads=0;
        synchronized(violations){for(Violation violation:violations){
            boolean shared=false;JSONArray stack=new JSONArray();
            for(StackTraceElement frame:violation.getStackTrace()){
                if(frame.getClassName().equals(RhShared.class.getName())&&frame.getMethodName().equals("read"))shared=true;
                stack.put(frame.toString());
            }
            if(shared)sharedReads++;
            stacks.put(RhActions.obj("type",violation.getClass().getSimpleName(),"sharedConfigurationRead",shared,"frames",stack));
        }}
        JSONObject record=RhActions.obj("name",name,"bannerVisible",shown,"positiveControl",positiveControl,
            "diskReadViolations",violations.size(),"sharedConfigurationReads",sharedReads,"stacks",stacks);
        observations.put(record);(positiveControl?controls:shown?visible:hidden).add(record);return record;
    }
    void assertAlias(String alias){rig.main(()->{
        android.view.ViewGroup banner=rig.activity.findViewById(android.R.id.content).findViewWithTag("diagnostic.banner");
        assertEquals(View.VISIBLE,banner.getVisibility());
        assertTrue(((TextView)banner.getChildAt(0)).getText().toString(),((TextView)banner.getChildAt(0)).getText().toString().endsWith(" · "+alias));
    });}
    void scenario(RhDiagnosticCancellationDeviceTest.Server server,boolean dark,int width,int font)throws Exception {
        String prefix=(dark?"dark":"light")+"-"+width+"-font"+font,uuid="banner-native-host";
        rig.main(()->{
            rig.context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
            rig.home=new RhHome(rig.activity,rig.actions);rig.home.resumed();
        });
        rig.instrument.waitForIdleSync();observe(prefix+"-idle",false,false);observe(prefix+"-observer-control",false,true);
        ComputerDetails target=new ComputerDetails();target.uuid=uuid;target.name="原设备名称";target.manualAddress=new ComputerDetails.AddressTuple("127.0.0.8",47989);
        rig.main(()->{
            RhShared.capture(rig.context,Collections.singletonList(target));RhShared.saveDisplayName(rig.context,target,"原别名");rig.home.update(target);
            rig.home.perform("device.settings.open",RhActions.obj("uuid",uuid));rig.home.perform("settings.tab",RhActions.obj("tab","network"));
            rig.home.perform("settings.set",RhActions.obj("key","routeOrder","value",new JSONArray().put("lan")));
        });
        rig.instrument.waitForIdleSync();observe(prefix+"-profile-idle",false,false);
        RhDiagnosticCancellationDeviceTest.Plan plan=new RhDiagnosticCancellationDeviceTest.Plan(uuid,false);server.plans.add(plan);rig.clickPrimary();assertTrue(plan.entered.await(2,TimeUnit.SECONDS));
        assertTrue(rig.state().getBoolean("networkBusy"));observe(prefix+"-profile-running",false,false);
        rig.main(()->RhShared.saveDisplayName(rig.context,target,"隐藏期间改名"));
        rig.action("settings.cancel",RhActions.obj());rig.action("app.settings.tab",RhActions.obj("tab","about"));
        assertAlias("隐藏期间改名");observe(prefix+"-about-running",true,false);rig.capture(prefix+"-visible");
        rig.main(()->RhShared.saveDisplayName(rig.context,target,"显示期间改名"));observe(prefix+"-about-renamed",true,false);assertAlias("显示期间改名");
        android.widget.Button cancel=rig.button("diagnostic.banner.cancel");rig.visible(cancel);
        rig.main(()->{assertTrue(cancel.isEnabled());assertTrue(cancel.performClick());});rig.idle();
        assertTrue(plan.closed.await(1,TimeUnit.SECONDS));assertTrue(plan.peerClosed);
        assertEquals("cancelled",rig.state().getJSONObject("diagnostics").getString("phase"));observe(prefix+"-cancelled",false,false);
        rig.main(()->assertFalse(cancel.isEnabled()));assertFalse(RhActions.inspecting());assertNull(server.failure.get());
    }
    @Test public void hiddenBannerAvoidsSharedConfigReadsButVisibleStateStaysCurrent()throws Exception {
        String run=InstrumentationRegistry.getArguments().getString("diagnosticBannerRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        rig.context=rig.instrument.getTargetContext();assertTrue(rig.context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));
        assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());
        refresh=RhHome.class.getDeclaredMethod("refreshDiagnosticBanner");refresh.setAccessible(true);
        try(RhDiagnosticCancellationDeviceTest.Server server=new RhDiagnosticCancellationDeviceTest.Server()){
            for(int index=0;index<2;index++){
                final int width=index==0?360:1008,font=index==0?1:2;final boolean dark=index==1;
                rig.main(()->RhUiFixtureActivity.fontScale(font));
                rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*density),Math.round(640*density));});rig.instrument.waitForIdleSync();
                scenario(server,dark,width,font);
                rig.main(()->{rig.home.dispose();rig.home=null;rig.activity.finish();rig.activity=null;});rig.instrument.waitForIdleSync();
            }
        }finally{
            rig.main(()->{if(rig.home!=null){JSONObject work=rig.home.state().getJSONObject("diagnostics");if(work.optBoolean("busy"))rig.home.perform("device.network.cancel",RhActions.obj("operationId",work.optLong("operationId")));rig.home.dispose();}if(rig.activity!=null)rig.activity.finish();RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();
        }
        boolean clean=true;for(JSONObject row:hidden)clean&=row.getInt("sharedConfigurationReads")==0;
        RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"package",rig.context.getPackageName(),
            "passed",clean,"scope","Actual main-thread StrictMode disk reads; reflection invokes the existing banner refresh; real loopback diagnostic and actual cancellation button",
            "observations",observations,"hiddenObservations",hidden.size(),"visibleObservations",visible.size(),"positiveControls",controls.size(),
            "aliasesUpdatedWhileHiddenAndVisible",true,"actualCancellationClosedSocket",true,"remoteServicesContacted",false,"oppoOperated",false,"osInputInjected",false));
        assertEquals(8,hidden.size());assertEquals(4,visible.size());assertEquals(2,controls.size());
        for(JSONObject row:controls)assertTrue("Disk-read observer must detect the positive control: "+row,row.getInt("sharedConfigurationReads")>0);
        for(JSONObject row:visible)assertTrue("Visible banner must read the current alias: "+row,row.getInt("sharedConfigurationReads")>0);
        for(JSONObject row:hidden)assertEquals("Hidden banner reread full shared configuration: "+row,0,row.getInt("sharedConfigurationReads"));
    }
}
