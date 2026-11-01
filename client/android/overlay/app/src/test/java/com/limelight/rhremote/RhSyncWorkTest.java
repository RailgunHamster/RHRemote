package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLHandshakeException;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhSyncWorkTest.Network.class})
public class RhSyncWorkTest {
    @Implements(value=RhSync.class,isInAndroidSdk=false)
    public static class Network {
        static JSONObject result,captured;static Exception failure;static int runs;
        @Implementation protected JSONObject run(JSONObject local,String resolution)throws Exception{runs++;captured=new JSONObject(local.toString());if(failure!=null)throw failure;return result;}
        @Implementation protected void applied(JSONObject shared){}
    }
    Activity activity;RhSyncWork work;List<Runnable> queued;
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();work=RhSyncWork.shared(activity);queued=new ArrayList<>();
        Network.result=RhActions.obj("conflicts",new JSONArray().put("deviceOrder"));Network.captured=null;Network.failure=null;Network.runs=0;
    }
    void complete(){queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void syncSummaryCannotPresentAnOldSuccessAsTheCurrentOutcome()throws Exception{
        JSONObject state=RhActions.obj("hasPassword",true,"lastSyncMs",42,"automaticBlocked",true,"conflicts",new JSONArray());
        assertEquals("上次同步结果待确认 · 自动同步已暂停",RhHome.syncSummary(state,false,"结果未确认"));
        state.put("conflicts",new JSONArray().put("deviceOrder"));assertEquals("配置存在冲突，请选择保留项",RhHome.syncSummary(state,false,"结果未确认"));
        assertEquals("同步进行中",RhHome.syncSummary(state,true,""));state.put("conflicts",new JSONArray()).put("automaticBlocked",false);assertEquals("同步失败，请查看下方提示",RhHome.syncSummary(state,false,"HTTP 401"));
        assertTrue(RhHome.syncSummary(state,false,"").startsWith("上次完成："));
    }
    @Test public void automaticWorkCannotReplaceAConflictWhileWaitingForTheUsersChoice()throws Exception{
        work.start(RhShared.read(activity),"review",queued::add);complete();assertEquals("conflict",work.phase());assertFalse(work.canAutomaticallySync());long operation=work.state().getLong("operationId");
        try{work.start(RhShared.read(activity),"review",true,queued::add);fail("Automatic request replaced review");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("同步冲突"));}
        assertTrue(queued.isEmpty());assertEquals(operation,work.state().getLong("operationId"));assertEquals(1,Network.runs);
    }
    RhSyncWork coldOwner()throws Exception{java.lang.reflect.Field shared=RhSyncWork.class.getDeclaredField("shared");shared.setAccessible(true);shared.set(null,null);return RhSyncWork.shared(activity);}
    @Test public void aColdOwnerReportsTheInterruptedOperationWithoutReplayingIt()throws Exception{
        work.start(RhShared.read(activity),"review",queued::add);long attempted=work.lastAttempt(),operation=work.state().getLong("operationId");
        RhSyncWork restored=coldOwner();assertFalse(restored.busy());assertEquals("interrupted",restored.phase());
        assertTrue(restored.error().contains("结果未确认"));assertTrue(restored.state().optBoolean("automaticBlocked"));assertEquals(operation,restored.state().getLong("operationId"));assertEquals(attempted,restored.lastAttempt());assertEquals(0,Network.runs);
    }
    @Test public void interruptedFeedbackSurvivesAnotherColdStartAndUnrelatedFormEdits()throws Exception{
        work.start(RhShared.read(activity),"review",queued::add);RhSyncWork restored=coldOwner();String warning=restored.error();assertFalse(warning.isEmpty());
        restored.dismissError();restored.configured();assertEquals("interrupted",restored.phase());assertEquals(warning,restored.error());
        RhSyncWork again=coldOwner();assertEquals(warning,again.error());assertTrue(again.state().optBoolean("automaticBlocked"));assertEquals(0,Network.runs);
    }
    @Test public void onlyAnExplicitRetryCanClearTheInterruptedBarrier()throws Exception{
        work.start(RhShared.read(activity),"review",queued::add);work=coldOwner();List<Runnable> retry=new ArrayList<>();
        try{work.start(RhShared.read(activity),"review",true,retry::add);fail("Automatic replay accepted");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("结果未确认"));}
        assertFalse(work.canAutomaticallySync());assertTrue(retry.isEmpty());assertEquals(0,Network.runs);
        work.start(RhShared.read(activity),"review",retry::add);assertTrue(work.busy());retry.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("conflict",work.phase());assertEquals(1,Network.runs);assertFalse(work.canAutomaticallySync());assertEquals("idle",coldOwner().phase());
    }
    @Test public void invalidJournalTypesBecomeActionableFeedbackInsteadOfCrashingStartup()throws Exception{
        activity.getSharedPreferences("rhremote.sync.operations",0).edit().putString("pending","invalid").commit();work=coldOwner();
        assertEquals("interrupted",work.phase());assertTrue(work.error().contains("状态无法读取"));assertFalse(work.canAutomaticallySync());
        work.removed();assertTrue(work.canAutomaticallySync());assertEquals("idle",coldOwner().phase());
    }
    void rejectJournalCommit()throws Exception{
        java.lang.reflect.Field field=RhSyncWork.class.getDeclaredField("journal");field.setAccessible(true);android.content.SharedPreferences original=(android.content.SharedPreferences)field.get(work);
        Object editor=java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{android.content.SharedPreferences.Editor.class},(proxy,method,args)->{
            if(method.getName().equals("commit"))return false;
            if(method.getReturnType()==android.content.SharedPreferences.Editor.class)return proxy;
            throw new UnsupportedOperationException(method.getName());
        });
        field.set(work,java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{android.content.SharedPreferences.class},(proxy,method,args)->method.getName().equals("edit")?editor:method.invoke(original,args)));
    }
    @Test public void aFailedJournalWriteDoesNotQueueOrTransmitAnOperation()throws Exception{
        rejectJournalCommit();try{work.start(RhShared.read(activity),"review",queued::add);fail("Storage failure hidden");}catch(IOException expected){assertTrue(expected.getMessage().contains("存储空间"));}
        assertFalse(work.busy());assertTrue(queued.isEmpty());assertEquals(0,Network.runs);assertEquals("error",work.phase());
    }
    @Test public void anUnrecordedCompletionKeepsTheBarrierAndTheNextColdStartWarning()throws Exception{
        work.start(RhShared.read(activity),"review",queued::add);rejectJournalCommit();complete();
        assertFalse(work.busy());assertEquals("interrupted",work.phase());assertFalse(work.canAutomaticallySync());assertEquals(1,Network.runs);
        assertEquals("interrupted",coldOwner().phase());
    }
    @Test public void recreationSharesTheSingleOperationAndOldDetachCannotRetireTheNewListener()throws Exception{
        int[] oldCalls={0},newCalls={0};RhSyncWork.Listener old=()->oldCalls[0]++,next=()->newCalls[0]++;work.attach(old);
        work.start(RhShared.read(activity),"review",queued::add);assertTrue(work.busy());assertEquals(1,oldCalls[0]);long operation=work.state().getLong("operationId");
        Activity recreated=Robolectric.buildActivity(Activity.class).setup().get();RhSyncWork current=RhSyncWork.shared(recreated);assertSame(work,current);assertTrue(current.busy());assertEquals(operation,current.state().getLong("operationId"));current.attach(next);work.detach(old);
        try{current.start(RhShared.read(activity),"review",queued::add);fail("Duplicate sync accepted");}catch(IllegalStateException expected){}
        assertEquals(1,queued.size());complete();assertFalse(current.busy());assertEquals("conflict",current.phase());assertEquals(1,Network.runs);assertEquals(1,oldCalls[0]);assertEquals(1,newCalls[0]);
    }
    @Test public void finishingWithoutAViewStillAppliesTheCompletedConfigurationOnce()throws Exception{
        JSONObject result=RhShared.read(activity);result.put("dark",!RhShared.dark(activity));Network.result=RhActions.obj("shared",result,"conflicts",new JSONArray());
        RhSyncWork.Listener closed=()->{};work.attach(closed);work.start(RhShared.read(activity),"review",queued::add);work.detach(closed);complete();
        assertEquals("completed",work.phase());assertEquals(result.getBoolean("dark"),RhShared.dark(activity));assertEquals(RhShared.canonical(result),RhShared.canonical(RhShared.read(activity)));assertEquals(1,Network.runs);
        assertSame(work,RhSyncWork.shared(activity.getApplicationContext()));assertFalse(work.busy());assertEquals(1,work.state().getLong("operationId"));
    }
    @Test public void submittedConfigurationIsFrozenUntilItsWorkerStarts()throws Exception{
        JSONObject local=RhShared.read(activity);boolean submitted=local.getBoolean("dark");work.start(local,"review",queued::add);long attempted=work.lastAttempt();local.put("dark",!submitted);complete();
        assertEquals(submitted,Network.captured.getBoolean("dark"));assertTrue(attempted>0);assertEquals(attempted,work.lastAttempt());
    }
    @Test public void failureSurvivesReattachmentAndOnlyAnExplicitRetryStartsAnotherRequest()throws Exception{
        Network.failure=new IOException("WebDAV HTTP 401");work.start(RhShared.read(activity),"review",queued::add);complete();String message=work.error();assertTrue(message.contains("401"));assertEquals("error",work.phase());assertFalse(work.busy());
        RhSyncWork current=RhSyncWork.shared(activity);assertEquals(message,current.error());assertEquals(1,Network.runs);
        Network.failure=null;current.start(RhShared.read(activity),"review",queued::add);assertEquals("",work.error());complete();assertEquals(2,Network.runs);assertEquals(2,work.state().getLong("operationId"));assertEquals("conflict",work.phase());
    }
    @Test public void rejectedTaskReleasesBusyAndKeepsAnActionableFailure()throws Exception{
        try{work.start(RhShared.read(activity),"review",task->{throw new RejectedExecutionException();});fail("Rejection hidden");}catch(RejectedExecutionException expected){}
        assertFalse(work.busy());assertEquals("error",work.phase());assertFalse(work.error().isEmpty());assertEquals(0,Network.runs);
        work.start(RhShared.read(activity),"review",queued::add);complete();assertEquals(1,Network.runs);
    }
    @Test public void networkAndCertificateFailuresRemainDistinctAndActionable(){
        assertTrue(RhSync.failureMessage(new SSLHandshakeException("untrusted fixture")).contains("证书"));
        assertTrue(RhSync.failureMessage(new IOException("outer",new SSLHandshakeException("bad host"))).contains("证书"));
        assertTrue(RhSync.failureMessage(new SocketTimeoutException()).contains("超时"));
        assertTrue(RhSync.failureMessage(new UnknownHostException()).contains("解析"));
        assertTrue(RhSync.failureMessage(new IOException("WebDAV HTTP 412")).contains("重新同步"));
        assertTrue(RhSync.failureMessage(new IOException("WebDAV HTTP 302")).contains("最终目录"));
        assertEquals("WebDAV 服务器未提供强 ETag",RhSync.failureMessage(new IOException("WebDAV 服务器未提供强 ETag")));
    }
    @Test public void conflictReviewShowsNamesAndTheValuesBeingChosenInsteadOfStorageKeys()throws Exception{
        JSONObject left=RhShared.read(activity);left.put("devices",new JSONArray().put(RhActions.obj("uuid","host-123","name","Workstation","displayName","工作电脑","address","192.0.2.1")));
        left.getJSONObject("clients").put("pad-123",RhActions.obj("name","平板","platform","android"));left.getJSONObject("profiles").put("pad-123",RhActions.obj("host-123",RhProfile.load(activity,"host-123").clientJson()));
        JSONObject right=new JSONObject(left.toString());right.getJSONArray("devices").getJSONObject(0).put("displayName","远端名称");right.getJSONObject("profiles").getJSONObject("pad-123").getJSONObject("host-123").put("bitrateMbps",40);right.put("dark",!left.getBoolean("dark"));
        JSONArray review=RhSync.review(activity,left,right,new JSONArray().put("devices.host-123").put("profiles.pad-123.host-123").put("appearance.dark").put("deviceOrder"));
        assertEquals("远程设备：工作电脑",review.getJSONObject(0).getString("label"));assertTrue(review.getJSONObject(0).getString("remote").contains("远端名称"));
        assertEquals("连接配置：平板 → 工作电脑",review.getJSONObject(1).getString("label"));assertTrue(review.getJSONObject(1).getString("local").contains("25 Mbps"));assertTrue(review.getJSONObject(1).getString("remote").contains("40 Mbps"));
        assertEquals("主题",review.getJSONObject(2).getString("label"));assertNotEquals(review.getJSONObject(2).getString("local"),review.getJSONObject(2).getString("remote"));assertEquals("工作电脑",review.getJSONObject(3).getString("local"));
    }
}
