package com.limelight.rhremote;

import android.app.*;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public class RhFormFlowTest {
    Activity activity;RhHome home;View root;
    @Before public void setup(){
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError();}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}});
        ComputerDetails d=new ComputerDetails();d.uuid="host";d.name="winserver";d.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);home.update(d);
        root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
    }
    @After public void dispose(){home.dispose();}
    List<View> views(View v){List<View> out=new ArrayList<>();if(v.getVisibility()!=View.VISIBLE)return out;out.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)out.addAll(views(((ViewGroup)v).getChildAt(i)));return out;}
    Button button(String text){for(View v:views(root))if(v instanceof Button&&((Button)v).getText().toString().equals(text))return(Button)v;throw new AssertionError("Missing button "+text);}
    EditText edit(String description){for(View v:views(root))if(v instanceof EditText&&description.contentEquals(v.getContentDescription()))return(EditText)v;throw new AssertionError("Missing field "+description);}
    void layout(int width,int height){for(int i=0;i<4;i++){root.requestLayout();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    boolean inScroll(View v){for(android.view.ViewParent p=v.getParent();p instanceof View;p=p.getParent())if(p instanceof ScrollView)return true;return false;}
    void profile()throws Exception{home.perform("device.settings.open",RhActions.obj("uuid","host"));layout(360,800);}
    @Test public void partialNumericInputSurvivesTabsAndInvalidSavePreservesTheForm()throws Exception{
        profile();String before=RhShared.canonical(RhShared.read(activity));
        String widthLabel=null;org.json.JSONArray catalog=RhSettings.catalog(activity);for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("key").equals("width"))widthLabel=catalog.getJSONObject(i).getString("label");
        EditText width=edit(widthLabel);width.requestFocus();width.setText("");assertSame(width,edit(widthLabel));assertTrue(width.hasFocus());assertEquals("",home.state().getJSONObject("settingsInputs").getString("width"));
        home.perform("settings.tab",RhActions.obj("tab","audio"));home.perform("settings.tab",RhActions.obj("tab","video"));assertEquals("",edit(widthLabel).getText().toString());
        button("保存").performClick();assertEquals("width",home.state().getString("settingsErrorField"));assertEquals("settings",home.state().getString("page"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));assertEquals("",edit(widthLabel).getText().toString());
    }
    @Test public void backDecisionSupportsStayDiscardAndSaveThroughTheSameActions()throws Exception{
        profile();home.perform("settings.input",RhActions.obj("key","bitrateMbps","value","37"));
        assertTrue(home.goBack());assertEquals("unsaved-device",home.state().getJSONObject("navigationPrompt").getString("kind"));
        AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(home.state().isNull("navigationPrompt"));assertEquals("37",home.state().getJSONObject("settingsInputs").getString("bitrateMbps"));
        home.perform("app.settings.tab",RhActions.obj("tab","about"));home.perform("navigation.resolve",RhActions.obj("choice","save"));assertEquals(37,RhProfile.load(activity,"host").bitrateMbps);assertEquals("about",home.state().getString("settingsTab"));
        profile();home.perform("settings.input",RhActions.obj("key","fps","value","24"));home.perform("devices.open",RhActions.obj());home.perform("navigation.resolve",RhActions.obj("choice","discard"));assertEquals("devices",home.state().getString("page"));assertEquals(30,RhProfile.load(activity,"host").fps);
    }
    @Test public void untouchedSettingsCanSelectADeviceWithoutLeavingAHiddenDraft()throws Exception{
        profile();home.perform("device.select",RhActions.obj("uuid","host"));assertEquals("devices",home.state().getString("page"));assertTrue(home.state().isNull("draft"));assertTrue(home.state().isNull("navigationPrompt"));
    }
    View described(String description){for(View v:views(root))if(v.getContentDescription()!=null&&description.contentEquals(v.getContentDescription()))return v;throw new AssertionError("Missing control "+description);}
    @Test public void routeCheckboxesKeepOnePathAndReorderingHasVisibleBoundaries()throws Exception{
        profile();String before=RhShared.canonical(RhShared.read(activity));
        home.perform("settings.set",RhActions.obj("key","routeOrder","value",new org.json.JSONArray().put("lan").put("relay")));home.perform("settings.tab",RhActions.obj("tab","network"));layout(360,800);
        assertFalse(described("上移局域网").isEnabled());assertTrue(described("下移局域网").isEnabled());assertFalse(described("下移自建中继").isEnabled());
        described("下移局域网").performClick();assertEquals("relay",home.state().getJSONObject("draft").getJSONArray("routeOrder").getString(0));assertFalse(described("上移自建中继").isEnabled());assertFalse(described("下移局域网").isEnabled());
        CheckBox lan=(CheckBox)described("连接路径：局域网");assertTrue(lan.isChecked());lan.performClick();assertEquals(1,home.state().getJSONObject("draft").getJSONArray("routeOrder").length());
        CheckBox relay=(CheckBox)described("连接路径：自建中继");assertTrue(relay.isChecked());assertFalse(relay.isEnabled());assertFalse(described("上移自建中继").isEnabled());assertFalse(described("下移自建中继").isEnabled());
        ((CheckBox)described("连接路径：公网直连")).performClick();assertEquals(2,home.state().getJSONObject("draft").getJSONArray("routeOrder").length());assertTrue(described("连接路径：自建中继").isEnabled());
        button("取消").performClick();assertEquals(before,RhShared.canonical(RhShared.read(activity)));
    }
    @Test public void saveBarAndCategoryNavigationRemainOutsideTheScrollableBody()throws Exception{
        profile();for(int[] size:new int[][]{{360,800},{640,360},{1008,672}}){layout(size[0],size[1]);Button save=button("保存"),back=button(size[0]>=700?"概览":"‹ 概览");assertFalse(inScroll(save));assertFalse(inScroll(back));int[] location=new int[2];save.getLocationInWindow(location);assertTrue(location[1]>=0&&location[1]+save.getHeight()<=size[1]);assertTrue(save.getHeight()>=48);}
    }
    @Test public void webdavInputIsRetainedOnFailureAndLeavingUsesADecision()throws Exception{
        home.perform("app.settings.tab",RhActions.obj("tab","sync"));layout(360,800);
        EditText url=edit("WebDAV 目录地址（HTTP / HTTPS）");url.setText("invalid");edit("密码").setText("fixture-secret");assertSame(url,edit("WebDAV 目录地址（HTTP / HTTPS）"));assertTrue(home.state().getBoolean("syncDirty"));assertFalse(home.state().toString().contains("fixture-secret"));
        home.perform("app.settings.tab",RhActions.obj("tab","updates"));assertEquals("sync",home.state().getString("settingsTab"));assertEquals("unsaved-sync",home.state().getJSONObject("navigationPrompt").getString("kind"));
        try{home.perform("navigation.resolve",RhActions.obj("choice","save"));fail("Invalid WebDAV saved");}catch(Exception expected){}
        assertEquals("sync",home.state().getString("settingsTab"));assertEquals("invalid",edit("WebDAV 目录地址（HTTP / HTTPS）").getText().toString());assertEquals("fixture-secret",edit("密码").getText().toString());assertFalse(home.state().getString("syncError").isEmpty());assertTrue(home.state().isNull("navigationPrompt"));
        button("取消").performClick();assertEquals("",edit("WebDAV 目录地址（HTTP / HTTPS）").getText().toString());assertFalse(home.state().getBoolean("syncDirty"));assertFalse(inScroll(button("保存")));
    }
    @Test public void webdavSaveStateIsVisibleAndBusySyncCannotAcceptEdits()throws Exception{
        home.perform("app.settings.tab",RhActions.obj("tab","sync"));layout(1008,672);
        Button save=button("保存");assertFalse(save.isEnabled());assertNotEquals(android.graphics.Color.WHITE,save.getCurrentTextColor());
        int[] location=new int[2];save.getLocationInWindow(location);assertTrue("Save stays on the right",location[0]>672);
        edit("WebDAV 目录地址（HTTP / HTTPS）").setText("http://example.test/dav/");assertTrue(button("保存").isEnabled());assertEquals(android.graphics.Color.WHITE,button("保存").getCurrentTextColor());
        java.lang.reflect.Field busy=RhHome.class.getDeclaredField("syncBusy");busy.setAccessible(true);busy.setBoolean(home,true);home.rebind();
        for(View v:views(root))if(v instanceof EditText||v instanceof Spinner||v instanceof CheckBox)assertFalse("Sync form must be read-only while running",v.isEnabled());
        assertFalse(button("保存").isEnabled());assertEquals("http://example.test/dav/",home.state().getJSONObject("syncDraft").getString("url"));
        try{home.perform("sync.edit",RhActions.obj("key","username","value","ignored"));fail("Busy edit accepted");}catch(IllegalStateException expected){}
        busy.setBoolean(home,false);
    }
    void addClaw(){ComputerDetails d=new ComputerDetails();d.uuid="claw";d.name="claw";d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.3",47989);home.update(d);}
    void choose(String uuid,int width){if(width>=700)described("选择设备 "+(uuid.equals("host")?"winserver":"claw")).performClick();else{Spinner chooser=(Spinner)described("切换远程设备");List<ComputerDetails> ordered=RhShared.ordered(activity,Arrays.asList(host("host","winserver"),host("claw","claw")));int index=ordered.get(0).uuid.equals(uuid)?0:1;chooser.setSelection(index);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}}
    ComputerDetails host(String uuid,String name){ComputerDetails d=new ComputerDetails();d.uuid=uuid;d.name=name;return d;}
    @Test public void deviceListAndNarrowChooserProtectIndependentDraftsAndKeepCategory()throws Exception{
        addClaw();
        for(int width:new int[]{1008,360}){
            home.perform("device.settings.open",RhActions.obj("uuid","host"));home.perform("settings.tab",RhActions.obj("tab","audio"));layout(width,800);
            String before=RhShared.canonical(RhShared.read(activity));home.perform("settings.input",RhActions.obj("key","volume","value","42"));choose("claw",width);
            assertEquals("unsaved-device",home.state().getJSONObject("navigationPrompt").getString("kind"));assertEquals("host",home.state().getString("selected"));
            if(width<700)assertEquals("winserver",((Spinner)described("切换远程设备")).getSelectedItem().toString());
            home.perform("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("42",home.state().getJSONObject("settingsInputs").getString("volume"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
            choose("claw",width);home.perform("navigation.resolve",RhActions.obj("choice","discard"));layout(width,800);assertEquals("claw",home.state().getString("selected"));assertEquals("audio",home.state().getString("profileTab"));assertFalse(home.state().getBoolean("settingsDirty"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));
            int savedVolume=width>=700?75:74;home.perform("settings.input",RhActions.obj("key","volume","value",Integer.toString(savedVolume)));choose("host",width);home.perform("navigation.resolve",RhActions.obj("choice","save"));layout(width,800);assertEquals(savedVolume,RhProfile.load(activity,"claw").volume);assertEquals(100,RhProfile.load(activity,"host").volume);assertEquals("host",home.state().getString("selected"));assertEquals("audio",home.state().getString("profileTab"));
            String saved=RhShared.canonical(RhShared.read(activity));home.perform("settings.input",RhActions.obj("key","volume","value","unfinished"));choose("claw",width);try{home.perform("navigation.resolve",RhActions.obj("choice","save"));fail("Invalid volume saved");}catch(RhSettingsDraft.Invalid expected){}
            assertEquals("host",home.state().getString("selected"));assertEquals("unfinished",home.state().getJSONObject("settingsInputs").getString("volume"));assertEquals(saved,RhShared.canonical(RhShared.read(activity)));
            button(width>=700?"概览":"‹ 概览").performClick();home.perform("navigation.resolve",RhActions.obj("choice","discard"));assertEquals("devices",home.state().getString("page"));assertEquals("host",home.state().getString("selected"));
        }
    }

}
