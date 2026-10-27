package com.limelight.rhremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
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

/** Real HTTP, APK validation and native controls; release hosts use a loopback fixture.
 * Install permission is a test-environment precondition. This test never changes it
 * and never confirms an installation; the opt-in emulator branch cancels the installer. */
@RunWith(AndroidJUnit4.class)
public final class RhUpdateFlowDeviceTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    Activity activity;RhHome home;
    final java.util.Map<String,Integer> pixels=new java.util.HashMap<>();
    JSONObject state()throws Exception{JSONObject[] out={null};instrument.runOnMainSync(()->{try{out[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return out[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(label.equals(view.getContentDescription())||view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    View control(String label){View result=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,result);return result;}
    void click(String label){instrument.runOnMainSync(()->{View view=control(label);assertTrue(label,view.isEnabled());view.performClick();});instrument.waitForIdleSync();}
    void waitFor(java.util.function.Predicate<JSONObject> condition,String name)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!condition.test(state())){assertTrue(name+": "+state().optJSONObject("updates"),System.nanoTime()<end);Thread.sleep(25);}instrument.waitForIdleSync();}
    void footer(String label){instrument.runOnMainSync(()->{View button=control(label);android.graphics.Rect visible=new android.graphics.Rect();assertTrue(button.getGlobalVisibleRect(visible));assertEquals(button.getHeight(),visible.height());assertEquals(button.getWidth(),visible.width());int[] position=new int[2];button.getLocationOnScreen(position);assertTrue("Main update action must remain at the bottom",position[1]>activity.getResources().getDisplayMetrics().heightPixels/2);});}
    boolean installationConfirmation(android.view.accessibility.AccessibilityNodeInfo node){
        if(node==null)return false;String text=node.getText()==null?"":node.getText().toString();if(node.isEnabled()&&node.isClickable()&&(text.equalsIgnoreCase("Update")||text.equalsIgnoreCase("Install")||text.equals("更新")||text.equals("安装")))return true;
        for(int i=0;i<node.getChildCount();i++)if(installationConfirmation(node.getChild(i)))return true;return false;
    }
    void capture(String name)throws Exception{
        if(!InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true"))return;
        long focusEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!activity.hasWindowFocus()){assertTrue("Update activity did not receive focus",System.nanoTime()<focusEnd);Thread.sleep(25);}
        // Waiting for an idle Looper alone can capture the previous frame. Commit
        // two draws so the compositor has received this action's visible result.
        for(int frame=0;frame<2;frame++){
            CountDownLatch committed=new CountDownLatch(1);instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();assertTrue(decor.isHardwareAccelerated());decor.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);decor.invalidate();});assertTrue("Update UI frame did not commit",committed.await(3,TimeUnit.SECONDS));
        }
        instrument.waitForIdleSync();android.graphics.Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);int[] content=new int[image.getWidth()*image.getHeight()];image.getPixels(content,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());pixels.put(name,java.util.Arrays.hashCode(content));
        File folder=instrument.getTargetContext().getExternalFilesDir("ui-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
    }
    private static String sha(File file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)hash.update(bytes,0,n);}StringBuilder text=new StringBuilder();for(byte value:hash.digest())text.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return text.toString();}
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers=Executors.newCachedThreadPool();final CountDownLatch downloading=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicInteger downloads=new AtomicInteger();
        volatile boolean available;final File apk;final byte[] feed;
        Fixture(File apk,byte[] feed)throws IOException{this.apk=apk;this.feed=feed;workers.execute(()->{try{while(!server.isClosed()){Socket socket=server.accept();workers.execute(()->reply(socket));}}catch(IOException closed){}});}
        void reply(Socket socket){try(Socket owned=socket){BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String first=reader.readLine(),line;while((line=reader.readLine())!=null&&!line.isEmpty()){}boolean metadata=first!=null&&first.contains("/feed");OutputStream out=socket.getOutputStream();if(metadata&&!available){out.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));return;}long length=metadata?feed.length:apk.length();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();if(metadata)out.write(feed);else{downloads.incrementAndGet();downloading.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Fixture download gate timed out");try(InputStream in=new FileInputStream(apk)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}}out.flush();}catch(Exception stopped){}}
        HttpURLConnection open(String original)throws IOException{return(HttpURLConnection)new URL("http://127.0.0.1:"+server.getLocalPort()+ (original.endsWith("releases.android.json")?"/feed":"/apk")).openConnection(Proxy.NO_PROXY);}
        public void close()throws IOException{release.countDown();server.close();workers.shutdownNow();}
    }
    @Test public void singleNativeActionHandlesFailureDownloadValidationAndReturnFromSystemUi()throws Exception{
        Context context=instrument.getTargetContext();File candidate=new File(context.getExternalFilesDir("ui-evidence"),"update-candidate.apk");assertTrue("Push the signed future test APK into the emulator",candidate.isFile());
        PackageInfo incoming=context.getPackageManager().getPackageArchiveInfo(candidate.getAbsolutePath(),0),installed=context.getPackageManager().getPackageInfo(context.getPackageName(),0);assertNotNull(incoming);assertEquals(context.getPackageName(),incoming.packageName);assertTrue(incoming.getLongVersionCode()>installed.getLongVersionCode());
        JSONObject manifest=RhActions.obj("version",incoming.versionName,"versionCode",incoming.getLongVersionCode(),"packageName",incoming.packageName,"fileName",candidate.getName(),"tag","v"+incoming.versionName,"size",candidate.length(),"sha256",sha(candidate));
        File directory=new File(context.getFilesDir(),"updates"),backup=new File(context.getFilesDir(),"updates-before-native-flow"),captured=new File(context.getFilesDir(),"updates-native-flow-evidence-"+System.nanoTime());assertFalse(backup.exists());assertFalse(captured.exists());boolean hadUpdates=directory.exists();if(hadUpdates)Files.move(directory.toPath(),backup.toPath());
        android.content.SharedPreferences global=context.getSharedPreferences("rhremote.global",0);boolean hadCheck=global.contains("lastUpdateCheck");long previousCheck=global.getLong("lastUpdateCheck",0);global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
        try(Fixture fixture=new Fixture(candidate,RhActions.obj("schemaVersion",1,"android",manifest).toString().getBytes(StandardCharsets.UTF_8))){
            activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertFalse(RhActions.hasStream());String before=RhShared.canonical(RhShared.read(context));
            Field updaterField=RhHome.class.getDeclaredField("updates");updaterField.setAccessible(true);RhUpdates updater=(RhUpdates)updaterField.get(home);Field connections=RhUpdates.class.getDeclaredField("connections");connections.setAccessible(true);connections.set(updater,(RhUpdates.Connections)fixture::open);
            action("app.settings.tab",RhActions.obj("tab","updates"));footer("检查更新");click("检查更新");waitFor(s->!s.optBoolean("updatesBusy")&&s.optJSONObject("updates").optString("phase").equals("error"),"Failed check");footer("重新检查");capture("update-failed");click("查看详情");instrument.runOnMainSync(()->assertNotNull(control("更新错误详情")));footer("重新检查");capture("update-failed-details");if(!pixels.isEmpty())assertNotEquals("Expanded details must produce a different rendered image",pixels.get("update-failed"),pixels.get("update-failed-details"));click("收起详情");
            fixture.available=true;click("重新检查");waitFor(s->!s.optBoolean("updatesBusy")&&s.optJSONObject("updates").optString("phase").equals("available"),"Available update");footer("下载并安装");capture("update-available");assertEquals(0,fixture.downloads.get());
            fixture.available=false;action("updates.check",RhActions.obj());waitFor(s->!s.optBoolean("updatesBusy")&&s.optJSONObject("updates").optString("phase").equals("error"),"Recheck failure with cached version");assertEquals(incoming.versionName,state().getJSONObject("updates").getString("availableVersion"));assertEquals("updates.check",state().getJSONObject("updates").getString("failedAction"));footer("重新检查");capture("update-recheck-failed");assertEquals(0,fixture.downloads.get());
            fixture.available=true;click("重新检查");waitFor(s->!s.optBoolean("updatesBusy")&&s.optJSONObject("updates").optString("phase").equals("available"),"Recheck succeeds without installing");footer("下载并安装");assertEquals(0,fixture.downloads.get());
            click("下载并安装");assertTrue(fixture.downloading.await(3,TimeUnit.SECONDS));assertTrue(state().getBoolean("updatesBusy"));JSONObject[] duplicate={null};instrument.runOnMainSync(()->duplicate[0]=RhActions.dispatch("updates.primary",RhActions.obj()));assertFalse(duplicate[0].optBoolean("ok"));
            action("app.settings.tab",RhActions.obj("tab","about"));assertEquals("",state().getString("status"));action("app.settings.tab",RhActions.obj("tab","updates"));assertEquals(1,fixture.downloads.get());capture("update-downloading");fixture.release.countDown();
            waitFor(s->!s.optBoolean("updatesBusy")&&java.util.Arrays.asList("permission","installer").contains(s.optJSONObject("updates").optString("phase")),"Validated package and external UI");boolean permission=state().getJSONObject("updates").getString("phase").equals("permission");
            boolean cancelInstaller=InstrumentationRegistry.getArguments().getString("cancelInstaller","false").equals("true");
            if(cancelInstaller){
                assertTrue("Installer cancellation is restricted to the dedicated emulator",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));assertFalse("Test environment must already allow installation",permission);
                long installerEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);String packageName="";while(!packageName.equals("com.android.packageinstaller")&&!packageName.equals("com.google.android.packageinstaller")){android.view.accessibility.AccessibilityNodeInfo root=instrument.getUiAutomation().getRootInActiveWindow();packageName=root==null||root.getPackageName()==null?"":root.getPackageName().toString();assertTrue("Expected the system package installer, got "+packageName,System.nanoTime()<installerEnd);Thread.sleep(25);}
                long confirmationEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!installationConfirmation(instrument.getUiAutomation().getRootInActiveWindow())){assertTrue("Installer must finish staging and expose Update or Install before cancellation",System.nanoTime()<confirmationEnd);Thread.sleep(25);}
                if(InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true")){instrument.getUiAutomation().waitForIdle(100,3000);android.graphics.Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir("ui-evidence"),"update-system-installer.png"))){assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}}
                assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            }else{
                context.startActivity(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            waitFor(s->s.optJSONObject("updates").optString("phase").equals("downloaded"),"External UI return");assertTrue(state().getJSONObject("updates").getBoolean("downloaded"));assertEquals(1,fixture.downloads.get());footer("安装更新");capture("update-install-return");
            assertEquals(manifest.getString("sha256"),sha(new File(directory,"RHRemote-update.apk")));assertEquals(before,RhShared.canonical(RhShared.read(context)));assertEquals(installed.getLongVersionCode(),context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode());
            JSONObject proof=RhActions.obj("phase",cancelInstaller?"installer_confirmation_cancelled":permission?"permission_denied_return":"installer_return_without_confirmation","installerConfirmationVisible",cancelInstaller,"failedRecheckStayedCheck",true,"recheckDidNotDownload",true,"downloadCount",fixture.downloads.get(),"versionCode",incoming.getLongVersionCode(),"sha256",manifest.getString("sha256"),"manualInstallConfirmed",false,"configurationPreserved",true);try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(context.getExternalFilesDir("ui-evidence"),"update-flow.json")),StandardCharsets.UTF_8)){out.write(proof.toString());}
        }finally{
            if(hadCheck)global.edit().putLong("lastUpdateCheck",previousCheck).commit();else global.edit().remove("lastUpdateCheck").commit();
            if(activity!=null)instrument.runOnMainSync(()->activity.finish());if(directory.exists())Files.move(directory.toPath(),captured.toPath());if(hadUpdates)Files.move(backup.toPath(),directory.toPath());
        }
    }
}
