package com.limelight.rhremote;

import android.app.*;
import android.content.*;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native Views and actual partial-file cleanup. HTTP is a controlled in-memory
 * boundary; no payload here is claimed to be a valid APK or a public download. */
@RunWith(AndroidJUnit4.class)
public final class RhUpdateCancelDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;
    JSONObject state(){JSONObject[] value={null};instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception error){throw new RuntimeException(error);}});return value[0];}
    void bind(Activity value)throws Exception{activity=value;Field field=value.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(value);}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    void waitFor(java.util.function.Predicate<JSONObject> test)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!test.test(state())){assertTrue(state().toString(),System.nanoTime()<end);Thread.sleep(20);}instrument.waitForIdleSync();}
    Button button(String id){View view=activity.getWindow().getDecorView().findViewWithTag(id);assertTrue(id,view instanceof Button);return(Button)view;}
    void click(String id){instrument.runOnMainSync(()->{Button button=button(id);assertEquals(View.VISIBLE,button.getVisibility());assertTrue(button.isEnabled());assertTrue(button.performClick());});instrument.waitForIdleSync();}
    void recreateTheme()throws Exception{
        Activity previous=activity;action("appearance.toggle",RhActions.obj());Activity[] next={null};long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(next[0]==null){instrument.runOnMainSync(()->{for(Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(candidate!=previous&&candidate.getClass().getName().equals("com.limelight.PcView"))next[0]=candidate;});assertTrue(System.nanoTime()<end);if(next[0]==null)Thread.sleep(20);}bind(next[0]);instrument.waitForIdleSync();
    }
    void capture(File evidence,String name)throws Exception{
        Bitmap[] picture={null};instrument.runOnMainSync(()->{
            Button primary=button("updates.primary"),cancel=button("updates.cancel");Rect first=new Rect(),second=new Rect();assertTrue(primary.getGlobalVisibleRect(first));assertTrue(cancel.getGlobalVisibleRect(second));assertEquals(primary.getWidth(),first.width());assertEquals(primary.getHeight(),first.height());assertEquals(cancel.getWidth(),second.width());assertEquals(cancel.getHeight(),second.height());assertTrue("Update actions need an 8 dp gap",first.right+Math.round(8*activity.getResources().getDisplayMetrics().density)<=second.left);
            View view=activity.getWindow().getDecorView();picture[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(picture[0]));
        });File destination=new File(evidence,name+".png");assertFalse(destination.exists());try(OutputStream out=new FileOutputStream(destination)){assertTrue(picture[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{picture[0].recycle();}
    }
    static final class Boundary implements RhUpdates.Connections {
        final AtomicInteger checks=new AtomicInteger(),downloads=new AtomicInteger();final String feed;
        volatile CountDownLatch entered,release,cleanup;
        Boundary(String name){feed=RhActions.obj("schemaVersion",1,"android",RhActions.obj("version","9.0.0","versionCode",1900000000,"packageName",name,"tag","v9.0.0","fileName","cancel-only.apk","size",1024*1024,"sha256",new String(new char[64]).replace('\0','0'))).toString();}
        void next(){entered=new CountDownLatch(1);release=new CountDownLatch(1);cleanup=new CountDownLatch(1);}
        public HttpURLConnection open(String address)throws IOException{
            boolean metadata=address.endsWith("releases.android.json");if(metadata)checks.incrementAndGet();else downloads.incrementAndGet();
            return new HttpURLConnection(new URL(address)){
                @Override public int getResponseCode(){return 200;}
                @Override public InputStream getInputStream(){if(metadata)return new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8));return new InputStream(){boolean first=true;
                    @Override public int read(byte[] buffer,int offset,int length)throws IOException{if(first){first=false;java.util.Arrays.fill(buffer,offset,offset+8,(byte)42);return 8;}entered.countDown();try{if(!release.await(8,TimeUnit.SECONDS)||!cleanup.await(8,TimeUnit.SECONDS))throw new IOException("boundary cleanup timed out");}catch(InterruptedException error){throw new IOException(error);}throw new IOException("cancelled boundary read");}
                    @Override public int read()throws IOException{throw new IOException("bulk reads required");}};}
                @Override public void disconnect(){if(!metadata)release.countDown();}
                @Override public void connect(){}@Override public boolean usingProxy(){return false;}
            };
        }
        void close(){if(release!=null)release.countDown();if(cleanup!=null)cleanup.countDown();}
    }
    @Test public void nativeCancelSurvivesRecreationCleansPartialAndDoesNotInstall()throws Exception{
        Context context=instrument.getTargetContext();String before=RhShared.canonical(RhShared.read(context));boolean dark=RhShared.dark(context);
        SharedPreferences global=context.getSharedPreferences("rhremote.global",0);boolean hadCheck=global.contains("lastUpdateCheck");long lastCheck=global.getLong("lastUpdateCheck",0);global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
        String run=InstrumentationRegistry.getArguments().getString("caseId","cancel64");File evidence=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(evidence.exists());assertTrue(evidence.mkdirs());
        File directory=new File(context.getFilesDir(),"updates"),backup=new File(context.getFilesDir(),"updates-before-"+run),retired=new File(context.getFilesDir(),"updates-retired-"+run);assertFalse(backup.exists());assertFalse(retired.exists());boolean hadUpdates=directory.exists();if(hadUpdates)Files.move(directory.toPath(),backup.toPath());
        Boundary boundary=new Boundary(context.getPackageName());RhUpdateWork work=null;RhUpdates updater=null;RhUpdates.Connections previous=null;
        try{
            bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();Field field=RhHome.class.getDeclaredField("updateWork");field.setAccessible(true);work=(RhUpdateWork)field.get(home);assertFalse(work.busy());updater=work.updates;field=RhUpdates.class.getDeclaredField("connections");field.setAccessible(true);previous=(RhUpdates.Connections)field.get(updater);field.set(updater,boundary);
            action("app.settings.tab",RhActions.obj("tab","updates"));click("updates.primary");waitFor(s->!s.optBoolean("updatesBusy"));assertEquals("available",state().getJSONObject("updates").getString("phase"));long oldOperation=0;
            for(int round=0;round<2;round++){
                boundary.next();click("updates.primary");assertTrue(boundary.entered.await(3,TimeUnit.SECONDS));waitFor(s->s.optJSONObject("updates").optLong("downloadedBytes")==8);assertEquals(8,new File(directory,"pending.apk").length());long operation=state().getJSONObject("updates").getLong("operationId");
                if(round>0){long stale=oldOperation;instrument.runOnMainSync(()->assertFalse(RhActions.dispatch("updates.cancel",RhActions.obj("operationId",stale)).optBoolean("ok")));assertFalse(state().getJSONObject("updates").optBoolean("cancelRequested"));}
                capture(evidence,"partial-"+round);click("updates.cancel");waitFor(s->s.optJSONObject("updates").optString("phase").equals("cancelling"));capture(evidence,"cancelling-"+round);recreateTheme();assertEquals(operation,state().getJSONObject("updates").getLong("operationId"));capture(evidence,"recreated-"+round);
                assertTrue(state().getBoolean("updatesBusy"));assertFalse(state().getJSONObject("updates").getBoolean("canCancel"));boundary.cleanup.countDown();waitFor(s->!s.optBoolean("updatesBusy"));assertEquals("cancelled",state().getJSONObject("updates").getString("phase"));assertFalse(new File(directory,"pending.apk").exists());assertFalse(new File(directory,"RHRemote-update.apk").exists());assertEquals("下载并安装",button("updates.primary").getText().toString());assertEquals(View.GONE,button("updates.cancel").getVisibility());oldOperation=operation;
            }
            assertEquals(1,boundary.checks.get());assertEquals(2,boundary.downloads.get());RhShared.theme(context,dark);assertEquals(before,RhShared.canonical(RhShared.read(context)));
            RhShared.write(new File(evidence,"result.json"),RhActions.obj("passed",true,"metadataRequests",boundary.checks.get(),"partialDownloads",boundary.downloads.get(),"bytesBeforeEachCancel",8,"actualRecreations",2,"partialRemoved",true,"staleCancelRejected",true,"configurationPreserved",true,"syntheticHttpBoundary",true,"validApkOrPublicDownloadTested",false,"systemInstallerOpened",false));
        }finally{
            boundary.close();RhUpdateWork owned=work;instrument.runOnMainSync(()->{if(owned!=null)owned.abandonInstall();if(activity!=null)activity.finish();});instrument.waitForIdleSync();if(work!=null){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(work.busy()){assertTrue("Update work did not clean up",System.nanoTime()<end);Thread.sleep(20);}}
            if(updater!=null&&previous!=null){Field field=RhUpdates.class.getDeclaredField("connections");field.setAccessible(true);field.set(updater,previous);}Field shared=RhUpdateWork.class.getDeclaredField("shared");shared.setAccessible(true);shared.set(null,null);RhShared.theme(context,dark);if(hadCheck)global.edit().putLong("lastUpdateCheck",lastCheck).commit();else global.edit().remove("lastUpdateCheck").commit();if(directory.exists())Files.move(directory.toPath(),retired.toPath());if(hadUpdates)Files.move(backup.toPath(),directory.toPath());
        }
    }
}
