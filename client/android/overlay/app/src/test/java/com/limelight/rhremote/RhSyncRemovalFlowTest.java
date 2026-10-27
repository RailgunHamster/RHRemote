package com.limelight.rhremote;

import android.app.*;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public class RhSyncRemovalFlowTest {
    Activity activity;RhHome home;File account;String accountBefore,sharedBefore;
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        account=new File(activity.getFilesDir(),"webdav-local.json");
        RhShared.write(account,RhActions.obj("url","https://example.test/dav/","username","fixture","automatic",true,"intervalMinutes",15,"lastSyncMs",System.currentTimeMillis(),"ciphertext","test-only-encrypted-placeholder"));
        accountBefore=new String(Files.readAllBytes(account.toPath()),java.nio.charset.StandardCharsets.UTF_8);
        home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError();}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}});
        sharedBefore=RhShared.canonical(RhShared.read(activity));
        home.perform("app.settings.tab",RhActions.obj("tab","sync"));
    }
    @After public void cleanup(){home.dispose();}
    View find(View v,String text){if(v.getVisibility()!=View.VISIBLE)return null;if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=find(((ViewGroup)v).getChildAt(i),text);if(child!=null)return child;}return null;}
    void request(){View button=find(activity.findViewById(android.R.id.content),"移除同步设置");assertNotNull(button);assertTrue(button.performClick());Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    AlertDialog dialog(){return (AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();}
    void unchanged()throws Exception{assertEquals(accountBefore,new String(Files.readAllBytes(account.toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(sharedBefore,RhShared.canonical(RhShared.read(activity)));assertTrue(home.state().getJSONObject("sync").getBoolean("hasPassword"));}
    @Test public void cancelAndBackPreserveSavedCredentialsAndTheDraft()throws Exception{
        home.message("同步设置已保存");home.perform("sync.edit",RhActions.obj("key","username","value","unfinished"));assertEquals("",home.state().getString("status"));request();
        assertTrue(dialog().isShowing());assertFalse(home.state().isNull("syncClearPrompt"));unchanged();
        assertTrue(home.perform("sync.run",RhActions.obj("automatic",true)).getBoolean("skipped"));
        try{home.perform("sync.save",RhActions.obj());fail("modal allowed a save");}catch(IllegalStateException expected){}
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(home.state().isNull("syncClearPrompt"));unchanged();assertEquals("unfinished",home.state().getJSONObject("syncDraft").getString("username"));
        request();assertTrue(home.goBack());assertTrue(home.state().isNull("syncClearPrompt"));unchanged();
        request();dialog().cancel();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(home.state().isNull("syncClearPrompt"));unchanged();
    }
    @Test public void onlyTheExplicitRemoveButtonClearsTheLocalAccount()throws Exception{
        try{home.perform("sync.clear.resolve",RhActions.obj("choice","remove"));fail("removal without request");}catch(IllegalStateException expected){}
        unchanged();request();dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(home.state().isNull("syncClearPrompt"));assertFalse(home.state().getJSONObject("sync").getBoolean("hasPassword"));assertFalse(home.state().getJSONObject("sync").getBoolean("automatic"));assertFalse(home.state().getBoolean("syncDirty"));
        assertEquals(sharedBefore,RhShared.canonical(RhShared.read(activity)));assertFalse(new RhSync(activity).state().getBoolean("hasPassword"));
        try{home.perform("sync.clear.resolve",RhActions.obj("choice","remove"));fail("duplicate removal");}catch(IllegalStateException expected){}
    }
    @Test public void failedRemovalKeepsTheDialogAndAccountForRetry()throws Exception{
        request();File parent=account.getParentFile(),backup=new File(parent,"webdav-removal-backup.json");assertTrue(account.renameTo(backup));assertTrue(account.mkdir());new File(account,"cannot-replace").createNewFile();
        try{home.perform("sync.clear.resolve",RhActions.obj("choice","remove"));fail("write failure ignored");}catch(java.io.IOException expected){}
        assertTrue(dialog().isShowing());assertFalse(home.state().isNull("syncClearPrompt"));assertFalse(home.state().getString("syncError").isEmpty());assertTrue(home.state().getJSONObject("sync").getBoolean("hasPassword"));
        assertTrue(new File(account,"cannot-replace").delete());assertTrue(account.delete());assertTrue(backup.renameTo(account));unchanged();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(home.state().isNull("syncClearPrompt"));assertFalse(home.state().getJSONObject("sync").getBoolean("hasPassword"));
    }
}
