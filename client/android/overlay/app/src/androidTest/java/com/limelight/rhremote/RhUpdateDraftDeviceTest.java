package com.limelight.rhremote;

import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageInfo;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Signed APK over real emulator HTTP; stops before Android's installer. */
@RunWith(AndroidJUnit4.class)
public final class RhUpdateDraftDeviceTest {
    private final RhUpdateRecreationDeviceTest driver = new RhUpdateRecreationDeviceTest();
    private File folder;

    private Object field(Object owner,String name)throws Exception {
        Field value=owner.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(owner);
    }
    private View find(View view,String label){
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(label.equals(view.getContentDescription())||view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private void click(String label){
        driver.instrument.runOnMainSync(()->{View view=find(driver.activity.getWindow().getDecorView(),label);assertNotNull(label,view);assertTrue(view.isEnabled());assertTrue(view.performClick());});driver.instrument.waitForIdleSync();
    }
    private void decision(int which)throws Exception {
        AlertDialog dialog=(AlertDialog)field(driver.home,"navigationDialog");assertNotNull(dialog);
        driver.instrument.runOnMainSync(()->assertTrue(dialog.getButton(which).performClick()));driver.instrument.waitForIdleSync();
    }
    private void capture(String name,boolean modal)throws Exception {
        AlertDialog dialog=modal?(AlertDialog)field(driver.home,"navigationDialog"):null;Bitmap[] frame={null};
        driver.instrument.runOnMainSync(()->{View decor=dialog==null?driver.activity.getWindow().getDecorView():dialog.getWindow().getDecorView();decor.jumpDrawablesToCurrentState();frame[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(frame[0]));});
        File file=new File(folder,name+".png");assertFalse(file.exists());try(OutputStream output=new FileOutputStream(file)){assertTrue(frame[0].compress(Bitmap.CompressFormat.PNG,100,output));}finally{frame[0].recycle();}
    }

    @Test public void realDownloadProtectsTheCurrentDraftBeforeAnyInstallerHandoff()throws Exception {
        assertTrue("Dedicated emulator only",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        String run=InstrumentationRegistry.getArguments().getString("draftRun","");assertTrue(run.matches("[a-zA-Z0-9_-]{1,64}"));
        Context context=driver.instrument.getTargetContext();boolean originalDark=RhShared.dark(context);boolean dark=InstrumentationRegistry.getArguments().getString("draftTheme","light").equals("dark");
        File shared=new File(context.getFilesDir(),"rhremote-shared.json");byte[] beforeBytes=Files.readAllBytes(shared.toPath());JSONObject original=RhShared.read(context);
        File candidate=new File(context.getExternalFilesDir("ui-evidence"),"update-draft-candidate.apk");assertTrue(candidate.isFile());
        PackageInfo incoming=context.getPackageManager().getPackageArchiveInfo(candidate.getAbsolutePath(),0),installed=context.getPackageManager().getPackageInfo(context.getPackageName(),0);assertNotNull(incoming);assertEquals(context.getPackageName(),incoming.packageName);assertTrue(incoming.getLongVersionCode()>installed.getLongVersionCode());
        JSONObject manifest=RhActions.obj("version",incoming.versionName,"versionCode",incoming.getLongVersionCode(),"packageName",incoming.packageName,"tag","v"+incoming.versionName,"fileName",candidate.getName(),"size",candidate.length(),"sha256",RhUpdateRecreationDeviceTest.sha(candidate));
        folder=new File(context.getExternalFilesDir("ui-evidence"),"update-draft-"+run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        File directory=new File(context.getFilesDir(),"updates"),backup=new File(context.getFilesDir(),"updates-before-draft-"+run),captured=new File(context.getFilesDir(),"updates-draft-captured-"+run);assertFalse(backup.exists());assertFalse(captured.exists());
        boolean hadUpdates=directory.exists();if(hadUpdates)Files.move(directory.toPath(),backup.toPath());
        SharedPreferences global=context.getSharedPreferences("rhremote.global",0);boolean hadCheck=global.contains("lastUpdateCheck");long check=global.getLong("lastUpdateCheck",0);global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
        RhUpdateWork work=null;RhUpdates updater=null;RhUpdates.Connections oldConnections=null;boolean passed=false;
        RhUpdateRecreationDeviceTest.Fixture fixture=new RhUpdateRecreationDeviceTest.Fixture(candidate,RhActions.obj("schemaVersion",1,"android",manifest).toString().getBytes(StandardCharsets.UTF_8));
        try {
            RhShared.theme(context,dark);JSONObject themed=RhShared.read(context);themed.put("dark",dark);RhShared.write(shared,themed);
            driver.bind(driver.instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));driver.instrument.waitForIdleSync();
            assertFalse(driver.state().getJSONObject("sync").optBoolean("automatic"));assertFalse(RhActions.hasStream());
            String uuid=driver.state().getJSONArray("devices").getJSONObject(0).getString("uuid");String baseline=RhShared.canonical(RhShared.read(context));int volume=RhProfile.load(context,uuid).volume==37?38:37;
            updater=(RhUpdates)field(driver.home,"updates");work=(RhUpdateWork)field(driver.home,"updateWork");Field connections=RhUpdates.class.getDeclaredField("connections");connections.setAccessible(true);oldConnections=(RhUpdates.Connections)connections.get(updater);connections.set(updater,(RhUpdates.Connections)fixture::open);
            driver.action("app.settings.tab",RhActions.obj("tab","updates"));fixture.releaseCheck.countDown();driver.clickPrimary();driver.waitFor(s->!s.optBoolean("updatesBusy")&&s.optJSONObject("updates").optString("phase").equals("available"),"metadata");driver.footer("下载并安装",true);driver.clickPrimary();assertTrue(fixture.downloading.await(3,TimeUnit.SECONDS));driver.waitFor(s->s.optJSONObject("updates").optLong("downloadedBytes")>0,"first APK bytes");
            click("返回设备");driver.action("device.settings.open",RhActions.obj("uuid",uuid));click("音频");JSONArray fields=RhSettings.catalog(context);String volumeLabel="";for(int i=0;i<fields.length();i++)if(fields.getJSONObject(i).getString("key").equals("volume"))volumeLabel=fields.getJSONObject(i).getString("label");final String label=volumeLabel;
            driver.instrument.runOnMainSync(()->{View input=find(driver.activity.getWindow().getDecorView(),label);assertTrue(input instanceof EditText);assertTrue(input.isEnabled());((EditText)input).setText(Integer.toString(volume));});driver.instrument.waitForIdleSync();fixture.releaseDownload.countDown();
            driver.waitFor(s->!s.optBoolean("updatesBusy")&&!s.isNull("navigationPrompt"),"draft decision after APK verification");assertEquals("unsaved-device",driver.state().getJSONObject("navigationPrompt").getString("kind"));assertEquals("downloaded",driver.state().getJSONObject("updates").getString("phase"));assertEquals(baseline,RhShared.canonical(RhShared.read(context)));capture("decision",true);
            decision(AlertDialog.BUTTON_NEUTRAL);assertEquals(Integer.toString(volume),driver.state().getJSONObject("settingsInputs").getString("volume"));assertFalse(driver.state().getBoolean("updatesBusy"));capture("continue-editing",false);
            driver.action("updates.install",RhActions.obj());decision(AlertDialog.BUTTON_NEUTRAL);assertEquals(1,fixture.downloads.get());assertEquals(baseline,RhShared.canonical(RhShared.read(context)));
            driver.action("app.settings.tab",RhActions.obj("tab","updates"));decision(AlertDialog.BUTTON_POSITIVE);driver.footer("安装更新",true);assertEquals(volume,RhProfile.load(context,uuid).volume);assertEquals(1,fixture.downloads.get());assertEquals(manifest.getString("sha256"),RhUpdateRecreationDeviceTest.sha(new File(directory,"RHRemote-update.apk")));capture("ready-after-save",false);
            assertEquals(installed.getLongVersionCode(),context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode());assertEquals("downloaded",driver.state().getJSONObject("updates").getString("phase"));assertFalse(RhActions.hasStream());
            RhShared.write(new File(folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"theme",dark?"dark":"light","metadataRequests",fixture.checks.get(),"downloads",fixture.downloads.get(),"verifiedApkSha256",manifest.getString("sha256"),"draftRetained",true,"savedOnlyAfterDecision",true,"installerOpened",false,"installationConfirmed",false,"streamStarted",false,"osScreenshots",false,"systemInputInjected",false));passed=true;
        } finally {
            RhUpdateWork owned=work;driver.instrument.runOnMainSync(()->{if(owned!=null)owned.abandonInstall();if(driver.activity!=null)driver.activity.finish();});driver.instrument.waitForIdleSync();fixture.close();
            if(work!=null){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(work.busy()){assertTrue("Update work did not finish",System.nanoTime()<end);Thread.sleep(20);}}
            if(updater!=null&&oldConnections!=null){Field connections=RhUpdates.class.getDeclaredField("connections");connections.setAccessible(true);connections.set(updater,oldConnections);}Field current=RhUpdateWork.class.getDeclaredField("shared");current.setAccessible(true);current.set(null,null);
            if(directory.exists())Files.move(directory.toPath(),captured.toPath());if(hadUpdates)Files.move(backup.toPath(),directory.toPath());
            RhShared.apply(context,original);try(FileOutputStream output=new FileOutputStream(shared)){output.write(beforeBytes);}RhShared.theme(context,originalDark);SharedPreferences.Editor restore=global.edit();if(hadCheck)restore.putLong("lastUpdateCheck",check);else restore.remove("lastUpdateCheck");restore.commit();assertArrayEquals(beforeBytes,Files.readAllBytes(shared.toPath()));
            if(passed&&captured.exists()){assertEquals(context.getFilesDir().getCanonicalFile(),captured.getCanonicalFile().getParentFile());assertEquals("updates-draft-captured-"+run,captured.getName());try(java.util.stream.Stream<java.nio.file.Path> entries=Files.walk(captured.toPath())){for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)entries.sorted(java.util.Comparator.reverseOrder())::iterator)Files.delete(path);}}
        }
    }
}
