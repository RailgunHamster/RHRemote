package com.limelight.rhremote;

import android.app.Activity;
import android.net.Network;
import android.widget.TextView;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.net.ConnectException;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Feed current discovery evidence through the production Home observer and Views. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhAuthenticationObservationTest {
    Activity activity;RhHome home;ComputerDetails target;RhDiagnosticWork work;Map<String,JSONObject> checks;
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(com.limelight.R.style.RhTheme);
        target=new ComputerDetails();target.uuid="authentication-observation";target.name="Fixture";target.manualAddress=new ComputerDetails.AddressTuple("192.0.2.1",47989);target.state=ComputerDetails.State.ONLINE;target.pairState=PairingManager.PairState.PAIRED;target.serverCert=PlatformBinding.getCryptoProvider(activity).getClientCertificate();RhShared.capture(activity,Collections.singleton(target));
        home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No stream");}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String pin){throw new AssertionError("No pairing");}public void cancelPair(){}public void refresh(){}});
        work=RhDiagnosticWork.shared(activity);checks=ReflectionHelpers.getField(work,"checks");home.update(target);home.perform("device.select",RhActions.obj("uuid",target.uuid));
    }
    @After public void cleanup(){home.dispose();}
    void reject(String outcome)throws Exception{
        JSONObject check=RhDeviceCheck.report(target.uuid,target.uuid,"https",true,outcome,System.nanoTime());check.put("phase","server_info");check.put("requestIntent",RhDeviceCheck.intent(activity,RhShared.read(activity),target));checks.put(target.uuid,check);
    }
    RhDiscoveryObservation proof(String intent,String source,String outcome,boolean verified){return RhDiscoveryObservation.result(target,target.serverCert,source,outcome,"",verified,System.nanoTime()).bind(intent);}
    void good()throws Exception{target.state=ComputerDetails.State.ONLINE;target.pairState=PairingManager.PairState.PAIRED;target.rhDiscoveryObservation=proof(RhDiscoveryObservation.begin(activity,target),"https","paired",true);home.update(target);}
    void failed()throws Exception{
        RhDiscoveryFailures failures=RhDiscoveryFailures.begin(activity,target);failures.register(target.manualAddress);failures.failed(target.manualAddress,new ConnectException("Fixture"));target.rhDiscoveryObservation=failures.finish();target.state=ComputerDetails.State.OFFLINE;home.update(target);
    }
    JSONObject row()throws Exception{return home.state().getJSONArray("devices").getJSONObject(0);}
    String visiblePairing(){Object browser=ReflectionHelpers.getField(home,"browser");TextView[] values=ReflectionHelpers.getField(browser,"values");return values[4].getText().toString();}
    @Test public void freshPinnedSuccessDoesNotResurrectOldRejectionAfterNetworkFailure()throws Exception{
        reject("auth_rejected");String saved=RhShared.canonical(RhShared.read(activity));java.security.cert.X509Certificate pin=target.serverCert;
        good();assertTrue(row().getBoolean("paired"));failed();JSONObject current=row();
        assertEquals("Resolved authentication error returned: "+current,"unavailable",current.getJSONObject("check").optString("outcome"));assertEquals("device.connect",current.getString("primaryAction"));assertEquals("已保存配对",visiblePairing());assertFalse(work.hasCheck(target.uuid));assertSame(pin,target.serverCert);assertEquals(saved,RhShared.canonical(RhShared.read(activity)));
    }
    @Test public void aPlainFallbackCannotClearAKnownAuthenticationRejection()throws Exception{
        reject("auth_rejected");target.rhDiscoveryObservation=proof(RhDiscoveryObservation.begin(activity,target),"http","certificate_mismatch",false);home.update(target);assertTrue(work.hasCheck(target.uuid));failed();assertEquals("auth_rejected",row().getJSONObject("check").getString("outcome"));assertTrue(RhDeviceCheck.hint(target,row().getJSONObject("check")).endsWith("。请先检查连接。"));
    }
    @Test public void anOlderPinnedResponseCannotClearANewerRejectedCheck()throws Exception{
        target.rhDiscoveryObservation=proof(RhDiscoveryObservation.begin(activity,target),"https","paired",true);reject("auth_rejected");home.update(target);assertTrue(work.hasCheck(target.uuid));assertEquals("auth_rejected",row().getJSONObject("check").getString("outcome"));
    }
    void late(String change)throws Exception{
        String captured=RhDiscoveryObservation.begin(activity,target);
        if(change.equals("address"))RhShared.saveAddresses(activity,target.uuid,"192.0.2.9",new JSONArray());
        if(change.equals("network")){Network network=ReflectionHelpers.callConstructor(Network.class,ReflectionHelpers.ClassParameter.from(int.class,301));RhNetworkObservation.available(network);}
        reject("auth_rejected");target.rhDiscoveryObservation=proof(captured,"https","paired",true);home.update(target);assertTrue("Stale "+change+" proof cleared current failure",work.hasCheck(target.uuid));assertEquals("auth_rejected",row().getJSONObject("check").getString("outcome"));
    }
    @Test public void proofFromAnOlderAddressCannotClearCurrentCheck()throws Exception{late("address");}
    @Test public void proofFromAnOlderPinCannotClearCurrentCheck()throws Exception{
        String captured=RhDiscoveryObservation.begin(activity,target);java.security.cert.X509Certificate original=target.serverCert;
        java.io.File changed=new java.io.File(activity.getCacheDir(),"changed-server-identity");assertTrue(changed.mkdir());target.serverCert=new AndroidCryptoProvider(new android.content.ContextWrapper(activity){@Override public java.io.File getFilesDir(){return changed;}}).getClientCertificate();assertNotEquals(original,target.serverCert);
        reject("auth_rejected");target.rhDiscoveryObservation=RhDiscoveryObservation.result(target,original,"https","paired","",true,System.nanoTime()).bind(captured);home.update(target);assertTrue(work.hasCheck(target.uuid));assertEquals("auth_rejected",row().getJSONObject("check").getString("outcome"));
    }
    @Test public void proofFromAnOlderNetworkCannotClearCurrentCheck()throws Exception{late("network");}
    @Test public void aVerifiedUnpairedResultStillRequiresRepairAfterAProbeFailure()throws Exception{
        reject("certificate_mismatch");target.pairState=PairingManager.PairState.NOT_PAIRED;target.rhDiscoveryObservation=proof(RhDiscoveryObservation.begin(activity,target),"https","unpaired",true);home.update(target);assertFalse(work.hasCheck(target.uuid));failed();JSONObject current=row();assertEquals("unpaired",current.getJSONObject("check").getString("outcome"));assertEquals("device.check",current.getString("primaryAction"));assertEquals("需要重新配对",visiblePairing());
    }
    @Test public void aVerifiedObservationOnlyRetiresItsOwnDevice()throws Exception{
        reject("auth_rejected");JSONObject other=new JSONObject(checks.get(target.uuid).toString());other.put("targetUuid","other");checks.put("other",other);good();assertFalse(work.hasCheck(target.uuid));assertTrue(work.hasCheck("other"));
    }
    @Test public void equalSignatureWithNewerProofUpdatesTheVisiblePairingValue()throws Exception{
        good();String signature=RhDiscoveryObservation.signature(target);reject("auth_rejected");checks.get(target.uuid).put("reachabilityCurrent",false);home.perform("device.select",RhActions.obj("uuid",target.uuid));assertEquals("认证失败",visiblePairing());good();assertEquals(signature,RhDiscoveryObservation.signature(target));assertEquals("已配对",visiblePairing());assertFalse(work.hasCheck(target.uuid));
    }
}
