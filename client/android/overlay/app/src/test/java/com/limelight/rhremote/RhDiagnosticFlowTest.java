package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhDiagnosticFlowTest.Network.class})
public class RhDiagnosticFlowTest {
    @Implements(value=RhNetwork.class,isInAndroidSdk=false)
    public static class Network {
        static CountDownLatch entered,release,finished;
        static int calls;
        @Implementation protected static JSONObject diagnose(Context context,ComputerDetails source,RhProfile profile,JSONObject options)throws Exception{
            calls++;entered.countDown();
            try {assertTrue("Diagnostic was never released",release.await(5,TimeUnit.SECONDS));return RhActions.obj("uuid",source.uuid,"selectedAddress","192.0.2.1","attempts",new org.json.JSONArray());}
            finally {finished.countDown();}
        }
    }
    Activity activity;RhHome home;
    final RhHome.Actions callbacks=new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No stream in diagnostic UI tests");}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String pin){throw new AssertionError();}public void cancelPair(){}public void refresh(){}};
    void create(){
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        home=new RhHome(activity,callbacks);ComputerDetails d=new ComputerDetails();d.uuid="host";d.name="winserver";d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.1",47989);home.update(d);
    }
    @Before public void setup()throws Exception{
        Network.entered=new CountDownLatch(1);Network.release=new CountDownLatch(1);Network.finished=new CountDownLatch(1);Network.calls=0;
        create();home.perform("device.settings.open",RhActions.obj("uuid","host"));home.perform("settings.tab",RhActions.obj("tab","network"));
    }
    void start()throws Exception {home.perform("device.network.diagnose",RhActions.obj("uuid","host"));assertTrue(Network.entered.await(3,TimeUnit.SECONDS));assertTrue(home.state().getBoolean("networkBusy"));}
    void complete()throws Exception{
        Network.release.countDown();assertTrue(Network.finished.await(3,TimeUnit.SECONDS));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do {Shadows.shadowOf(Looper.getMainLooper()).idle();if(!home.state().getBoolean("networkBusy"))break;Thread.sleep(10);}while(System.nanoTime()<deadline);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @After public void cleanup()throws Exception{
        Network.release.countDown();if(Network.calls>0)Network.finished.await(3,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        if(home!=null)home.dispose();
    }
    @Test public void recreatedPageKeepsTheInFlightDiagnosticAndRejectsDuplicates()throws Exception{
        String before=RhShared.canonical(RhShared.read(activity));start();Bundle navigation=new Bundle();home.saveNavigation(navigation);home.dispose();
        create();home.restoreNavigation(navigation);
        assertEquals("settings",home.state().getString("page"));assertEquals("network",home.state().getString("profileTab"));
        assertTrue("A recreated page forgot the still-running diagnostic",home.state().getBoolean("networkBusy"));
        assertFalse(RhActions.dispatch("device.network.diagnose",RhActions.obj("uuid","host")).getBoolean("ok"));assertEquals(1,Network.calls);
        complete();assertFalse(home.state().getBoolean("networkBusy"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
    }
    @Test public void diagnosticCompletionDoesNotReplaceAnotherPagesFeedback()throws Exception{
        start();home.perform("settings.cancel",RhActions.obj());home.perform("app.settings.tab",RhActions.obj("tab","about"));String before=home.state().getString("status");
        complete();assertEquals("about",home.state().getString("settingsTab"));assertEquals("A background diagnostic changed the other page's message",before,home.state().getString("status"));
    }
}
