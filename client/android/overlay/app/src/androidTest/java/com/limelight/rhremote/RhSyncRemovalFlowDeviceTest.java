package com.limelight.rhremote;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual native confirmation controls, isolated to the dedicated UI emulator. */
@RunWith(AndroidJUnit4.class)
public class RhSyncRemovalFlowDeviceTest extends RhFormFlowDeviceTest {
    void click(String label){instrument.runOnMainSync(()->assertTrue(control(label,false).performClick()));instrument.waitForIdleSync();}
    AlertDialog confirmation()throws Exception{Field field=RhHome.class.getDeclaredField("syncClearDialog");field.setAccessible(true);return (AlertDialog)field.get(home);}
    void decision(int button)throws Exception{AlertDialog dialog=confirmation();instrument.runOnMainSync(()->assertTrue(dialog.getButton(button).performClick()));instrument.waitForIdleSync();}
    void capture(String name)throws Exception{Thread.sleep(250);Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);File folder=activity.getExternalFilesDir("ui-evidence");assertTrue(folder.isDirectory()||folder.mkdirs());try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
    @Test public void nativeRemovalCancelBackAndConfirmPreserveSharedConfiguration()throws Exception{
        File account=new File(instrument.getTargetContext().getFilesDir(),"webdav-local.json");byte[] original=account.exists()?Files.readAllBytes(account.toPath()):null;
        try{
            activity=instrument.startActivitySync(new Intent().setClassName(instrument.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();
            Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);
            action("app.settings.tab",RhActions.obj("tab","sync"));
            action("sync.configure",RhActions.obj("url","https://example.test/dav/","username","fixture","password","test-only-removal-fixture","automatic",false,"intervalMinutes",15));
            byte[] configured=Files.readAllBytes(account.toPath());String shared=RhShared.canonical(RhShared.read(activity));
            action("sync.edit",RhActions.obj("key","username","value","unfinished"));click("移除同步设置");assertFalse(state().isNull("syncClearPrompt"));assertArrayEquals(configured,Files.readAllBytes(account.toPath()));
            capture("sync-removal-confirmation");decision(AlertDialog.BUTTON_NEGATIVE);assertTrue(state().isNull("syncClearPrompt"));assertEquals("unfinished",state().getJSONObject("syncDraft").getString("username"));assertArrayEquals(configured,Files.readAllBytes(account.toPath()));
            click("移除同步设置");assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(!state().isNull("syncClearPrompt")){assertTrue("Back did not cancel removal",System.nanoTime()<deadline);Thread.sleep(25);}
            assertArrayEquals(configured,Files.readAllBytes(account.toPath()));click("移除同步设置");decision(AlertDialog.BUTTON_POSITIVE);
            assertTrue(state().isNull("syncClearPrompt"));assertFalse(state().getJSONObject("sync").getBoolean("hasPassword"));assertFalse(state().getBoolean("syncDirty"));assertFalse(new RhSync(activity).state().getBoolean("hasPassword"));assertEquals(shared,RhShared.canonical(RhShared.read(activity)));
            capture("sync-removal-complete");RhShared.write(new File(activity.getExternalFilesDir("ui-evidence"),"sync-removal-flow.json"),RhActions.obj("cancelPreservedAccount",true,"backPreservedAccount",true,"draftPreservedOnCancel",true,"sharedUnchanged",true,"removedPersisted",true,"width",activity.getWindow().getDecorView().getWidth(),"height",activity.getWindow().getDecorView().getHeight()));
        }finally{
            if(original==null)new android.util.AtomicFile(account).delete();else RhShared.write(account,new JSONObject(new String(original,java.nio.charset.StandardCharsets.UTF_8)));
            if(activity!=null)instrument.runOnMainSync(()->activity.finish());
        }
    }
}
