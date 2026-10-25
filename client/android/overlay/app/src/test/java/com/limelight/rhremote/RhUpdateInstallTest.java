package com.limelight.rhremote;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

/** Real update workflow, file hashes, PackageManager metadata and Android intents.
 * HTTP bytes and archive parsing are fixtures; FileProvider uses the host's separator.
 * No production key or server is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhUpdateInstallTest.Requests.class,RhUpdateInstallTest.FileRoots.class,RhAdmissionTest.Controls.class})
public class RhUpdateInstallTest {
    /** AndroidX compares root + '/' against canonical paths. Windows JVM paths use '\\'.
     * Keep its real manifest parsing, root selection and URI generation; adapt only this check. */
    @Implements(className="androidx.core.content.FileProvider$SimplePathStrategy",isInAndroidSdk=false)
    public static class FileRoots {
        @Implementation protected boolean belongsToRoot(String file,String root){
            java.nio.file.Path path=new File(file).toPath(),base=new File(root).toPath();return !path.equals(base)&&path.startsWith(base);
        }
    }
    @Implements(value=RhUpdates.class,isInAndroidSdk=false)
    public static class Requests {
        static byte[] bytes;static String feed;static int downloads,checks;static boolean corrupt,failMetadata,failFirstDownloadOpen,failAllDownloads;
        static CountDownLatch entered,release;
        @Implementation protected static HttpURLConnection open(String address)throws IOException {
            boolean metadata=address.endsWith("releases.android.json");if(metadata)checks++;else downloads++;
            if(metadata&&failMetadata)throw new SocketTimeoutException("metadata fixture timeout");
            if(!metadata&&(failAllDownloads||failFirstDownloadOpen&&downloads==1))throw new UnknownHostException("download fixture DNS");
            return new HttpURLConnection(new URL(address)){
                @Override public int getResponseCode(){return 200;}
                @Override public InputStream getInputStream()throws IOException{
                    if(!metadata&&entered!=null){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test download gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
                    byte[] content=metadata?feed.getBytes(StandardCharsets.UTF_8):bytes.clone();if(!metadata&&corrupt)content[0]^=1;return new ByteArrayInputStream(content);
                }
                @Override public void connect(){}@Override public void disconnect(){}@Override public boolean usingProxy(){return false;}
            };
        }
    }
    public static class NoInstaller extends Activity {
        @Override public void startActivity(Intent intent){if(Intent.ACTION_VIEW.equals(intent.getAction()))throw new ActivityNotFoundException("test missing installer");super.startActivity(intent);}
    }
    Activity activity;RhUpdates updates;ShadowPackageManager manager;PackageInfo incoming;JSONObject manifest;File folder;
    RhHome home;
    private static final RhHome.Actions CALLBACKS=new RhHome.Actions(){
        public void connect(com.limelight.nvstream.http.ComputerDetails d){}public void add(String a){}public void advanced(){}
        public void pair(com.limelight.nvstream.http.ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}
    };
    private SigningInfo signing(String value){SigningInfo info=Shadow.newInstanceOf(SigningInfo.class);((ShadowSigningInfo)Shadow.extract(info)).setSignatures(new Signature[]{new Signature(value)});return info;}
    private void archive(){for(String name:new String[]{"pending.apk","RHRemote-update.apk"})manager.setPackageArchiveInfo(new File(folder,name).getAbsolutePath(),incoming);}
    private String sha(byte[] bytes)throws Exception{StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    @Before public void setup()throws Exception{
        Requests.downloads=0;Requests.checks=0;Requests.corrupt=false;Requests.failMetadata=false;Requests.failFirstDownloadOpen=false;Requests.failAllDownloads=false;Requests.entered=null;Requests.release=null;Requests.bytes="test APK fixture bytes".getBytes(StandardCharsets.UTF_8);
        // AndroidX retains its static cache while Robolectric allocates a new filesDir per test.
        java.lang.reflect.Field cache=androidx.core.content.FileProvider.class.getDeclaredField("sCache");cache.setAccessible(true);((java.util.Map<?,?>)cache.get(null)).clear();
        activity=Robolectric.buildActivity(Activity.class).setup().get();manager=Shadows.shadowOf(activity.getPackageManager());
        PackageInfo installed=manager.getInternalMutablePackageInfo(activity.getPackageName());installed.signingInfo=signing("aa");installed.signatures=new Signature[]{new Signature("aa")};manager.setCanRequestPackageInstalls(true);
        incoming=new PackageInfo();incoming.packageName=activity.getPackageName();incoming.versionCode=installed.versionCode+1;incoming.versionName="9.0.0";incoming.signingInfo=signing("aa");incoming.signatures=new Signature[]{new Signature("aa")};
        folder=new File(activity.getFilesDir(),"updates");archive();
        manifest=RhActions.obj("version","9.0.0","versionCode",incoming.getLongVersionCode(),"packageName",activity.getPackageName(),"tag","v9.0.0","fileName","RHRemote-Android-9.0.0.apk","size",Requests.bytes.length,"sha256",sha(Requests.bytes));
        feed();updates=new RhUpdates(activity);
    }
    private void feed(){Requests.feed=RhActions.obj("schemaVersion",1,"android",manifest).toString();}
    @After public void cleanup()throws Exception{
        if(Requests.release!=null)Requests.release.countDown();
        if(home!=null){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);do{idle();if(!home.state().getBoolean("updatesBusy"))break;Thread.sleep(10);}while(System.nanoTime()<end);home.dispose();assertFalse("Update worker leaked",home.state().getBoolean("updatesBusy"));}
        assertFalse(RhActions.hasStream());
    }
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void downloaded()throws Exception{updates.check();updates.download();assertTrue(updates.state().getBoolean("downloaded"));}
    private Intent next(){return Shadows.shadowOf(activity).getNextStartedActivity();}
    private void assertInstaller(Intent intent){assertNotNull(intent);assertEquals(Intent.ACTION_VIEW,intent.getAction());assertEquals("content",intent.getData().getScheme());assertEquals(activity.getPackageName()+".updates",intent.getData().getAuthority());assertEquals("application/vnd.android.package-archive",intent.getType());assertNotEquals(0,intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);assertNotNull(intent.getClipData());}
    @Test public void primaryInstallDownloadsFirstThenOpensTheSystemInstaller()throws Exception{
        updates.check();assertFalse(updates.state().getBoolean("downloaded"));assertTrue(updates.prepareInstall());updates.install(activity);
        assertEquals(1,Requests.downloads);assertInstaller(next());assertEquals("installer",updates.state().getString("phase"));
    }
    @Test public void directInstallActionCanCheckAndDownloadWithoutEarlierActions()throws Exception{
        assertTrue(updates.prepareInstall());updates.install(activity);assertEquals(1,Requests.checks);assertEquals(1,Requests.downloads);assertInstaller(next());
    }
    @Test public void checkingAgainPreservesTheVerifiedDownloadedPackage()throws Exception{
        downloaded();updates.check();assertTrue(updates.state().getBoolean("downloaded"));assertTrue(updates.prepareInstall());assertEquals(1,Requests.downloads);
    }
    @Test public void downloadConnectionFailureFallsBackBeforeReadingAnyBytes()throws Exception{
        Requests.failFirstDownloadOpen=true;downloaded();assertEquals(2,Requests.downloads);assertEquals("GitHub",updates.state().getString("provider"));assertEquals(1,updates.state().getJSONArray("sourceFailures").length());
    }
    @Test public void bothDownloadConnectionFailuresExposeTheirSources()throws Exception{
        Requests.failAllDownloads=true;updates.check();try{updates.download();fail("Unreachable APK sources accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("GitHub UnknownHostException"));}
        assertEquals(2,Requests.downloads);assertEquals(2,updates.state().getJSONArray("sourceFailures").length());assertFalse(updates.state().getBoolean("downloaded"));assertNull(next());
    }
    @Test public void newUpdaterRestoresThePackageAndDoesNotDownloadAgain()throws Exception{
        downloaded();updates=new RhUpdates(activity);assertTrue(updates.state().getBoolean("downloaded"));assertTrue(updates.prepareInstall());assertEquals(1,Requests.downloads);
    }
    @Test public void corruptedCachedPackageCannotReachAnInstaller()throws Exception{
        downloaded();byte[] bad=Requests.bytes.clone();bad[0]^=1;Files.write(new File(folder,"RHRemote-update.apk").toPath(),bad);
        try{updates.prepareInstall();fail("Corrupted APK accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("校验失败"));}
        assertFalse(updates.state().getBoolean("downloaded"));assertNull(next());
    }
    @Test public void changedPackageSignatureIsRejected()throws Exception{
        incoming.signingInfo=signing("bb");archive();updates.check();try{updates.download();fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("签名"));}
        assertFalse(updates.state().getBoolean("downloaded"));assertEquals(2,Requests.downloads);assertNull(next());
    }
    @Test public void wrongPackageIdentityIsRejected()throws Exception{
        incoming.packageName="other.application";archive();updates.check();try{updates.download();fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("包名或版本"));}assertNull(next());
    }
    @Test public void wrongPackageVersionIsRejected()throws Exception{
        incoming.versionCode--;archive();updates.check();try{updates.download();fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("包名或版本"));}assertNull(next());
    }
    @Test public void permissionIsRequestedAndGrantResumesTheSameDownloadedPackage()throws Exception{
        manager.setCanRequestPackageInstalls(false);assertTrue(updates.prepareInstall());updates.install(activity);Intent permission=next();assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,permission.getAction());assertEquals("package:"+activity.getPackageName(),permission.getDataString());
        assertTrue(updates.state().getBoolean("installPermissionPending"));manager.setCanRequestPackageInstalls(true);assertTrue(updates.resumeInstallRequest());assertTrue(updates.prepareInstall());updates.install(activity);assertInstaller(next());assertEquals(1,Requests.downloads);
    }
    @Test public void deniedPermissionIsVisibleAndDoesNotReopenAnInstaller()throws Exception{
        manager.setCanRequestPackageInstalls(false);assertTrue(updates.prepareInstall());updates.install(activity);assertNotNull(next());
        assertFalse(updates.resumeInstallRequest());assertFalse(updates.state().getBoolean("installPermissionPending"));assertTrue(updates.state().getString("status").contains("尚未允许"));assertNull(next());
    }
    @Test public void permissionIntentSurvivesUpdaterRecreation()throws Exception{
        manager.setCanRequestPackageInstalls(false);assertTrue(updates.prepareInstall());updates.install(activity);next();updates=new RhUpdates(activity);manager.setCanRequestPackageInstalls(true);
        assertTrue(updates.resumeInstallRequest());assertTrue(updates.prepareInstall());updates.install(activity);assertInstaller(next());assertEquals(1,Requests.downloads);
    }
    @Test public void returningWithoutInstallingKeepsThePackageAndAllowsRetry()throws Exception{
        assertTrue(updates.prepareInstall());updates.install(activity);next();assertFalse(updates.resumeInstallRequest());assertTrue(updates.state().getBoolean("downloaded"));assertTrue(updates.state().getString("status").contains("安装尚未完成"));assertNull(next());
    }
    private Button button(View view,String text){if(view instanceof Button&&((Button)view).getText().toString().equals(text))return(Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button b=button(((ViewGroup)view).getChildAt(i),text);if(b!=null)return b;}return null;}
    private void awaitUpdate()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);do{idle();if(!home.state().getBoolean("updatesBusy"))return;Thread.sleep(10);}while(System.nanoTime()<end);fail(home.state().getJSONObject("updates").toString());}
    private void page()throws Exception{home=new RhHome(activity,CALLBACKS);home.perform("app.settings.open",RhActions.obj());home.perform("updates.check",RhActions.obj());awaitUpdate();}
    @Test public void visiblePrimaryButtonDownloadsAndShowsBusyStateThroughTheSameActionApi()throws Exception{
        page();Button install=button(activity.getWindow().getDecorView(),"下载并安装");assertNotNull(install);assertTrue(install.isEnabled());
        Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);install.performClick();assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));
        assertTrue(home.state().getBoolean("updatesBusy"));assertFalse(button(activity.getWindow().getDecorView(),"下载并安装").isEnabled());
        assertFalse(RhActions.dispatch("updates.install",RhActions.obj()).getBoolean("ok"));home.rebind();assertTrue(home.state().getBoolean("updatesBusy"));
        Requests.release.countDown();awaitUpdate();assertInstaller(next());assertEquals(1,Requests.downloads);assertNotNull(button(activity.getWindow().getDecorView(),"安装更新"));
    }
    @Test public void updateFailureIsVisibleInThePageAndToast()throws Exception{
        page();Requests.corrupt=true;home.perform("updates.install",RhActions.obj());awaitUpdate();
        JSONObject state=home.state().getJSONObject("updates");assertEquals("error",state.getString("phase"));assertTrue(state.getString("status").contains("校验失败"));assertTrue(ShadowToast.getTextOfLatestToast().contains("校验失败"));assertNull(next());
    }
    @Test public void visibleDownloadButtonChecksFirstAndDoesNotOpenAnInstaller()throws Exception{
        home=new RhHome(activity,CALLBACKS);home.perform("app.settings.open",RhActions.obj());
        Button download=button(activity.getWindow().getDecorView(),"下载 APK");assertNotNull(download);assertTrue(download.isEnabled());
        assertTrue(button(activity.getWindow().getDecorView(),"下载并安装").isEnabled());download.performClick();awaitUpdate();
        assertTrue(home.state().getJSONObject("updates").getBoolean("downloaded"));assertEquals(1,Requests.checks);assertEquals(1,Requests.downloads);assertNull(next());
    }
    @Test public void visibleCheckButtonCompletesThroughTheDispatcher()throws Exception{
        home=new RhHome(activity,CALLBACKS);home.perform("app.settings.open",RhActions.obj());
        Button check=button(activity.getWindow().getDecorView(),"检查更新");assertTrue(check.isEnabled());check.performClick();awaitUpdate();
        assertEquals(1,Requests.checks);assertEquals("available",home.state().getJSONObject("updates").getString("phase"));assertEquals(0,Requests.downloads);
    }
    @Test public void progressTicksKeepThePressedViewsInsteadOfRebuildingThePage()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);
        Button download=button(activity.getWindow().getDecorView(),"下载 APK");download.performClick();assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(650));
        assertSame(download,button(activity.getWindow().getDecorView(),"下载 APK"));assertFalse(download.isEnabled());assertEquals(0.45f,download.getAlpha(),0.001f);
        Requests.release.countDown();awaitUpdate();assertNull(next());
    }
    @Test public void failedSourcesExposeBothReasonsAndLeaveRetryButtonsEnabled()throws Exception{
        Requests.failMetadata=true;home=new RhHome(activity,CALLBACKS);home.perform("app.settings.open",RhActions.obj());
        button(activity.getWindow().getDecorView(),"检查更新").performClick();awaitUpdate();JSONObject state=home.state().getJSONObject("updates");
        assertEquals("error",state.getString("phase"));assertEquals(2,state.getJSONArray("sourceFailures").length());
        assertTrue(state.getString("status").contains("Gitee SocketTimeoutException"));assertTrue(state.getString("status").contains("GitHub SocketTimeoutException"));
        assertTrue(button(activity.getWindow().getDecorView(),"下载 APK").isEnabled());assertTrue(button(activity.getWindow().getDecorView(),"下载并安装").isEnabled());
    }
    @Test public void missingInstallerHasAVisibleFailureInsteadOfSilentNoOp()throws Exception{
        activity=Robolectric.buildActivity(NoInstaller.class).setup().get();page();home.perform("updates.install",RhActions.obj());awaitUpdate();assertTrue(home.state().getJSONObject("updates").getString("status").contains("系统安装界面未能打开"));assertNotNull(ShadowToast.getTextOfLatestToast());
    }
    @Test public void automaticDownloadDoesNotOpenPermissionsOrTheInstaller()throws Exception{
        manager.setCanRequestPackageInstalls(false);home=new RhHome(activity,CALLBACKS);
        java.lang.reflect.Method method=RhHome.class.getDeclaredMethod("startUpdate",String.class,boolean.class);method.setAccessible(true);method.invoke(home,"updates.check",true);awaitUpdate();assertTrue(home.state().getJSONObject("updates").getBoolean("downloaded"));assertNull(next());
    }
}
