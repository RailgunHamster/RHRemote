package com.limelight.rhremote;

import android.app.Activity;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import com.limelight.nvstream.http.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public class RhAddFlowTest {
    Activity activity;RhHome home;View root;CountDownLatch entered,release;String failure="";boolean failSave;int commits;volatile RhDeviceAdd request;
    boolean awaitingBackend;long readyBudget=1000;java.util.concurrent.atomic.AtomicReference<Object> backendReady=new java.util.concurrent.atomic.AtomicReference<>();
    ComputerDetails device(){ComputerDetails d=new ComputerDetails();d.uuid="new-host-123";d.name="New server";d.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);d.activeAddress=d.manualAddress;d.state=ComputerDetails.State.ONLINE;d.pairState=PairingManager.PairState.NOT_PAIRED;return d;}
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);resetProbe();
        home=new RhHome(activity,new RhHome.Actions(){
            public void connect(ComputerDetails d){throw new AssertionError();}public void add(String a){throw new AssertionError("Legacy unowned add must not run");}public void advanced(){}public void pair(ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}
            public ComputerDetails inspectAdd(String address,RhDeviceAdd operation)throws Exception{request=operation;if(awaitingBackend)operation.awaitReady(backendReady::get,readyBudget);CountDownLatch gate=release;String error=failure;entered.countDown();if(!gate.await(3,TimeUnit.SECONDS))throw new java.io.IOException("fixture timeout");if(!error.isEmpty())throw new java.io.IOException(error);ComputerDetails d=device();d.manualAddress=new ComputerDetails.AddressTuple(address,47989);d.activeAddress=d.manualAddress;return d;}
            public ComputerDetails commitAdd(ComputerDetails d)throws Exception{commits++;if(failSave)throw new java.io.IOException("存储不可用");return d;}
        });root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);home.perform("device.add.open",RhActions.obj());
    }
    void resetProbe(){entered=new CountDownLatch(1);release=new CountDownLatch(1);}
    @After public void cleanup(){release.countDown();home.dispose();}
    View find(View v,String label){if(v.getVisibility()!=View.VISIBLE)return null;if(v instanceof EditText&&label.contentEquals(v.getContentDescription()))return v;if(v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=find(((ViewGroup)v).getChildAt(i),label);if(child!=null)return child;}return null;}
    View findText(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    void input(String address){((EditText)find(root,"设备地址")).setText(address);}
    void start(String address)throws Exception{input(address);((Button)find(root,"添加")).performClick();assertTrue(entered.await(1,TimeUnit.SECONDS));assertEquals("probing",home.state().getJSONObject("add").getString("phase"));}
    void finish()throws Exception{release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(home.state().getJSONObject("add").getString("phase").equals("editing")&&request.isFinished())return;Thread.sleep(2);}while(System.nanoTime()<deadline);fail("lookup did not finish");}
    @Test public void invalidInputStaysVisibleWithoutStartingWork()throws Exception{
        String before=RhShared.canonical(RhShared.read(activity));input("bad/path");((Button)find(root,"添加")).performClick();assertEquals(1,entered.getCount());assertEquals("add",home.state().getString("page"));assertEquals("bad/path",home.state().getJSONObject("add").getString("address"));assertFalse(home.state().getJSONObject("add").getString("error").isEmpty());assertEquals(before,RhShared.canonical(RhShared.read(activity)));
    }
    @Test public void waitStateBlocksDuplicatesAndSuccessSelectsSavedDevice()throws Exception{
        start("192.168.0.104");assertFalse(find(root,"设备地址").isEnabled());assertFalse(find(root,"正在查找…").isEnabled());try{home.perform("device.add.submit",RhActions.obj());fail("duplicate accepted");}catch(IllegalStateException expected){}
        assertEquals(0,RhShared.read(activity).getJSONArray("devices").length());finish();assertEquals(1,commits);assertEquals("devices",home.state().getString("page"));assertEquals("new-host-123",home.state().getString("selected"));assertEquals(1,RhShared.read(activity).getJSONArray("devices").length());
    }
    @Test public void failureRetainsTheAddressAndAllowsRetry()throws Exception{
        failure="设备不可达";start("192.168.0.104");finish();assertEquals("add",home.state().getString("page"));assertEquals("192.168.0.104",((EditText)find(root,"设备地址")).getText().toString());assertEquals("设备不可达",home.state().getJSONObject("add").getString("error"));assertEquals(0,commits);
        failure="";resetProbe();start("100.73.139.109");finish();assertEquals("100.73.139.109",RhShared.read(activity).getJSONArray("devices").getJSONObject(0).getString("address"));
    }
    @Test public void cancellingNeverSavesALateSuccessfulResponse()throws Exception{
        String before=RhShared.canonical(RhShared.read(activity));start("192.168.0.104");home.perform("device.add.cancel",RhActions.obj());assertEquals("devices",home.state().getString("page"));finish();assertEquals(0,commits);assertEquals("devices",home.state().getString("page"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
    }
    @Test public void systemBackReturnsToDevicesAndRetiresAnyLookup()throws Exception{
        input("100.73.");assertTrue(home.goBack());assertEquals("devices",home.state().getString("page"));home.perform("device.add.open",RhActions.obj());assertEquals("",home.state().getJSONObject("add").getString("address"));start("192.168.0.104");assertTrue(home.goBack());assertEquals("devices",home.state().getString("page"));finish();assertEquals(0,commits);assertEquals("devices",home.state().getString("page"));
    }
    @Test public void systemBackFromSettingsProtectsAnUnsavedSyncForm()throws Exception{
        home.perform("app.settings.tab",RhActions.obj("tab","sync"));home.perform("sync.edit",RhActions.obj("key","url","value","invalid"));assertTrue(home.goBack());assertEquals("unsaved-sync",home.state().getJSONObject("navigationPrompt").getString("kind"));assertEquals("invalid",home.state().getJSONObject("syncDraft").getString("url"));home.perform("navigation.resolve",RhActions.obj("choice","discard"));assertEquals("devices",home.state().getString("page"));
    }
    @Test public void navigationCancelsLookupBeforeOpeningAnotherPage()throws Exception{
        start("192.168.0.104");home.perform("app.settings.tab",RhActions.obj("tab","updates"));assertEquals("global-settings",home.state().getString("page"));finish();assertEquals("updates",home.state().getString("settingsTab"));assertEquals("global-settings",home.state().getString("page"));assertEquals(0,commits);
    }
    @Test public void themeChangeCancelsLookupOnceAndPreservesTheAddressWithoutSavingALateResult()throws Exception{
        boolean before=RhShared.dark(activity);start("192.168.0.104");home.perform("appearance.toggle",RhActions.obj());
        assertEquals(!before,RhShared.dark(activity));assertEquals("add",home.state().getString("page"));assertEquals("editing",home.state().getJSONObject("add").getString("phase"));assertEquals("192.168.0.104",home.state().getJSONObject("add").getString("address"));
        finish();assertEquals(0,commits);assertEquals("add",home.state().getString("page"));assertEquals("192.168.0.104",home.state().getJSONObject("add").getString("address"));assertEquals(0,RhShared.read(activity).getJSONArray("devices").length());
    }
    @Test public void storageFailureRollsBackSharedMetadataAndPreservesInput()throws Exception{
        String before=RhShared.canonical(RhShared.read(activity));failSave=true;start("192.168.0.104");finish();assertEquals(before,RhShared.canonical(RhShared.read(activity)));assertEquals("add",home.state().getString("page"));assertEquals("192.168.0.104",home.state().getJSONObject("add").getString("address"));assertEquals("存储不可用",home.state().getJSONObject("add").getString("error"));
    }
    @Test public void lateCancelledWorkCannotSaveIntoANewerLookup()throws Exception{
        start("192.168.0.104");RhDeviceAdd retired=request;CountDownLatch oldRelease=release;home.perform("device.add.cancel",RhActions.obj());home.perform("device.add.open",RhActions.obj());resetProbe();start("100.73.139.109");oldRelease.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!retired.isFinished()){Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(System.nanoTime()<deadline);Thread.sleep(2);}assertEquals(0,commits);assertEquals("probing",home.state().getJSONObject("add").getString("phase"));finish();assertEquals(1,commits);assertEquals("100.73.139.109",RhShared.read(activity).getJSONArray("devices").getJSONObject(0).getString("address"));
    }
    @Test public void preparingShowsProgressAndContinuesTheOriginalSingleSubmit()throws Exception{
        awaitingBackend=true;input("192.168.0.104");((Button)find(root,"添加")).performClick();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!home.state().getJSONObject("add").getString("phase").equals("preparing")){Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(System.nanoTime()<deadline);Thread.sleep(2);}Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(find(root,"设备地址").isEnabled());assertFalse(find(root,"正在准备…").isEnabled());assertNotNull(findText(root,"正在准备设备查找…"));assertEquals(0,commits);assertEquals("",home.state().getJSONObject("add").getString("error"));try{home.perform("device.add.submit",RhActions.obj());fail("Preparing accepted a second submit");}catch(IllegalStateException expected){}
        backendReady.set(new Object());assertTrue(entered.await(1,TimeUnit.SECONDS));finish();assertEquals(1,commits);assertEquals("devices",home.state().getString("page"));
    }
    @Test public void preparationTimeoutKeepsInputAndAllowsAnExplicitRetry()throws Exception{
        awaitingBackend=true;readyBudget=30;input("192.168.0.104");((Button)find(root,"添加")).performClick();finish();assertEquals("add",home.state().getString("page"));assertEquals("192.168.0.104",((EditText)find(root,"设备地址")).getText().toString());assertTrue(home.state().getJSONObject("add").getString("error").contains("尚未准备好"));assertTrue(find(root,"添加").isEnabled());assertEquals(0,commits);
        backendReady.set(new Object());resetProbe();start("192.168.0.104");finish();assertEquals(1,commits);assertEquals("devices",home.state().getString("page"));
    }
    @Test public void readdingPreservesAliasOrderPrimaryAndClientProfile()throws Exception{
        ComputerDetails old=device();home.update(old);RhShared.capture(activity,java.util.Collections.singletonList(old));RhShared.saveProfile(activity,old,RhProfile.load(activity,old.uuid),"工作机","192.168.0.104",new org.json.JSONArray());JSONObject before=RhShared.read(activity);String profiles=RhShared.canonical(before.getJSONObject("profiles"));start("100.73.139.109");finish();JSONObject after=RhShared.read(activity),host=after.getJSONArray("devices").getJSONObject(0);assertEquals(1,after.getJSONArray("devices").length());assertEquals("工作机",host.getString("displayName"));assertEquals("192.168.0.104",host.getString("address"));assertEquals("100.73.139.109",host.getJSONArray("addresses").getString(0));assertEquals(profiles,RhShared.canonical(after.getJSONObject("profiles")));
    }
}
