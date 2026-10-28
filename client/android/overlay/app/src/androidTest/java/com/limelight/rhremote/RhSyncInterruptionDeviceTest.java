package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Two invocations separated by an actual process stop. The queued worker never performs WebDAV I/O. */
@RunWith(AndroidJUnit4.class)
public class RhSyncInterruptionDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    final Context context=instrument.getTargetContext();
    Activity activity;RhHome home;File backup,evidence;
    static final String PREFS="rhremote.sync.operations";
    JSONObject state(){JSONObject[] value={null};instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception failure){throw new RuntimeException(failure);}});return value[0];}
    JSONObject sync(){JSONObject[] value={null};instrument.runOnMainSync(()->value[0]=RhSyncWork.shared(context).state());return value[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject reply=RhActions.dispatch(id,args);assertTrue(reply.toString(),reply.optBoolean("ok"));});instrument.waitForIdleSync();}
    void bind(Activity current)throws Exception{activity=current;Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);}
    void launch()throws Exception{bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();assertFalse(RhActions.hasStream());}
    View find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(label.equals(view.getContentDescription())||view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View child=find(((ViewGroup)view).getChildAt(i),label);if(child!=null)return child;}return null;}
    View control(String label){View found=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,found);return found;}
    void visible(String label){instrument.runOnMainSync(()->{View control=control(label);Rect bounds=new Rect();assertTrue(label,control.getGlobalVisibleRect(bounds));assertEquals(label,control.getWidth(),bounds.width());assertEquals(label,control.getHeight(),bounds.height());});}
    void capture(String name)throws Exception{Bitmap[] frame={null};instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();frame[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(frame[0]));});try(OutputStream out=new FileOutputStream(new File(evidence,name+".png"))){assertTrue(frame[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{frame[0].recycle();}}
    static String sha(File file)throws Exception{if(!file.isFile())return "absent";MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))!=-1)digest.update(buffer,0,count);}StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    JSONObject read(String name)throws Exception{return new JSONObject(new String(Files.readAllBytes(new File(backup,name).toPath()),java.nio.charset.StandardCharsets.UTF_8));}
    void changeTheme()throws Exception{
        Activity previous=activity;action("appearance.toggle",RhActions.obj());Activity[] next={null};long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(next[0]==null){instrument.runOnMainSync(()->{for(Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(candidate!=previous&&candidate.getClass().getName().equals("com.limelight.PcView"))next[0]=candidate;});assertTrue("Theme recreation",System.nanoTime()<until);if(next[0]==null)Thread.sleep(20);}bind(next[0]);instrument.waitForIdleSync();
    }
    void restore()throws Exception{
        JSONObject saved=read("saved.json");if(activity!=null)instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();
        File original=new File(backup,"shared.json");RhShared.apply(context,new JSONObject(new String(Files.readAllBytes(original.toPath()),java.nio.charset.StandardCharsets.UTF_8)));Files.copy(original.toPath(),new File(context.getFilesDir(),"rhremote-shared.json").toPath(),StandardCopyOption.REPLACE_EXISTING);
        SharedPreferences.Editor journal=context.getSharedPreferences(PREFS,0).edit().clear();JSONObject entries=saved.getJSONObject("journal");Iterator<String> keys=entries.keys();while(keys.hasNext()){String key=keys.next();JSONObject item=entries.getJSONObject(key);String type=item.getString("type");if(type.equals("boolean"))journal.putBoolean(key,item.getBoolean("value"));else if(type.equals("long"))journal.putLong(key,item.getLong("value"));else if(type.equals("int"))journal.putInt(key,item.getInt("value"));else if(type.equals("string"))journal.putString(key,item.getString("value"));else fail("Unexpected journal metadata type");}assertTrue(journal.commit());if(!saved.getBoolean("journalExisted"))assertTrue(context.deleteSharedPreferences(PREFS));
        SharedPreferences.Editor global=context.getSharedPreferences("rhremote.global",0).edit();if(saved.getBoolean("hadCheck"))global.putLong("lastUpdateCheck",saved.getLong("lastCheck"));else global.remove("lastUpdateCheck");assertTrue(global.commit());
        assertEquals(saved.getString("sharedSha256"),sha(new File(context.getFilesDir(),"rhremote-shared.json")));assertEquals(saved.getString("accountSha256"),sha(new File(context.getFilesDir(),"webdav-local.json")));Field owner=RhSyncWork.class.getDeclaredField("shared");owner.setAccessible(true);owner.set(null,null);
    }
    @Test public void processInterruptionRemainsVisibleAndDoesNotReplayTheQueuedSync()throws Exception{
        assertTrue("Only the owned emulator",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));String run=InstrumentationRegistry.getArguments().getString("syncDeathRun","");assertTrue(run.matches("[A-Za-z0-9_-]{1,80}"));String phase=InstrumentationRegistry.getArguments().getString("syncDeathPhase","");assertTrue(Arrays.asList("prepare","verify","cleanup").contains(phase));
        backup=new File(context.getFilesDir(),"ui-test-backups/sync-death-"+run);evidence=new File(context.getExternalFilesDir("ui-evidence"),"sync-death-"+run);assertTrue(evidence.isDirectory()||evidence.mkdirs());
        if(phase.equals("cleanup")){restore();return;}
        if(phase.equals("prepare")){
            assertFalse("Use a fresh run ID",backup.exists());assertTrue(backup.mkdirs());JSONObject original=RhShared.read(context);File shared=new File(context.getFilesDir(),"rhremote-shared.json");Files.copy(shared.toPath(),new File(backup,"shared.json").toPath());
            JSONObject journal=new JSONObject();for(Map.Entry<String,?> entry:context.getSharedPreferences(PREFS,0).getAll().entrySet()){Object value=entry.getValue();String type=value instanceof Boolean?"boolean":value instanceof Long?"long":value instanceof Integer?"int":value instanceof String?"string":"unsupported";journal.put(entry.getKey(),RhActions.obj("type",type,"value",value));}
            SharedPreferences global=context.getSharedPreferences("rhremote.global",0);JSONObject saved=RhActions.obj("journal",journal,"journalExisted",new File(context.getApplicationInfo().dataDir,"shared_prefs/"+PREFS+".xml").isFile(),"hadCheck",global.contains("lastUpdateCheck"),"lastCheck",global.getLong("lastUpdateCheck",0),"sharedSha256",sha(shared),"accountSha256",sha(new File(context.getFilesDir(),"webdav-local.json")),"pid",android.os.Process.myPid());RhShared.write(new File(backup,"saved.json"),saved);assertTrue(global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit());
            launch();List<Runnable> queued=new ArrayList<>();long[] admissionNanos={0};instrument.runOnMainSync(()->{try{RhSyncWork work=RhSyncWork.shared(context);Field callback=RhSyncWork.class.getDeclaredField("listener");callback.setAccessible(true);RhSyncWork.Listener listener=(RhSyncWork.Listener)callback.get(work);work.detach(listener);try{long began=System.nanoTime();work.start(original,"review",queued::add);admissionNanos[0]=System.nanoTime()-began;}finally{work.attach(listener);}}catch(Exception error){throw new RuntimeException(error);}});assertTrue(sync().getBoolean("busy"));assertEquals(1,queued.size());RhShared.write(new File(evidence,"prepared.json"),RhActions.obj("pid",android.os.Process.myPid(),"operationId",sync().getLong("operationId"),"queuedWorkers",queued.size(),"executedWorkers",0,"networkRequests",0,"metadataOnly",true,"admissionExcludesUiRendering",true,"admissionMs",admissionNanos[0]/1000000.0));if(InstrumentationRegistry.getArguments().getString("syncDeathHold","false").equals("true")){Thread.sleep(120000);fail("The controller must stop this prepared test process");}return;
        }
        JSONObject report=RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"pid",android.os.Process.myPid(),"oldPid",read("saved.json").getInt("pid"),"osScreenshots",false,"systemInputInjected",false,"networkRequests",0);
        try{
            assertNotEquals("A real process restart is required",report.getInt("oldPid"),report.getInt("pid"));assertTrue(context.getSharedPreferences("rhremote.global",0).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit());launch();action("app.settings.tab",RhActions.obj("tab","sync"));
            assertEquals("interrupted",sync().getString("phase"));assertFalse(sync().getBoolean("busy"));assertTrue(sync().getBoolean("automaticBlocked"));String warning=sync().getString("error");assertTrue(warning.contains("结果未确认"));assertEquals(warning,state().getString("syncError"));
            JSONObject[] rejected={null};instrument.runOnMainSync(()->rejected[0]=RhActions.dispatch("sync.run",RhActions.obj("automatic",true)));report.put("automaticResponse",rejected[0]);assertTrue(rejected[0].optBoolean("ok"));assertTrue(rejected[0].getJSONObject("result").getBoolean("skipped"));assertEquals("sync_interrupted",rejected[0].getJSONObject("result").getString("reason"));assertFalse(sync().getBoolean("busy"));report.put("automaticRequestSkipped",true);
            visible("配置同步错误");visible("上次同步结果待确认 · 自动同步已暂停");visible("保存");visible("取消");capture("interrupted-"+(RhShared.dark(context)?"dark":"light"));
            action("sync.edit",RhActions.obj("key","url","value","http://192.0.2.1/"));instrument.runOnMainSync(()->assertTrue(control("取消").performClick()));instrument.waitForIdleSync();assertEquals(warning,state().getString("syncError"));assertTrue(sync().getBoolean("automaticBlocked"));report.put("draftCancelPreservedWarning",true);
            changeTheme();assertEquals("sync",state().getString("settingsTab"));assertEquals(warning,state().getString("syncError"));visible("配置同步错误");visible("保存");visible("取消");capture("interrupted-"+(RhShared.dark(context)?"dark":"light"));report.put("actualThemeRecreation",true).put("interruptedFeedback",sync());
        }catch(Exception|AssertionError failure){report.put("failure",failure.toString()).put("failureSyncState",sync());throw failure;}finally{restore();report.put("preferencesRestored",true);RhShared.write(new File(evidence,"verified.json"),report);}
    }
}
