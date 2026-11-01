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
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhDiagnosticFlowTest.Network.class,RhDiagnosticFlowTest.Transport.class})
public class RhDiagnosticFlowTest {
    @Implements(value=RhNetwork.class,isInAndroidSdk=false)
    public static class Network {
        static CountDownLatch entered,release,finished;
        static int calls;
        @Implementation protected static JSONObject diagnose(Context context,ComputerDetails source,RhProfile profile,JSONObject options,RhCancellation operation)throws Exception{
            calls++;entered.countDown();
            try(RhCancellation.Watch request=operation.watch(()->release.countDown())) {assertTrue("Diagnostic was never released",release.await(5,TimeUnit.SECONDS));operation.checkpoint();return RhActions.obj("uuid",source.uuid,"selectedAddress","192.0.2.1","attempts",new org.json.JSONArray());}
            finally {finished.countDown();}
        }
    }
    @Implements(value=RhTunnel.class,isInAndroidSdk=false)
    public static class Transport {
        static volatile CountDownLatch stopping,release;static volatile boolean fail;static int stops;
        @Implementation protected static void stop(){stops++;if(stopping!=null)stopping.countDown();try{if(release!=null)assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}if(fail)throw new IllegalStateException("Injected cleanup failure");}
    }
    Activity activity;RhHome home;
    final RhHome.Actions callbacks=new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No stream in diagnostic UI tests");}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String pin){throw new AssertionError();}public void cancelPair(){}public void refresh(){}};
    void create(){
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        home=new RhHome(activity,callbacks);ComputerDetails d=new ComputerDetails();d.uuid="host";d.name="winserver";d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.1",47989);home.update(d);
    }
    @Before public void setup()throws Exception{
        Network.entered=new CountDownLatch(1);Network.release=new CountDownLatch(1);Network.finished=new CountDownLatch(1);Network.calls=0;
        Transport.stops=0;Transport.fail=false;Transport.stopping=null;Transport.release=null;
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
        Network.release.countDown();if(Transport.release!=null)Transport.release.countDown();Transport.fail=false;if(Network.calls>0)Network.finished.await(3,TimeUnit.SECONDS);awaitIdle();
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
    @Test public void anotherDeviceDoesNotPretendToBeTheActiveDiagnosticAndReenablesAfterCompletion()throws Exception{
        start();ComputerDetails other=new ComputerDetails();other.uuid="other";other.name="Claw";other.manualAddress=new ComputerDetails.AddressTuple("192.0.2.2",47989);home.update(other);
        home.perform("device.settings.open",RhActions.obj("uuid","other"));home.perform("settings.tab",RhActions.obj("tab","network"));
        assertNotNull("The current device must keep its own action label",findButton(activity.findViewById(android.R.id.content),"诊断连接"));
        assertFalse(findButton(activity.findViewById(android.R.id.content),"诊断连接").isEnabled());complete();assertTrue(findButton(activity.findViewById(android.R.id.content),"诊断连接").isEnabled());
    }
    android.widget.Button findButton(android.view.View view,String label){if(view.getVisibility()!=android.view.View.VISIBLE)return null;if(view instanceof android.widget.Button&&label.contentEquals(((android.widget.Button)view).getText()))return (android.widget.Button)view;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.widget.Button found=findButton(((android.view.ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    void awaitIdle()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(!home.state().getBoolean("networkBusy"))return;Thread.sleep(10);}while(System.nanoTime()<end);}
    void resetNetwork(){Network.entered=new CountDownLatch(1);Network.release=new CountDownLatch(1);Network.finished=new CountDownLatch(1);}
    @Test public void cancelReturnsQuicklyButWaitsForCleanupBeforeARealRetry()throws Exception{
        String saved=RhShared.canonical(RhShared.read(activity));start();Transport.stopping=new CountDownLatch(1);Transport.release=new CountDownLatch(1);
        android.widget.Button cancel=findButton(activity.findViewById(android.R.id.content),"取消诊断");assertNotNull(cancel);long began=System.nanoTime();assertTrue(cancel.performClick());assertTrue(System.nanoTime()-began<TimeUnit.MILLISECONDS.toNanos(250));assertTrue(Transport.stopping.await(3,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(home.state().getBoolean("networkBusy"));assertEquals("cancelling",home.state().getJSONObject("diagnostics").getString("phase"));assertFalse(cancel.isEnabled());
        try{home.perform("device.connect",RhActions.obj("uuid","host"));fail("Connection overlapped cleanup");}catch(IllegalStateException expected){}
        Transport.release.countDown();awaitIdle();assertFalse(home.state().getBoolean("networkBusy"));assertEquals("cancelled",home.state().getJSONObject("diagnostics").getString("phase"));assertEquals(saved,RhShared.canonical(RhShared.read(activity)));
        Transport.stopping=null;Transport.release=null;resetNetwork();start();complete();assertEquals(2,Network.calls);assertEquals("completed",home.state().getJSONObject("diagnostics").getString("phase"));
    }
    @Test public void cleanupFailureKeepsOwnershipAndRetriesOnlyCleanup()throws Exception{
        start();Transport.fail=true;complete();assertTrue(home.state().getBoolean("networkBusy"));assertEquals("cleanup-error",home.state().getJSONObject("diagnostics").getString("phase"));
        android.widget.Button retry=findButton(activity.findViewById(android.R.id.content),"重试清理");assertNotNull(retry);assertTrue(retry.isEnabled());assertTrue(RhActions.inspecting());
        long id=home.state().getJSONObject("diagnostics").getLong("operationId");try{home.perform("device.network.cancel",RhActions.obj("operationId",id,"expectedAction","cancel"));fail("Old cancel became a cleanup retry");}catch(IllegalStateException expected){}
        Transport.fail=false;retry.performClick();awaitIdle();assertFalse(home.state().getBoolean("networkBusy"));assertFalse(RhActions.inspecting());assertEquals(1,Network.calls);assertEquals(2,Transport.stops);
    }
    @Test public void oldCancellationCannotCancelANewerRunningDiagnostic()throws Exception{
        start();long old=home.state().getJSONObject("diagnostics").getLong("operationId");complete();resetNetwork();start();
        try{home.perform("device.network.cancel",RhActions.obj("operationId",old));fail("Old cancellation targeted the new task");}catch(IllegalStateException expected){}
        assertFalse(home.state().getJSONObject("diagnostics").getBoolean("cancelRequested"));complete();assertEquals(2,Network.calls);
    }
    @Test public void editedDraftMarksTheOldResultAndRejectsTheOldStartIntent()throws Exception{
        String old=org.robolectric.util.ReflectionHelpers.<RhDiagnosticWork>getField(home,"diagnostics").state().optString("requestIntent");start();old=home.state().getJSONObject("diagnostics").getString("requestIntent");
        home.perform("settings.network.edit",RhActions.obj("key","address","value","192.0.2.9"));complete();android.widget.TextView report=org.robolectric.util.ReflectionHelpers.getField(home,"diagnosticReport");assertTrue(report.getText().toString().contains("上次诊断结果（连接设置已更改）"));
        try{home.perform("device.network.diagnose",RhActions.obj("uuid","host","expectedIntent",old));fail("Old configuration was diagnosed");}catch(IllegalStateException expected){}
        assertEquals(1,Network.calls);assertEquals("192.0.2.9",home.state().getString("draftAddress"));
    }
    @Test public void cancellationStillTargetsTheSameTaskWhenItHasJustEnteredCleanup()throws Exception{
        start();long id=home.state().getJSONObject("diagnostics").getLong("operationId");Transport.stopping=new CountDownLatch(1);Transport.release=new CountDownLatch(1);Network.release.countDown();assertTrue(Transport.stopping.await(3,TimeUnit.SECONDS));
        home.perform("device.network.cancel",RhActions.obj("operationId",id,"expectedAction","cancel"));assertTrue(home.state().getJSONObject("diagnostics").getBoolean("cancelRequested"));Transport.release.countDown();awaitIdle();assertEquals("cancelled",home.state().getJSONObject("diagnostics").getString("phase"));
    }
    @Test public void backgroundDiagnosticHasAVisibleCancellationOnOtherPages()throws Exception{
        start();home.perform("settings.cancel",RhActions.obj());home.perform("app.settings.tab",RhActions.obj("tab","about"));android.view.View root=activity.findViewById(android.R.id.content);android.view.View banner=root.findViewWithTag("diagnostic.banner");android.widget.Button cancel=(android.widget.Button)root.findViewWithTag("diagnostic.banner.cancel");
        assertEquals(android.view.View.VISIBLE,banner.getVisibility());assertTrue(cancel.isEnabled());cancel.performClick();awaitIdle();assertEquals("cancelled",home.state().getJSONObject("diagnostics").getString("phase"));assertEquals("about",home.state().getString("settingsTab"));assertEquals(android.view.View.GONE,banner.getVisibility());
    }
    @Test public void detailsAreBoundToTheCompletedDeviceTaskAndExpansionState()throws Exception{
        start();long id=home.state().getJSONObject("diagnostics").getLong("operationId");try{home.perform("device.network.details.toggle",RhActions.obj("uuid","host","operationId",id));fail("Unfinished details opened");}catch(IllegalStateException expected){}complete();
        try{home.perform("device.network.details.toggle",RhActions.obj("uuid","other","operationId",id));fail("Wrong device details opened");}catch(IllegalStateException expected){}
        assertTrue(home.perform("device.network.details.toggle",RhActions.obj("uuid","host","operationId",id,"expectedExpanded",false)).getBoolean("expanded"));assertTrue(home.state().getBoolean("diagnosticDetailsExpanded"));
        try{home.perform("device.network.details.toggle",RhActions.obj("uuid","host","operationId",id,"expectedExpanded",false));fail("Old expansion toggled the new state");}catch(IllegalStateException expected){}
        assertFalse(home.perform("device.network.details.toggle",RhActions.obj("uuid","host","operationId",id,"expectedExpanded",true)).getBoolean("expanded"));assertFalse(home.state().getBoolean("diagnosticDetailsExpanded"));assertEquals(1,Network.calls);
    }
    @Test public void recreatingThePageKeepsExpandedDetailsForTheSameCompletedTask()throws Exception{
        start();complete();long id=home.state().getJSONObject("diagnostics").getLong("operationId");home.perform("device.network.details.toggle",RhActions.obj("uuid","host","operationId",id));Bundle navigation=new Bundle();home.saveNavigation(navigation);Object retained=home.retainUiState();home.dispose();
        create();home.restoreNavigation(navigation);home.restoreUiState(retained);assertTrue(home.state().getBoolean("diagnosticDetailsExpanded"));android.widget.TextView details=org.robolectric.util.ReflectionHelpers.getField(home,"diagnosticDetailsText");assertEquals(android.view.View.VISIBLE,details.getVisibility());assertTrue(details.getText().toString().contains("192.0.2.1"));assertEquals(1,Network.calls);
    }
}
