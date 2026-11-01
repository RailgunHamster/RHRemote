package com.limelight.rhremote;

import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Proves that an app-local visibility observer uses completed, current layout geometry. */
@RunWith(AndroidJUnit4.class)
public final class RhDeferredVisibilityDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();final JSONArray cases=new JSONArray();
    Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    void settle()throws Exception{long end=SystemClock.uptimeMillis()+2500;boolean[] ready={false};do{rig.instrument.waitForIdleSync();rig.main(()->{View root=rig.activity.findViewById(android.R.id.content);ready[0]=root.getWidth()>0&&!root.isLayoutRequested();});if(ready[0])return;Thread.sleep(10);}while(SystemClock.uptimeMillis()<end);fail("Layout did not settle");}
    boolean fully(Button button){Rect bounds=new Rect();return button.getWidth()>0&&button.getHeight()>0&&button.getGlobalVisibleRect(bounds)&&bounds.width()==button.getWidth()&&bounds.height()==button.getHeight();}
    void scenario(String kind,boolean dark)throws Exception{
        String name=kind+"-"+(dark?"dark":"light"),uuid="deferred-visibility";AtomicLong released=new AtomicLong();Button[] primary={null};ViewGroup[] gate={null};JSONObject[] initial={null};
        rig.main(()->{rig.context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();RhUiFixtureActivity.fontScale(2);});
        rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        rig.main(()->{
            float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round((kind.equals("resize")?1008:360)*density),Math.round((kind.equals("resize")?640:400)*density));
            gate[0]=rig.activity.findViewById(android.R.id.content);if(kind.equals("first-layout"))gate[0].suppressLayout(true);
            ComputerDetails target=new ComputerDetails();target.uuid=uuid;target.name="布局测试设备";target.manualAddress=new ComputerDetails.AddressTuple("192.0.2.19",47989);RhShared.capture(rig.context,Collections.singleton(target));rig.home=new RhHome(rig.activity,rig.actions);rig.home.resumed();rig.home.update(target);rig.home.perform("device.select",RhActions.obj("uuid",uuid));primary[0]=(Button)field(field(rig.home,"browser"),"connect");
        });
        if(kind.equals("resize")){settle();rig.main(()->{gate[0].suppressLayout(true);float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(360*density),Math.round(400*density));gate[0].requestLayout();});}
        rig.main(()->{initial[0]=RhActions.obj("width",primary[0].getWidth(),"height",primary[0].getHeight(),"layoutRequested",gate[0].isLayoutRequested(),"visible",fully(primary[0]));new Handler(Looper.getMainLooper()).postDelayed(()->{released.set(SystemClock.uptimeMillis());gate[0].suppressLayout(false);gate[0].requestLayout();},120);});
        long began=SystemClock.uptimeMillis();boolean accepted=true;String error="";try{rig.visible(primary[0]);}catch(AssertionError failure){accepted=false;error=failure.getMessage();}long returned=SystemClock.uptimeMillis();
        // Observe the actual final frame even when the old helper accepted stale geometry.
        while(released.get()==0&&SystemClock.uptimeMillis()-began<2500)Thread.sleep(5);settle();boolean[] finalVisible={false};rig.main(()->finalVisible[0]=fully(primary[0]));rig.capture(name);
        cases.put(RhActions.obj("name",name,"initial",initial[0],"helperAccepted",accepted,"helperError",error,"beganMs",began,"releasedMs",released.get(),"returnedMs",returned,"returnedAfterRelease",returned>=released.get(),"finallyFullyVisible",finalVisible[0],"noNetworkRequests",true));
        rig.main(()->{gate[0].suppressLayout(false);rig.home.dispose();rig.home=null;rig.activity.finish();rig.activity=null;});rig.instrument.waitForIdleSync();
    }
    @Test public void pendingFirstLayoutAndResizeCannotUseStaleButtonGeometry()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("deferredVisibilityRun","");org.junit.Assume.assumeFalse("Dedicated opt-in required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));rig.context=rig.instrument.getTargetContext();assertTrue(rig.context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));assertTrue(Build.VERSION.SDK_INT>=29);rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());
        try{for(String kind:new String[]{"first-layout","resize"})for(boolean dark:new boolean[]{false,true})scenario(kind,dark);}finally{rig.main(()->{if(rig.home!=null)rig.home.dispose();if(rig.activity!=null){ViewGroup content=rig.activity.findViewById(android.R.id.content);content.suppressLayout(false);rig.activity.finish();}RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();}
        RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"package",rig.context.getPackageName(),"cases",cases,"scope","Real Home and ScrollView, app-local 120 ms layout suppression. Tests observer behavior; not proof of the historical 162 failure's root cause, system input or OPPO.","productionChanged",false,"oppoOperated",false));
        for(int i=0;i<cases.length();i++){JSONObject row=cases.getJSONObject(i);assertTrue(row.toString(),row.getBoolean("helperAccepted")&&row.getBoolean("finallyFullyVisible")&&row.getBoolean("returnedAfterRelease"));}
    }
}
