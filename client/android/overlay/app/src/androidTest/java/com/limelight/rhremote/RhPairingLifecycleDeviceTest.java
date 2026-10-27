package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in real protocol regression. The desktop controller owns host PIN approval. */
@RunWith(AndroidJUnit4.class)
public class RhPairingLifecycleDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;File folder;String run,uuid;
    JSONObject state(){JSONObject[] value={null};instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return value[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    void waitFor(java.util.function.BooleanSupplier ready,int seconds,String detail)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!ready.getAsBoolean()){assertTrue(detail+": "+state().optJSONObject("connection"),System.nanoTime()<end);Thread.sleep(40);}instrument.waitForIdleSync();}
    void idle()throws Exception{waitFor(()->state().isNull("admission")&&!state().optBoolean("networkBusy")&&!state().optBoolean("pairing"),20,"Pairing cleanup");}
    void waiting()throws Exception{waitFor(()->state().optBoolean("pairing")&&state().optString("page").equals("pair"),15,"PIN page");assertTrue(state().optString("pairPin").matches("[0-9]{4}"));}
    void stage(String phase)throws Exception{RhShared.write(new File(folder,"pairing-native-stage.json"),RhActions.obj("runId",run,"phase",phase,"uuid",uuid,"pin",state().optString("pairPin"),"expectedName","roth"));}
    void ack(String phase)throws Exception{File file=new File(folder,"pairing-native-ack.json");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);while(true){try{JSONObject value=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));if(value.optString("runId").equals(run)&&value.optString("phase").equals(phase))return;}catch(IOException|org.json.JSONException notReady){}assertTrue("Host controller did not acknowledge "+phase,System.nanoTime()<end);Thread.sleep(100);}}
    View find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    View text(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=text(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    void pairChrome(String buttonLabel){instrument.runOnMainSync(()->{
        View root=activity.findViewById(android.R.id.content),button=find(root,buttonLabel);assertNotNull(buttonLabel,button);assertNotNull("Pairing header",text(root,"配对"));
        android.graphics.Rect visible=new android.graphics.Rect();assertTrue(button.getGlobalVisibleRect(visible));assertEquals(button.getWidth(),visible.width());assertEquals(button.getHeight(),visible.height());
        for(android.view.ViewParent parent=button.getParent();parent!=null;parent=parent.getParent())assertFalse("Pairing footer must not scroll",parent instanceof ScrollView);
        for(String step:new String[]{"1  检查地址与配对","2  建立串流","3  显示远程画面"})assertNull("Pairing must not show stream steps",text(root,step));
    });}
    void click(String label){instrument.runOnMainSync(()->{View button=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,button);assertTrue(label,button.isEnabled());assertTrue(button.performClick());});instrument.waitForIdleSync();}
    String certificate(Certificate cert)throws Exception{assertNotNull(cert);byte[] bytes=MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    ComputerDetails persisted(){ComputerDatabaseManager database=new ComputerDatabaseManager(instrument.getTargetContext());try{return database.getComputerByUUID(uuid);}finally{database.close();}}
    void capture(String name)throws Exception{Thread.sleep(200);Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
    void beginPair()throws Exception{String pin=String.format(Locale.ROOT,"%04d",new SecureRandom().nextInt(10000));action("device.pair",RhActions.obj("uuid",uuid,"pin",pin));waiting();}
    @Test public void cancelWrongPinRetryAndRepeatPairingKeepTheOriginalIdentity()throws Exception{
        run=InstrumentationRegistry.getArguments().getString("pairingRun");Assume.assumeTrue("Real pairing requires an explicit desktop controller",run!=null&&run.matches("[A-Za-z0-9-]{1,100}"));
        uuid=InstrumentationRegistry.getArguments().getString("pairingHostUuid");assertNotNull(uuid);Context context=instrument.getTargetContext();
        try{
            activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);
            folder=activity.getExternalFilesDir("ui-evidence");assertTrue(folder.isDirectory()||folder.mkdirs());
            ComputerDetails before=persisted();assertNotNull("Dedicated emulator must already own its test pairing",before);String serverCertificate=certificate(before.serverCert),clientCertificate=certificate(PlatformBinding.getCryptoProvider(context).getClientCertificate());String profiles=RhShared.canonical(RhShared.read(context).getJSONObject("profiles"));
            beginPair();stage("cancel-ready");ack("cancel-observed");pairChrome("取消配对");capture("pairing-native-pin");click("取消配对");idle();assertEquals("cancelled",state().getJSONObject("connection").getString("phase"));assertEquals("devices",state().getString("page"));stage("cancel-done");ack("cancel-cleared");
            beginPair();stage("wrong-pin-ready");idle();assertEquals("failed",state().getJSONObject("connection").getString("phase"));assertEquals("pair",state().getJSONObject("connection").getString("operation"));assertTrue(state().getJSONObject("connection").getString("error").contains("配对码"));assertEquals("connection",state().getString("page"));pairChrome("重试配对");capture("pairing-native-failed");stage("wrong-pin-done");ack("wrong-pin-cleared");
            click("重试配对");waiting();assertEquals(uuid,state().getString("selected"));stage("retry-ready");idle();assertEquals("paired",state().getJSONObject("connection").getString("phase"));assertEquals("配对完成",state().getString("status"));assertEquals("devices",state().getString("page"));stage("retry-done");ack("retry-verified");
            beginPair();stage("repeat-ready");idle();assertEquals("paired",state().getJSONObject("connection").getString("phase"));assertEquals("配对完成",state().getString("status"));stage("repeat-done");ack("repeat-verified");
            assertEquals(serverCertificate,certificate(persisted().serverCert));assertEquals(clientCertificate,certificate(PlatformBinding.getCryptoProvider(context).getClientCertificate()));assertEquals(profiles,RhShared.canonical(RhShared.read(context).getJSONObject("profiles")));
            capture("pairing-native-complete");RhShared.write(new File(folder,"pairing-native-result.json"),RhActions.obj("runId",run,"cancelReleased",true,"wrongPinVisible",true,"retryPaired",true,"repeatPaired",true,"serverCertificateUnchanged",true,"clientCertificateUnchanged",true,"profilesUnchanged",true,"videoStarted",false));stage("finished");
        }finally{
            if(home!=null){if(RhActions.hasStream()){instrument.runOnMainSync(()->RhActions.dispatch("pair.cancel",RhActions.obj()));idle();}if(activity!=null)instrument.runOnMainSync(()->activity.finish());}
        }
    }
}
