package com.limelight.rhremote;

import android.app.Activity;
import android.util.AtomicFile;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import com.limelight.nvstream.http.*;
import java.io.*;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows={RhAdmissionTest.Controls.class,RhDeviceInfoFlowTest.Storage.class})
public class RhDeviceInfoFlowTest {
    @Implements(AtomicFile.class) public static class Storage {
        static boolean fail;
        @RealObject AtomicFile file;
        @Implementation protected FileOutputStream startWrite()throws IOException {
            if(fail)throw new IOException("存储不可用");
            return Shadow.directlyOn(file,AtomicFile.class,"startWrite");
        }
    }
    Activity activity;RhHome home;ComputerDetails host;View root;
    @Before public void setup()throws Exception {
        Storage.fail=false;activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError();}public void add(String address){throw new AssertionError();}public void advanced(){}public void pair(ComputerDetails d,String pin){throw new AssertionError();}public void cancelPair(){}public void refresh(){}});
        host=new ComputerDetails();host.uuid="info-host";host.name="winserver";host.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);host.activeAddress=host.manualAddress;host.state=ComputerDetails.State.ONLINE;host.pairState=PairingManager.PairState.PAIRED;home.update(host);
        RhShared.capture(activity,Collections.singletonList(host));RhProfile profile=RhProfile.load(activity,host.uuid);JSONObject p=profile.json();p.put("fps",24);RhShared.saveProfile(activity,host,RhProfile.fromJson(host.uuid,p),"工作机");
        home.perform("devices.filter",RhActions.obj("query","winserver"));home.perform("device.info.open",RhActions.obj("uuid",host.uuid));root=activity.findViewById(android.R.id.content);
    }
    @After public void cleanup(){Storage.fail=false;home.dispose();}
    View find(View v,String label){if(v.getVisibility()!=View.VISIBLE)return null;if(v instanceof EditText&&label.contentEquals(v.getContentDescription())||v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=find(((ViewGroup)v).getChildAt(i),label);if(child!=null)return child;}return null;}
    void type(String value){EditText editor=(EditText)find(root,"设备名称");assertNotNull(editor);editor.setText(value);}
    void click(String label){View button=find(root,label);assertNotNull(label,button);button.performClick();}
    String saved()throws Exception{return RhShared.canonical(RhShared.read(activity));}

    @Test public void doneSavesOnlyTheHostAliasAndKeepsSelectionQueryAndProfiles()throws Exception {
        JSONObject before=RhShared.read(activity);type(" 渲染机 ");click("完成");before.getJSONArray("devices").getJSONObject(0).put("displayName","渲染机");
        assertEquals(RhShared.canonical(before),saved());assertEquals("winserver",host.name);assertEquals("devices",home.state().getString("page"));assertEquals(host.uuid,home.state().getString("selected"));assertEquals("winserver",home.state().getString("deviceQuery"));
    }
    @Test public void resetOnlyChangesTheEditorAndCancelDoesNotPersist()throws Exception {
        String before=saved();click("恢复原名");assertEquals("",((EditText)find(root,"设备名称")).getText().toString());assertEquals("winserver",((EditText)find(root,"设备名称")).getHint());assertEquals(before,saved());click("取消");assertEquals(before,saved());
        home.perform("device.info.open",RhActions.obj("uuid",host.uuid));click("恢复原名");click("完成");assertEquals("",RhShared.alias(activity,host.uuid));assertEquals("winserver",host.name);
    }
    @Test public void invalidInputIsRetainedAndDoesNotChangeTheSavedName()throws Exception {
        String before=saved();for(String value:new String[]{"bad\nname",String.join("",Collections.nCopies(86,"名"))}){type(value);click("完成");assertEquals("device-info",home.state().getString("page"));assertEquals(value,home.state().getJSONObject("infoDraft").getString("displayName"));assertFalse(home.state().getJSONObject("infoDraft").getString("error").isEmpty());assertEquals(before,saved());}
    }
    @Test public void failedStorageKeepsTheEditorAndOriginalMetadata()throws Exception {
        String before=saved();type("新名称");Storage.fail=true;click("完成");assertEquals("device-info",home.state().getString("page"));assertEquals("存储不可用",home.state().getJSONObject("infoDraft").getString("error"));assertEquals("新名称",home.state().getJSONObject("infoDraft").getString("displayName"));assertEquals(before,saved());
    }
    @Test public void navigationOffersStayDiscardAndComplete()throws Exception {
        for(String choice:new String[]{"stay","discard","save"}){type("新名称");home.perform("app.settings.tab",RhActions.obj("tab","about"));assertEquals("unsaved-info",home.state().getJSONObject("navigationPrompt").getString("kind"));home.perform("navigation.resolve",RhActions.obj("choice",choice));assertEquals(choice.equals("save")?"新名称":"工作机",RhShared.alias(activity,host.uuid));assertEquals(choice.equals("stay")?"device-info":"global-settings",home.state().getString("page"));if(!choice.equals("stay"))home.perform("device.info.open",RhActions.obj("uuid",host.uuid));}
    }
    @Test public void cleanCompletionDoesNotWriteAndOpeningAgainDoesNotEraseInput()throws Exception {
        type("新名称");home.perform("device.info.open",RhActions.obj("uuid",host.uuid));assertEquals("新名称",home.state().getJSONObject("infoDraft").getString("displayName"));type(" 工作机 ");assertFalse(home.state().getBoolean("infoDirty"));String before=saved();Storage.fail=true;click("完成");assertEquals("devices",home.state().getString("page"));assertEquals(before,saved());
    }
    @Test public void infoCancelCannotDiscardAnUnrelatedSettingsDraft()throws Exception {
        home.perform("device.info.cancel",RhActions.obj());home.perform("device.settings.open",RhActions.obj("uuid",host.uuid));home.perform("settings.input",RhActions.obj("key","fps","value","29"));assertFalse(RhActions.dispatch("device.info.cancel",RhActions.obj()).optBoolean("ok"));assertTrue(home.state().getBoolean("settingsDirty"));assertEquals("29",home.state().getJSONObject("settingsInputs").getString("fps"));
    }
    @Test public void automaticSyncQuietlyDefersWhileManualSyncProtectsTheName()throws Exception {
        type("新名称");String before=saved();assertTrue(home.perform("sync.run",RhActions.obj("automatic",true)).optBoolean("skipped"));assertTrue(home.state().isNull("navigationPrompt"));assertEquals("新名称",home.state().getJSONObject("infoDraft").getString("displayName"));assertEquals(before,saved());
        home.perform("sync.run",RhActions.obj());assertEquals("unsaved-info",home.state().getJSONObject("navigationPrompt").getString("kind"));home.perform("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("device-info",home.state().getString("page"));assertEquals("新名称",home.state().getJSONObject("infoDraft").getString("displayName"));
    }
}
