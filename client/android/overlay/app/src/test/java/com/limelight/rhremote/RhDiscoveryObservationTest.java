package com.limelight.rhremote;

import android.content.Context;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhDiscoveryObservationTest {
    Context context;ComputerDetails target;
    @Before public void setup()throws Exception{context=RuntimeEnvironment.getApplication();target=new ComputerDetails();target.uuid="observation-fixture";target.name="Fixture";target.state=ComputerDetails.State.ONLINE;target.manualAddress=new ComputerDetails.AddressTuple("192.0.2.1",47989);target.serverCert=PlatformBinding.getCryptoProvider(context).getClientCertificate();RhShared.capture(context,Collections.singleton(target));observe();}
    void observe(){target.rhDiscoveryObservation=RhDiscoveryObservation.result(target,target.serverCert,"https","paired","",true,System.nanoTime()).bind(RhDiscoveryObservation.begin(context,target));}
    JSONObject snapshot(){return RhDeviceCheck.snapshot(context,RhShared.read(context),target);}
    @Test public void copiesRetainOnlyRuntimeEvidenceAndAPlainUpdateDropsIt()throws Exception{
        ComputerDetails copy=new ComputerDetails(target);assertSame(target.rhDiscoveryObservation,copy.rhDiscoveryObservation);assertEquals("paired",snapshot().getString("outcome"));assertFalse(RhShared.read(context).toString().contains("httpsPinVerified"));
        ComputerDetails raw=new ComputerDetails();raw.uuid=target.uuid;raw.name=target.name;target.update(raw);assertNull(target.rhDiscoveryObservation);assertEquals(0,snapshot().length());
    }
    @Test public void addressPolicyAndPinChangesRequireNewEvidence()throws Exception{
        RhShared.saveAddresses(context,target.uuid,"192.0.2.2",new JSONArray());assertTrue(snapshot().getBoolean("stale"));assertFalse(snapshot().has("outcome"));
        RhShared.saveAddresses(context,target.uuid,"192.0.2.1",new JSONArray());assertEquals("paired",snapshot().getString("outcome"));RhProfile profile=RhProfile.load(context,target.uuid);profile.routeOrder=new ArrayList<>(Collections.singletonList("relay"));RhShared.saveProfile(context,target,profile,"");assertTrue(snapshot().getBoolean("stale"));observe();assertEquals("paired",snapshot().getString("outcome"));target.serverCert=null;assertTrue(snapshot().getBoolean("stale"));
    }
    @Test public void lateResultsRemainBoundToTheirOriginalConfiguration()throws Exception{
        String original=RhDiscoveryObservation.begin(context,target);RhShared.saveAddresses(context,target.uuid,"192.0.2.9",new JSONArray());target.rhDiscoveryObservation=RhDiscoveryObservation.result(target,target.serverCert,"https","paired","",true,System.nanoTime()).bind(original);assertTrue(snapshot().getBoolean("stale"));assertEquals("stored",RhDeviceCheck.status(target,snapshot()));assertNull(RhDeviceCheck.admission(target,snapshot(),true).pairState);
    }
    @Test public void aLaterPartialOfflineObservationDoesNotStayOnline()throws Exception{
        assertTrue(RhDeviceCheck.online(target,snapshot()));target.state=ComputerDetails.State.OFFLINE;assertEquals(0,snapshot().length());assertFalse(RhDeviceCheck.online(target,snapshot()));assertNotNull(target.serverCert);
    }
    @Test public void callersCannotMutateAnObservationAndTimingsDoNotRebuildTheRow()throws Exception{
        String signature=RhDiscoveryObservation.signature(target);JSONObject exposed=snapshot();exposed.put("outcome","auth_rejected");assertEquals("paired",snapshot().getString("outcome"));observe();assertEquals(signature,RhDiscoveryObservation.signature(target));
    }
}
