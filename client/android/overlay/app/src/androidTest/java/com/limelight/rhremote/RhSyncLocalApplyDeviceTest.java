package com.limelight.rhremote;

import android.content.Context;
import android.util.AtomicFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Simulated transfer result, real app-private configuration and Android AtomicFile. No HTTP or UI automation. */
@RunWith(AndroidJUnit4.class)
public class RhSyncLocalApplyDeviceTest {
    static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    static JSONObject read(File file)throws Exception{return new JSONObject(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8));}
    static void set(RhSync sync,String name,Object value)throws Exception{Field field=RhSync.class.getDeclaredField(name);field.setAccessible(true);field.set(sync,value);}
    static String sha(File file)throws Exception{if(!file.isFile())return "absent";MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(Files.readAllBytes(file.toPath()));StringBuilder text=new StringBuilder();for(byte value:hash.digest())text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return text.toString();}
    @Test public void nativeApplicationAndFinalRecordFailuresCannotAdvanceAnUnappliedMergeBase()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));String run=InstrumentationRegistry.getArguments().getString("localApplyRun","");assertTrue(run.matches("[A-Za-z0-9_-]{1,80}"));
        File directory=new File(context.getFilesDir(),"ui-test-backups/local-apply-"+run),output=new File(context.getExternalFilesDir("ui-evidence"),"local-apply-"+run);assertFalse(directory.exists());assertTrue(directory.mkdirs());assertTrue(output.mkdirs());
        JSONObject original=RhShared.read(context);boolean originalDark=RhShared.dark(context);File shared=new File(context.getFilesDir(),"rhremote-shared.json"),account=new File(context.getFilesDir(),"webdav-local.json"),saved=new File(directory,"shared-before.json"),scratch=new File(directory,"account.json");String originalHash=sha(shared),accountHash=sha(account);Files.copy(shared.toPath(),saved.toPath());JSONObject proof=RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"transferResultInjected",true,"httpRequests",0,"osInputInjected",false,"osScreenshots",false);
        try{
            assertTrue(original.getJSONArray("devices").length()>0);JSONObject merged=copy(original);merged.getJSONArray("devices").getJSONObject(0).put("displayName","同步收尾验收");JSONObject metadata=RhActions.obj("base",original,"lastSyncMs",1);RhShared.write(scratch,metadata);
            RhSync sync=new RhSync(context);set(sync,"file",scratch);set(sync,"local",copy(metadata));set(sync,"awaitingApply",copy(merged));set(sync,"appliedAccount",copy(metadata));
            try{sync.applied(merged);fail("Unapplied configuration marked successful");}catch(IOException expected){assertTrue(expected.getMessage().contains("尚未完整应用"));}assertEquals(1,read(scratch).getLong("lastSyncMs"));proof.put("unappliedAcknowledgementRejected",true);
            RhShared.apply(context,merged);assertEquals(1,read(scratch).getLong("lastSyncMs"));File beforeFault=new File(directory,"account-before-fault.json"),blocker=new File(scratch,"keep-directory-nonempty");Files.move(scratch.toPath(),beforeFault.toPath());assertTrue(scratch.mkdir());assertTrue(blocker.createNewFile());
            boolean failed=false;try{sync.applied(merged);}catch(Exception expected){failed=true;proof.put("recordFailureType",expected.getClass().getSimpleName());}finally{Files.delete(blocker.toPath());Files.delete(scratch.toPath());Files.move(beforeFault.toPath(),scratch.toPath());}assertTrue("Real filesystem failure must reach the caller",failed);assertEquals(1,sync.state().getLong("lastSyncMs"));assertTrue(sync.state().getBoolean("awaitingLocalApply"));assertEquals(1,read(scratch).getLong("lastSyncMs"));proof.put("failedRecordKeptOriginalBase",true);
            sync.applied(merged);assertTrue(sync.state().getLong("lastSyncMs")>1);assertFalse(sync.state().getBoolean("awaitingLocalApply"));assertEquals(RhShared.canonical(merged),RhShared.canonical(read(scratch).getJSONObject("base")));proof.put("explicitRetryRecordedExactAppliedConfiguration",true);
        }catch(Exception|AssertionError failure){proof.put("failure",failure.toString());throw failure;}finally{
            RhShared.apply(context,original);assertTrue(context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",originalDark).commit());Files.copy(saved.toPath(),shared.toPath(),StandardCopyOption.REPLACE_EXISTING);assertEquals(originalHash,sha(shared));assertEquals(accountHash,sha(account));proof.put("sharedConfigurationRestored",true).put("realWebdavAccountUnchanged",true);RhShared.write(new File(output,"local-apply-result.json"),proof);
        }
    }
}
