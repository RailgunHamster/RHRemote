package com.limelight.rhremote;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.*;
import com.limelight.nvstream.http.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Current mTLS responses retire older check failures through the real Home observer. */
@RunWith(AndroidJUnit4.class)
public final class RhAuthenticationObservationDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final JSONArray views=new JSONArray(),flows=new JSONArray();String currentUuid;
    Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    JSONObject row()throws Exception{JSONArray rows=rig.state().getJSONArray("devices");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("uuid").equals(currentUuid))return row;}throw new AssertionError("Current fixture target missing");}
    void publish(ComputerDetails target){rig.main(()->rig.home.update(target));rig.instrument.waitForIdleSync();}
    void layout()throws Exception{
        long deadline=SystemClock.uptimeMillis()+3000;boolean[] ready={false};
        do{rig.instrument.waitForIdleSync();rig.main(()->{View root=rig.activity.findViewById(android.R.id.content);ready[0]=!root.isLayoutRequested()&&root.getWidth()>0;});if(ready[0])break;Thread.sleep(10);}while(SystemClock.uptimeMillis()<deadline);assertTrue("Layout not ready",ready[0]);
    }
    void view(String name,String outcome,String action,String label)throws Exception{
        layout();JSONObject row=row();assertEquals(outcome,row.getJSONObject("check").optString("outcome"));assertEquals(action,row.getString("primaryAction"));
        Button[] primary={null};String[] notice={null};rig.main(()->{Object browser=field(rig.home,"browser");TextView pairing=((TextView[])field(browser,"values"))[4];assertEquals(label,pairing.getText().toString());primary[0]=(Button)field(browser,"connect");assertEquals(row.getString("primaryLabel"),primary[0].getText().toString());assertTrue(primary[0].isEnabled());notice[0]=((TextView)field(browser,"checkNotice")).getText().toString();});
        if(name.endsWith("-offline-rejected")||name.startsWith("unpaired-")&&name.endsWith("-offline-after-verification"))assertTrue(notice[0],notice[0].contains("。请先检查连接。"));rig.visible(primary[0]);rig.capture(name);views.put(RhActions.obj("name",name,"row",row,"visiblePairing",label,"notice",notice[0]));
    }
    ComputerDetails poll(ComputerManagerService service,Method poll,ComputerDetails target)throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();try{return worker.submit(()->(ComputerDetails)poll.invoke(service,target)).get(8,TimeUnit.SECONDS);}finally{worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
    }
    void failed(ComputerManagerService service,Method poll,ComputerDetails target)throws Exception{assertNull(poll(service,poll,target));target.state=ComputerDetails.State.OFFLINE;publish(target);}
    @Test public void currentPinnedEvidenceRetiresResolvedErrorsAndOfflineRepairRequiresCheck()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("authenticationObservationRun","");org.junit.Assume.assumeFalse("Dedicated opt-in required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));rig.context=rig.instrument.getTargetContext();assertTrue(rig.context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());File keys=new File(rig.context.getCacheDir(),run);assertTrue(keys.mkdir());AndroidCryptoProvider identity=RhDeviceCheckProtocolDeviceTest.identity(rig.context,new File(keys,"server"));
        ComputerManagerService service=new ComputerManagerService();Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,rig.context);Field id=ComputerManagerService.class.getDeclaredField("idManager");id.setAccessible(true);id.set(service,new IdentityManager(rig.context));Method poll=ComputerManagerService.class.getDeclaredMethod("parallelPollPc",ComputerDetails.class);poll.setAccessible(true);
        try{
            for(int width:new int[]{1008,360})for(boolean unpaired:new boolean[]{false,true}){
                int font=width==360?2:1;boolean dark=width==360;String name=(unpaired?"unpaired":"paired")+"-"+width+"-font"+font+"-"+(dark?"dark":"light");String uuid=UUID.randomUUID().toString();currentUuid=uuid;
                try(RhDeviceCheckProtocolDeviceTest.Host host=new RhDeviceCheckProtocolDeviceTest.Host("route-paired",uuid,identity,PlatformBinding.getCryptoProvider(rig.context).getClientCertificate())){
                    ComputerDetails target=new ComputerDetails();target.uuid=uuid;target.name="Fixture";target.manualAddress=target.activeAddress=new ComputerDetails.AddressTuple("127.0.0.8",47989);target.httpsPort=host.tls.getLocalPort();target.serverCert=identity.getClientCertificate();target.state=ComputerDetails.State.ONLINE;target.pairState=PairingManager.PairState.PAIRED;
                    rig.main(()->{RhShared.capture(rig.context,Collections.singleton(target));RhProfile profile=RhProfile.load(rig.context,uuid);profile.routeOrder=new ArrayList<>(Collections.singletonList("lan"));RhShared.saveProfile(rig.context,target,profile,"");rig.context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();RhUiFixtureActivity.fontScale(font);});
                    String saved=RhShared.canonical(RhShared.read(rig.context));java.security.cert.X509Certificate pin=target.serverCert;
                    rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*density),Math.round(640*density));rig.home=new RhHome(rig.activity,rig.actions);rig.home.resumed();rig.home.update(target);rig.home.perform("device.select",RhActions.obj("uuid",uuid));});layout();
                    host.kind="https-http401";rig.action("device.check",RhActions.obj("uuid",uuid));rig.idle();view(name+"-rejected","auth_rejected","device.pair","认证失败");assertTrue(RhDiagnosticWork.shared(rig.context).hasCheck(uuid));
                    host.kind="drop-all";failed(service,poll,target);view(name+"-offline-rejected","auth_rejected","device.check","认证失败");JSONObject[] denied={null};rig.main(()->denied[0]=RhActions.dispatch("device.pair",RhActions.obj("uuid",uuid,"pin","1234")));assertFalse(denied[0].toString(),denied[0].getBoolean("ok"));assertFalse(RhActions.hasStream());assertFalse(RhActions.inspecting());
                    host.kind=unpaired?"https-unpaired":"route-paired";ComputerDetails result=poll(service,poll,target);assertNotNull(result);target.update(result);target.activeAddress=result.activeAddress;publish(target);assertFalse(RhDiagnosticWork.shared(rig.context).hasCheck(uuid));view(name+"-verified",unpaired?"unpaired":"paired",unpaired?"device.pair":"device.connect",unpaired?"需要重新配对":"已配对");
                    host.kind="drop-all";failed(service,poll,target);view(name+"-offline-after-verification",unpaired?"unpaired":"unavailable",unpaired?"device.check":"device.connect",unpaired?"需要重新配对":"已保存配对");assertFalse(row().getBoolean("paired"));assertFalse(RhDiagnosticWork.shared(rig.context).hasCheck(uuid));assertEquals(pin,target.serverCert);assertEquals(saved,RhShared.canonical(RhShared.read(rig.context)));assertTrue(host.clientVerified.get());
                    flows.put(RhActions.obj("name",name,"plainRequests",host.plainRequests.get(),"secureRequests",host.secureRequests.get(),"clientCertificateVerified",host.clientVerified.get(),"offlinePairApi",denied[0],"savedPinPreserved",true,"savedConfigUnchanged",true,"oldCheckRetired",true));
                }finally{rig.main(()->{if(rig.home!=null){rig.home.dispose();rig.home=null;}if(rig.activity!=null){rig.activity.finish();rig.activity=null;}});rig.instrument.waitForIdleSync();}
            }
        }finally{rig.main(()->RhUiFixtureActivity.fontScale(0));rig.instrument.waitForIdleSync();}
        assertEquals(4,flows.length());assertEquals(16,views.length());RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"package",rig.context.getPackageName(),"flows",flows,"views",views,"scope","Actual manual device.check, parallelPollPc, loopback HTTP/mTLS and Home observer/native Views. Failed polling is explicitly projected OFFLINE; not the timed service, system network switch, PIN or stream.","oppoOperated",false,"osInputInjected",false));
    }
}
