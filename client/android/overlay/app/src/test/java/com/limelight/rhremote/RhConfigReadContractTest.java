package com.limelight.rhremote;

import android.content.Context;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public final class RhConfigReadContractTest {
    private Context context;private JSONObject snapshot;
    @Before public void create()throws Exception{
        context=RuntimeEnvironment.getApplication();snapshot=RhShared.read(context);String client=RhShared.clientId(context);
        snapshot.put("devices",new JSONArray().put(RhActions.obj("uuid","host","name","Fixture host","address","192.0.2.10","displayName","Original","swapButtons",true)));
        JSONObject profile=RhProfile.load(context,"host").clientJson();profile.put("width",1600).put("height",900);snapshot.getJSONObject("profiles").put(client,RhActions.obj("host",profile));RhShared.apply(context,snapshot);
    }
    @Test public void anExistingIdentityAndProvidedProfileDoNotWaitForAnUnrelatedDeviceCommit()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        ComputerDetails added=new ComputerDetails();added.uuid="new-host";added.name="New fixture";added.manualAddress=new ComputerDetails.AddressTuple("192.0.2.11",47989);
        Future<ComputerDetails> write=workers.submit(()->RhShared.addDevice(context,added,verified->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Commit guard expired");return verified;}));
        try{
            assertTrue("Writer did not reach its actual commit callback",entered.await(5,TimeUnit.SECONDS));
            Future<RhProfile> read=workers.submit(()->RhProfile.load(context,snapshot,"host"));RhProfile profile=read.get(1,TimeUnit.SECONDS);
            assertFalse("The commit was released before the snapshot rendered",write.isDone());assertEquals(1600,profile.width);assertTrue(profile.swapButtons);
        }finally{release.countDown();try{write.get(5,TimeUnit.SECONDS);}finally{workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}}
        assertEquals(2,RhShared.read(context).getJSONArray("devices").length());
    }
    @Test public void concurrentFirstIdentityReadersStillReceiveOnePersistedIdentity()throws Exception{
        assertTrue(context.getSharedPreferences("rhremote.global",0).edit().remove("clientId").commit());CountDownLatch begin=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(8);List<Future<String>> reads=new ArrayList<>();
        try{for(int i=0;i<16;i++)reads.add(workers.submit(()->{assertTrue(begin.await(5,TimeUnit.SECONDS));return RhShared.clientId(context);}));begin.countDown();Set<String> identities=new HashSet<>();for(Future<String> read:reads)identities.add(read.get(5,TimeUnit.SECONDS));assertEquals(1,identities.size());assertEquals(identities.iterator().next(),context.getSharedPreferences("rhremote.global",0).getString("clientId",""));}
        finally{begin.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void normalizingCallerOwnedDocumentsKeepsThemUnchangedEvenWhenValidationFails()throws Exception{
        JSONObject legacy=RhActions.obj("schemaVersion",1,"devices",new JSONArray().put(RhActions.obj("uuid","host","name","Legacy","address","192.0.2.10")),"profiles",RhActions.obj("host",RhProfile.load(context,"host").json()),"dark",false);
        String before=RhShared.canonical(legacy);JSONObject normalized=RhShared.normalize(legacy);assertEquals(before,RhShared.canonical(legacy));assertEquals(2,normalized.getInt("schemaVersion"));assertTrue(normalized.getJSONArray("devices").getJSONObject(0).getBoolean("swapButtons"));
        JSONObject invalid=new JSONObject(snapshot.toString());invalid.getJSONArray("devices").getJSONObject(0).put("unexpected",true);String original=RhShared.canonical(invalid);assertThrows(Exception.class,()->RhShared.normalize(invalid));assertEquals(original,RhShared.canonical(invalid));
    }
    @Test public void eachReadIsIndependentAndSeesExternalAtomicReplacement()throws Exception{
        JSONObject first=RhShared.read(context);first.getJSONArray("devices").getJSONObject(0).put("displayName","Unsaved");assertEquals("Original",RhShared.read(context).getJSONArray("devices").getJSONObject(0).getString("displayName"));
        JSONObject next=new JSONObject(snapshot.toString());next.getJSONArray("devices").getJSONObject(0).put("displayName","Replaced");RhShared.write(new File(context.getFilesDir(),"rhremote-shared.json"),next);assertEquals("Replaced",RhShared.read(context).getJSONArray("devices").getJSONObject(0).getString("displayName"));
        next.put("schemaVersion",99);RhShared.write(new File(context.getFilesDir(),"rhremote-shared.json"),next);assertThrows(IllegalStateException.class,()->RhShared.read(context));
    }
    @Test public void freshReadKeepsNormalizationOfAcceptedNumericRepresentations()throws Exception{
        for(String version:new String[]{"2.0","2e0","2.9"}){
            String text=snapshot.toString().replace("\"schemaVersion\":2","\"schemaVersion\":"+version);assertTrue(text.contains("\"schemaVersion\":"+version));
            java.nio.file.Files.write(new File(context.getFilesDir(),"rhremote-shared.json").toPath(),text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("Read changed the existing normalization contract for "+version,RhShared.canonical(RhShared.normalize(new JSONObject(text))),RhShared.canonical(RhShared.read(context)));
        }
    }
}
