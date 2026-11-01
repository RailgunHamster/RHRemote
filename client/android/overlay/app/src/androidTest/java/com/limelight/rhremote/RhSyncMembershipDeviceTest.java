package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real sync controls/HTTP after a controlled full-document replacement; discovery and Add transport are substituted. */
@RunWith(AndroidJUnit4.class)
public final class RhSyncMembershipDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final JSONArray cases=new JSONArray();final AtomicInteger inspections=new AtomicInteger(),commits=new AtomicInteger();
    ComputerDetails alpha,beta,gamma;String prefix,foreign,runId;
    final RhHome.Actions actions=new RhHome.Actions(){
        public void connect(ComputerDetails d){throw new AssertionError("No remote stream");}public void add(String a){}public void advanced(){}
        public void pair(ComputerDetails d,String pin){throw new AssertionError("No pairing");}public void cancelPair(){}public void refresh(){}
        public ComputerDetails inspectAdd(String address,RhDeviceAdd operation){assertEquals(alpha.manualAddress.address,address);inspections.incrementAndGet();return new ComputerDetails(alpha);}
        public ComputerDetails commitAdd(ComputerDetails verified){assertEquals(alpha.uuid,verified.uuid);commits.incrementAndGet();return new ComputerDetails(verified);}
    };
    ComputerDetails device(String suffix,String address){ComputerDetails d=new ComputerDetails();d.uuid=prefix+suffix;d.name="Fixture "+suffix;d.manualAddress=new ComputerDetails.AddressTuple(address,47989);d.state=ComputerDetails.State.ONLINE;d.pairState=PairingManager.PairState.PAIRED;d.serverCert=PlatformBinding.getCryptoProvider(rig.context).getClientCertificate();return d;}
    List<String> ids(JSONArray rows)throws Exception{List<String> ids=new ArrayList<>();for(int i=0;i<rows.length();i++)ids.add(rows.getJSONObject(i).getString("uuid"));return ids;}
    void rows(String...expected)throws Exception{assertEquals(Arrays.asList(expected),ids(rig.state().getJSONArray("devices")));}
    void remoteRows(RhSyncFlowDeviceTest.Fixture fixture,String...expected)throws Exception{assertEquals(Arrays.asList(expected),ids(new JSONObject(fixture.document).getJSONArray("devices")));}
    View find(View view,String text){if(!view.isShown())return null;if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    void click(String text)throws Exception{Button[] found={null};rig.main(()->{found[0]=(Button)find(rig.activity.findViewById(android.R.id.content),text);assertNotNull(text,found[0]);});rig.visible(found[0]);rig.main(()->{assertTrue(found[0].isEnabled());assertTrue(found[0].performClick());});rig.instrument.waitForIdleSync();}
    void sync(RhSyncFlowDeviceTest.Fixture fixture,int expectedPuts)throws Exception{
        rig.action("app.settings.tab",RhActions.obj("tab","sync"));click("立即同步");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(rig.state().optBoolean("syncBusy")){assertTrue("Sync did not retire",System.nanoTime()<until);Thread.sleep(20);}rig.instrument.waitForIdleSync();
        JSONObject state=rig.state();assertEquals(state.toString(),"",state.getString("syncError"));assertEquals("completed",state.getJSONObject("sync").getString("phase"));assertEquals(expectedPuts,fixture.puts.get());assertNull(fixture.failure);assertFalse(RhActions.hasStream());
        rig.action("devices.open",RhActions.obj());
    }
    void capture(String name,JSONArray stages)throws Exception{rig.capture(name);JSONObject state=rig.state();stages.put(RhActions.obj("image",name+".png","devices",state.getJSONArray("devices"),"selected",state.getString("selected"),"query",state.getString("deviceQuery"),"page",state.getString("page")));}
    void scenario(boolean dark,int width,int font)throws Exception{
        String name=(dark?"dark":"light")+"-"+width+"-font"+font;prefix="membership171-"+runId+"-"+name+"-";foreign="membership-other-client171";JSONArray stages=new JSONArray();int beforeInspections=inspections.get(),beforeCommits=commits.get();
        rig.main(()->{
            if(rig.home!=null)rig.home.dispose();RhShared.theme(rig.context,dark);rig.context.getSharedPreferences("rhremote.global",0).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
            alpha=device("Alpha","192.0.2.1");beta=device("Beta","192.0.2.2");gamma=device("Gamma","192.0.2.3");
            JSONObject seed=RhShared.read(rig.context);seed.put("devices",new JSONArray()).put("dark",dark);RhShared.apply(rig.context,seed);RhShared.capture(rig.context,Arrays.asList(alpha,beta));
            seed=RhShared.read(rig.context);seed.getJSONObject("clients").put(foreign,RhActions.obj("name","Other client fixture","platform","windows"));seed.getJSONObject("profiles").put(foreign,RhActions.obj(alpha.uuid,RhProfile.load(rig.context,alpha.uuid).clientJson().put("width",1600).put("height",900)));RhShared.apply(rig.context,seed);
            rig.home=new RhHome(rig.activity,actions);rig.home.resumed();rig.home.update(alpha);rig.home.update(beta);
        });rig.instrument.waitForIdleSync();assertEquals(dark,RhShared.dark(rig.context));assertEquals(dark,rig.state().getJSONObject("saved").getBoolean("dark"));String foreignBefore=RhShared.canonical(RhShared.read(rig.context).getJSONObject("profiles").getJSONObject(foreign));java.security.cert.X509Certificate pin=alpha.serverCert;
        try(RhSyncFlowDeviceTest.Fixture fixture=new RhSyncFlowDeviceTest.Fixture()){
            rig.action("sync.configure",RhActions.obj("url",fixture.url(),"username","membership-fixture","password","owned-loopback-only","automatic",false,"intervalMinutes",15));sync(fixture,1);rows(alpha.uuid,beta.uuid);
            rig.action("devices.filter",RhActions.obj("query","Fixture"));rig.action("device.select",RhActions.obj("uuid",alpha.uuid));capture(name+"-before",stages);
            JSONObject remote=new JSONObject(fixture.document);JSONArray original=remote.getJSONArray("devices");original.getJSONObject(1).put("displayName","Fixture Beta alias");remote.put("devices",new JSONArray().put(original.getJSONObject(1)));fixture.remote(remote);
            // The public merge contract is additive: absence alone must not delete Alpha.
            sync(fixture,2);rows(beta.uuid,alpha.uuid);capture(name+"-absence-kept",stages);
            // Inject an accepted complete snapshot at the configuration boundary. This
            // is not a remote-delete UI. The next ordinary sync must filter the stale
            // native row on capture, and its completion must retire the old Home row.
            JSONObject replacement=new JSONObject(fixture.document);JSONArray replacementRows=replacement.getJSONArray("devices");for(int i=0;i<replacementRows.length();i++)if(replacementRows.getJSONObject(i).getString("uuid").equals(beta.uuid)){replacement.put("devices",new JSONArray().put(replacementRows.getJSONObject(i)));break;}
            rig.main(()->RhShared.apply(rig.context,replacement));fixture.remote(replacement);sync(fixture,3);rows(beta.uuid);assertEquals(beta.uuid,rig.state().getString("selected"));assertEquals("Fixture",rig.state().getString("deviceQuery"));assertEquals("Fixture Beta alias",rig.state().getJSONArray("devices").getJSONObject(0).getString("displayName"));capture(name+"-removed",stages);
            rig.main(()->{rig.home.update(new ComputerDetails(alpha));rig.home.update(new ComputerDetails(beta));JSONObject response=RhActions.dispatch("device.select",RhActions.obj("uuid",alpha.uuid));assertFalse(response.toString(),response.optBoolean("ok"));});rows(beta.uuid);assertSame(pin,alpha.serverCert);capture(name+"-late-cache",stages);
            rig.main(()->rig.home.update(gamma));rows(beta.uuid,gamma.uuid);sync(fixture,4);remoteRows(fixture,beta.uuid,gamma.uuid);assertEquals(foreignBefore,RhShared.canonical(new JSONObject(fixture.document).getJSONObject("profiles").getJSONObject(foreign)));capture(name+"-new-discovery-synced",stages);
            rig.main(()->{rig.home.dispose();rig.home=new RhHome(rig.activity,actions);rig.home.resumed();rig.home.update(new ComputerDetails(alpha));rig.home.update(new ComputerDetails(beta));rig.home.update(new ComputerDetails(gamma));});rig.instrument.waitForIdleSync();rows(beta.uuid,gamma.uuid);capture(name+"-home-recreated",stages);
            rig.action("device.add.open",RhActions.obj());rig.action("device.add.input",RhActions.obj("address",alpha.manualAddress.address));click("添加");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(rig.state().getString("page").equals("add")){assertTrue(rig.state().toString(),System.nanoTime()<until);Thread.sleep(20);}rig.instrument.waitForIdleSync();rows(beta.uuid,gamma.uuid,alpha.uuid);assertEquals(alpha.uuid,rig.state().getString("selected"));assertEquals(beforeInspections+1,inspections.get());assertEquals(beforeCommits+1,commits.get());sync(fixture,5);remoteRows(fixture,beta.uuid,gamma.uuid,alpha.uuid);assertSame(pin,alpha.serverCert);capture(name+"-explicitly-added",stages);
            JSONObject empty=new JSONObject(fixture.document);empty.put("devices",new JSONArray());rig.main(()->RhShared.apply(rig.context,empty));fixture.remote(empty);sync(fixture,6);rig.main(()->{rig.home.update(alpha);rig.home.update(beta);rig.home.update(gamma);});rows();assertEquals("",rig.state().getString("selected"));assertTrue(RhShared.capture(rig.context,Arrays.asList(alpha,beta,gamma)).getJSONArray("devices").length()==0);capture(name+"-empty-list",stages);
            assertEquals(dark,RhShared.dark(rig.context));assertEquals(dark,rig.state().getJSONObject("saved").getBoolean("dark"));cases.put(RhActions.obj("name",name,"dark",dark,"actualDark",RhShared.dark(rig.context),"widthDp",width,"fontScale",font,"getRequests",fixture.gets.get(),"conditionalPuts",fixture.puts.get(),"stages",stages,"foreignProfilePreserved",true,"oldPinObjectPreserved",true,"absenceDoesNotDelete",true,"fullSnapshotInjected",true,"addTransportSubstituted",true,"nativePublicationsControlled",true));
        }
    }
    @Test public void syncRemovalLateDiscoveryAndExplicitReaddUseTheCurrentControls()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("membershipRun","");org.junit.Assume.assumeFalse("Opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,70}"));runId=run;rig.context=rig.instrument.getTargetContext();assertTrue(rig.context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());
        try{for(int width:new int[]{360,1008})for(int font:new int[]{1,2}){
            rig.main(()->RhUiFixtureActivity.fontScale(font));rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));rig.main(()->{assertEquals((float)font,rig.activity.getResources().getConfiguration().fontScale,0f);float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*density),Math.round((width==360?560:640)*density));});rig.instrument.waitForIdleSync();
            for(boolean dark:new boolean[]{false,true})scenario(dark,width,font);rig.main(()->{rig.home.dispose();rig.home=null;rig.activity.finish();});rig.instrument.waitForIdleSync();
        }}finally{rig.main(()->{if(rig.home!=null)rig.home.dispose();if(rig.activity!=null)rig.activity.finish();RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();}
        assertEquals(8,cases.length());assertEquals(8,inspections.get());assertEquals(8,commits.get());RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("scope","Actual native Home, sync/Add controls and loopback conditional HTTP; full-document replacement and discovery/Add transport are controlled boundaries; remote absence is additive; Home recreation is not process death","cases",cases,"oppoOperated",false,"osScreenshots",false));
    }
}
