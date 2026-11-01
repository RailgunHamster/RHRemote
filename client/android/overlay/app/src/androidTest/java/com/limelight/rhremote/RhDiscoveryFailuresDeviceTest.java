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
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real parallel polling and native views, using only owned loopback endpoints. */
@RunWith(AndroidJUnit4.class)
public final class RhDiscoveryFailuresDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final JSONArray protocol=new JSONArray(),views=new JSONArray();
    Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    void view(ComputerDetails target,String kind,JSONObject expected)throws Exception{
        for(int width:new int[]{360,1008})for(int font:new int[]{1,2})for(boolean dark:new boolean[]{false,true}){
            String name=kind+"-"+width+"-font"+font+"-"+(dark?"dark":"light");
            rig.main(()->{rig.context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();RhUiFixtureActivity.fontScale(font);});
            rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*density),Math.round(640*density));rig.home=new RhHome(rig.activity,rig.actions);rig.home.resumed();rig.home.update(new ComputerDetails(target));rig.home.perform("device.select",RhActions.obj("uuid",target.uuid));});
            long deadline=SystemClock.uptimeMillis()+3000;boolean[] ready={false};
            do{rig.instrument.waitForIdleSync();rig.main(()->{View root=rig.activity.findViewById(android.R.id.content);ready[0]=!root.isLayoutRequested()&&root.getWidth()>0;});if(ready[0])break;Thread.sleep(10);}while(SystemClock.uptimeMillis()<deadline);assertTrue("Fixture layout not ready",ready[0]);
            JSONObject state=rig.state(),row=null;for(int i=0;i<state.getJSONArray("devices").length();i++)if(state.getJSONArray("devices").getJSONObject(i).getString("uuid").equals(target.uuid))row=state.getJSONArray("devices").getJSONObject(i);assertNotNull(row);
            assertEquals(expected.optString("outcome"),row.getJSONObject("check").optString("outcome"));assertFalse(row.getBoolean("paired"));
            JSONObject actual=row;Button[] primary={null};rig.main(()->{Object browser=field(rig.home,"browser");TextView pairing=((TextView[])field(browser,"values"))[4];assertEquals(RhDeviceCheck.label(target,expected),pairing.getText().toString());
                TextView hint=(TextView)field(browser,"checkNotice");if(expected.optBoolean("backgroundFailure")){assertEquals(View.VISIBLE,hint.getVisibility());assertEquals(expected.getString("failureSummary"),hint.getText().toString());if(expected.optString("outcome").equals("unavailable"))assertEquals(((Integer)field(browser,"muted")).intValue(),pairing.getCurrentTextColor());}
                primary[0]=(Button)field(browser,"connect");assertEquals(actual.getString("primaryLabel"),primary[0].getText().toString());assertTrue(primary[0].isEnabled());});
            rig.visible(primary[0]);
            rig.capture(name);views.put(RhActions.obj("name",name,"row",row,"widthDp",width,"font",font,"dark",dark));
            rig.main(()->{rig.home.dispose();rig.home=null;rig.activity.finish();rig.activity=null;});rig.instrument.waitForIdleSync();
        }
    }
    @Test public void actualFailedCyclesPublishReasonsAndPreserveTrustInNativeViews()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("discoveryFailuresRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));rig.context=rig.instrument.getTargetContext();assertTrue(rig.context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());
        try(Socket check=new Socket()){try{check.connect(new InetSocketAddress("127.0.0.1",47989),100);fail("Fixture canonical port is in use; do not disturb its listener");}catch(ConnectException free){}}
        File keys=new File(rig.context.getCacheDir(),run);assertTrue(keys.mkdir());AndroidCryptoProvider original=RhDeviceCheckProtocolDeviceTest.identity(rig.context,new File(keys,"original")),changed=RhDeviceCheckProtocolDeviceTest.identity(rig.context,new File(keys,"changed"));
        ComputerManagerService service=new ComputerManagerService();Method attach=android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,rig.context);Field identity=ComputerManagerService.class.getDeclaredField("idManager");identity.setAccessible(true);identity.set(service,new IdentityManager(rig.context));Method poll=ComputerManagerService.class.getDeclaredMethod("parallelPollPc",ComputerDetails.class);poll.setAccessible(true);
        try{
            for(String kind:new String[]{"refused","pin-change-drop-http","wrong-uuid","malformed","http-unpaired"}){
                String uuid=UUID.randomUUID().toString();boolean pinned=kind.startsWith("pin-change");
                try(RhDeviceCheckProtocolDeviceTest.Host host=new RhDeviceCheckProtocolDeviceTest.Host(kind,uuid,pinned?changed:original,PlatformBinding.getCryptoProvider(rig.context).getClientCertificate())){
                    int port=host.http.getLocalPort();if(kind.equals("refused")){try(ServerSocket reserved=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=reserved.getLocalPort();}}
                    ComputerDetails seed=new ComputerDetails();seed.uuid=uuid;seed.name="后台探测测试";seed.manualAddress=new ComputerDetails.AddressTuple("127.0.0.1",47989);RhShared.capture(rig.context,Collections.singleton(seed));
                    ComputerDetails target=new ComputerDetails(seed);target.manualAddress=target.activeAddress=new ComputerDetails.AddressTuple("127.0.0.1",port);target.localAddress=new ComputerDetails.AddressTuple("127.0.0.2",port);target.httpsPort=host.tls.getLocalPort();target.state=ComputerDetails.State.ONLINE;target.serverCert=pinned?original.getClientCertificate():null;target.pairState=pinned?PairingManager.PairState.PAIRED:PairingManager.PairState.NOT_PAIRED;
                    String saved=RhShared.canonical(RhShared.read(rig.context));java.security.cert.X509Certificate pin=target.serverCert;ExecutorService worker=Executors.newSingleThreadExecutor();ComputerDetails result;
                    try{result=worker.submit(()->(ComputerDetails)poll.invoke(service,target)).get(8,TimeUnit.SECONDS);}finally{worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
                    if(kind.equals("http-unpaired")){assertNotNull(result);target.update(result);target.activeAddress=result.activeAddress;assertEquals("unpaired",RhDeviceCheck.snapshot(rig.context,RhShared.read(rig.context),target).optString("outcome"));}
                    else{assertNull(result);target.state=ComputerDetails.State.OFFLINE;}
                    JSONObject report=RhDeviceCheck.snapshot(rig.context,RhShared.read(rig.context),target);
                    RhShared.write(new File(rig.folder,"protocol-"+kind+".json"),report);
                    if(!kind.equals("http-unpaired")){assertTrue(report.toString(),report.getBoolean("backgroundFailure"));assertEquals(2,report.getJSONArray("attempts").length());assertFalse(report.getBoolean("reachabilityCurrent"));assertFalse(RhDeviceCheck.paired(target,report));
                        if(kind.equals("wrong-uuid")||kind.equals("malformed")){assertEquals(kind.equals("wrong-uuid")?"identity_mismatch":"invalid_response",report.getString("outcome"));assertTrue(RhDeviceCheck.blocks(report));}else assertEquals("unavailable",report.getString("outcome"));
                        if(pinned){assertTrue(report.toString(),report.toString().contains("certificate_mismatch"));assertEquals("stored",RhDeviceCheck.status(target,report));assertEquals("device.connect",RhDeviceCheck.primaryAction(target,report));}
                    }
                    assertEquals(pin,target.serverCert);assertEquals(saved,RhShared.canonical(RhShared.read(rig.context)));
                    protocol.put(RhActions.obj("case",kind,"report",report,"savedConfigurationUnchanged",true,"savedPinPreserved",true,"plainRequests",host.plainRequests.get(),"secureRequests",host.secureRequests.get()));view(target,kind,report);assertEquals(saved,RhShared.canonical(RhShared.read(rig.context)));
                }
            }
        }finally{rig.main(()->{if(rig.home!=null)rig.home.dispose();if(rig.activity!=null)rig.activity.finish();RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();}
        assertEquals(5,protocol.length());assertEquals(40,views.length());RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"package",rig.context.getPackageName(),"protocolCases",protocol,"views",views,"scope","Real parallelPollPc and NvHTTP against owned loopback peers; explicit offline publication projection, real View.draw; not the timed background service or external network","oppoOperated",false,"osInputInjected",false));
    }
}
