package com.limelight.rhremote;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.util.Collections;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real controller and native View buttons, without network or stream side effects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows={RhAdmissionTest.Controls.class,RhAdmissionTest.Transport.class})
public class RhDeviceCheckFlowTest {
    Activity activity; RhHome home; ComputerDetails host; int allRefreshes; String refreshed="";java.util.ArrayDeque<Runnable> checks=new java.util.ArrayDeque<>();
    @Before public void setup()throws Exception {
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        home=new RhHome(activity,new RhHome.Actions(){
            public void connect(ComputerDetails d){throw new AssertionError("No stream in this test");}
            public void add(String address){throw new AssertionError();}public void advanced(){}
            public void pair(ComputerDetails d,String pin){throw new AssertionError("No pairing in this test");}
            public void cancelPair(){}public void refresh(){allRefreshes++;}
            public void refresh(String uuid){refreshed=uuid;}
            public java.util.concurrent.Executor checkExecutor(){return checks::add;}
        });
        host=new ComputerDetails();host.uuid="check-host";host.name="Claw";
        host.manualAddress=new ComputerDetails.AddressTuple("192.0.2.10",47989);
        host.state=ComputerDetails.State.UNKNOWN;
        RhShared.capture(activity,Collections.singletonList(host));home.update(host);
        home.perform("device.select",RhActions.obj("uuid",host.uuid));layout();
    }
    @After public void cleanup(){RhDiagnosticWork work=RhDiagnosticWork.shared(activity);if(work.busy())work.cancel(work.state().optLong("operationId"));while(!checks.isEmpty())checks.remove().run();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();home.dispose();}
    void layout(){View root=activity.findViewById(android.R.id.content);root.measure(View.MeasureSpec.makeMeasureSpec(1008,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(672,View.MeasureSpec.EXACTLY));root.layout(0,0,1008,672);}
    Button button(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button result=button(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
    @Test public void anUnobservedHostIsUnknownAndItsPrimaryButtonChecksOnlyThatHost()throws Exception {
        assertEquals("unknown",RhNetwork.pairingStatus(host));assertEquals("未确认",RhNetwork.pairingLabel(host));
        String saved=RhShared.canonical(RhShared.read(activity));
        Button check=button(activity.findViewById(android.R.id.content),"检查连接");assertNotNull(check);assertTrue(check.performClick());
        assertEquals("",refreshed);assertEquals(host.uuid,home.state().getJSONObject("diagnostics").getString("uuid"));assertEquals("check",home.state().getJSONObject("diagnostics").getString("mode"));assertTrue(home.state().getBoolean("networkBusy"));assertEquals(0,allRefreshes);assertFalse(RhActions.hasStream());assertEquals("devices",home.state().getString("page"));
        assertEquals(saved,RhShared.canonical(RhShared.read(activity)));
    }
    @Test public void scopedRefreshRejectsUnknownTargetsAndToolbarRefreshStillChecksTheList()throws Exception {
        home.perform("devices.refresh",RhActions.obj("uuid",host.uuid));assertEquals(host.uuid,refreshed);assertEquals(0,allRefreshes);
        try{home.perform("devices.refresh",RhActions.obj("uuid","missing"));fail("Unknown target accepted");}catch(IllegalArgumentException expected){}
        home.perform("devices.refresh",RhActions.obj());assertEquals(1,allRefreshes);
    }
    @Test public void aKnownUnpairedObservationIsDifferentFromAnUnavailableUnknownHost()throws Exception {
        host.state=ComputerDetails.State.OFFLINE;home.update(host);layout();assertEquals("unknown",RhNetwork.pairingStatus(host));
        assertNotNull(button(activity.findViewById(android.R.id.content),"检查连接"));
        host.state=ComputerDetails.State.ONLINE;host.pairState=PairingManager.PairState.NOT_PAIRED;home.update(host);layout();
        assertEquals("unpaired",RhNetwork.pairingStatus(host));assertNotNull(button(activity.findViewById(android.R.id.content),"配对"));
    }
    @Test public void aRelayOnlyUnknownDeviceKeepsAnExplicitPairingEntryWithoutPairingDuringCheck()throws Exception{
        RhShared.saveAddresses(activity,host.uuid,"rhr:"+String.join("",Collections.nCopies(64,"a")),new org.json.JSONArray());
        java.util.List<String> sent=new java.util.ArrayList<>();RhDeviceBrowser browser=new RhDeviceBrowser(activity,(id,args)->{assertEquals(host.uuid,args.optString("uuid"));sent.add(id);});
        browser.update(Collections.singletonList(host),host.uuid,"");browser.showDetail(true);browser.measure(View.MeasureSpec.makeMeasureSpec(1008,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(672,View.MeasureSpec.EXACTLY));browser.layout(0,0,1008,672);
        String saved=RhShared.canonical(RhShared.read(activity));Button check=button(browser,"检查连接"),pair=button(browser,"配对");assertNotNull(check);assertNotNull(pair);
        check.performClick();assertEquals(Collections.singletonList("device.check"),sent);pair.performClick();assertEquals(java.util.Arrays.asList("device.check","device.pair"),sent);
        assertEquals(saved,RhShared.canonical(RhShared.read(activity)));assertFalse(RhActions.hasStream());
    }
    @Test public void addingTheStoredCertificateRefreshesTheVisibleButtonWithoutAnotherStateChange()throws Exception{
        host.serverCert=com.limelight.binding.PlatformBinding.getCryptoProvider(activity).getClientCertificate();home.update(host);layout();
        assertEquals("stored",RhNetwork.pairingStatus(host));assertNotNull(button(activity.findViewById(android.R.id.content),"连接"));
        assertTrue(home.state().getJSONArray("devices").getJSONObject(0).getBoolean("canConnect"));assertEquals("device.connect",home.state().getJSONArray("devices").getJSONObject(0).getString("primaryAction"));
        host.pairState=PairingManager.PairState.NOT_PAIRED;home.update(host);layout();assertEquals("unpaired",RhNetwork.pairingStatus(host));assertNotNull(button(activity.findViewById(android.R.id.content),"检查连接"));assertNull(button(activity.findViewById(android.R.id.content),"配对"));
        host.state=ComputerDetails.State.ONLINE;home.update(host);layout();assertNotNull(button(activity.findViewById(android.R.id.content),"配对"));
    }
    @Test public void anUnauthenticatedPairedClaimWithoutALocalCertificateCannotOfferConnect()throws Exception{
        host.pairState=PairingManager.PairState.PAIRED;host.state=ComputerDetails.State.ONLINE;home.update(host);layout();
        assertFalse(RhNetwork.connectInsteadOfPair(host));assertEquals("unpaired",RhNetwork.pairingStatus(host));assertNotNull(button(activity.findViewById(android.R.id.content),"配对"));
        assertFalse(home.state().getJSONArray("devices").getJSONObject(0).getBoolean("canConnect"));assertFalse(home.state().getJSONArray("devices").getJSONObject(0).getBoolean("paired"));
    }
}
