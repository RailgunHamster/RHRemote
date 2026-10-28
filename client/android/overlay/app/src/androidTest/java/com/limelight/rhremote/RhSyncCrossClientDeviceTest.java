package com.limelight.rhremote;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Dedicated emulator + existing WebDAV service. No transport, ETag or merge stand-ins. */
@RunWith(AndroidJUnit4.class)
public final class RhSyncCrossClientDeviceTest {
    private final RhUpdateRecreationDeviceTest driver = new RhUpdateRecreationDeviceTest();
    private File output;
    private String stage="setup";

    private static Object field(Object owner,String name)throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static void field(Object owner,String name,Object value)throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);
    }
    private View find(View view,String label) {
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(label.equals(view.getContentDescription())||view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private void click(String label) {
        driver.instrument.runOnMainSync(()->{
            View view=find(driver.activity.getWindow().getDecorView(),label);assertNotNull(label,view);assertTrue(label,view.isEnabled());
            Rect visible=new Rect();assertTrue(label,view.getGlobalVisibleRect(visible));assertEquals(label,view.getHeight(),visible.height());assertEquals(label,view.getWidth(),visible.width());assertTrue(view.performClick());
        });driver.instrument.waitForIdleSync();
    }
    private void input(String label,String value) {
        driver.instrument.runOnMainSync(()->{View view=find(driver.activity.getWindow().getDecorView(),label);assertTrue(label,view instanceof EditText);((EditText)view).setText(value);});driver.instrument.waitForIdleSync();
    }
    private void capture(String name)throws Exception {
        Bitmap[] frame={null};driver.instrument.runOnMainSync(()->{View view=driver.activity.getWindow().getDecorView();view.jumpDrawablesToCurrentState();frame[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(frame[0]));});
        File file=new File(output,name+".png");assertFalse(file.exists());try(OutputStream stream=new FileOutputStream(file)){assertTrue(frame[0].compress(Bitmap.CompressFormat.PNG,100,stream));}finally{frame[0].recycle();}
    }
    private void signal(String name,JSONObject value)throws Exception {stage=name;RhShared.write(new File(output,name+".json"),value);}
    private JSONObject await(String name)throws Exception {
        File ready=new File(output,name+".json"),abort=new File(output,"abort.json");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);
        while(!ready.isFile()){assertFalse("Windows coordinator aborted",abort.isFile());assertTrue("Coordinator did not reach "+name,System.nanoTime()<end);Thread.sleep(100);}
        return new JSONObject(new String(Files.readAllBytes(ready.toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }
    private JSONObject sync()throws Exception {
        long before=driver.state().getJSONObject("sync").optLong("operationId");click("立即同步");
        completion(before);
        JSONObject value=driver.state().getJSONObject("sync");assertTrue(value.optString("error"),value.optString("error").isEmpty());return value;
    }
    private JSONObject resolve(String choice)throws Exception {
        long before=driver.state().getJSONObject("sync").optLong("operationId");click(choice);
        completion(before);
        JSONObject value=driver.state().getJSONObject("sync");assertEquals(value.optString("error"),"completed",value.getString("phase"));assertEquals(0,value.getJSONArray("conflicts").length());return value;
    }
    private void completion(long before)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(true){JSONObject state=driver.state();if(!state.optBoolean("syncBusy")&&state.getJSONObject("sync").optLong("operationId")>before)break;assertTrue("WebDAV operation did not complete",System.nanoTime()<end);Thread.sleep(40);}driver.instrument.waitForIdleSync();
    }
    private JSONObject profile(String uuid)throws Exception {return driver.state().getJSONObject("saved").getJSONObject("profiles").getJSONObject(uuid);}
    private String alias(String uuid)throws Exception {
        JSONArray devices=driver.state().getJSONArray("devices");for(int i=0;i<devices.length();i++)if(devices.getJSONObject(i).getString("uuid").equals(uuid))return devices.getJSONObject(i).optString("displayName");throw new AssertionError("Missing target device");
    }
    private void alias(String uuid,String value) {
        stage="device-info";driver.action("device.info.open",RhActions.obj("uuid",uuid));driver.action("device.info.input",RhActions.obj("displayName",value));click("完成");
    }
    private static void restore(SharedPreferences preferences,Map<String,?> values) {
        SharedPreferences.Editor edit=preferences.edit().clear();
        for(Map.Entry<String,?> item:values.entrySet()){
            Object value=item.getValue();String key=item.getKey();
            if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));else throw new AssertionError("Unexpected preference type");
        }
        assertTrue(edit.commit());
    }

    @Test public void nativeViewsSynchronizeIndependentProfilesResolveConcurrentNamesAndKeepOrdering()throws Exception {
        assertTrue("Dedicated emulator only",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        String run=InstrumentationRegistry.getArguments().getString("crossRun","");assertTrue(run.matches("cross-[a-f0-9]{12}"));
        Context context=driver.instrument.getTargetContext();File privateInput=new File(context.getFilesDir(),"ui-sync-private-"+run+".json");assertTrue(privateInput.isFile());
        JSONObject configuration=new JSONObject(new String(Files.readAllBytes(privateInput.toPath()),java.nio.charset.StandardCharsets.UTF_8));Files.delete(privateInput.toPath());
        String url=configuration.getString("url"),username=configuration.getString("username"),password=configuration.getString("password"),uuid=configuration.getString("uuid"),windows=configuration.getString("windowsClientId");
        assertEquals("http",new java.net.URI(url).getScheme());assertEquals("192.168.0.104",new java.net.URI(url).getHost());assertTrue(new java.net.URI(url).getPath().endsWith("/RHRemote-UI-"+run));
        output=new File(context.getExternalFilesDir("ui-evidence"),"real-sync-"+run);assertFalse(output.exists());assertTrue(output.mkdirs());
        File shared=new File(context.getFilesDir(),"rhremote-shared.json"),account=new File(context.getFilesDir(),"webdav-local.json");byte[] originalBytes=Files.readAllBytes(shared.toPath());JSONObject original=RhShared.read(context);String accountHash=RhSyncLocalApplyDeviceTest.sha(account);boolean originalDark=RhShared.dark(context);
        SharedPreferences global=context.getSharedPreferences("rhremote.global",0),journal=context.getSharedPreferences("rhremote.sync.operations",0);Map<String,?> globalBefore=new HashMap<>(global.getAll()),journalBefore=new HashMap<>(journal.getAll());
        File scratch=new File(context.getFilesDir(),"ui-test-backups/real-sync-"+run),backups=new File(context.getFilesDir(),"sync-backups"),savedBackups=new File(context.getFilesDir(),"sync-backups-before-"+run),capturedBackups=new File(scratch,"captured-backups");assertFalse(scratch.exists());assertFalse(savedBackups.exists());assertTrue(scratch.mkdirs());
        boolean hadBackups=backups.exists(),movedBackups=false;RhSync model=null;Object oldFile=null,oldLocal=null;RhSyncWork work=null;boolean passed=false;JSONObject proof=RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"run",run,"transportInjected",false,"webdavService","existing-winserver-service","automatic",false,"osScreenshots",false,"systemInputInjected",false,"streamStarted",false);
        try {
            JSONObject themed=RhShared.read(context);String originalClient=RhShared.clientId(context),testClient="client-ui-android-"+run;JSONObject currentGroup=themed.getJSONObject("profiles").optJSONObject(originalClient);themed.getJSONObject("clients").put(testClient,RhShared.clientInfo());themed.getJSONObject("profiles").put(testClient,currentGroup==null?new JSONObject():new JSONObject(currentGroup.toString()));themed.put("dark",false);
            assertTrue(global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).putString("clientId",testClient).putBoolean("dark",false).commit());RhShared.write(shared,themed);
            driver.bind(driver.instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));driver.instrument.waitForIdleSync();
            assertFalse(RhActions.hasStream());assertFalse(driver.state().getJSONObject("sync").optBoolean("automatic"));assertNotEquals(windows,driver.state().getJSONObject("saved").getString("clientId"));
            work=(RhSyncWork)field(driver.home,"syncWork");assertFalse(work.busy());model=(RhSync)field(driver.home,"sync");oldFile=field(model,"file");oldLocal=field(model,"local");
            if(hadBackups){Files.move(backups.toPath(),savedBackups.toPath());movedBackups=true;}
            field(model,"file",new File(scratch,"account.json"));field(model,"local",new JSONObject());work.configured();
            driver.action("device.settings.open",RhActions.obj("uuid",uuid));driver.action("settings.set",RhActions.obj("key","resolution","value",new JSONArray(new int[]{2520,1680})));driver.action("settings.set",RhActions.obj("key","volume","value",65));click("保存");alias(uuid,"UI 同步基线");
            stage="webdav-configuration";driver.action("app.settings.tab",RhActions.obj("tab","sync"));input("WebDAV 目录地址（HTTP / HTTPS）",url);input("用户名",username);input("密码",password);click("保存");assertFalse(driver.state().getBoolean("syncDirty"));assertFalse(driver.state().getJSONObject("sync").getBoolean("automatic"));
            signal("android-ready",RhActions.obj("clientId",driver.state().getJSONObject("saved").getString("clientId")));
            await("windows-initial-published");JSONObject initial=sync();if(initial.getJSONArray("conflicts").length()>0)resolve("冲突项保留本机");
            assertEquals(2520,profile(uuid).getInt("width"));assertEquals(65,profile(uuid).getInt("volume"));capture("initial-synchronized");
            signal("android-initial-published",RhActions.obj("clientId",driver.state().getJSONObject("saved").getString("clientId"),"width",2520,"volume",65));
            await("windows-concurrent-edit");alias(uuid,"Android 并发别名");driver.action("app.settings.tab",RhActions.obj("tab","sync"));signal("android-concurrent-edit",RhActions.obj("displayName",alias(uuid)));
            await("windows-published-conflict");JSONObject conflict=sync();assertEquals("conflict",conflict.getString("phase"));assertTrue(conflict.getJSONArray("conflicts").toString().contains(uuid));capture("conflict-review");signal("android-conflict-visible",RhActions.obj("conflicts",conflict.getJSONArray("conflicts")));
            await("windows-conflict-reviewed");resolve("冲突项保留远端");assertEquals("Windows 并发别名",alias(uuid));assertEquals(2520,profile(uuid).getInt("width"));assertEquals(65,profile(uuid).getInt("volume"));capture("conflict-resolved");signal("android-resolved",RhActions.obj("displayName",alias(uuid),"width",2520,"volume",65));
            await("windows-verified");driver.action("devices.open",RhActions.obj());JSONArray beforeOrder=driver.state().getJSONArray("devices");String last=beforeOrder.getJSONObject(beforeOrder.length()-1).getString("uuid");driver.action("device.move",RhActions.obj("uuid",last,"direction","up"));JSONArray order=new JSONArray();JSONArray devices=driver.state().getJSONArray("devices");for(int i=0;i<devices.length();i++)order.put(devices.getJSONObject(i).getString("uuid"));driver.action("app.settings.tab",RhActions.obj("tab","sync"));assertEquals("completed",sync().getString("phase"));signal("android-ordered",RhActions.obj("order",order));
            await("windows-order-verified");driver.action("device.settings.open",RhActions.obj("uuid",uuid));driver.action("settings.tab",RhActions.obj("tab","advanced"));driver.action("settings.copy.preview",RhActions.obj("clientId",windows));assertEquals("profile-copy",driver.state().getString("page"));assertEquals(1920,driver.state().getJSONObject("profileCopy").getJSONObject("profile").getInt("width"));assertEquals(25,driver.state().getJSONObject("profileCopy").getJSONObject("profile").getInt("volume"));capture("foreign-profile-preview");click("取消");assertEquals("settings",driver.state().getString("page"));assertEquals(2520,driver.state().getJSONObject("draft").getInt("width"));assertEquals(65,profile(uuid).getInt("volume"));
            assertFalse(driver.state().getBoolean("syncBusy"));assertFalse(RhActions.hasStream());assertFalse(driver.state().getJSONObject("sync").has("password"));if(password.length()>=8)assertFalse(driver.state().toString().contains(password));
            proof.put("profileIsolation",true).put("conflictResolution",true).put("orderPublished",true).put("foreignPreviewCancelKeptOwnProfile",true);passed=true;
        } catch(Exception|AssertionError failure) {
            proof.put("failureStage",stage).put("failureType",failure.getClass().getSimpleName());
            if(!stage.equals("webdav-configuration"))try{capture("failure-page");}catch(Exception ignored){}
            throw failure;
        } finally {
            RhSyncWork owned=work;driver.instrument.runOnMainSync(()->{if(driver.activity!=null)driver.activity.finish();});driver.instrument.waitForIdleSync();
            if(owned!=null){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(owned.busy()){assertTrue("Sync did not finish; preserve files",System.nanoTime()<end);Thread.sleep(50);}}
            if(model!=null&&oldFile!=null){field(model,"file",oldFile);field(model,"local",oldLocal);}Field singleton=RhSyncWork.class.getDeclaredField("shared");singleton.setAccessible(true);singleton.set(null,null);
            if(movedBackups){if(backups.exists())Files.move(backups.toPath(),capturedBackups.toPath());Files.move(savedBackups.toPath(),backups.toPath());}else if(!hadBackups&&backups.exists())Files.move(backups.toPath(),capturedBackups.toPath());
            restore(global,globalBefore);RhShared.apply(context,original);Files.write(shared.toPath(),originalBytes);RhShared.theme(context,originalDark);restore(global,globalBefore);restore(journal,journalBefore);assertArrayEquals(originalBytes,Files.readAllBytes(shared.toPath()));assertEquals(accountHash,RhSyncLocalApplyDeviceTest.sha(account));
            Files.deleteIfExists(new File(scratch,"account.json").toPath());
            proof.put("passed",passed).put("sharedConfigurationRestored",true).put("realWebdavAccountUnchanged",true).put("testEncryptedAccountRemoved",true).put("originalBackupsRestored",true);RhShared.write(new File(output,"result.json"),proof);
            if(passed){assertEquals(context.getFilesDir().getCanonicalFile(),scratch.getCanonicalFile().getParentFile().getParentFile());assertTrue(scratch.getName().equals("real-sync-"+run));try(java.util.stream.Stream<Path> entries=Files.walk(scratch.toPath())){for(Path path:(Iterable<Path>)entries.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}}
        }
    }
}
