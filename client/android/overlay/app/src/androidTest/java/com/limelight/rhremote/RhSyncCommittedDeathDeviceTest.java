package com.limelight.rhremote;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicit emulator-only process exit at a real DAV commit/local-file boundary. */
@RunWith(AndroidJUnit4.class)
public final class RhSyncCommittedDeathDeviceTest {
    private final RhSyncFlowDeviceTest driver=new RhSyncFlowDeviceTest();
    private final Context context=driver.instrument.getTargetContext();
    private File scratch,output;
    private String run,point;
    private static Object get(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
    private static String digest(byte[] bytes)throws Exception{StringBuilder text=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return text.toString();}
    private JSONObject read(File file)throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
    private File sharedFile(){return new File(context.getFilesDir(),"rhremote-shared.json");}
    private static String alias(JSONObject shared,String uuid)throws Exception{JSONArray devices=shared.getJSONArray("devices");for(int i=0;i<devices.length();i++){JSONObject device=devices.getJSONObject(i);if(uuid.equals(device.getString("uuid"))){String name=device.optString("displayName");return name.isEmpty()?device.optString("name"):name;}}throw new AssertionError("Owned device missing");}
    private static void alias(JSONObject shared,String uuid,String value)throws Exception{JSONArray devices=shared.getJSONArray("devices");for(int i=0;i<devices.length();i++)if(uuid.equals(devices.getJSONObject(i).getString("uuid"))){devices.getJSONObject(i).put("displayName",value);return;}throw new AssertionError("Owned device missing");}
    private void launch()throws Exception{driver.activity=driver.instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));driver.instrument.waitForIdleSync();driver.home=(RhHome)get(driver.activity,"rhHome");assertFalse(RhActions.hasStream());}
    private void closeActivity()throws Exception{Activity activity=driver.activity;if(activity==null)return;driver.instrument.runOnMainSync(activity::finish);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!activity.isDestroyed()){assertTrue("Owned Activity did not close",System.nanoTime()<end);Thread.sleep(20);}driver.activity=null;driver.instrument.waitForIdleSync();}
    private void input(String label,String value){driver.instrument.runOnMainSync(()->{View control=driver.find(driver.activity.getWindow().getDecorView(),label);assertTrue(label,control instanceof EditText);((EditText)control).setText(value);});}
    private void idle()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);while(driver.state().getBoolean("syncBusy")){assertTrue("Owned synchronization did not finish",System.nanoTime()<end);Thread.sleep(25);}driver.instrument.waitForIdleSync();}
    private void sync()throws Exception{driver.click("立即同步");idle();assertEquals("completed",driver.state().getJSONObject("sync").getString("phase"));}
    private String texts(View view){if(view.getVisibility()!=View.VISIBLE)return "";StringBuilder text=new StringBuilder();if(view instanceof TextView&&!(view instanceof EditText))text.append(((TextView)view).getText()).append('\n');if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)text.append(texts(((ViewGroup)view).getChildAt(i)));return text.toString();}
    private String texts(){String[] text={""};driver.instrument.runOnMainSync(()->text[0]=texts(driver.activity.getWindow().getDecorView()));return text[0];}
    private void capture(String name)throws Exception{Bitmap[] image={null};driver.instrument.runOnMainSync(()->{View view=driver.activity.getWindow().getDecorView();image[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image[0]));});try(OutputStream stream=new FileOutputStream(new File(output,name+".png"))){assertTrue(image[0].compress(Bitmap.CompressFormat.PNG,100,stream));}finally{image[0].recycle();}}
    private void configure(RhSyncWork work,File account,JSONObject local)throws Exception{set(work.sync,"file",account);set(work.sync,"local",local);}
    private void peerEdit(RhSyncResponseDeviceTest.Forwarder proxy,String uuid,String value)throws Exception{
        URL url=new URL(proxy.upstream+"/rhremote-config-v1.json");
        try(RhDavLease lease=RhDavLease.acquire(url,proxy.upstreamAuth)){
            JSONObject remote;String etag;try(Response response=proxy.http.newCall(new Request.Builder().url(url).header("Authorization",proxy.upstreamAuth).build()).execute()){assertEquals(200,response.code());etag=response.header("ETag");assertNotNull(etag);assertFalse(etag.startsWith("W/"));remote=new JSONObject(response.body().string());}
            alias(remote,uuid,value);remote.put("dark",!remote.getBoolean("dark"));RhShared.validate(remote);Request request=new Request.Builder().url(url).header("Authorization",proxy.upstreamAuth).header("If-Match",etag).header("If",lease.condition()).put(RequestBody.create(remote.toString().getBytes(StandardCharsets.UTF_8),MediaType.parse("application/json"))).build();try(Response response=proxy.http.newCall(request).execute()){assertTrue("Owned peer update failed",response.isSuccessful());}
        }
    }
    private void checkpoint(RhSyncWork work,RhSyncResponseDeviceTest.Forwarder proxy,JSONObject metadata)throws Exception{
        JSONObject proof=RhActions.obj("run",run,"point",point,"pid",android.os.Process.myPid(),"version",com.limelight.BuildConfig.VERSION_NAME,"actualService",true,"intentionalTestExit",true,"systemInputInjected",false,"remoteInputSent",false,"wallTimeMs",System.currentTimeMillis());
        try{
            boolean recorded=point.equals("after_account"),responseHeld=point.equals("after_remote");String stage=recorded||point.equals("after_apply")?"recording":responseHeld?"syncing":"applying";
            assertNull("The owned window must close before process exit",driver.activity);assertEquals(stage,work.phase());assertTrue(context.getSharedPreferences("rhremote.sync.operations",0).getBoolean("pending",false));
            JSONObject account=read(new File(scratch,"account.json"));assertEquals(!recorded,account.optBoolean("pendingTransfer"));if(recorded){assertTrue(account.getLong("lastSyncMs")>metadata.getLong("previousTime"));assertNotEquals(metadata.getString("previousBaseSha"),digest(RhShared.canonical(account.getJSONObject("base")).getBytes(StandardCharsets.UTF_8)));}else{assertEquals(metadata.getLong("previousTime"),account.getLong("lastSyncMs"));assertEquals(metadata.getString("previousBaseSha"),digest(RhShared.canonical(account.getJSONObject("base")).getBytes(StandardCharsets.UTF_8)));}
            byte[] remote=proxy.directDocument();JSONObject merged=new JSONObject(new String(remote,StandardCharsets.UTF_8));assertEquals(metadata.getString("localAlias"),alias(merged,metadata.getString("localUuid")));assertEquals(metadata.getString("remoteAlias"),alias(merged,metadata.getString("remoteUuid")));assertEquals(2,proxy.puts.get());assertEquals(proxy.locks.get()-(responseHeld?1:0),proxy.unlocks.get());if(responseHeld)assertTrue("Bounded real lease timeout required",proxy.lastLeaseSeconds>0&&proxy.lastLeaseSeconds<=120);
            JSONObject local=read(sharedFile());boolean applied=alias(local,metadata.getString("remoteUuid")).equals(metadata.getString("remoteAlias"));assertEquals(!point.equals("before_apply")&&!responseHeld,applied);
            boolean themeApplied=context.getSharedPreferences("rhremote.global",0).getBoolean("dark",false)==local.getBoolean("dark");if(point.equals("after_shared"))assertFalse(themeApplied);if(point.equals("after_apply")||recorded)assertTrue(themeApplied);
            proof.put("remoteDocumentSha",digest(remote)).put("sharedFileApplied",applied).put("themeMatchesShared",themeApplied).put("previousBasePreserved",!recorded).put("previousTimePreserved",!recorded).put("accountCompletionSaved",recorded).put("journalPending",true).put("pendingTransfer",!recorded).put("productPutRequests",proxy.puts.get()).put("productLocks",proxy.locks.get()).put("productUnlocks",proxy.unlocks.get()).put("outstandingOwnLease",responseHeld).put("leaseSeconds",proxy.lastLeaseSeconds).put("activityDestroyedBeforeExit",true).put("checkpointVerified",true);
        }catch(Throwable failure){proof.put("checkpointVerified",false).put("failureType",failure.getClass().getSimpleName());}
        RhShared.write(new File(output,"checkpoint.json"),proof);
        // The only exit path is this opt-in test source in the dedicated emulator.
        Runtime.getRuntime().exit(proof.optBoolean("checkpointVerified")?0:42);
    }
    private Context boundaryContext(RhSyncWork work,RhSyncResponseDeviceTest.Forwarder proxy,JSONObject metadata){
        AtomicBoolean armed=new AtomicBoolean(true);File files=context.getFilesDir();
        return new ContextWrapper(context){
            private void stop(boolean after){
                String stage=work.phase();boolean match=point.equals("before_apply")&&stage.equals("applying")&&!after||point.equals("after_shared")&&stage.equals("applying")&&after||point.equals("after_apply")&&stage.equals("recording");if(!armed.get()||!match)return;
                try{if(point.equals("after_shared")&&!alias(read(new File(files,"rhremote-shared.json")),metadata.getString("remoteUuid")).equals(metadata.getString("remoteAlias")))return;if(armed.compareAndSet(true,false))checkpoint(work,proxy,metadata);}catch(Exception error){throw new IllegalStateException("Owned checkpoint failed",error);}
            }
            @Override public File getFilesDir(){stop(false);return files;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){if(name.equals("rhremote.global"))stop(true);return context.getSharedPreferences(name,mode);}
        };
    }
    private File accountBoundary(RhSyncWork work,RhSyncResponseDeviceTest.Forwarder proxy,JSONObject metadata){
        AtomicBoolean armed=new AtomicBoolean(true);File actual=new File(scratch,"account.json");
        return new File(actual.getPath()){@Override public String getPath(){if(armed.get()&&work.phase().equals("recording"))try{JSONObject saved=read(actual);if(!saved.optBoolean("pendingTransfer")&&armed.compareAndSet(true,false))checkpoint(work,proxy,metadata);}catch(Exception failure){throw new IllegalStateException("Owned account checkpoint failed",failure);}return super.getPath();}};
    }
    @Test public void committedSyncSurvivesARealProcessExitAtTheApplyBoundary()throws Exception{
        org.junit.Assume.assumeTrue("Explicit dedicated emulator run required",InstrumentationRegistry.getArguments().containsKey("syncCommitRun"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        run=InstrumentationRegistry.getArguments().getString("syncCommitRun");point=InstrumentationRegistry.getArguments().getString("syncCommitPoint");String phase=InstrumentationRegistry.getArguments().getString("syncCommitPhase");assertTrue(run.matches("commit-[a-f0-9]{12}"));assertTrue(java.util.Arrays.asList("after_remote","before_apply","after_shared","after_apply","after_account").contains(point));assertTrue(phase.equals("prepare")||phase.equals("verify"));
        scratch=new File(context.getFilesDir(),"ui-test-backups/"+run);output=new File(context.getExternalFilesDir("ui-evidence"),run);assertTrue(output.isDirectory()||output.mkdirs());
        File input=new File(context.getFilesDir(),"ui-sync-private-"+run+"-"+phase+".json");JSONObject connection=read(input);Files.delete(input.toPath());String upstream=connection.getString("url");URI uri=URI.create(upstream);assertEquals("http",uri.getScheme());assertEquals("192.168.0.104",uri.getHost());assertEquals(5244,uri.getPort());assertEquals("/dav/RHRemote-UI-"+run,uri.getPath());
        assertTrue(context.getSharedPreferences("rhremote.global",0).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit());
        if(phase.equals("prepare")){
            assertFalse(scratch.exists());assertTrue(scratch.mkdirs());File backups=new File(context.getFilesDir(),"sync-backups");if(backups.exists())Files.move(backups.toPath(),new File(scratch,"original-backups").toPath());
            try(RhSyncResponseDeviceTest.Forwarder proxy=new RhSyncResponseDeviceTest.Forwarder(upstream,connection.getString("username"),connection.getString("password"))){
                RhSyncWork work=RhSyncWork.shared(context);configure(work,new File(scratch,"account.json"),new JSONObject());work.removed();launch();driver.action("app.settings.tab",RhActions.obj("tab","sync"));input("WebDAV 目录地址（HTTP / HTTPS）",proxy.url());input("用户名","ui-sync");input("密码",proxy.clientPassword);driver.click("保存");assertFalse(work.state().getBoolean("automatic"));sync();if(point.equals("after_remote"))assertTrue("Real finite lease timeout must be known before injecting an exit",proxy.lastLeaseSeconds>0&&proxy.lastLeaseSeconds<=120);
                JSONArray devices=driver.state().getJSONArray("devices");assertTrue(devices.length()>=2);String localUuid=devices.getJSONObject(0).getString("uuid"),remoteUuid=devices.getJSONObject(1).getString("uuid"),localAlias="本机中断测试 "+run.substring(7),remoteAlias="远端中断测试 "+run.substring(7);JSONObject account=read(new File(scratch,"account.json"));
                JSONObject metadata=RhActions.obj("run",run,"point",point,"port",proxy.server.getLocalPort(),"previousTime",account.getLong("lastSyncMs"),"previousBaseSha",digest(RhShared.canonical(account.getJSONObject("base")).getBytes(StandardCharsets.UTF_8)),"localUuid",localUuid,"remoteUuid",remoteUuid,"localAlias",localAlias,"remoteAlias",remoteAlias,"oldRemoteAlias",RhShared.alias(context,remoteUuid),"originalAccountSha",RhSyncInterruptionDeviceTest.sha(new File(context.getFilesDir(),"webdav-local.json")));
                RhShared.write(new File(scratch,"metadata.json"),metadata);driver.action("device.info.open",RhActions.obj("uuid",localUuid));EditText editor=(EditText)get(driver.home,"infoEditor");driver.instrument.runOnMainSync(()->editor.setText(localAlias));driver.click("完成");peerEdit(proxy,remoteUuid,remoteAlias);driver.action("app.settings.tab",RhActions.obj("tab","sync"));
                Context boundary=boundaryContext(work,proxy,metadata);set(work,"context",boundary);set(work.sync,"context",boundary);if(point.equals("after_account"))set(work.sync,"file",accountBoundary(work,proxy,metadata));proxy.afterSuccessfulPut=body->{assertEquals(digest(body),digest(proxy.directDocument()));closeActivity();if(point.equals("after_remote"))checkpoint(work,proxy,metadata);};driver.click("立即同步");
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);while(System.nanoTime()<until){Thread.sleep(50);}fail("Owned process did not reach the requested apply boundary");
            }finally{closeActivity();}
        }else{
            JSONObject metadata=read(new File(scratch,"metadata.json")),checkpoint=read(new File(output,"checkpoint.json"));assertTrue(checkpoint.getBoolean("checkpointVerified"));assertNotEquals(checkpoint.getInt("pid"),android.os.Process.myPid());assertEquals(point,metadata.getString("point"));
            RhSyncWork work=RhSyncWork.shared(context);JSONObject account=read(new File(scratch,"account.json"));configure(work,new File(scratch,"account.json"),account);Method password=RhSync.class.getDeclaredMethod("password",JSONObject.class);password.setAccessible(true);String clientPassword=(String)password.invoke(work.sync,account);
            try(RhSyncResponseDeviceTest.Forwarder proxy=new RhSyncResponseDeviceTest.Forwarder(upstream,connection.getString("username"),connection.getString("password"),metadata.getInt("port"),clientPassword)){
                if(!point.equals("after_account")){assertEquals(metadata.getString("previousBaseSha"),digest(RhShared.canonical(account.getJSONObject("base")).getBytes(StandardCharsets.UTF_8)));assertEquals(metadata.getLong("previousTime"),account.getLong("lastSyncMs"));}launch();driver.action("app.settings.tab",RhActions.obj("tab","sync"));JSONObject pending=driver.state().getJSONObject("sync");assertEquals("interrupted",pending.getString("phase"));assertTrue(pending.getBoolean("automaticBlocked"));assertEquals(!point.equals("after_account"),pending.getBoolean("outcomeUncertain"));assertTrue(texts().contains("同步结果待确认"));capture("cold-start-unconfirmed");
                JSONObject[] automatic={null};driver.instrument.runOnMainSync(()->automatic[0]=RhActions.dispatch("sync.run",RhActions.obj("automatic",true)));assertTrue(automatic[0].getJSONObject("result").getBoolean("skipped"));assertEquals("sync_interrupted",automatic[0].getJSONObject("result").getString("reason"));assertEquals(0,proxy.puts.get());assertEquals(0,proxy.gets.get());
                driver.action("sync.edit",RhActions.obj("key","url","value","http://192.0.2.1/"));driver.click("取消");assertTrue(work.state().getBoolean("automaticBlocked"));assertTrue(texts().contains("同步结果待确认"));
                RhSyncInterruptionDeviceTest theme=new RhSyncInterruptionDeviceTest();theme.activity=driver.activity;theme.home=driver.home;theme.changeTheme();driver.activity=theme.activity;driver.home=theme.home;assertTrue(work.state().getBoolean("automaticBlocked"));assertEquals(pending.getLong("lastSyncMs"),work.state().getLong("lastSyncMs"));assertTrue(texts().contains("同步结果待确认"));assertEquals(0,proxy.puts.get());assertEquals(0,proxy.gets.get());capture("unconfirmed-after-theme-recreation");
                long waited=0;if(point.equals("after_remote")){driver.click("立即同步");idle();assertTrue(work.state().getString("error").contains("WebDAV 文件被占用"));assertFalse(work.state().getString("error").contains("另一台设备"));assertTrue(work.state().getBoolean("automaticBlocked"));assertEquals(0,proxy.puts.get());capture("owned-lease-still-held");long until=checkpoint.getLong("wallTimeMs")+checkpoint.getInt("leaseSeconds")*1000L+5000;long began=System.currentTimeMillis();RhShared.write(new File(output,"waiting-for-own-lease.json"),RhActions.obj("untilWallMs",until,"productAutoRetry",false,"staleLeaseNotStolen",true));while(System.currentTimeMillis()<until)Thread.sleep(Math.max(1,Math.min(1000,until-System.currentTimeMillis())));waited=System.currentTimeMillis()-began;}
                sync();JSONObject done=driver.state().getJSONObject("sync");assertFalse(done.getBoolean("automaticBlocked"));assertFalse(done.getBoolean("outcomeUncertain"));assertTrue(done.getLong("lastSyncMs")>pending.getLong("lastSyncMs"));assertEquals(metadata.getString("localAlias"),RhShared.alias(context,metadata.getString("localUuid")));assertEquals(metadata.getString("remoteAlias"),RhShared.alias(context,metadata.getString("remoteUuid")));assertEquals(1,proxy.puts.get());assertEquals(RhShared.canonical(RhShared.read(context)),RhShared.canonical(new JSONObject(new String(proxy.directDocument(),StandardCharsets.UTF_8))));assertEquals(metadata.getString("originalAccountSha"),RhSyncInterruptionDeviceTest.sha(new File(context.getFilesDir(),"webdav-local.json")));capture("manual-retry-completed");
                RhShared.write(new File(output,"verified.json"),RhActions.obj("passed",true,"point",point,"oldPid",checkpoint.getInt("pid"),"newPid",android.os.Process.myPid(),"actualService",true,"automaticBlockedWithoutRequests",true,"draftCancelPreservedWarning",true,"actualThemeRecreation",true,"ownExpiredLeaseWaitMs",waited,"manualRetryReconciled",true,"retryPuts",proxy.puts.get(),"retryLocks",proxy.locks.get(),"retryUnlocks",proxy.unlocks.get(),"originalAccountUnchanged",true,"systemInputInjected",false));
            }finally{closeActivity();}
        }
    }
}
