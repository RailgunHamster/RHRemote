package com.limelight.rhremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Android views, APK digest/signature verification and system installer.
 * Only the HTTP connection factory is replaced by a USB-loopback fixture.
 * No release source, pairing, network preference or installation permission changes. */
@RunWith(AndroidJUnit4.class)
public class RhUpdateDeviceFlowTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private Activity activity;private RhHome home;private RhUpdates updates;
    private void main(Runnable task){instrumentation.runOnMainSync(task);}
    private JSONObject state()throws Exception{
        JSONObject[] value=new JSONObject[1];main(()->value[0]=RhActions.dispatch("updates.state",RhActions.obj()));
        assertTrue(value[0].toString(),value[0].getBoolean("ok"));return value[0].getJSONObject("result");
    }
    private boolean busy()throws Exception{
        JSONObject[] value=new JSONObject[1];main(()->{try{value[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});
        return value[0].getBoolean("updatesBusy");
    }
    private void settled()throws Exception{
        long deadline=SystemClock.elapsedRealtime()+90000;
        while(busy()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
        assertFalse("Device update worker exceeded the bounded test wait",busy());
    }
    private Button find(View view,String title){
        if(view instanceof Button&&((Button)view).getText().toString().equals(title))return(Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button button=find(((ViewGroup)view).getChildAt(i),title);if(button!=null)return button;}
        return null;
    }
    private Button click(String title){
        Button[] value=new Button[1];main(()->{value[0]=find(activity.getWindow().getDecorView(),title);assertNotNull(title,value[0]);assertTrue(title,value[0].isEnabled());assertTrue(title,value[0].performClick());});return value[0];
    }
    private void proof(JSONObject proof){Bundle result=new Bundle();result.putString("stream","RHREMOTE_UPDATE_DEVICE_PROOF "+proof+"\n");instrumentation.sendStatus(0,result);}
    @Test public void checkDownloadAndInstallButtonsOnTheRealDevice()throws Exception{
        Intent launch=new Intent().setClassName(instrumentation.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity=instrumentation.startActivitySync(launch);instrumentation.waitForIdleSync();
        Field homeField=activity.getClass().getDeclaredField("rhHome");homeField.setAccessible(true);home=(RhHome)homeField.get(activity);assertNotNull(home);
        Field updatesField=RhHome.class.getDeclaredField("updates");updatesField.setAccessible(true);updates=(RhUpdates)updatesField.get(home);
        Field factory=RhUpdates.class.getDeclaredField("connections");factory.setAccessible(true);Object original=factory.get(updates);
        Field available=RhUpdates.class.getDeclaredField("available"),ready=RhUpdates.class.getDeclaredField("ready");available.setAccessible(true);ready.setAccessible(true);
        Object oldAvailable=available.get(updates),oldReady=ready.get(updates);
        File directory=new File(instrumentation.getTargetContext().getFilesDir(),"updates"),receipt=new File(directory,"ready.json");
        assertFalse("Preserve an existing downloaded update receipt",receipt.exists());
        AtomicBoolean fail=new AtomicBoolean(true);JSONObject evidence=new JSONObject().put("fixture",true).put("transport","USB loopback; public-network download not claimed");
        Object local=Proxy.newProxyInstance(factory.getType().getClassLoader(),new Class<?>[]{factory.getType()},(object,method,args)->{
            if(!method.getName().equals("open"))throw new UnsupportedOperationException();
            if(fail.get())throw new UnknownHostException("device test fixture");
            String path=((String)args[0]).endsWith("releases.android.json")?"metadata.json":"candidate.apk";
            HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:48770/"+path).openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(10000);return connection;
        });
        try{
            factory.set(updates,local);main(()->assertTrue(RhActions.dispatch("app.settings.open",RhActions.obj()).optBoolean("ok")));
            click("检查更新");settled();JSONObject failed=state();assertEquals("error",failed.getString("phase"));assertEquals(2,failed.getJSONArray("sourceFailures").length());
            evidence.put("visibleFailure",failed.getString("status"));
            click("下载 APK");settled();assertEquals("error",state().getString("phase"));
            click("下载并安装");settled();assertEquals("error",state().getString("phase"));
            evidence.put("allButtonsExposeNetworkFailure",true);
            fail.set(false);click("检查更新");settled();JSONObject checked=state();assertEquals("available",checked.getString("phase"));evidence.put("check",checked);
            Button download=click("下载 APK");long deadline=SystemClock.elapsedRealtime()+15000;JSONObject progress=state();
            while((progress.optLong("downloadedBytes")==0||!busy())&&SystemClock.elapsedRealtime()<deadline){SystemClock.sleep(100);progress=state();}
            assertTrue(progress.toString(),progress.getLong("downloadedBytes")>0);assertTrue("APK fixture must allow a real progress sample",busy());
            SystemClock.sleep(700);main(()->{assertSame(download,find(activity.getWindow().getDecorView(),"下载 APK"));assertFalse(download.isEnabled());});
            evidence.put("progress",progress);settled();JSONObject downloaded=state();assertTrue(downloaded.getBoolean("downloaded"));assertEquals("downloaded",downloaded.getString("phase"));evidence.put("downloadOnly",downloaded);
            assertTrue("System install permission must be granted by the user",downloaded.getBoolean("installPermissionGranted"));
            // Restart only this test's downloaded update so the primary button must
            // perform metadata, transfer, verification and installer launch itself.
            assertTrue("Owned fixture receipt",receipt.delete());
            assertTrue("Owned fixture APK",new File(directory,"RHRemote-update.apk").delete());
            available.set(updates,null);ready.set(updates,false);
            main(home::rebind);
            click("下载并安装");settled();JSONObject installed=state();assertEquals("installer",installed.getString("phase"));evidence.put("installer",installed);evidence.put("result","PASS_REAL_VIEWS_DOWNLOAD_VERIFY_AND_INSTALLER_LAUNCH");proof(evidence);
        }finally{
            factory.set(updates,original);available.set(updates,oldAvailable);ready.set(updates,oldReady);
            // The fixture receipt must not advertise an unpublished version after a cancelled install.
            if(receipt.isFile()&&!receipt.delete())throw new IOException("Could not remove the owned device-test update receipt");
        }
    }
}
