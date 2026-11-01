package com.limelight.rhremote;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
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
        static CountDownLatch finishCleanup;
        static boolean gateMetadata,pauseAfterChunk;
        @Implementation protected static HttpURLConnection open(String address)throws IOException {
            boolean metadata=address.endsWith("releases.android.json");if(metadata)checks++;else downloads++;
            if(metadata&&failMetadata)throw new SocketTimeoutException("metadata fixture timeout");
            if(!metadata&&(failAllDownloads||failFirstDownloadOpen&&downloads==1))throw new UnknownHostException("download fixture DNS");
            return new HttpURLConnection(new URL(address)){
                @Override public int getResponseCode(){return 200;}
                @Override public InputStream getInputStream()throws IOException{
                    if((!metadata&&!pauseAfterChunk||metadata&&gateMetadata)&&entered!=null){gate();}
                    byte[] content=metadata?feed.getBytes(StandardCharsets.UTF_8):bytes.clone();if(!metadata&&corrupt)content[0]^=1;
                    if(!metadata&&pauseAfterChunk)return new InputStream(){final ByteArrayInputStream data=new ByteArrayInputStream(content);boolean first=true,paused;
                        @Override public int read(byte[] value,int offset,int count)throws IOException{if(!first&&!paused){paused=true;gate();}first=false;return data.read(value,offset,Math.min(count,8));}
                        @Override public int read()throws IOException{byte[] value=new byte[1];return read(value,0,1)==-1?-1:value[0]&255;}};
                    return new ByteArrayInputStream(content);
                }
                @Override public void connect(){}@Override public void disconnect(){if((!metadata||gateMetadata)&&release!=null)release.countDown();}@Override public boolean usingProxy(){return false;}
            };
        }
        static void gate()throws IOException{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test request gate timed out");if(finishCleanup!=null&&!finishCleanup.await(5,TimeUnit.SECONDS))throw new IOException("test cleanup gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
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
    private File snapshot()throws Exception{return new File(folder,"RHRemote-installer-"+incoming.getLongVersionCode()+"-"+sha(Requests.bytes)+".apk");}
    private void archive()throws Exception{for(String name:new String[]{"pending.apk","RHRemote-update.apk",snapshot().getName()})manager.setPackageArchiveInfo(new File(folder,name).getAbsolutePath(),incoming);}
    private String sha(byte[] bytes)throws Exception{StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    @Before public void setup()throws Exception{
        Requests.downloads=0;Requests.checks=0;Requests.corrupt=false;Requests.failMetadata=false;Requests.failFirstDownloadOpen=false;Requests.failAllDownloads=false;Requests.entered=null;Requests.release=null;Requests.finishCleanup=null;Requests.gateMetadata=false;Requests.pauseAfterChunk=false;Requests.bytes="test APK fixture bytes".getBytes(StandardCharsets.UTF_8);
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
        if(Requests.finishCleanup!=null)Requests.finishCleanup.countDown();
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
    @Test public void recheckFailureWithACachedCandidateNeedsAnotherExplicitInstallClick()throws Exception{
        home=newHome();updates=org.robolectric.util.ReflectionHelpers.getField(home,"updates");home.perform("app.settings.tab",RhActions.obj("tab","updates"));
        Button primary=button(activity.getWindow().getDecorView(),"检查更新");primary.performClick();awaitUpdate();assertEquals(0,Requests.downloads);
        Requests.failMetadata=true;home.perform("updates.check",RhActions.obj());awaitUpdate();assertSame(primary,button(activity.getWindow().getDecorView(),"重新检查"));assertEquals("updates.check",updates.state().getString("failedAction"));
        Requests.failMetadata=false;primary.performClick();awaitUpdate();assertSame(primary,button(activity.getWindow().getDecorView(),"下载并安装"));assertEquals(0,Requests.downloads);assertNull(next());
        primary.performClick();awaitUpdate();assertEquals(1,Requests.downloads);assertInstaller(next());
    }
    @Test public void directInstallActionCanCheckAndDownloadWithoutEarlierActions()throws Exception{
        assertTrue(updates.prepareInstall());updates.install(activity);assertEquals(1,Requests.checks);assertEquals(1,Requests.downloads);assertInstaller(next());
    }
    @Test public void checkingAgainPreservesTheVerifiedDownloadedPackage()throws Exception{
        downloaded();updates.check();assertTrue(updates.state().getBoolean("downloaded"));assertTrue(updates.prepareInstall());assertEquals(1,Requests.downloads);
    }
    @Test public void failedRecheckKeepsTheDownloadedPackageWithoutClaimingInstallationFailed()throws Exception{
        downloaded();Requests.failMetadata=true;try{updates.check();fail("Metadata request must fail");}catch(IOException expected){}
        assertTrue(updates.state().getBoolean("downloaded"));assertEquals("updates.check",updates.primaryAction());assertEquals("重新检查",updates.primaryLabel(false));
        Requests.failMetadata=false;updates.check();assertEquals("安装更新",updates.primaryLabel(false));assertTrue(updates.prepareInstall());assertEquals(1,Requests.downloads);
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
    @Test public void successiveVersionsHaveDistinctInstallerUrisAndKeepTheOldContents()throws Exception{
        assertTrue(updates.prepareInstall());updates.install(activity);Intent first=next();File oldSnapshot=snapshot();byte[] old=Requests.bytes.clone();
        incoming.versionCode++;Requests.bytes="another signed APK fixture".getBytes(StandardCharsets.UTF_8);archive();
        manifest.put("versionCode",incoming.getLongVersionCode()).put("size",Requests.bytes.length).put("sha256",sha(Requests.bytes));feed();
        updates.check();assertTrue(updates.prepareInstall());updates.install(activity);Intent second=next();
        assertInstaller(first);assertInstaller(second);assertNotEquals(first.getData(),second.getData());
        assertTrue(second.getData().getLastPathSegment().contains(sha(Requests.bytes)));
        assertArrayEquals(old,Files.readAllBytes(oldSnapshot.toPath()));assertArrayEquals(Requests.bytes,Files.readAllBytes(snapshot().toPath()));
    }
    @Test public void aCorruptInstallerSnapshotIsRebuiltBeforeItCanBeShared()throws Exception{
        assertTrue(updates.prepareInstall());Files.write(snapshot().toPath(),new byte[]{0,1,2});
        updates=new RhUpdates(activity);assertTrue(updates.prepareInstall());updates.install(activity);assertInstaller(next());
        assertArrayEquals(Requests.bytes,Files.readAllBytes(snapshot().toPath()));assertEquals(1,Requests.downloads);
    }
    @Test public void completedInstallCleansOwnedSnapshotsWithoutDeletingOtherFiles()throws Exception{
        assertTrue(updates.prepareInstall());File complete=snapshot();File other=new File(folder,"user-owned.apk");Files.write(other.toPath(),new byte[]{42});
        File future=new File(folder,"RHRemote-installer-2100000000-"+sha(Requests.bytes)+".apk");Files.write(future.toPath(),Requests.bytes);
        manager.getInternalMutablePackageInfo(activity.getPackageName()).versionCode=incoming.versionCode;
        updates=new RhUpdates(activity);assertFalse(complete.exists());assertTrue(other.isFile());assertTrue(future.isFile());
    }
    private Button button(View view,String text){if(view instanceof Button&&((Button)view).getText().toString().equals(text))return(Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button b=button(((ViewGroup)view).getChildAt(i),text);if(b!=null)return b;}return null;}
    private void awaitUpdate()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);do{idle();if(!home.state().getBoolean("updatesBusy"))return;Thread.sleep(10);}while(System.nanoTime()<end);fail(home.state().getJSONObject("updates").toString());}
    private RhHome newHome(){RhHome value=new RhHome(activity,CALLBACKS);value.resumed();return value;}
    private void page()throws Exception{home=newHome();updates=org.robolectric.util.ReflectionHelpers.getField(home,"updates");home.perform("app.settings.open",RhActions.obj());home.perform("updates.check",RhActions.obj());awaitUpdate();}
    private void recreateHome()throws Exception{
        android.os.Bundle navigation=new android.os.Bundle();home.saveNavigation(navigation);Object retained=home.retainUiState();home.dispose();
        activity=Robolectric.buildActivity(Activity.class).setup().get();home=newHome();updates=org.robolectric.util.ReflectionHelpers.getField(home,"updates");home.restoreNavigation(navigation);home.restoreUiState(retained);idle();
    }
    @Test public void availableCandidateSurvivesHomeRecreationWithoutAnotherRequest()throws Exception{
        page();assertEquals("9.0.0",home.state().getJSONObject("updates").getString("availableVersion"));recreateHome();
        assertEquals("available",home.state().getJSONObject("updates").getString("phase"));assertEquals("9.0.0",home.state().getJSONObject("updates").getString("availableVersion"));assertNotNull(button(activity.getWindow().getDecorView(),"下载并安装"));assertEquals(1,Requests.checks);assertEquals(0,Requests.downloads);
    }
    @Test public void aRecreatedHomeKeepsOneDownloadAndItsInstallationHandoff()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));
        long operation=home.state().getJSONObject("updates").getLong("operationId");boolean dark=RhShared.dark(activity);home.perform("appearance.toggle",RhActions.obj());assertEquals(!dark,RhShared.dark(activity));recreateHome();
        assertTrue(home.state().getBoolean("updatesBusy"));assertEquals(operation,home.state().getJSONObject("updates").getLong("operationId"));assertEquals("downloading",home.state().getJSONObject("updates").getString("phase"));assertFalse(RhActions.dispatch("updates.primary",RhActions.obj()).getBoolean("ok"));assertFalse(button(activity.getWindow().getDecorView(),"下载中 0%").isEnabled());
        Requests.release.countDown();awaitUpdate();assertInstaller(next());assertNull(next());assertEquals(1,Requests.downloads);assertEquals(1,Requests.checks);
    }
    @Test public void editsOpenedDuringDownloadAreCheckedBeforeTheInstallerHandoff()throws Exception{
        page();com.limelight.nvstream.http.ComputerDetails device=new com.limelight.nvstream.http.ComputerDetails();device.uuid="update-draft-host";device.name="workstation";device.manualAddress=new com.limelight.nvstream.http.ComputerDetails.AddressTuple("192.0.2.1",47989);home.update(device);home.perform("devices.open",RhActions.obj());home.perform("app.settings.tab",RhActions.obj("tab","updates"));
        String before=RhShared.canonical(RhShared.read(activity));Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));
        home.perform("device.settings.open",RhActions.obj("uuid",device.uuid));home.perform("settings.input",RhActions.obj("key","fps","value","24"));Requests.release.countDown();awaitUpdate();
        assertNull("Download completion must not open Android's installer over an unsaved form",next());assertEquals("unsaved-device",home.state().getJSONObject("navigationPrompt").getString("kind"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
        home.perform("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("24",home.state().getJSONObject("settingsInputs").getString("fps"));assertNull(next());assertFalse(home.state().getBoolean("updatesBusy"));
        home.perform("settings.save",RhActions.obj());home.perform("app.settings.tab",RhActions.obj("tab","updates"));Button install=button(activity.getWindow().getDecorView(),"安装更新");assertNotNull(install);install.performClick();awaitUpdate();assertInstaller(next());assertEquals(1,Requests.downloads);assertEquals(24,RhProfile.load(activity,device.uuid).fps);
    }
    private String beginDownloadWithDraft(String kind,boolean invalid)throws Exception{
        page();com.limelight.nvstream.http.ComputerDetails device=new com.limelight.nvstream.http.ComputerDetails();device.uuid="update-draft-host";device.name="workstation";device.manualAddress=new com.limelight.nvstream.http.ComputerDetails.AddressTuple("192.0.2.1",47989);home.update(device);home.perform("devices.open",RhActions.obj());home.perform("app.settings.tab",RhActions.obj("tab","updates"));String before=RhShared.canonical(RhShared.read(activity));
        Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));
        if(kind.equals("device")){home.perform("device.settings.open",RhActions.obj("uuid",device.uuid));home.perform("settings.input",RhActions.obj("key","fps","value",invalid?"":"24"));}
        else if(kind.equals("info")){home.perform("device.info.open",RhActions.obj("uuid",device.uuid));home.perform("device.info.input",RhActions.obj("displayName","Changed name"));}
        else{home.perform("app.settings.tab",RhActions.obj("tab","sync"));home.perform("sync.edit",RhActions.obj("key","username","value","changed"));}
        return before;
    }
    @Test public void aChangedDeviceNameCanStayOrBeDiscardedBeforeInstallation()throws Exception{
        String before=beginDownloadWithDraft("info",false);Requests.release.countDown();awaitUpdate();assertNull(next());assertEquals("unsaved-info",home.state().getJSONObject("navigationPrompt").getString("kind"));home.perform("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("Changed name",home.state().getJSONObject("infoDraft").getString("displayName"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
        home.perform("updates.install",RhActions.obj());home.perform("navigation.resolve",RhActions.obj("choice","discard"));awaitUpdate();assertInstaller(next());assertEquals(before,RhShared.canonical(RhShared.read(activity)));assertEquals(1,Requests.downloads);
    }
    @Test public void changedSyncSettingsAreProtectedBeforeInstallation()throws Exception{
        String before=beginDownloadWithDraft("sync",false);Requests.release.countDown();awaitUpdate();assertNull(next());assertEquals("unsaved-sync",home.state().getJSONObject("navigationPrompt").getString("kind"));home.perform("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("changed",home.state().getJSONObject("syncDraft").getString("username"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
        home.perform("updates.install",RhActions.obj());home.perform("navigation.resolve",RhActions.obj("choice","discard"));awaitUpdate();assertInstaller(next());assertEquals(1,Requests.downloads);
    }
    @Test public void invalidEditsCanBeCorrectedWithoutDownloadingTheApkAgain()throws Exception{
        String before=beginDownloadWithDraft("device",true);Requests.release.countDown();awaitUpdate();try{home.perform("navigation.resolve",RhActions.obj("choice","save"));fail("Invalid frame rate saved");}catch(RhSettingsDraft.Invalid expected){}
        assertNull(next());assertEquals("",home.state().getJSONObject("settingsInputs").getString("fps"));assertEquals("fps",home.state().getString("settingsErrorField"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
        home.perform("settings.input",RhActions.obj("key","fps","value","24"));home.perform("updates.install",RhActions.obj());home.perform("navigation.resolve",RhActions.obj("choice","save"));awaitUpdate();assertInstaller(next());assertEquals(1,Requests.downloads);
    }
    @Test public void backgroundCompletionDoesNotPromptOverAnUnsavedForm()throws Exception{
        String before=beginDownloadWithDraft("device",false);home.paused();Requests.release.countDown();awaitUpdate();assertNull(next());assertTrue(home.state().isNull("navigationPrompt"));home.resumed();idle();assertNull(next());assertEquals("24",home.state().getJSONObject("settingsInputs").getString("fps"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
    }
    @Test public void completingDownloadDoesNotReplaceAnExistingDecision()throws Exception{
        beginDownloadWithDraft("device",false);home.perform("app.settings.tab",RhActions.obj("tab","about"));JSONObject before=org.robolectric.util.ReflectionHelpers.getField(home,"pendingNavigation");String decision=before.toString();Requests.release.countDown();awaitUpdate();assertNull(next());JSONObject after=org.robolectric.util.ReflectionHelpers.getField(home,"pendingNavigation");assertEquals(decision,after.toString());
        home.perform("navigation.resolve",RhActions.obj("choice","stay"));assertNull(next());assertEquals("24",home.state().getJSONObject("settingsInputs").getString("fps"));
    }
    @Test public void addingADeviceDefersInstallationUntilAnotherExplicitClick()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));home.perform("device.add.open",RhActions.obj());home.perform("device.add.input",RhActions.obj("address","192.0.2.99"));Requests.release.countDown();awaitUpdate();assertNull(next());assertEquals("192.0.2.99",home.state().getJSONObject("add").getString("address"));assertFalse(home.state().getString("updateInstallHint").isEmpty());
        home.perform("device.add.cancel",RhActions.obj());home.perform("app.settings.tab",RhActions.obj("tab","updates"));button(activity.getWindow().getDecorView(),"安装更新").performClick();awaitUpdate();assertInstaller(next());assertEquals(1,Requests.downloads);
    }
    @Test public void backgroundCompletionWaitsForOneRealForegroundEntry()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));home.paused();Requests.release.countDown();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!updates.state().optString("phase").equals("ready")){idle();assertTrue("Download did not prepare",System.nanoTime()<end);Thread.sleep(5);}idle();
        assertTrue(home.state().getBoolean("updatesBusy"));assertTrue(home.state().getJSONObject("updates").getBoolean("awaitingForeground"));assertNull(next());
        home.resumed();awaitUpdate();assertInstaller(next());home.resumed();idle();assertEquals("installer",updates.state().getString("phase"));assertNull(next());
        home.paused();home.resumed();idle();assertEquals("downloaded",updates.state().getString("phase"));assertNull(next());assertEquals(1,Requests.downloads);
    }
    @Test public void explicitCloseKeepsTheDownloadButDropsItsInstallHandoff()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));activity.finish();home.dispose();Requests.release.countDown();awaitUpdate();assertNull(next());assertEquals("downloaded",updates.state().getString("phase"));
        activity=Robolectric.buildActivity(Activity.class).setup().get();home=newHome();updates=org.robolectric.util.ReflectionHelpers.getField(home,"updates");home.perform("app.settings.open",RhActions.obj());idle();assertNull(next());assertNotNull(button(activity.getWindow().getDecorView(),"安装更新"));assertEquals(1,Requests.downloads);
    }
    @Test public void finishingHomeCannotLaunchTheInstallerBeforeItsDestroyCallback()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));activity.finish();Requests.release.countDown();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!home.state().getJSONObject("updates").optBoolean("awaitingForeground")){idle();assertTrue("Finishing page consumed the installer handoff",System.nanoTime()<end);Thread.sleep(5);}assertNull(next());home.dispose();awaitUpdate();assertEquals("downloaded",updates.state().getString("phase"));assertNull(next());
    }
    @Test public void expandedFailureAndItsRetryActionSurviveThemeRecreation()throws Exception{
        page();Requests.failMetadata=true;home.perform("updates.check",RhActions.obj());awaitUpdate();home.perform("updates.details.toggle",RhActions.obj());assertTrue(home.state().getBoolean("updateDetails"));home.perform("appearance.toggle",RhActions.obj());recreateHome();
        assertEquals("error",updates.state().getString("phase"));assertEquals("updates.check",updates.state().getString("failedAction"));assertEquals(2,updates.state().getJSONArray("sourceFailures").length());assertTrue(home.state().getBoolean("updateDetails"));assertEquals(View.VISIBLE,find(activity.getWindow().getDecorView(),"更新错误详情").getVisibility());assertNotNull(button(activity.getWindow().getDecorView(),"重新检查"));assertEquals(3,Requests.checks);assertEquals(0,Requests.downloads);assertNull(next());
    }
    @Test public void installationPermissionReturnUsesTheReplacementHomeWithoutAnotherDownload()throws Exception{
        page();manager.setCanRequestPackageInstalls(false);home.perform("updates.install",RhActions.obj());awaitUpdate();assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,next().getAction());home.paused();manager.setCanRequestPackageInstalls(true);recreateHome();awaitUpdate();assertInstaller(next());assertNull(next());home.resumed();idle();assertEquals("installer",updates.state().getString("phase"));assertEquals(1,Requests.downloads);assertNull(next());
    }
    @Test public void retiringTheOldPageCannotDetachItsReplacement()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));RhHome old=home;
        home=newHome();home.perform("app.settings.open",RhActions.obj());idle();old.dispose();Requests.release.countDown();awaitUpdate();assertInstaller(next());assertEquals(1,Requests.downloads);assertEquals("installer",home.state().getJSONObject("updates").getString("phase"));
    }
    @Test public void visiblePrimaryButtonDownloadsAndShowsBusyStateThroughTheSameActionApi()throws Exception{
        page();Button install=button(activity.getWindow().getDecorView(),"下载并安装");assertNotNull(install);assertTrue(install.isEnabled());
        Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);install.performClick();assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));
        assertTrue(home.state().getBoolean("updatesBusy"));assertFalse(install.isEnabled());
        assertFalse(RhActions.dispatch("updates.install",RhActions.obj()).getBoolean("ok"));home.rebind();assertTrue(home.state().getBoolean("updatesBusy"));
        Requests.release.countDown();awaitUpdate();assertInstaller(next());assertEquals(1,Requests.downloads);assertNotNull(button(activity.getWindow().getDecorView(),"安装更新"));
    }
    @Test public void updateFailureHasOneInlineStateAndExpandableDetails()throws Exception{
        page();Requests.corrupt=true;home.perform("updates.install",RhActions.obj());awaitUpdate();
        JSONObject state=home.state().getJSONObject("updates");assertEquals("error",state.getString("phase"));assertTrue(state.getString("status").contains("校验失败"));assertNull(ShadowToast.getTextOfLatestToast());assertNull(next());
        View details=find(activity.getWindow().getDecorView(),"更新错误详情");assertNotNull(details);assertEquals(View.GONE,details.getVisibility());
        Button primary=button(activity.getWindow().getDecorView(),"重试下载并安装");assertNotNull(primary);button(activity.getWindow().getDecorView(),"查看详情").performClick();assertEquals(View.VISIBLE,details.getVisibility());assertSame(primary,button(activity.getWindow().getDecorView(),"重试下载并安装"));
        button(activity.getWindow().getDecorView(),"收起详情").performClick();assertEquals(View.GONE,details.getVisibility());assertEquals("",home.state().getString("status"));
    }
    private View find(View view,String description){if(description.equals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null;}
    @Test public void aLateUpdateFailureDoesNotNotifyOrChangeAnotherPage()throws Exception{
        page();Requests.corrupt=true;Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);
        button(activity.getWindow().getDecorView(),"下载并安装").performClick();assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));
        home.perform("app.settings.tab",RhActions.obj("tab","about"));Requests.release.countDown();awaitUpdate();
        assertEquals("about",home.state().getString("settingsTab"));assertEquals("",home.state().getString("status"));assertNull(ShadowToast.getTextOfLatestToast());assertEquals("error",home.state().getJSONObject("updates").getString("phase"));
        home.perform("app.settings.tab",RhActions.obj("tab","updates"));assertNotNull(button(activity.getWindow().getDecorView(),"重试下载并安装"));assertFalse(home.state().getBoolean("updateDetails"));
    }
    @Test public void onePrimaryButtonChecksBeforeOfferingDownloadAndInstall()throws Exception{
        home=newHome();home.perform("app.settings.open",RhActions.obj());
        Button primary=button(activity.getWindow().getDecorView(),"检查更新");assertNotNull(primary);assertNull(button(activity.getWindow().getDecorView(),"下载 APK"));assertNull(button(activity.getWindow().getDecorView(),"下载并安装"));
        primary.performClick();awaitUpdate();assertSame(primary,button(activity.getWindow().getDecorView(),"下载并安装"));assertEquals(0,Requests.downloads);assertNull(next());
        primary.performClick();awaitUpdate();assertSame(primary,button(activity.getWindow().getDecorView(),"安装更新"));assertEquals(1,Requests.checks);assertEquals(1,Requests.downloads);assertInstaller(next());
    }
    @Test public void visibleCheckButtonCompletesThroughTheDispatcher()throws Exception{
        home=newHome();home.perform("app.settings.open",RhActions.obj());
        Button check=button(activity.getWindow().getDecorView(),"检查更新");assertTrue(check.isEnabled());check.performClick();awaitUpdate();
        assertEquals(1,Requests.checks);assertEquals("available",home.state().getJSONObject("updates").getString("phase"));assertEquals(0,Requests.downloads);
    }
    @Test public void progressTicksKeepThePressedViewsInsteadOfRebuildingThePage()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);
        Button download=button(activity.getWindow().getDecorView(),"下载并安装");download.performClick();assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(650));
        assertSame(download,activity.getWindow().getDecorView().findViewWithTag("updates.primary"));assertFalse(download.isEnabled());assertEquals(0.65f,download.getAlpha(),0.001f);assertTrue(home.perform("updates.state",RhActions.obj()).has("phase"));
        Requests.release.countDown();awaitUpdate();assertInstaller(next());
    }
    @Test public void failedSourcesExposeBothReasonsAndLeaveRetryButtonsEnabled()throws Exception{
        Requests.failMetadata=true;home=newHome();home.perform("app.settings.open",RhActions.obj());
        button(activity.getWindow().getDecorView(),"检查更新").performClick();awaitUpdate();JSONObject state=home.state().getJSONObject("updates");
        assertEquals("error",state.getString("phase"));assertEquals(2,state.getJSONArray("sourceFailures").length());
        assertTrue(state.getString("status").contains("Gitee SocketTimeoutException"));assertTrue(state.getString("status").contains("GitHub SocketTimeoutException"));
        assertNull(button(activity.getWindow().getDecorView(),"下载 APK"));assertTrue(button(activity.getWindow().getDecorView(),"重新检查").isEnabled());
    }
    @Test public void failedDownloadRetriesFromTheSamePrimaryWithoutAnExtraCheck()throws Exception{
        page();Requests.corrupt=true;button(activity.getWindow().getDecorView(),"下载并安装").performClick();awaitUpdate();
        Button retry=button(activity.getWindow().getDecorView(),"重试下载并安装");assertNotNull(retry);assertTrue(retry.isEnabled());assertNull(next());
        Requests.corrupt=false;retry.performClick();awaitUpdate();assertInstaller(next());assertEquals(1,Requests.checks);
    }
    @Test public void settingsNavigationRemainsAvailableDuringDownload()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);
        button(activity.getWindow().getDecorView(),"下载并安装").performClick();assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));
        home.perform("app.settings.tab",RhActions.obj("tab","about"));assertEquals("about",home.state().getString("settingsTab"));
        home.perform("app.settings.tab",RhActions.obj("tab","updates"));assertFalse(((Button)activity.getWindow().getDecorView().findViewWithTag("updates.primary")).isEnabled());
        Requests.release.countDown();awaitUpdate();assertInstaller(next());
    }
    @Test public void missingInstallerHasAVisibleFailureInsteadOfSilentNoOp()throws Exception{
        activity=Robolectric.buildActivity(NoInstaller.class).setup().get();page();home.perform("updates.install",RhActions.obj());awaitUpdate();assertTrue(home.state().getJSONObject("updates").getString("status").contains("系统安装界面未能打开"));assertNull(ShadowToast.getTextOfLatestToast());
        assertTrue(button(activity.getWindow().getDecorView(),"重试安装").isEnabled());button(activity.getWindow().getDecorView(),"查看详情").performClick();assertTrue(((TextView)find(activity.getWindow().getDecorView(),"更新错误详情")).getText().toString().contains("系统安装界面未能打开"));
    }
    @Test public void automaticDownloadDoesNotOpenPermissionsOrTheInstaller()throws Exception{
        manager.setCanRequestPackageInstalls(false);home=newHome();
        java.lang.reflect.Method method=RhHome.class.getDeclaredMethod("startUpdate",String.class,boolean.class);method.setAccessible(true);method.invoke(home,"updates.check",true);awaitUpdate();assertTrue(home.state().getJSONObject("updates").getBoolean("downloaded"));assertNull(next());
    }
    @Test public void cancellingDownloadKeepsAdmissionUntilCleanupThenAllowsOneRetry()throws Exception{
        page();String before=RhShared.canonical(RhShared.read(activity));Requests.pauseAfterChunk=true;Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);Requests.finishCleanup=new CountDownLatch(1);
        button(activity.getWindow().getDecorView(),"下载并安装").performClick();assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));idle();
        assertEquals(8,new File(folder,"pending.apk").length());long operation=home.state().getJSONObject("updates").getLong("operationId");Button cancel=button(activity.getWindow().getDecorView(),"取消");assertNotNull(cancel);assertTrue(cancel.isEnabled());cancel.performClick();idle();
        assertTrue(home.state().getBoolean("updatesBusy"));assertEquals("cancelling",home.state().getJSONObject("updates").getString("phase"));assertFalse(button(activity.getWindow().getDecorView(),"正在取消…").isEnabled());
        try{home.perform("updates.primary",RhActions.obj());fail("Cleanup cannot admit another worker");}catch(IllegalStateException expected){}
        Requests.finishCleanup.countDown();awaitUpdate();assertEquals("cancelled",home.state().getJSONObject("updates").getString("phase"));assertEquals(1,Requests.downloads);assertFalse(new File(folder,"pending.apk").exists());assertNull(next());assertEquals(before,RhShared.canonical(RhShared.read(activity)));
        Requests.pauseAfterChunk=false;Requests.entered=null;Requests.release=null;Requests.finishCleanup=null;button(activity.getWindow().getDecorView(),"下载并安装").performClick();awaitUpdate();assertEquals(2,Requests.downloads);assertInstaller(next());assertNull(next());
        try{home.perform("updates.cancel",RhActions.obj("operationId",operation));fail("Stale cancellation accepted");}catch(IllegalStateException expected){}
    }
    @Test public void cancellingMetadataSurvivesRecreationAndDoesNotTryAnotherMirror()throws Exception{
        home=newHome();home.perform("app.settings.open",RhActions.obj());Requests.gateMetadata=true;Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);Requests.finishCleanup=new CountDownLatch(1);
        button(activity.getWindow().getDecorView(),"检查更新").performClick();assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));idle();button(activity.getWindow().getDecorView(),"取消").performClick();recreateHome();
        assertEquals("cancelling",home.state().getJSONObject("updates").getString("phase"));assertFalse(((Button)activity.getWindow().getDecorView().findViewWithTag("updates.cancel")).isEnabled());
        Requests.finishCleanup.countDown();awaitUpdate();assertEquals(1,Requests.checks);assertEquals(0,Requests.downloads);assertEquals("cancelled",home.state().getJSONObject("updates").getString("phase"));assertTrue(button(activity.getWindow().getDecorView(),"检查更新").isEnabled());assertNull(next());
        Requests.gateMetadata=false;Requests.entered=null;Requests.release=null;Requests.finishCleanup=null;button(activity.getWindow().getDecorView(),"检查更新").performClick();awaitUpdate();assertEquals(2,Requests.checks);assertNotNull(button(activity.getWindow().getDecorView(),"下载并安装"));
    }
    @Test public void cancellationOfForegroundHandoffKeepsTheVerifiedApk()throws Exception{
        page();home.paused();home.perform("updates.install",RhActions.obj());long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do{idle();if(home.state().getJSONObject("updates").getBoolean("awaitingForeground"))break;Thread.sleep(10);}while(System.nanoTime()<end);
        assertTrue(home.state().getJSONObject("updates").getBoolean("awaitingForeground"));long operation=home.state().getJSONObject("updates").getLong("operationId");home.perform("updates.cancel",RhActions.obj("operationId",operation));home.resumed();idle();
        assertFalse(home.state().getBoolean("updatesBusy"));assertTrue(home.state().getJSONObject("updates").getBoolean("downloaded"));assertNull(next());assertEquals(1,Requests.downloads);
        button(activity.getWindow().getDecorView(),"安装更新").performClick();awaitUpdate();assertInstaller(next());assertEquals(1,Requests.downloads);
    }
    private void updateLayout(){View root=activity.findViewById(android.R.id.content);root.measure(View.MeasureSpec.makeMeasureSpec(1008,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(672,View.MeasureSpec.EXACTLY));root.layout(0,0,1008,672);}
    private void updateTouch(Button target,int action,long down){android.view.MotionEvent event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,target.getWidth()/2f,target.getHeight()/2f,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{assertTrue(target.dispatchTouchEvent(event));}finally{event.recycle();}}
    private void updateCandidate(JSONObject candidate)throws Exception{
        org.robolectric.util.ReflectionHelpers.setField(updates,"available",new JSONObject(candidate.toString()));org.robolectric.util.ReflectionHelpers.setField(updates,"phase","available");org.robolectric.util.ReflectionHelpers.setField(updates,"ready",false);
        java.lang.reflect.Method refresh=RhHome.class.getDeclaredMethod("refreshUpdateControls");refresh.setAccessible(true);refresh.invoke(home);updateLayout();
    }
    @Test public void queuedCheckCannotTurnIntoDownloadWhenACandidateArrives()throws Exception{
        home=newHome();updates=org.robolectric.util.ReflectionHelpers.getField(home,"updates");home.perform("app.settings.open",RhActions.obj());updateLayout();Button primary=(Button)activity.getWindow().getDecorView().findViewWithTag("updates.primary");
        long down=android.os.SystemClock.uptimeMillis();updateTouch(primary,android.view.MotionEvent.ACTION_DOWN,down);updateTouch(primary,android.view.MotionEvent.ACTION_UP,down);updateCandidate(manifest);idle();awaitUpdate();
        assertEquals("The old Check gesture downloaded an APK",0,Requests.downloads);assertEquals(0,Requests.checks);assertNull(next());
    }
    @Test public void heldInstallCannotSwitchToAnotherArtifactWithTheSameVersionAndLabel()throws Exception{
        page();updateLayout();Button primary=(Button)activity.getWindow().getDecorView().findViewWithTag("updates.primary");long down=android.os.SystemClock.uptimeMillis();updateTouch(primary,android.view.MotionEvent.ACTION_DOWN,down);
        JSONObject other=new JSONObject(manifest.toString());other.put("sha256",String.join("",java.util.Collections.nCopies(64,"1")));updateCandidate(other);assertEquals("下载并安装",primary.getText().toString());updateTouch(primary,android.view.MotionEvent.ACTION_UP,down);idle();awaitUpdate();
        assertEquals("A changed artifact was downloaded by an old gesture",0,Requests.downloads);assertNull(next());
    }
    @Test public void staleUpdateIntentIsRejectedBeforeStartingAnyWork()throws Exception{
        home=newHome();try{home.perform("updates.primary",RhActions.obj("expectedIntent","obsolete-update-state"));fail("Stale update intent accepted");}catch(IllegalStateException expected){assertEquals("更新状态已变化，请重新点击当前操作",expected.getMessage());}
        assertFalse(home.state().getBoolean("updatesBusy"));assertEquals(0,Requests.checks);assertEquals(0,Requests.downloads);assertNull(next());
    }
    @Test public void noteChangesKeepOneFreshDownloadAndInstallerHandoff()throws Exception{
        page();updateLayout();Button primary=(Button)activity.getWindow().getDecorView().findViewWithTag("updates.primary");long down=android.os.SystemClock.uptimeMillis();updateTouch(primary,android.view.MotionEvent.ACTION_DOWN,down);
        JSONObject same=new JSONObject(manifest.toString());same.put("notes","Only notes changed");updateCandidate(same);updateTouch(primary,android.view.MotionEvent.ACTION_UP,down);idle();awaitUpdate();
        assertEquals(1,Requests.downloads);assertInstaller(next());assertNull(next());
    }
    @Test public void staleUpdateIntentCannotInterruptAnUnsavedDeviceForm()throws Exception{
        page();String intent=home.state().getJSONObject("updates").getString("intent");com.limelight.nvstream.http.ComputerDetails device=new com.limelight.nvstream.http.ComputerDetails();device.uuid="update-stale-draft";device.name="Fixture";device.manualAddress=new com.limelight.nvstream.http.ComputerDetails.AddressTuple("192.0.2.1",47989);home.update(device);
        home.perform("device.settings.open",RhActions.obj("uuid",device.uuid));home.perform("settings.input",RhActions.obj("key","fps","value","unfinished"));JSONObject other=new JSONObject(manifest.toString());other.put("sha256",String.join("",java.util.Collections.nCopies(64,"1")));updateCandidate(other);
        try{home.perform("updates.primary",RhActions.obj("expectedIntent",intent));fail("Stale action interrupted draft");}catch(IllegalStateException expected){}
        assertTrue(home.state().isNull("navigationPrompt"));assertEquals("unfinished",home.state().getJSONObject("settingsInputs").getString("fps"));assertEquals(0,Requests.downloads);assertNull(next());
    }
    @Test public void heldCancelCannotCancelANewerRealDownload()throws Exception{
        page();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));idle();updateLayout();
        Button cancel=(Button)activity.getWindow().getDecorView().findViewWithTag("updates.cancel");long down=android.os.SystemClock.uptimeMillis();updateTouch(cancel,android.view.MotionEvent.ACTION_DOWN,down);long old=home.state().getJSONObject("updates").getLong("operationId");
        home.perform("updates.cancel",RhActions.obj("operationId",old));awaitUpdate();Requests.entered=new CountDownLatch(1);Requests.release=new CountDownLatch(1);home.perform("updates.install",RhActions.obj());assertTrue(Requests.entered.await(3,TimeUnit.SECONDS));idle();updateLayout();
        long current=home.state().getJSONObject("updates").getLong("operationId");assertTrue(current>old);updateTouch(cancel,android.view.MotionEvent.ACTION_UP,down);idle();assertFalse(home.state().getJSONObject("updates").getBoolean("cancelRequested"));assertTrue(home.state().getBoolean("updatesBusy"));
        home.perform("updates.cancel",RhActions.obj("operationId",current));awaitUpdate();assertEquals(2,Requests.downloads);assertNull(next());
    }
}
