package com.limelight.rhremote;

import android.app.*;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Two explicitly selected stages around the Windows client, against a task-owned WebDAV fixture. */
@RunWith(AndroidJUnit4.class)
public class RhIdentitySyncDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;String uuid;
    JSONObject state(){JSONObject[] result=new JSONObject[1];instrument.runOnMainSync(()->{try{result[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return result[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    void open()throws Exception{activity=instrument.startActivitySync(new Intent().setClassName(instrument.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);uuid=InstrumentationRegistry.getArguments().getString("hostUuid");assertNotNull("Pass the fixture host UUID",uuid);}
    void synchronize()throws Exception{
        long previous=state().getJSONObject("sync").optLong("lastSyncMs");action("sync.run",RhActions.obj());waitForSync();
        if(state().getJSONObject("sync").getJSONArray("conflicts").length()>0){action("sync.resolve",RhActions.obj("resolution","remote"));waitForSync();}
        assertTrue("Sync did not complete: "+state().optString("syncError"),state().getJSONObject("sync").optLong("lastSyncMs")>previous);
    }
    void waitForSync()throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);while(state().getBoolean("syncBusy")){assertTrue("Fixture sync did not finish",System.nanoTime()<deadline);Thread.sleep(25);}instrument.waitForIdleSync();}
    @Test public void configureRenameAndUpload()throws Exception{
        open();assertFalse("The isolated emulator must not have a user WebDAV credential",state().getJSONObject("sync").optBoolean("hasPassword"));String url=InstrumentationRegistry.getArguments().getString("webdavUrl");assertNotNull(url);
        action("app.settings.tab",RhActions.obj("tab","sync"));action("sync.edit",RhActions.obj("key","url","value",url));action("sync.edit",RhActions.obj("key","username","value","qa"));action("sync.edit",RhActions.obj("key","password","value","fixture-rhremote-sync"));action("sync.edit",RhActions.obj("key","automatic","value",false));action("sync.save",RhActions.obj());synchronize();
        String profiles=RhShared.canonical(RhShared.read(activity).getJSONObject("profiles"));action("devices.open",RhActions.obj());action("device.info.open",RhActions.obj("uuid",uuid));action("device.info.input",RhActions.obj("displayName","资料同步验收"));action("device.info.save",RhActions.obj());assertEquals("资料同步验收",RhShared.alias(activity,uuid));assertEquals(profiles,RhShared.canonical(RhShared.read(activity).getJSONObject("profiles")));
        action("app.settings.tab",RhActions.obj("tab","sync"));synchronize();assertFalse(RhActions.hasStream());
    }
    @Test public void pullRestoredNameAndRemoveFixtureCredentials()throws Exception{
        open();String profile=RhShared.canonical(RhProfile.load(activity,uuid).json());action("app.settings.tab",RhActions.obj("tab","sync"));synchronize();assertEquals("",RhShared.alias(activity,uuid));assertEquals(profile,RhShared.canonical(RhProfile.load(activity,uuid).json()));action("sync.clear",RhActions.obj());assertFalse(state().getJSONObject("sync").optBoolean("hasPassword"));assertFalse(RhActions.hasStream());
    }
}
