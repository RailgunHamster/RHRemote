package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.widget.TextView;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Actual service callbacks and evidence projection; no socket or system network changes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhNetworkObservationTest {
    public static class NoDiscovery extends ComputerManagerService {
        @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){return false;}
    }
    Context context;ServiceController<NoDiscovery> lifecycle;ComputerManagerService service;
    ConnectivityManager.NetworkCallback callback;Network first,second;ComputerDetails target;RhHome home;
    Network network(int id){return ReflectionHelpers.callConstructor(Network.class,ReflectionHelpers.ClassParameter.from(int.class,id));}
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();lifecycle=Robolectric.buildService(NoDiscovery.class).create();service=lifecycle.get();callback=ReflectionHelpers.getField(service,"networkCallback");
        first=network(101);second=network(102);callback.onAvailable(first);
        target=new ComputerDetails();target.uuid="network-observation";target.name="Fixture";target.state=ComputerDetails.State.ONLINE;target.pairState=PairingManager.PairState.PAIRED;
        target.manualAddress=new ComputerDetails.AddressTuple("192.0.2.1",47989);target.serverCert=PlatformBinding.getCryptoProvider(context).getClientCertificate();
        RhShared.capture(context,Collections.singleton(target));ComputerDatabaseManager db=ReflectionHelpers.getField(service,"dbManager");assertTrue(db.updateComputer(target));
        Method add=ComputerManagerService.class.getDeclaredMethod("addTuple",ComputerDetails.class);add.setAccessible(true);add.invoke(service,target);
    }
    @After public void cleanup(){if(home!=null)home.dispose();if(lifecycle!=null)lifecycle.destroy();}
    void observe(String intent){target.rhDiscoveryObservation=RhDiscoveryObservation.result(target,target.serverCert,"https","paired","",true,System.nanoTime()).bind(intent);}
    JSONObject snapshot(){return RhDeviceCheck.snapshot(context,RhShared.read(context),target);}
    void assertFresh(){JSONObject report=snapshot();assertEquals(report.toString(),"paired",report.optString("outcome"));assertFalse(report.optBoolean("stale"));assertTrue(RhDeviceCheck.online(target,report));}
    void assertNetworkStale(){JSONObject report=snapshot();assertTrue("Prior network evidence was accepted: "+report,report.optBoolean("stale"));assertEquals("network_changed",report.optString("staleReason"));assertFalse(RhDeviceCheck.online(target,report));assertFalse(RhDeviceCheck.paired(target,report));assertTrue(RhDeviceCheck.hint(report).contains("网络"));}
    @Test public void aLateDiscoveryResponseCannotVerifyThePreviousNetwork()throws Exception{
        String saved=RhShared.canonical(RhShared.read(context)),intent=RhDiscoveryObservation.begin(context,target);java.security.cert.X509Certificate pin=target.serverCert;
        callback.onLost(first);target.state=ComputerDetails.State.ONLINE;observe(intent);assertNetworkStale();assertSame(pin,target.serverCert);assertEquals(saved,RhShared.canonical(RhShared.read(context)));
    }
    @Test public void anActiveCheckAlsoExpiresWhenItsNetworkChanges()throws Exception{
        JSONObject report=RhDeviceCheck.report(target.uuid,target.uuid,"https",true,"paired",System.nanoTime());report.put("phase","server_info");report.put("requestIntent",RhDeviceCheck.intent(context,RhShared.read(context),target));
        Map<String,JSONObject> checks=ReflectionHelpers.getField(RhDiagnosticWork.shared(context),"checks");checks.put(target.uuid,report);assertFresh();callback.onLost(first);assertNetworkStale();
    }
    @Test public void repeatedAvailabilityForTheSameNetworkKeepsFreshEvidence()throws Exception{
        observe(RhDiscoveryObservation.begin(context,target));String signature=RhDiscoveryObservation.signature(target);callback.onAvailable(first);assertFresh();assertEquals(signature,RhDiscoveryObservation.signature(target));
    }
    @Test public void losingAnOldNetworkCannotOfflineTheNewDefaultNetwork()throws Exception{
        callback.onAvailable(second);target.state=ComputerDetails.State.ONLINE;observe(RhDiscoveryObservation.begin(context,target));callback.onLost(first);assertFresh();
    }
    @Test public void returningToANetworkDoesNotReviveEarlierEvidence()throws Exception{
        String intent=RhDiscoveryObservation.begin(context,target);callback.onLost(first);callback.onAvailable(first);target.state=ComputerDetails.State.ONLINE;observe(intent);assertNetworkStale();
    }
    @Test public void aNewObservationAfterTheChangeRestoresNormalConnection()throws Exception{
        observe(RhDiscoveryObservation.begin(context,target));callback.onLost(first);callback.onAvailable(second);target.state=ComputerDetails.State.ONLINE;observe(RhDiscoveryObservation.begin(context,target));assertFresh();assertEquals("device.connect",RhDeviceCheck.primaryAction(target,snapshot()));
    }
    @Test public void networkChangesKeepRejectedActiveIdentityUntilItIsVerified()throws Exception{
        Map<String,JSONObject> checks=ReflectionHelpers.getField(RhDiagnosticWork.shared(context),"checks");
        for(String outcome:new String[]{"auth_rejected","certificate_mismatch","unpaired","identity_mismatch","invalid_response"}){
            callback.onAvailable(first);target.state=ComputerDetails.State.ONLINE;
            String actual=outcome.equals("identity_mismatch")?"other-fixture":outcome.equals("invalid_response")?"":target.uuid;
            JSONObject report=RhDeviceCheck.report(target.uuid,actual,"https",true,outcome,System.nanoTime());report.put("phase","server_info");report.put("requestIntent",RhDeviceCheck.intent(context,RhShared.read(context),target));checks.put(target.uuid,report);
            callback.onLost(first);JSONObject retained=snapshot();assertEquals(outcome,retained.optString("outcome"));assertTrue(retained.toString(),retained.optBoolean("networkStale"));assertFalse(RhDeviceCheck.online(target,retained));assertFalse(RhDeviceCheck.paired(target,retained));assertEquals("device.check",RhDeviceCheck.primaryAction(target,retained));
            callback.onAvailable(second);target.state=ComputerDetails.State.ONLINE;JSONObject recovered=RhDeviceCheck.report(target.uuid,target.uuid,"https",true,"paired",System.nanoTime());recovered.put("phase","server_info");recovered.put("requestIntent",RhDeviceCheck.intent(context,RhShared.read(context),target));checks.put(target.uuid,recovered);assertFresh();
        }
    }
    @Test public void networkChangesKeepDiscoveryIdentityFailuresButConfigurationChangesInvalidateThem()throws Exception{
        for(String outcome:new String[]{"auth_rejected","certificate_mismatch","unpaired","identity_mismatch","invalid_response"}){
            callback.onAvailable(first);target.state=ComputerDetails.State.ONLINE;RhShared.saveAddresses(context,target.uuid,"192.0.2.1",new JSONArray());
            if(outcome.equals("identity_mismatch")||outcome.equals("invalid_response")){
                RhDiscoveryFailures failures=RhDiscoveryFailures.begin(context,target);failures.register(target.manualAddress);failures.failed(target.manualAddress,outcome,"other-fixture");target.rhDiscoveryObservation=failures.finish();target.state=ComputerDetails.State.OFFLINE;
            }else target.rhDiscoveryObservation=RhDiscoveryObservation.result(target,target.serverCert,outcome.equals("unpaired")?"https":"http",outcome,"",outcome.equals("unpaired"),System.nanoTime()).bind(RhDiscoveryObservation.begin(context,target));
            callback.onLost(first);JSONObject retained=snapshot();assertEquals(outcome,retained.optString("outcome"));assertTrue(retained.toString(),retained.optBoolean("networkStale"));assertFalse(RhDeviceCheck.online(target,retained));assertEquals("device.check",RhDeviceCheck.primaryAction(target,retained));
            RhShared.saveAddresses(context,target.uuid,"192.0.2.9",new JSONArray());JSONObject changed=snapshot();assertTrue(changed.toString(),changed.optBoolean("stale"));assertEquals("configuration_changed",changed.optString("staleReason"));assertFalse(changed.has("outcome"));
        }
    }
    @Test public void aMissingCaptureStillExplainsThatConfigurationEvidenceIsStale()throws Exception{
        observe(null);JSONObject report=snapshot();assertTrue(report.toString(),report.optBoolean("stale"));assertEquals("configuration_changed",report.optString("staleReason"));
    }
    @Test public void aSameStateNetworkEventRebindsTheVisibleOfflineRow()throws Exception{
        target.state=ComputerDetails.State.OFFLINE;RhDiscoveryFailures failures=RhDiscoveryFailures.begin(context,target);failures.register(target.manualAddress);failures.failed(target.manualAddress,new ConnectException("Fixture"));target.rhDiscoveryObservation=failures.finish();
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(com.limelight.R.style.RhTheme);
        home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError();}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String p){throw new AssertionError();}public void cancelPair(){}public void refresh(){}});
        home.update(target);home.perform("device.select",RhActions.obj("uuid",target.uuid));Object browser=ReflectionHelpers.getField(home,"browser");TextView[] values=ReflectionHelpers.getField(browser,"values");assertEquals("已保存配对",values[4].getText().toString());
        callback.onLost(first);home.update(target);assertEquals("需重新检查",values[4].getText().toString());
    }
}
