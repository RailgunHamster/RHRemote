package com.limelight.rhremote;

import android.app.Activity;
import android.content.*;
import android.os.Looper;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Actual Home/SyncWork/configuration commit; only transfer and acknowledgement are substituted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhSyncWorkTest.Network.class})
public class RhSyncMembershipTest {
    Activity activity;RhHome home;ComputerDetails alpha,beta;RhSyncWork work;
    final RhHome.Actions actions=new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No stream");}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String pin){throw new AssertionError("No pair");}public void cancelPair(){}public void refresh(){}};
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(com.limelight.R.style.RhTheme);activity.getSharedPreferences("rhremote.global",0).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
        alpha=device("alpha","192.0.2.1");beta=device("beta","192.0.2.2");RhShared.capture(activity,Arrays.asList(alpha,beta));
        home=new RhHome(activity,actions);home.resumed();home.update(alpha);home.update(beta);home.perform("device.select",RhActions.obj("uuid","alpha"));work=RhSyncWork.shared(activity);
        RhSyncWorkTest.Network.runs=0;RhSyncWorkTest.Network.failure=null;RhSyncWorkTest.Network.captured=null;
    }
    ComputerDetails device(String id,String address){ComputerDetails d=new ComputerDetails();d.uuid=id;d.name=id;d.manualAddress=new ComputerDetails.AddressTuple(address,47989);d.state=ComputerDetails.State.ONLINE;d.pairState=PairingManager.PairState.PAIRED;d.serverCert=PlatformBinding.getCryptoProvider(activity).getClientCertificate();return d;}
    @After public void cleanup(){home.dispose();}
    JSONObject onlyBeta()throws Exception{JSONObject next=new JSONObject(RhShared.read(activity).toString());JSONArray rows=next.getJSONArray("devices");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("uuid").equals("beta")){next.put("devices",new JSONArray().put(rows.getJSONObject(i)));return next;}throw new AssertionError("Fixture beta missing");}
    void sync(JSONObject next)throws Exception{
        RhSyncWorkTest.Network.result=RhActions.obj("shared",next,"conflicts",new JSONArray());home.perform("sync.run",RhActions.obj());long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(work.busy()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(work.state().toString(),work.busy());assertEquals(work.state().toString(),"completed",work.phase());
    }
    List<String> visible()throws Exception{List<String> ids=new ArrayList<>();JSONArray rows=home.state().getJSONArray("devices");for(int i=0;i<rows.length();i++)ids.add(rows.getJSONObject(i).getString("uuid"));return ids;}
    List<String> saved(JSONObject document)throws Exception{List<String> ids=new ArrayList<>();JSONArray rows=document.getJSONArray("devices");for(int i=0;i<rows.length();i++)ids.add(rows.getJSONObject(i).getString("uuid"));return ids;}
    @Test public void completedSyncRemovesTheOldCardAndSelectsTheRemainingDevice()throws Exception{
        java.security.cert.X509Certificate alphaPin=alpha.serverCert,betaPin=beta.serverCert;sync(onlyBeta());assertEquals(Collections.singletonList("beta"),visible());assertEquals("beta",home.state().getString("selected"));assertSame(alphaPin,alpha.serverCert);assertSame(betaPin,beta.serverCert);assertFalse(RhShared.read(activity).has("deviceMembershipAuthoritative"));
    }
    @Test public void aLateNativeUpdateCannotRestoreTheRemovedCardOrItsActions()throws Exception{
        sync(onlyBeta());home.update(new ComputerDetails(alpha));assertEquals(Collections.singletonList("beta"),visible());JSONObject response=RhActions.dispatch("device.select",RhActions.obj("uuid","alpha"));assertFalse(response.toString(),response.getBoolean("ok"));assertFalse(RhActions.hasStream());
    }
    @Test public void theNextSyncCannotReexportAnOldNativeCacheEntry()throws Exception{
        sync(onlyBeta());JSONObject captured=RhShared.capture(activity,Arrays.asList(alpha,beta));assertEquals(Collections.singletonList("beta"),saved(captured));sync(onlyBeta());assertEquals(Collections.singletonList("beta"),saved(RhSyncWorkTest.Network.captured));
    }
    @Test public void aColdHomeStillIgnoresTheOldNativeCacheWithoutDeletingThePin()throws Exception{
        java.security.cert.X509Certificate pin=alpha.serverCert;sync(onlyBeta());home.dispose();home=new RhHome(activity,actions);home.resumed();home.update(new ComputerDetails(alpha));home.update(new ComputerDetails(beta));assertEquals(Collections.singletonList("beta"),visible());assertSame(pin,alpha.serverCert);
    }
    @Test public void anExplicitAddCanEnrollTheSameDeviceAgain()throws Exception{
        sync(onlyBeta());RhShared.addDevice(activity,new ComputerDetails(alpha),verified->verified);home.update(new ComputerDetails(alpha));assertEquals(Arrays.asList("beta","alpha"),visible());assertEquals(Arrays.asList("beta","alpha"),saved(RhShared.capture(activity,Arrays.asList(alpha,beta))));
    }
    @Test public void aliasAndOrderChangesRetainOtherDevicesAndAuthenticationEvidence()throws Exception{
        RhDiagnosticWork diagnostic=RhDiagnosticWork.shared(activity);Map<String,JSONObject> checks=ReflectionHelpers.getField(diagnostic,"checks");JSONObject check=RhDeviceCheck.report(alpha.uuid,alpha.uuid,"https",true,"auth_rejected",System.nanoTime());check.put("requestIntent",RhDeviceCheck.intent(activity,RhShared.read(activity),alpha));checks.put(alpha.uuid,check);
        JSONObject next=new JSONObject(RhShared.read(activity).toString());JSONArray rows=next.getJSONArray("devices");rows.getJSONObject(0).put("displayName","Remote alias");next.put("devices",new JSONArray().put(rows.getJSONObject(1)).put(rows.getJSONObject(0)));sync(next);
        assertEquals(Arrays.asList("beta","alpha"),visible());assertTrue(diagnostic.hasCheck("alpha"));JSONObject row=home.state().getJSONArray("devices").getJSONObject(1);assertEquals("Remote alias",row.getString("displayName"));assertEquals("auth_rejected",row.getJSONObject("check").getString("outcome"));assertEquals("device.pair",row.getString("primaryAction"));
    }
    @Test public void legacyDiscoveryBeforeAnyImportStillAppearsAndIsCaptured()throws Exception{
        ComputerDetails gamma=device("gamma","192.0.2.3");home.update(gamma);assertEquals(Arrays.asList("alpha","beta","gamma"),visible());assertEquals(Arrays.asList("alpha","beta","gamma"),saved(RhShared.capture(activity,Arrays.asList(alpha,beta,gamma))));
    }
    @Test public void anInvalidImportDoesNotHideLegacyDiscoveredDevices()throws Exception{
        JSONObject invalid=onlyBeta();invalid.put("schemaVersion",999);try{RhShared.apply(activity,invalid);fail("Invalid document accepted");}catch(Exception expected){}
        ComputerDetails gamma=device("gamma","192.0.2.3");home.update(gamma);assertEquals(Arrays.asList("alpha","beta","gamma"),visible());
    }
    @Test public void failedMembershipPersistenceCannotApplyTheRemoteRemoval()throws Exception{
        String before=RhShared.canonical(RhShared.read(activity));Context wrapper=new ContextWrapper(activity){@Override public SharedPreferences getSharedPreferences(String name,int mode){SharedPreferences actual=super.getSharedPreferences(name,mode);return (SharedPreferences)Proxy.newProxyInstance(getClassLoader(),new Class[]{SharedPreferences.class},(proxy,method,args)->{
            if(!method.getName().equals("edit"))return method.invoke(actual,args);SharedPreferences.Editor editor=actual.edit();Object[] replacement={null};replacement[0]=Proxy.newProxyInstance(getClassLoader(),new Class[]{SharedPreferences.Editor.class},(p,m,a)->{if(m.getName().equals("commit"))return false;Object result=m.invoke(editor,a);return m.getReturnType()==SharedPreferences.Editor.class?replacement[0]:result;});return replacement[0];
        });}};
        boolean failed=false;try{RhShared.apply(wrapper,onlyBeta());}catch(IOException expected){failed=true;}assertTrue("Membership policy was not persisted before replacing the document",failed);assertEquals(before,RhShared.canonical(RhShared.read(activity)));
    }
    @Test public void anEmptyImportedListCannotBeRefilledByTheOldCache()throws Exception{
        JSONObject next=new JSONObject(RhShared.read(activity).toString());next.put("devices",new JSONArray());sync(next);home.update(alpha);home.update(beta);assertTrue(visible().isEmpty());assertEquals("",home.state().getString("selected"));assertTrue(saved(RhShared.capture(activity,Arrays.asList(alpha,beta))).isEmpty());
    }
    @Test public void aCorruptLocalMarkerCannotRestoreOldRowsAndANewImportRepairsIt()throws Exception{
        activity.getSharedPreferences("rhremote.global",0).edit().putString("deviceMembershipAuthoritative","corrupt").commit();ComputerDetails gamma=device("gamma","192.0.2.3");home.update(gamma);assertEquals(Arrays.asList("alpha","beta"),visible());sync(onlyBeta());assertTrue(activity.getSharedPreferences("rhremote.global",0).getBoolean("deviceMembershipAuthoritative",false));assertEquals(Collections.singletonList("beta"),visible());
    }
    @Test public void aGenuinelyNewDiscoveryAfterSyncStillAppearsAndIsCaptured()throws Exception{
        sync(onlyBeta());ComputerDetails gamma=device("gamma","192.0.2.3");home.update(gamma);assertEquals(Arrays.asList("beta","gamma"),visible());assertEquals(Arrays.asList("beta","gamma"),saved(RhShared.capture(activity,Arrays.asList(alpha,beta,gamma))));
    }
    @Test public void aNewDiscoveryDuringTheTransferIsNotMistakenForARemovedDevice()throws Exception{
        JSONObject next=onlyBeta();ComputerDetails gamma=device("gamma","192.0.2.3");List<Runnable> queued=new ArrayList<>();RhSyncWorkTest.Network.result=RhActions.obj("shared",next,"conflicts",new JSONArray());work.start(RhShared.read(activity),"review",queued::add);home.update(gamma);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("completed",work.phase());assertEquals(Arrays.asList("beta","gamma"),visible());
    }
    @Test public void changingUuidCaseCannotBypassTheRemoval()throws Exception{
        sync(onlyBeta());ComputerDetails old=new ComputerDetails(alpha);old.uuid="ALPHA";home.update(old);assertEquals(Collections.singletonList("beta"),visible());assertEquals(Collections.singletonList("beta"),saved(RhShared.capture(activity,Arrays.asList(old,beta))));
    }
    @Test public void repairingAMarkerPreservesEarlierRemovals()throws Exception{
        sync(onlyBeta());activity.getSharedPreferences("rhremote.global",0).edit().putString("deviceMembershipAuthoritative","corrupt").commit();sync(onlyBeta());home.update(alpha);home.update(device("gamma","192.0.2.3"));assertEquals(Arrays.asList("beta","gamma"),visible());
    }
    @Test public void aLaterImportCanExplicitlyRestoreTheDevice()throws Exception{
        JSONObject original=RhShared.read(activity);sync(onlyBeta());sync(original);home.update(alpha);assertEquals(Arrays.asList("alpha","beta"),visible());assertTrue(activity.getSharedPreferences("rhremote.global",0).getStringSet("removedSharedDevices",Collections.emptySet()).isEmpty());
    }
    @Test public void aRecordedRemovalCannotHideMembersOfTheCurrentDocument()throws Exception{
        activity.getSharedPreferences("rhremote.global",0).edit().putBoolean("deviceMembershipAuthoritative",true).putStringSet("removedSharedDevices",new HashSet<>(Collections.singletonList("alpha"))).commit();
        assertTrue(RhShared.acceptsDiscovery(activity,RhShared.read(activity),"alpha"));home.update(alpha);assertEquals(Arrays.asList("alpha","beta"),visible());assertEquals(Arrays.asList("alpha","beta"),saved(RhShared.capture(activity,Arrays.asList(alpha,beta))));
    }
}
