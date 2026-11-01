package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Production task, controller, cancellation and controls with explicit network boundaries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w1180dp-h780dp-mdpi",shadows={RhAdmissionTest.Controls.class,RhAdmissionTest.Transport.class,RhDeviceInspectionWorkTest.Routes.class,RhDeviceInspectionWorkTest.Protocol.class})
public class RhDeviceInspectionWorkTest {
    @Implements(value=RhNetwork.class,isInAndroidSdk=false)
    public static class Routes {
        static String uuid;static List<String> order;static JSONObject addresses;
        @Implementation protected static ComputerDetails resolve(Context c,ComputerDetails target,RhProfile profile,JSONObject options,RhCancellation operation)throws Exception{operation.checkpoint();uuid=target.uuid;order=new ArrayList<>(profile.routeOrder);addresses=new JSONObject(options.toString());return target;}
    }
    @Implements(value=RhDeviceCheck.class,isInAndroidSdk=false)
    public static class Protocol {
        static String outcome="paired";
        @Implementation protected static JSONObject inspect(Context c,ComputerDetails target,RhCancellation operation)throws Exception{operation.checkpoint();return RhDeviceCheck.report(target.uuid,target.uuid,"https",target.serverCert!=null,outcome,System.nanoTime());}
    }
    Activity activity;RhHome home;ComputerDetails first,second;ArrayDeque<Runnable> jobs=new ArrayDeque<>();RhDiagnosticWork work;
    ComputerDetails device(String uuid){ComputerDetails d=new ComputerDetails();d.uuid=uuid;d.name=uuid;d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.10",47989);d.serverCert=com.limelight.binding.PlatformBinding.getCryptoProvider(activity).getClientCertificate();return d;}
    @Before public void setup()throws Exception{
        RhAdmissionTest.Transport.fail=false;RhAdmissionTest.Transport.stops=0;Routes.uuid=null;Protocol.outcome="paired";
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError();}public void pair(ComputerDetails d,String pin){throw new AssertionError();}public void add(String address){throw new AssertionError();}public void advanced(){}public void cancelPair(){}public void refresh(){}public java.util.concurrent.Executor checkExecutor(){return jobs::add;}});
        first=device("inspection-first");second=device("inspection-second");RhShared.capture(activity,Arrays.asList(first,second));home.update(first);home.update(second);home.perform("device.select",RhActions.obj("uuid",first.uuid));work=RhDiagnosticWork.shared(activity);
    }
    void finish(){while(!jobs.isEmpty())jobs.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @After public void cleanup(){RhAdmissionTest.Transport.fail=false;if(work.busy())work.cancel(work.state().optLong("operationId"));finish();home.dispose();assertFalse(RhActions.inspecting());}
    JSONObject row()throws Exception{return home.state().getJSONArray("devices").getJSONObject(0);}
    @Test public void oneSavedDeviceUsesItsCapturedPolicyAndPublishesOnlyAfterCleanup()throws Exception{
        RhProfile profile=RhProfile.load(activity,first.uuid);profile.routeOrder=new ArrayList<>(Arrays.asList("relay","lan"));RhShared.saveProfile(activity,first,profile,RhShared.alias(activity,first.uuid));
        String saved=RhShared.canonical(RhShared.read(activity));home.perform("device.check",RhActions.obj("uuid",first.uuid));assertTrue(work.busy());assertEquals("check",work.state().getString("mode"));assertEquals(1,jobs.size());
        try{home.perform("device.check",RhActions.obj("uuid",second.uuid));fail();}catch(IllegalStateException expected){}
        finish();assertEquals(first.uuid,Routes.uuid);assertEquals(Arrays.asList("relay","lan"),Routes.order);assertEquals("192.0.2.10",Routes.addresses.getString("address"));assertEquals("paired",row().getString("pairingStatus"));assertEquals("device.connect",row().getString("primaryAction"));assertEquals(1,RhAdmissionTest.Transport.stops);assertEquals(saved,RhShared.canonical(RhShared.read(activity)));assertFalse(work.busy());
    }
    @Test public void cancelBeforeTheWorkerRunsCannotPublishOrTargetTheNewSelection()throws Exception{
        home.perform("device.check",RhActions.obj("uuid",first.uuid));long id=work.state().getLong("operationId");home.perform("device.select",RhActions.obj("uuid",second.uuid));home.perform("device.network.cancel",RhActions.obj("operationId",id));assertTrue(work.busy());finish();
        assertNull(Routes.uuid);assertEquals("cancelled",work.state().getString("phase"));assertEquals(second.uuid,home.state().getString("selected"));assertEquals(0,RhDeviceCheck.snapshot(activity,RhShared.read(activity),first).length());
        home.perform("device.check",RhActions.obj("uuid",second.uuid));try{home.perform("device.network.cancel",RhActions.obj("operationId",id));fail();}catch(IllegalStateException expected){}finish();assertEquals(second.uuid,Routes.uuid);
    }
    @Test public void changedAddressesPinsAndPolicyInvalidateOldEvidenceAndHeldCheckIntent()throws Exception{
        String intent=RhDeviceCheck.intent(activity,RhShared.read(activity),first);Protocol.outcome="auth_rejected";home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();assertEquals("重新配对",row().getString("primaryLabel"));
        RhShared.saveAddresses(activity,first.uuid,"192.0.2.20",new JSONArray());assertTrue(RhDeviceCheck.snapshot(activity,RhShared.read(activity),first).optBoolean("stale"));assertFalse(RhDeviceCheck.snapshot(activity,RhShared.read(activity),first).has("outcome"));
        try{home.perform("device.check",RhActions.obj("uuid",first.uuid,"expectedIntent",intent));fail();}catch(IllegalStateException expected){}assertFalse(work.busy());
        home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();assertEquals("auth_rejected",row().getString("pairingStatus"));first.serverCert=null;assertTrue(RhDeviceCheck.snapshot(activity,RhShared.read(activity),first).optBoolean("stale"));assertFalse(RhDeviceCheck.snapshot(activity,RhShared.read(activity),first).has("outcome"));
    }
    @Test public void cleanupFailureKeepsTheOwnerAndResultPrivateUntilRetrySucceeds()throws Exception{
        Protocol.outcome="certificate_mismatch";RhAdmissionTest.Transport.fail=true;home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();assertTrue(work.busy());assertEquals("cleanup-error",work.state().getString("phase"));assertEquals(0,RhDeviceCheck.snapshot(activity,RhShared.read(activity),first).length());
        try{home.perform("device.connect",RhActions.obj("uuid",second.uuid));fail();}catch(IllegalStateException expected){}
        RhAdmissionTest.Transport.fail=false;home.perform("device.network.cancel",RhActions.obj("operationId",work.state().getLong("operationId"),"expectedAction","cleanup"));finish();assertFalse(work.busy());assertEquals("certificate_mismatch",row().getString("pairingStatus"));assertEquals("重新配对",row().getString("primaryLabel"));
    }
    @Test public void identityFailureBlocksExplicitConnectAndPairAndBackgroundRefreshDoesNotEraseIt()throws Exception{
        Protocol.outcome="identity_mismatch";home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();assertEquals("device.check",row().getString("primaryAction"));
        for(String action:new String[]{"device.connect","device.pair"})try{home.perform(action,RhActions.obj("uuid",first.uuid));fail(action);}catch(IllegalStateException expected){}
        home.perform("devices.refresh",RhActions.obj());home.update(first);assertEquals("identity_mismatch",row().getString("pairingStatus"));
    }
    @Test public void authenticatedEvidenceOverridesAnOldDiscoveryFlagOnlyInTheAdmissionCopy()throws Exception{
        first.pairState=com.limelight.nvstream.http.PairingManager.PairState.NOT_PAIRED;
        home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();JSONObject check=RhDeviceCheck.snapshot(activity,RhShared.read(activity),first);check.put("httpsPort",5443);
        ComputerDetails admitted=RhDeviceCheck.admission(first,check,true);assertEquals(com.limelight.nvstream.http.PairingManager.PairState.PAIRED,admitted.pairState);assertEquals(5443,admitted.httpsPort);assertEquals(com.limelight.nvstream.http.PairingManager.PairState.NOT_PAIRED,first.pairState);assertEquals("device.connect",row().getString("primaryAction"));
    }
    @Test public void incompleteAuthenticationPreservesThePinAndRequiresFreshStrictAdmission()throws Exception{
        first.pairState=com.limelight.nvstream.http.PairingManager.PairState.NOT_PAIRED;Protocol.outcome="tls_failure";home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();JSONObject check=RhDeviceCheck.snapshot(activity,RhShared.read(activity),first);
        assertEquals("device.connect",row().getString("primaryAction"));ComputerDetails admitted=RhDeviceCheck.admission(first,check,true);assertNull(admitted.pairState);assertSame(first.serverCert,admitted.serverCert);
        check.put("outcome","certificate_mismatch");ComputerDetails repair=RhDeviceCheck.admission(first,check,false);assertNull(repair.pairState);assertSame(first.serverCert,repair.serverCert);assertEquals(com.limelight.nvstream.http.PairingManager.PairState.NOT_PAIRED,first.pairState);
    }
    @Test public void aLaterOfflineDiscoveryMustSupersedeTheChecksReachabilityWithoutRevokingItsPin()throws Exception{
        home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();assertTrue(row().getBoolean("online"));assertTrue(row().getBoolean("paired"));
        first.state=ComputerDetails.State.OFFLINE;home.update(first);assertFalse("An old successful check masked the current offline observation",row().getBoolean("online"));assertEquals("unknown",row().getString("reachabilityStatus"));assertTrue(row().getBoolean("paired"));assertEquals("device.connect",row().getString("primaryAction"));assertNotNull(first.serverCert);
    }
    void layoutWide(){android.view.View root=activity.findViewById(android.R.id.content);for(int i=0;i<4;i++){root.requestLayout();root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1180,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(780,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,1180,780);}Shadows.shadowOf(Looper.getMainLooper()).idle();}
    String sidebarStatus()throws Exception{java.lang.reflect.Field field=RhHome.class.getDeclaredField("browser");field.setAccessible(true);RhDeviceBrowser browser=(RhDeviceBrowser)field.get(home);field=RhDeviceBrowser.class.getDeclaredField("rows");field.setAccessible(true);android.view.ViewGroup row=(android.view.ViewGroup)((Map<?,?>)field.get(browser)).get(first.uuid);return ((android.widget.TextView)((android.view.ViewGroup)row.getChildAt(1)).getChildAt(2)).getText().toString();}
    @Test public void completionUpdatesTheWideSettingsSidebarWithoutReplacingTheDraftOrItsEditor()throws Exception{
        Protocol.outcome="auth_rejected";layoutWide();home.perform("device.check",RhActions.obj("uuid",first.uuid));home.perform("device.settings.open",RhActions.obj("uuid",first.uuid));layoutWide();assertEquals(home.state().getJSONObject("homeLayout").toString(),"settings-split",home.state().getJSONObject("homeLayout").getJSONObject("browser").getString("mode"));
        home.perform("settings.input",RhActions.obj("key","bitrateMbps","value","31"));java.lang.reflect.Field field=RhHome.class.getDeclaredField("numberEditors");field.setAccessible(true);android.widget.EditText editor=(android.widget.EditText)((Map<?,?>)field.get(home)).get("bitrateMbps");assertNotNull(editor);assertTrue(editor.requestFocus());String inputs=home.state().getJSONObject("settingsInputs").toString();
        finish();assertEquals("The visible sidebar kept the old device result","认证失败",sidebarStatus());assertEquals("settings",home.state().getString("page"));assertEquals(inputs,home.state().getJSONObject("settingsInputs").toString());assertTrue(editor.hasFocus());assertEquals("31",editor.getText().toString());
    }
    @Test public void changedConnectionInformationHasExplicitStaleFeedbackAndKeepsTheSavedPin()throws Exception{
        first.pairState=com.limelight.nvstream.http.PairingManager.PairState.NOT_PAIRED;Protocol.outcome="auth_rejected";home.perform("device.check",RhActions.obj("uuid",first.uuid));RhShared.saveAddresses(activity,first.uuid,"192.0.2.99",new JSONArray());finish();
        JSONObject check=row().getJSONObject("check");assertTrue("An outdated result vanished without explaining the changed configuration",check.optBoolean("stale"));assertFalse(check.has("outcome"));assertEquals("device.connect",row().getString("primaryAction"));assertEquals("stored",row().getString("pairingStatus"));assertEquals("unknown",row().getString("reachabilityStatus"));assertTrue(RhDeviceCheck.hint(check).contains("已更改"));assertEquals("需重新检查",sidebarStatus());assertNull(RhDeviceCheck.admission(first,check,true).pairState);assertNotNull(first.serverCert);
    }
    void discovery(String outcome){first.state=ComputerDetails.State.ONLINE;first.pairState=com.limelight.nvstream.http.PairingManager.PairState.NOT_PAIRED;first.rhDiscoveryObservation=RhDiscoveryObservation.result(first,first.serverCert,"http",outcome,outcome,!outcome.equals("certificate_mismatch"),System.nanoTime()).bind(RhDiscoveryObservation.begin(activity,first));}
    @Test public void aLaterAuthenticatedDiscoveryRejectionSupersedesTheOldPairedCheck()throws Exception{
        home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();assertTrue(row().getBoolean("paired"));discovery("auth_rejected");home.update(first);
        assertFalse("Old authenticated success masked the later rejection",row().getBoolean("paired"));assertEquals("auth_rejected",row().getString("pairingStatus"));assertEquals("device.pair",row().getString("primaryAction"));assertEquals("认证失败",sidebarStatus());assertNotNull(first.serverCert);
    }
    @Test public void anUnchangedRawPairFlagStillRefreshesTheVisibleProtocolFailure()throws Exception{
        discovery("auth_rejected");home.update(first);assertEquals("认证失败",sidebarStatus());discovery("certificate_mismatch");home.update(first);assertEquals("证书不符",sidebarStatus());
    }
    @Test public void aNewExplicitCheckTakesPrecedenceOverTheOlderDiscoveryObservation()throws Exception{
        discovery("auth_rejected");home.update(first);assertFalse(row().getBoolean("paired"));home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();assertTrue(row().getBoolean("paired"));assertEquals("paired",row().getString("pairingStatus"));
    }
    @Test public void backgroundDiscoveryStillCannotClearAnExplicitIdentityFailure()throws Exception{
        Protocol.outcome="identity_mismatch";home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();discovery("auth_rejected");home.update(first);assertEquals("identity_mismatch",row().getString("pairingStatus"));assertEquals("device.check",row().getString("primaryAction"));
    }
    @Test public void delayedCheckPublicationCannotOverwriteALaterDiscoveryResponse()throws Exception{
        home.perform("device.check",RhActions.obj("uuid",first.uuid));jobs.remove().run();discovery("auth_rejected");home.update(first);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("auth_rejected",row().getString("pairingStatus"));assertFalse(row().getBoolean("paired"));
    }
    @Test public void delayedOldDiscoveryCannotOverwriteTheNewExplicitCheck()throws Exception{
        discovery("auth_rejected");home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();home.update(first);assertEquals("paired",row().getString("pairingStatus"));assertTrue(row().getBoolean("paired"));assertTrue(row().getBoolean("online"));
    }
    @Test public void explicitIdentityFailuresCannotInheritTheOldVerifiedPairingFlag()throws Exception{
        first.state=ComputerDetails.State.ONLINE;first.pairState=com.limelight.nvstream.http.PairingManager.PairState.PAIRED;String saved=RhShared.canonical(RhShared.read(activity));
        for(String outcome:new String[]{"certificate_mismatch","identity_mismatch","invalid_response"}){Protocol.outcome=outcome;home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();assertFalse("Old PAIRED flag survived "+outcome,row().getBoolean("paired"));assertEquals(outcome,row().getString("pairingStatus"));assertEquals(outcome.equals("certificate_mismatch")?"device.pair":"device.check",row().getString("primaryAction"));if(!outcome.equals("certificate_mismatch")){assertFalse(row().getBoolean("online"));assertEquals("unknown",row().getString("reachabilityStatus"));}assertNotNull(first.serverCert);assertEquals(com.limelight.nvstream.http.PairingManager.PairState.PAIRED,first.pairState);assertEquals(saved,RhShared.canonical(RhShared.read(activity)));}
    }
    @Test public void temporaryFailuresKeepTheExistingPairingButRequireStrictAdmissionAgain()throws Exception{
        first.state=ComputerDetails.State.ONLINE;first.pairState=com.limelight.nvstream.http.PairingManager.PairState.PAIRED;
        for(String outcome:new String[]{"tls_failure","timeout","unavailable","unreachable"}){Protocol.outcome=outcome;home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();assertTrue(outcome,row().getBoolean("paired"));assertEquals("device.connect",row().getString("primaryAction"));ComputerDetails admitted=RhDeviceCheck.admission(first,row().getJSONObject("check"),true);assertNull(admitted.pairState);assertSame(first.serverCert,admitted.serverCert);}
    }
    @Test public void pairingFailureColorsDoNotUseTheReachableDevicesSuccessGreen()throws Exception{
        first.state=ComputerDetails.State.ONLINE;first.pairState=com.limelight.nvstream.http.PairingManager.PairState.PAIRED;java.lang.reflect.Field f=RhHome.class.getDeclaredField("browser");f.setAccessible(true);RhDeviceBrowser browser=(RhDeviceBrowser)f.get(home);f=RhDeviceBrowser.class.getDeclaredField("rows");f.setAccessible(true);Map<?,?> rows=(Map<?,?>)f.get(browser);
        for(String outcome:new String[]{"auth_rejected","certificate_mismatch","unpaired","tls_failure","identity_mismatch","invalid_response","timeout","unreachable","unavailable"}){Protocol.outcome=outcome;home.perform("device.check",RhActions.obj("uuid",first.uuid));finish();android.view.ViewGroup cell=(android.view.ViewGroup)rows.get(first.uuid);android.widget.TextView status=(android.widget.TextView)((android.view.ViewGroup)cell.getChildAt(1)).getChildAt(2);assertEquals("Reachable failure was painted as success: "+outcome,RhUiStyle.failure(RhShared.dark(activity)),status.getCurrentTextColor());f=RhDeviceBrowser.class.getDeclaredField("values");f.setAccessible(true);assertEquals(RhUiStyle.failure(RhShared.dark(activity)),((android.widget.TextView[])f.get(browser))[4].getCurrentTextColor());}
    }
}
