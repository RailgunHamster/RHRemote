package com.limelight.rhremote;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerService;
import com.limelight.computers.IdentityManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real background tryPollIp, generated loopback TLS identities and production view model. */
@RunWith(AndroidJUnit4.class)
public final class RhDiscoveryProtocolDeviceTest {
    @Test public void discoveryCannotAuthenticateAnHttpFallbackOrOverwriteANewerCheck()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("discoveryProtocolRun","");org.junit.Assume.assumeFalse("Explicit isolated emulator run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        File folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());File privateFolder=new File(context.getCacheDir(),run);assertTrue(privateFolder.mkdir());
        AndroidCryptoProvider original=RhDeviceCheckProtocolDeviceTest.identity(context,new File(privateFolder,"original")),changed=RhDeviceCheckProtocolDeviceTest.identity(context,new File(privateFolder,"changed"));
        ComputerManagerService service=new ComputerManagerService();Method attach=android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(service,context);Field identity=ComputerManagerService.class.getDeclaredField("idManager");identity.setAccessible(true);identity.set(service,new IdentityManager(context));
        Method poll=ComputerManagerService.class.getDeclaredMethod("tryPollIp",ComputerDetails.class,ComputerDetails.AddressTuple.class);poll.setAccessible(true);JSONArray rows=new JSONArray();
        String[][] cases={{"pin-change","certificate_mismatch"},{"pin-change401","certificate_mismatch"},{"https-http401","auth_rejected"},{"https-xml401","auth_rejected"},{"https-paired","paired"},{"https-unpaired","unpaired"},{"http-unpaired","unpaired"},{"wrong-uuid","rejected"}};
        for(String[] entry:cases){String kind=entry[0],uuid=UUID.randomUUID().toString();boolean pinned=!kind.equals("http-unpaired");ExecutorService worker=Executors.newSingleThreadExecutor();
            try(RhDeviceCheckProtocolDeviceTest.Host host=new RhDeviceCheckProtocolDeviceTest.Host(kind,uuid,kind.startsWith("pin-change")?changed:original,PlatformBinding.getCryptoProvider(context).getClientCertificate())){
                ComputerDetails target=new ComputerDetails();target.uuid=uuid;target.name="Discovery fixture";target.manualAddress=target.activeAddress=new ComputerDetails.AddressTuple("127.0.0.1",host.http.getLocalPort());target.state=ComputerDetails.State.ONLINE;target.httpsPort=host.tls.getLocalPort();target.serverCert=pinned?original.getClientCertificate():null;
                RhShared.capture(context,Collections.singletonList(target));String saved=RhShared.canonical(RhShared.read(context));
                ComputerDetails polled=worker.submit(()->(ComputerDetails)poll.invoke(service,target,target.activeAddress)).get(5,TimeUnit.SECONDS);
                if(kind.equals("wrong-uuid")){assertNull("A different host must be rejected",polled);rows.put(RhActions.obj("case",kind,"expected",entry[1],"rejected",true));continue;}
                assertNotNull(kind,polled);polled.activeAddress=target.activeAddress;target.update(polled);JSONObject check=RhDeviceCheck.snapshot(context,RhShared.read(context),target);
                RhShared.write(new File(folder,"observed-"+kind+".json"),RhActions.obj("case",kind,"report",check,"displayedPaired",RhDeviceCheck.paired(target,check),"rawPairState",String.valueOf(target.pairState),"rawPairingStatus",RhNetwork.pairingStatus(target),"plainRequests",host.plainRequests.get(),"secureRequests",host.secureRequests.get()));
                assertEquals("Background discovery needs protocol provenance: "+kind,entry[1],check.optString("outcome"));
                assertEquals(kind,kind.equals("https-paired"),RhDeviceCheck.paired(target,check));assertEquals(kind,"online",RhDeviceCheck.reachability(target,check));assertEquals(kind.equals("https-paired")?"device.connect":"device.pair",RhDeviceCheck.primaryAction(target,check));
                assertEquals("Background evidence must not change saved configuration",saved,RhShared.canonical(RhShared.read(context)));assertEquals(pinned?original.getClientCertificate():null,target.serverCert);
                if(kind.startsWith("pin-change")||kind.startsWith("https-")&&kind.contains("401")){assertEquals(1,host.plainRequests.get());assertFalse(RhDeviceCheck.paired(target,check));}else assertEquals(pinned?0:1,host.plainRequests.get());
                rows.put(RhActions.obj("case",kind,"expected",entry[1],"report",check,"pairingStatus",RhDeviceCheck.status(target,check),"primaryAction",RhDeviceCheck.primaryAction(target,check),"plainRequests",host.plainRequests.get(),"secureRequests",host.secureRequests.get(),"savedPinPreserved",true,"savedConfigurationUnchanged",true));
            }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(4,TimeUnit.SECONDS));}
        }
        RhShared.write(new File(folder,"result.json"),RhActions.obj("package",context.getPackageName(),"version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"publicTestCertificate",android.util.Base64.encodeToString(original.getClientCertificate().getEncoded(),android.util.Base64.NO_WRAP),"scope","Real ComputerManagerService.tryPollIp, generated loopback HTTP/TLS only; no PIN, stream or remote host","cases",rows,"oppoOperated",false));
    }
}
