package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.graphics.*;
import android.view.*;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real HTTP, signed APK validation and Activity recreation; never opens the system installer. */
@RunWith(AndroidJUnit4.class)
public final class RhUpdateRecreationDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;
    void bind(Activity value)throws Exception{activity=value;Field field=value.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(value);assertNotNull(home);}
    JSONObject state(){JSONObject[] value={null};instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return value[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    void waitFor(java.util.function.Predicate<JSONObject> condition,String name)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!condition.test(state())){assertTrue(name+": "+state().optJSONObject("updates"),System.nanoTime()<end);Thread.sleep(20);}instrument.waitForIdleSync();}
    void recreateByTheme()throws Exception{
        Activity old=activity;boolean dark=RhShared.dark(activity);action("appearance.toggle",RhActions.obj());Activity[] next={null};long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(next[0]==null){instrument.runOnMainSync(()->{for(Activity value:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(value!=old&&value.getClass().getName().equals("com.limelight.PcView"))next[0]=value;});assertTrue("PcView was not recreated",System.nanoTime()<end);if(next[0]==null)Thread.sleep(20);}
        bind(next[0]);instrument.waitForIdleSync();assertEquals(!dark,RhShared.dark(activity));assertEquals("global-settings",state().optString("page"));assertEquals("updates",state().optString("settingsTab"));
    }
    Button primary(){View value=activity.getWindow().getDecorView().findViewWithTag("updates.primary");assertTrue(value instanceof Button);return(Button)value;}
    void footer(String label,boolean enabled){instrument.runOnMainSync(()->{Button button=primary();assertEquals(label,button.getText().toString());assertEquals(enabled,button.isEnabled());Rect visible=new Rect();assertTrue(button.getGlobalVisibleRect(visible));assertEquals(button.getWidth(),visible.width());assertEquals(button.getHeight(),visible.height());});}
    void clickPrimary(){instrument.runOnMainSync(()->{Button button=primary();assertTrue(button.isEnabled());assertTrue(button.performClick());});instrument.waitForIdleSync();}
    void capture(String name)throws Exception{
        Bitmap[] picture={null};instrument.runOnMainSync(()->{View view=activity.getWindow().getDecorView();picture[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(picture[0]));});
        File folder=activity.getExternalFilesDir("ui-evidence");assertTrue(folder.isDirectory()||folder.mkdirs());try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(picture[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{picture[0].recycle();}
    }
    static String sha(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[65536];int count;while((count=in.read(bytes))!=-1)digest.update(bytes,0,count);}StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    static final class Fixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));final ExecutorService workers=Executors.newCachedThreadPool();
        final CountDownLatch checking=new CountDownLatch(1),releaseCheck=new CountDownLatch(1),downloading=new CountDownLatch(1),releaseDownload=new CountDownLatch(1);
        final AtomicInteger checks=new AtomicInteger(),downloads=new AtomicInteger();final File apk;final byte[] feed;volatile boolean failMetadata;
        Fixture(File apk,byte[] feed)throws Exception{this.apk=apk;this.feed=feed;workers.execute(()->{try{while(!server.isClosed()){Socket socket=server.accept();workers.execute(()->reply(socket));}}catch(IOException stopped){}});}
        void reply(Socket socket){try(Socket owned=socket){BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String first=reader.readLine(),line;while((line=reader.readLine())!=null&&!line.isEmpty()){}boolean metadata=first!=null&&first.contains("/feed");if(metadata)checks.incrementAndGet();else downloads.incrementAndGet();OutputStream out=socket.getOutputStream();
            if(metadata&&failMetadata){out.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));return;}
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+(metadata?feed.length:apk.length())+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
            if(metadata){checking.countDown();if(!releaseCheck.await(25,TimeUnit.SECONDS))throw new IOException("check gate timeout");out.write(feed);}else try(InputStream in=new FileInputStream(apk)){byte[] bytes=new byte[65536];int count=in.read(bytes);assertTrue(count>0);out.write(bytes,0,count);out.flush();downloading.countDown();if(!releaseDownload.await(25,TimeUnit.SECONDS))throw new IOException("download gate timeout");while((count=in.read(bytes))!=-1)out.write(bytes,0,count);}out.flush();
        }catch(Exception stopped){}}
        HttpURLConnection open(String original)throws IOException{HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+server.getLocalPort()+(original.endsWith("releases.android.json")?"/feed":"/apk")).openConnection(Proxy.NO_PROXY);connection.setConnectTimeout(3000);connection.setReadTimeout(30000);return connection;}
        public void close()throws IOException{releaseCheck.countDown();releaseDownload.countDown();server.close();workers.shutdownNow();}
    }
    @Test public void oneUpdateSurvivesCheckDownloadAndErrorPageRecreation()throws Exception{
        Context context=instrument.getTargetContext();assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));boolean originalDark=RhShared.dark(context);
        File candidate=new File(context.getExternalFilesDir("ui-evidence"),"recreation-candidate.apk");assertTrue("Push a signed future x86_64 fixture APK first",candidate.isFile());PackageInfo incoming=context.getPackageManager().getPackageArchiveInfo(candidate.getAbsolutePath(),0),installed=context.getPackageManager().getPackageInfo(context.getPackageName(),0);assertNotNull(incoming);assertEquals(context.getPackageName(),incoming.packageName);assertTrue(incoming.getLongVersionCode()>installed.getLongVersionCode());
        JSONObject manifest=RhActions.obj("version",incoming.versionName,"versionCode",incoming.getLongVersionCode(),"packageName",incoming.packageName,"tag","v"+incoming.versionName,"fileName",candidate.getName(),"size",candidate.length(),"sha256",sha(candidate));
        File directory=new File(context.getFilesDir(),"updates"),backup=new File(context.getFilesDir(),"updates-before-recreation-"+System.nanoTime()),captured=new File(context.getFilesDir(),"updates-recreation-evidence-"+System.nanoTime());boolean hadUpdates=directory.exists();if(hadUpdates)Files.move(directory.toPath(),backup.toPath());
        SharedPreferences global=context.getSharedPreferences("rhremote.global",0);boolean hadCheck=global.contains("lastUpdateCheck");long previousCheck=global.getLong("lastUpdateCheck",0);global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();RhUpdateWork work=null;RhUpdates updater=null;RhUpdates.Connections originalConnections=null;
        Fixture fixture=new Fixture(candidate,RhActions.obj("schemaVersion",1,"android",manifest).toString().getBytes(StandardCharsets.UTF_8));
        try{
            bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();assertFalse(RhActions.hasStream());String before=RhShared.canonical(RhShared.read(context));
            Field model=RhHome.class.getDeclaredField("updates");model.setAccessible(true);updater=(RhUpdates)model.get(home);Field connections=RhUpdates.class.getDeclaredField("connections");connections.setAccessible(true);originalConnections=(RhUpdates.Connections)connections.get(updater);connections.set(updater,(RhUpdates.Connections)fixture::open);Field owner=RhHome.class.getDeclaredField("updateWork");owner.setAccessible(true);work=(RhUpdateWork)owner.get(home);
            action("app.settings.tab",RhActions.obj("tab","updates"));footer("检查更新",true);clickPrimary();assertTrue(fixture.checking.await(3,TimeUnit.SECONDS));long check=state().getJSONObject("updates").getLong("operationId");
            for(int round=0;round<2;round++){recreateByTheme();assertTrue(state().getBoolean("updatesBusy"));assertEquals(check,state().getJSONObject("updates").getLong("operationId"));instrument.runOnMainSync(()->assertFalse(RhActions.dispatch("updates.primary",RhActions.obj()).optBoolean("ok")));assertFalse(primary().isEnabled());capture("recreation-check-"+round);}
            fixture.releaseCheck.countDown();waitFor(s->!s.optBoolean("updatesBusy")&&s.optJSONObject("updates").optString("phase").equals("available"),"check completed");footer("下载并安装",true);assertEquals(1,fixture.checks.get());assertEquals(0,fixture.downloads.get());
            clickPrimary();assertTrue(fixture.downloading.await(3,TimeUnit.SECONDS));waitFor(s->s.optJSONObject("updates").optLong("downloadedBytes")>0,"partial download");long download=state().getJSONObject("updates").getLong("operationId"),bytes=state().getJSONObject("updates").getLong("downloadedBytes");
            for(int round=0;round<2;round++){recreateByTheme();assertTrue(state().getBoolean("updatesBusy"));assertEquals(download,state().getJSONObject("updates").getLong("operationId"));assertTrue(state().getJSONObject("updates").getLong("downloadedBytes")>=bytes);assertFalse(primary().isEnabled());capture("recreation-download-"+round);}
            instrument.runOnMainSync(()->assertTrue(activity.moveTaskToBack(true)));instrument.waitForIdleSync();long pauseEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean[] resumed={true};while(resumed[0]){instrument.runOnMainSync(()->resumed[0]=ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)==Stage.RESUMED);assertTrue("Home stayed in foreground",System.nanoTime()<pauseEnd);if(resumed[0])Thread.sleep(20);}
            fixture.releaseDownload.countDown();waitFor(s->s.optJSONObject("updates").optBoolean("awaitingForeground"),"background preparation");assertEquals("ready",state().getJSONObject("updates").getString("phase"));assertTrue(state().getBoolean("updatesBusy"));assertEquals(1,fixture.downloads.get());assertEquals(manifest.getString("sha256"),sha(new File(directory,"RHRemote-update.apk")));
            Activity closed=activity;instrument.runOnMainSync(closed::finish);long closeEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!closed.isDestroyed()){assertTrue("Explicit close did not destroy home",System.nanoTime()<closeEnd);Thread.sleep(20);}instrument.waitForIdleSync();assertFalse(work.busy());assertEquals("downloaded",updater.state().getString("phase"));
            bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();action("app.settings.tab",RhActions.obj("tab","updates"));footer("安装更新",true);capture("recreation-download-ready");assertEquals(1,fixture.downloads.get());
            fixture.failMetadata=true;action("updates.check",RhActions.obj());waitFor(s->!s.optBoolean("updatesBusy")&&s.optJSONObject("updates").optString("phase").equals("error"),"recheck failure");footer("重新检查",true);action("updates.details.toggle",RhActions.obj());
            for(int round=0;round<2;round++){recreateByTheme();assertTrue(state().getBoolean("updateDetails"));assertEquals("updates.check",state().getJSONObject("updates").getString("failedAction"));assertEquals(2,state().getJSONObject("updates").getJSONArray("sourceFailures").length());assertTrue(state().getJSONObject("updates").getBoolean("downloaded"));footer("重新检查",true);capture("recreation-error-"+round);}
            assertEquals(3,fixture.checks.get());assertEquals(1,fixture.downloads.get());assertEquals(before,RhShared.canonical(RhShared.read(context)));assertEquals(installed.getLongVersionCode(),context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode());
            RhShared.write(new File(context.getExternalFilesDir("ui-evidence"),"update-recreation.json"),RhActions.obj("actualRecreations",6,"metadataRequests",fixture.checks.get(),"downloads",fixture.downloads.get(),"backgroundInstallDeferred",true,"explicitCloseDroppedInstallIntent",true,"verifiedApkSha256",manifest.getString("sha256"),"manualInstallationConfirmed",false,"configurationPreserved",true));
        }finally{
            RhUpdateWork owned=work;instrument.runOnMainSync(()->{if(owned!=null)owned.abandonInstall();if(activity!=null)activity.finish();});instrument.waitForIdleSync();fixture.close();if(work!=null){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(work.busy()){assertTrue("Update work did not finish during cleanup",System.nanoTime()<end);Thread.sleep(20);}}
            if(updater!=null&&originalConnections!=null){Field connections=RhUpdates.class.getDeclaredField("connections");connections.setAccessible(true);connections.set(updater,originalConnections);}Field shared=RhUpdateWork.class.getDeclaredField("shared");shared.setAccessible(true);shared.set(null,null);
            RhShared.theme(context,originalDark);if(hadCheck)global.edit().putLong("lastUpdateCheck",previousCheck).commit();else global.edit().remove("lastUpdateCheck").commit();if(directory.exists())Files.move(directory.toPath(),captured.toPath());if(hadUpdates)Files.move(backup.toPath(),directory.toPath());
        }
    }
}
