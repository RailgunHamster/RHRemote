package com.limelight.rhremote;

import android.app.Activity;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

/** Runs formal merge/conditional PUT/storage code. Only HTTP and decryption use in-memory stand-ins. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhSyncCommitTest.MemoryHttp.class,RhSyncCommitTest.AtomicFailure.class})
public class RhSyncCommitTest {
    @Implements(value=RhSync.class,isInAndroidSdk=false)
    public static class MemoryHttp {
        static JSONObject server;static int revision,puts,requests;static long appliedThread;static java.util.concurrent.CountDownLatch reached,release;
        static boolean losePutResponse;static int rejectedPut;
        @RealObject RhSync real;
        @Implementation protected void applied(JSONObject shared)throws Exception{
            appliedThread=Thread.currentThread().getId();if(reached!=null){reached.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Test acknowledgement gate timed out");}
            try{Shadow.directlyOn(real,RhSync.class,"applied",ClassParameter.from(JSONObject.class,shared));}catch(RuntimeException wrapped){if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();throw wrapped;}
        }
        @Implementation protected String password(JSONObject account){return "test-only-not-transmitted";}
        @Implementation protected static HttpURLConnection open(URL url,String method,String authorization){
            requests++;
            final boolean upload=method.equals("PUT");
            return new HttpURLConnection(url){
                final ByteArrayOutputStream request=new ByteArrayOutputStream();
                @Override public int getResponseCode()throws IOException{
                    if(upload){
                        if(rejectedPut!=0)return rejectedPut;
                        if(!("\"revision-"+revision+"\"").equals(getRequestProperty("If-Match")))return 412;
                        try{server=new JSONObject(new String(request.toByteArray(),StandardCharsets.UTF_8));}catch(JSONException invalid){throw new IOException(invalid);}revision++;puts++;
                        if(losePutResponse)throw new SocketTimeoutException("Test server applied the PUT but its response was lost");
                    }
                    return 200;
                }
                @Override public String getHeaderField(String name){return name.equalsIgnoreCase("ETag")?"\"revision-"+revision+"\"":null;}
                @Override public InputStream getInputStream(){return new ByteArrayInputStream(server.toString().getBytes(StandardCharsets.UTF_8));}
                @Override public OutputStream getOutputStream(){return request;}
                @Override public void connect(){}@Override public void disconnect(){}@Override public boolean usingProxy(){return false;}
            };
        }
    }
    @Implements(AtomicFile.class)
    public static class AtomicFailure {
        static boolean silentlyDiscardAccount;
        @RealObject AtomicFile real;
        @Implementation protected void finishWrite(FileOutputStream out){
            Shadow.directlyOn(real,AtomicFile.class,silentlyDiscardAccount&&real.getBaseFile().getName().equals("webdav-local.json")?"failWrite":"finishWrite",ClassParameter.from(FileOutputStream.class,out));
        }
    }
    Activity activity;JSONObject original;File account;
    static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    static String alias(JSONObject value)throws Exception{return value.getJSONArray("devices").getJSONObject(0).getString("displayName");}
    JSONObject persistedAccount()throws Exception{return new JSONObject(new String(new AtomicFile(account).readFully(),StandardCharsets.UTF_8));}
    @Before public void setup()throws Exception{
        AtomicFailure.silentlyDiscardAccount=false;MemoryHttp.appliedThread=0;MemoryHttp.reached=null;MemoryHttp.release=null;MemoryHttp.losePutResponse=false;MemoryHttp.rejectedPut=0;MemoryHttp.requests=0;
        activity=Robolectric.buildActivity(Activity.class).setup().get();original=RhShared.read(activity);original.put("devices",new JSONArray().put(RhActions.obj("uuid","host","name","workstation","displayName","本机旧名","address","192.0.2.10","swapButtons",false,"addresses",new JSONArray())));RhShared.apply(activity,original);original=RhShared.read(activity);
        account=new File(activity.getFilesDir(),"webdav-local.json");RhShared.write(account,RhActions.obj("url","https://sync.example.test/folder/","username","test","ciphertext","unused-test-value","base",original,"lastSyncMs",1));
        MemoryHttp.server=copy(original);MemoryHttp.server.getJSONArray("devices").getJSONObject(0).put("displayName","远端新名");MemoryHttp.revision=1;MemoryHttp.puts=0;
    }
    @Test public void aConfirmedPutDoesNotAdvanceTheLocalBaseBeforeItsConfigurationIsApplied()throws Exception{
        RhSync sync=new RhSync(activity);JSONObject transferred=sync.run(original,"review");assertEquals("远端新名",alias(transferred.getJSONObject("shared")));assertEquals(1,MemoryHttp.puts);
        assertEquals("本机旧名",alias(persistedAccount().getJSONObject("base")));assertEquals(1,persistedAccount().getLong("lastSyncMs"));assertEquals("本机旧名",alias(RhShared.read(activity)));
    }
    @Test public void reviewIdentityIsStableForTheSameSnapshotAndOldChoicesStopBeforeHttp()throws Exception{
        JSONObject ours=copy(original);ours.getJSONArray("devices").getJSONObject(0).put("displayName","本机新名");RhSync sync=new RhSync(activity);sync.run(ours,"review");String first=sync.state().getString("reviewId");assertFalse(first.isEmpty());sync.run(ours,"review");assertEquals(first,sync.state().getString("reviewId"));
        int count=sync.state().getJSONArray("conflicts").length();sync.state().getJSONArray("conflicts").put("outside-mutation");assertEquals(count,sync.state().getJSONArray("conflicts").length());
        MemoryHttp.server.getJSONArray("devices").getJSONObject(0).put("displayName","远端第二版");MemoryHttp.revision++;sync.run(ours,"review");String second=sync.state().getString("reviewId");assertNotEquals(first,second);int requests=MemoryHttp.requests;
        try{sync.runReviewed(ours,"remote",first);fail("Old review applied");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("内容已更新"));}
        assertEquals(requests,MemoryHttp.requests);assertEquals(0,MemoryHttp.puts);assertEquals(second,sync.state().getString("reviewId"));JSONObject result=sync.runReviewed(ours,"remote",second);assertEquals("远端第二版",alias(result.getJSONObject("shared")));assertEquals(1,MemoryHttp.puts);assertEquals("",sync.state().getString("reviewId"));
    }
    @Test public void homeGuardsAutomaticAndStaleRequestsBeforeUnsavedAccountDecisions()throws Exception{
        JSONObject settings=persistedAccount();settings.put("automatic",true);RhShared.write(account,settings);JSONObject ours=copy(original);ours.getJSONArray("devices").getJSONObject(0).put("displayName","本机新名");RhShared.apply(activity,ours);RhSyncWork work=RhSyncWork.shared(activity);work.sync.run(ours,"review");String review=work.state().getString("reviewId");
        activity.setTheme(com.limelight.R.style.RhTheme);RhHome home=new RhHome(activity,new RhHome.Actions(){public void connect(com.limelight.nvstream.http.ComputerDetails d){throw new AssertionError();}public void add(String address){}public void advanced(){}public void pair(com.limelight.nvstream.http.ComputerDetails d,String pin){throw new AssertionError();}public void cancelPair(){}public void refresh(){}});
        try{home.perform("app.settings.tab",RhActions.obj("tab","sync"));home.perform("sync.edit",RhActions.obj("key","username","value","unsaved"));int requests=MemoryHttp.requests;JSONObject skipped=home.perform("sync.run",RhActions.obj("automatic",true));assertTrue(skipped.getBoolean("skipped"));assertEquals("sync_conflict",skipped.getString("reason"));
            for(Object invalid:new Object[]{"true",1,JSONObject.NULL})try{home.perform("sync.run",RhActions.obj("automatic",invalid));fail("Invalid automatic flag accepted");}catch(IllegalArgumentException expected){}
            try{home.perform("sync.resolve",RhActions.obj("resolution","remote","reviewId","older"));fail("Old choice accepted");}catch(IllegalStateException expected){}
            assertTrue(home.state().isNull("navigationPrompt"));assertEquals(review,home.state().getJSONObject("sync").getString("reviewId"));assertEquals("unsaved",home.state().getJSONObject("syncDraft").getString("username"));assertEquals(requests,MemoryHttp.requests);assertEquals(0,MemoryHttp.puts);
        }finally{home.dispose();}
    }
    @Test public void aLostWriteResponseMustPauseAutomaticSyncAndSurviveAColdStart()throws Exception{
        MemoryHttp.losePutResponse=true;RhSyncWork work=RhSyncWork.shared(activity);List<Runnable> queued=new ArrayList<>();work.start(original,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,MemoryHttp.puts);assertEquals("本机旧名",alias(persistedAccount().getJSONObject("base")));assertEquals(1,persistedAccount().getLong("lastSyncMs"));
        assertFalse("A transport error after PUT may already have changed the remote document",work.canAutomaticallySync());
        java.lang.reflect.Field field=RhSyncWork.class.getDeclaredField("shared");field.setAccessible(true);field.set(null,null);RhSyncWork cold=RhSyncWork.shared(activity);assertEquals("interrupted",cold.phase());assertFalse(cold.canAutomaticallySync());
        // Even a missing operation journal cannot erase the persisted PUT barrier.
        activity.getSharedPreferences("rhremote.sync.operations",0).edit().clear().commit();field.set(null,null);cold=RhSyncWork.shared(activity);assertEquals("interrupted",cold.phase());assertFalse(cold.canAutomaticallySync());
        MemoryHttp.losePutResponse=false;queued.clear();cold.start(original,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("completed",cold.phase());assertTrue(cold.canAutomaticallySync());assertEquals("远端新名",alias(RhShared.read(activity)));assertFalse(persistedAccount().optBoolean("pendingTransfer"));
    }
    @Test public void aKnownRejectionDoesNotBlockAutomaticSyncButCannotSettleAnEarlierUnknownWrite()throws Exception{
        MemoryHttp.rejectedPut=401;RhSyncWork work=RhSyncWork.shared(activity);List<Runnable> queued=new ArrayList<>();work.start(original,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(work.error().contains("401"));assertFalse(work.error().contains("结果未确认"));assertTrue(work.canAutomaticallySync());assertEquals(0,MemoryHttp.puts);
        MemoryHttp.rejectedPut=0;MemoryHttp.losePutResponse=true;work.start(original,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(work.canAutomaticallySync());
        MemoryHttp.rejectedPut=412;work.start(original,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(work.error().contains("412"));assertTrue(work.error().contains("结果未确认"));assertFalse(work.canAutomaticallySync());assertEquals(1,MemoryHttp.puts);
    }
    @Test public void aSilentFailureToRecordTransferIntentCannotSendThePut()throws Exception{
        AtomicFailure.silentlyDiscardAccount=true;RhSyncWork work=RhSyncWork.shared(activity);List<Runnable> queued=new ArrayList<>();work.start(original,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,MemoryHttp.puts);assertTrue(work.error().contains("任务状态未能保存"));assertEquals(1,persistedAccount().getLong("lastSyncMs"));assertTrue(work.canAutomaticallySync());
    }
    @Test public void conflictsAfterAnUnknownWriteKeepBothReviewAndTheAutomaticSyncBarrier()throws Exception{
        MemoryHttp.losePutResponse=true;RhSyncWork work=RhSyncWork.shared(activity);List<Runnable> queued=new ArrayList<>();work.start(original,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        JSONObject changed=copy(original);changed.getJSONArray("devices").getJSONObject(0).put("displayName","本机另一个名称");MemoryHttp.losePutResponse=false;work.start(changed,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("conflict",work.phase());assertTrue(work.state().getJSONArray("conflicts").length()>0);assertFalse(work.canAutomaticallySync());assertEquals(1,MemoryHttp.puts);
        work.start(changed,"remote",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("completed",work.phase());assertTrue(work.canAutomaticallySync());assertEquals("远端新名",alias(RhShared.read(activity)));
    }
    @Test public void retryAfterAColdStartMustNotPublishTheOldLocalNameOverTheConfirmedRemoteEdit()throws Exception{
        new RhSync(activity).run(original,"review");RhSync restarted=new RhSync(activity);JSONObject retried=restarted.run(original,"review");
        assertEquals("远端新名",alias(retried.getJSONObject("shared")));assertEquals("远端新名",alias(MemoryHttp.server));assertEquals(2,MemoryHttp.puts);
    }
    @Test public void localApplicationFailureRetainsTheOldMergeBaseAndSuccessTime()throws Exception{
        File shared=new File(activity.getFilesDir(),"rhremote-shared.json");Files.delete(shared.toPath());assertTrue(shared.mkdir());
        RhSyncWork work=RhSyncWork.shared(activity);List<Runnable> queued=new ArrayList<>();work.start(original,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("error",work.phase());assertTrue(work.error().contains("本机配置应用失败"));assertEquals("远端新名",alias(MemoryHttp.server));assertEquals("本机旧名",alias(persistedAccount().getJSONObject("base")));assertEquals(1,persistedAccount().getLong("lastSyncMs"));
        assertFalse(work.canAutomaticallySync());
    }
    @Test public void acknowledgementRequiresTheExactPersistedConfiguration()throws Exception{
        RhSync sync=new RhSync(activity);JSONObject merged=sync.run(original,"review").getJSONObject("shared");
        try{sync.applied(merged);fail("An unapplied transfer was marked successful");}catch(IOException expected){assertTrue(expected.getMessage().contains("尚未完整应用"));}
        JSONObject changed=copy(merged);changed.getJSONArray("devices").getJSONObject(0).put("displayName","意外更改");RhShared.apply(activity,changed);
        try{sync.applied(changed);fail("A changed result was committed as the transfer");}catch(IOException expected){assertTrue(expected.getMessage().contains("结果或账户已变化"));}
        assertEquals("本机旧名",alias(persistedAccount().getJSONObject("base")));assertEquals(1,persistedAccount().getLong("lastSyncMs"));
    }
    @Test public void coldRetryAfterApplicationButBeforeAcknowledgementKeepsTheRemoteEdit()throws Exception{
        JSONObject merged=new RhSync(activity).run(original,"review").getJSONObject("shared");RhShared.apply(activity,merged);
        assertEquals("本机旧名",alias(persistedAccount().getJSONObject("base")));RhSync restarted=new RhSync(activity);JSONObject retried=restarted.run(RhShared.read(activity),"review").getJSONObject("shared");RhShared.apply(activity,retried);restarted.applied(retried);
        assertEquals("远端新名",alias(MemoryHttp.server));assertEquals("远端新名",alias(persistedAccount().getJSONObject("base")));assertTrue(persistedAccount().getLong("lastSyncMs")>1);assertFalse(restarted.state().getBoolean("awaitingLocalApply"));
    }
    @Test public void anAccountChangeInvalidatesTheOldTransferAcknowledgement()throws Exception{
        RhSync sync=new RhSync(activity);JSONObject merged=sync.run(original,"review").getJSONObject("shared");RhShared.apply(activity,merged);sync.clear();
        try{sync.applied(merged);fail("Old transfer recreated a removed account");}catch(IOException expected){assertTrue(expected.getMessage().contains("没有等待应用"));}assertEquals(0,persistedAccount().length());
    }
    @Test public void aSilentAtomicFinishFailureCannotReportSuccessAndCanBeRetried()throws Exception{
        RhSync sync=new RhSync(activity);JSONObject merged=sync.run(original,"review").getJSONObject("shared");RhShared.apply(activity,merged);AtomicFailure.silentlyDiscardAccount=true;
        try{sync.applied(merged);fail("Silent atomic failure advanced the success state");}catch(IOException expected){assertTrue(expected.getMessage().contains("完成状态未能保存"));}
        assertEquals("本机旧名",alias(persistedAccount().getJSONObject("base")));assertTrue(sync.state().getBoolean("awaitingLocalApply"));AtomicFailure.silentlyDiscardAccount=false;sync.applied(merged);assertEquals("远端新名",alias(persistedAccount().getJSONObject("base")));assertEquals(1,MemoryHttp.puts);
    }
    @Test public void successfulWorkAcknowledgesTheDurableConfigurationOnItsWorker()throws Exception{
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{RhSyncWork work=RhSyncWork.shared(activity);work.start(original,"review",executor);executor.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);assertTrue("Completion must retain the lease until the UI callback",work.busy());Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("completed",work.phase());assertTrue(work.canAutomaticallySync());assertNotEquals(Thread.currentThread().getId(),MemoryHttp.appliedThread);assertTrue(MemoryHttp.appliedThread>0);assertEquals("远端新名",alias(RhShared.read(activity)));assertEquals("远端新名",alias(persistedAccount().getJSONObject("base")));assertFalse(activity.getSharedPreferences("rhremote.sync.operations",0).getBoolean("pending",true));}
        finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
    }
    static android.widget.Button button(android.view.View view,String text){if(view instanceof android.widget.Button&&text.contentEquals(((android.widget.Button)view).getText()))return (android.widget.Button)view;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.widget.Button result=button(((android.view.ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    @Test public void theActualSyncPageShowsTheRecordingStageWithoutEnablingAnotherSubmission()throws Exception{
        RhHome home=new RhHome(activity,new RhHome.Actions(){public void connect(com.limelight.nvstream.http.ComputerDetails d){}public void add(String address){}public void advanced(){}public void pair(com.limelight.nvstream.http.ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}});home.perform("app.settings.tab",RhActions.obj("tab","sync"));
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();MemoryHttp.reached=new java.util.concurrent.CountDownLatch(1);MemoryHttp.release=new java.util.concurrent.CountDownLatch(1);
        try{RhSyncWork work=RhSyncWork.shared(activity);work.start(original,"review",executor);assertTrue(MemoryHttp.reached.await(3,java.util.concurrent.TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("recording",work.phase());android.widget.Button recording=button(activity.getWindow().getDecorView(),"正在保存…");assertNotNull(recording);assertFalse(recording.isEnabled());assertEquals(1,persistedAccount().getLong("lastSyncMs"));MemoryHttp.release.countDown();executor.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);assertTrue("Completion must retain the lease until the UI callback",work.busy());Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("completed",work.phase());assertNotNull(button(activity.getWindow().getDecorView(),"立即同步"));}
        finally{MemoryHttp.release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));home.dispose();}
    }
}
