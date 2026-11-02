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
    void forceLayout(View v){v.forceLayout();if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)forceLayout(((ViewGroup)v).getChildAt(i));}
    // A width transition may rebuild the form chrome during layout. Measure those new children too.
    void layout(int width,int height){for(int i=0;i<4;i++){forceLayout(root);root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    boolean inScroll(View v){for(android.view.ViewParent p=v.getParent();p instanceof View;p=p.getParent())if(p instanceof ScrollView)return true;return false;}
    void profile()throws Exception{home.perform("device.settings.open",RhActions.obj("uuid","host"));layout(360,800);}
    String settingLabel(String key)throws Exception{org.json.JSONArray fields=RhSettings.catalog(activity);for(int i=0;i<fields.length();i++)if(fields.getJSONObject(i).getString("key").equals(key))return fields.getJSONObject(i).getString("label");throw new AssertionError("Missing setting "+key);}
    private View content(){try{java.lang.reflect.Field f=RhHome.class.getDeclaredField("content");f.setAccessible(true);return (View)f.get(home);}catch(Exception e){throw new AssertionError(e);}}
    @Test public void automaticBitrateKeepsManualDraftAndDisablesTheActualEditor()throws Exception{
        profile();String label=settingLabel("bitrateMbps"),autoLabel=settingLabel("bitrateAuto");EditText manual=edit(label);manual.setText("41");
        CheckBox auto=null;for(View v:views(root))if(v instanceof CheckBox&&autoLabel.contentEquals(((CheckBox)v).getText()))auto=(CheckBox)v;assertNotNull(auto);auto.performClick();
        assertTrue(home.state().getJSONObject("draft").getBoolean("bitrateAuto"));assertFalse(edit(label).isEnabled());assertEquals("41",edit(label).getText().toString());
        for(String id:new String[]{"settings.input","settings.set","settings.step"})try{home.perform(id,RhActions.obj("key","bitrateMbps","value",id.equals("settings.input")?"13":13,"direction","up"));fail("Disabled manual editor accepted "+id);}catch(IllegalStateException expected){}
        home.perform("settings.tab",RhActions.obj("tab","audio"));home.perform("settings.tab",RhActions.obj("tab","video"));assertFalse(edit(label).isEnabled());
        home.perform("settings.cancel",RhActions.obj());assertFalse(RhProfile.load(activity,"host").bitrateAuto);
        profile();edit(label).setText("unfinished");home.perform("settings.set",RhActions.obj("key","bitrateAuto","value",true));assertFalse(edit(label).isEnabled());
        home.perform("settings.set",RhActions.obj("key","bitrateAuto","value",false));assertTrue(edit(label).isEnabled());assertEquals("unfinished",edit(label).getText().toString());
        button("保存").performClick();assertEquals("bitrateMbps",home.state().getString("settingsErrorField"));home.perform("settings.set",RhActions.obj("key","bitrateAuto","value",true));button("保存").performClick();
        assertTrue(RhProfile.load(activity,"host").bitrateAuto);assertEquals(25,RhProfile.load(activity,"host").bitrateMbps);
        secondDevice();assertFalse(RhProfile.load(activity,"retired-second").bitrateAuto);
        home.perform("device.settings.open",RhActions.obj("uuid","host"));assertTrue(home.state().getJSONObject("draft").getBoolean("bitrateAuto"));
    }
    @Test public void partialNumericInputSurvivesTabsAndInvalidSavePreservesTheForm()throws Exception{
        profile();String before=RhShared.canonical(RhShared.read(activity));
        String widthLabel=null;org.json.JSONArray catalog=RhSettings.catalog(activity);for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("key").equals("width"))widthLabel=catalog.getJSONObject(i).getString("label");
        EditText width=edit(widthLabel);width.requestFocus();width.setText("");assertSame(width,edit(widthLabel));assertTrue(width.hasFocus());assertEquals("",home.state().getJSONObject("settingsInputs").getString("width"));
        home.perform("settings.tab",RhActions.obj("tab","audio"));home.perform("settings.tab",RhActions.obj("tab","video"));assertEquals("",edit(widthLabel).getText().toString());
        button("保存").performClick();assertEquals("width",home.state().getString("settingsErrorField"));assertEquals("settings",home.state().getString("page"));assertEquals(before,RhShared.canonical(RhShared.read(activity)));assertEquals("",edit(widthLabel).getText().toString());
    }
    @Test public void failedSaveKeepsTheCurrentEditorAndItsCursor()throws Exception{
        profile();layout(840,700);String widthLabel=settingLabel("width");EditText original=edit(widthLabel);original.setText("1");assertTrue(original.requestFocusFromTouch());original.setSelection(1);
        button("保存").performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("width",home.state().getString("settingsErrorField"));assertSame("Validation must not replace an editor that is still being used",original,edit(widthLabel));assertTrue(original.hasFocus());assertEquals(1,original.getSelectionStart());assertEquals("1",original.getText().toString());
    }
    @Test public void leavingAnInvalidFormCancelsItsQueuedFocus()throws Exception{
        profile();EditText original=edit(settingLabel("width"));original.setText("");button("保存").performClick();
        home.perform("app.settings.open",RhActions.obj());home.perform("navigation.resolve",RhActions.obj("choice","discard"));View primary=root.findViewWithTag("updates.primary");assertNotNull(primary);assertTrue(primary.requestFocusFromTouch());
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue("A retired validation callback took focus from the new page",primary.hasFocus());assertEquals("updates",home.state().getString("settingsTab"));assertTrue(home.state().isNull("draft"));
    }
    @Test public void backDecisionSupportsStayDiscardAndSaveThroughTheSameActions()throws Exception{
        profile();home.perform("settings.input",RhActions.obj("key","bitrateMbps","value","37"));
        assertTrue(home.goBack());assertEquals("unsaved-device",home.state().getJSONObject("navigationPrompt").getString("kind"));
        AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(home.state().isNull("navigationPrompt"));assertEquals("37",home.state().getJSONObject("settingsInputs").getString("bitrateMbps"));
        home.perform("app.settings.tab",RhActions.obj("tab","about"));home.perform("navigation.resolve",RhActions.obj("choice","save"));assertEquals(37,RhProfile.load(activity,"host").bitrateMbps);assertEquals("about",home.state().getString("settingsTab"));
        profile();home.perform("settings.input",RhActions.obj("key","fps","value","24"));home.perform("devices.open",RhActions.obj());home.perform("navigation.resolve",RhActions.obj("choice","discard"));assertEquals("devices",home.state().getString("page"));assertEquals(30,RhProfile.load(activity,"host").fps);
    }
    private void secondDevice()throws Exception{ComputerDetails d=new ComputerDetails();d.uuid="retired-second";d.name="Claw";d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.87",47989);home.update(d);}
    @Test public void aRetiredInputConnectionCannotEditAnotherDevicesDraft()throws Exception{
        secondDevice();profile();EditText old=edit(settingLabel("bitrateMbps"));old.requestFocus();android.view.inputmethod.InputConnection connection=old.onCreateInputConnection(new android.view.inputmethod.EditorInfo());assertNotNull(connection);int original=RhProfile.load(activity,"host").bitrateMbps;
        home.perform("device.settings.open",RhActions.obj("uuid","retired-second"));EditText current=edit(settingLabel("bitrateMbps"));current.setText("22");connection.setSelection(0,old.length());connection.commitText("64",1);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("An old IME changed the current device", "22",home.state().getJSONObject("settingsInputs").getString("bitrateMbps"));assertEquals("22",current.getText().toString());
        android.view.inputmethod.InputConnection live=current.onCreateInputConnection(new android.view.inputmethod.EditorInfo());assertNotNull(live);live.setSelection(0,current.length());live.commitText("24",1);assertEquals("24",home.state().getJSONObject("settingsInputs").getString("bitrateMbps"));button("保存").performClick();assertEquals(24,RhProfile.load(activity,"retired-second").bitrateMbps);assertEquals(original,RhProfile.load(activity,"host").bitrateMbps);
    }
    @Test public void aRetiredSaveButtonCannotSaveTheReplacementDevicesDraft()throws Exception{
        secondDevice();profile();Button old=button("保存");home.perform("device.settings.open",RhActions.obj("uuid","retired-second"));edit(settingLabel("bitrateMbps")).setText("22");String saved=RhShared.canonical(RhShared.read(activity));old.performClick();
        assertEquals("An old button saved the replacement device", "settings",home.state().getString("page"));assertEquals("retired-second",home.state().getString("selected"));assertTrue(home.state().getBoolean("settingsDirty"));assertEquals(saved,RhShared.canonical(RhShared.read(activity)));button("保存").performClick();assertEquals(22,RhProfile.load(activity,"retired-second").bitrateMbps);
    }
    @Test public void aRetiredRouteCallbackCannotChangeTheReplacementDevice()throws Exception{
        secondDevice();profile();home.perform("settings.tab",RhActions.obj("tab","network"));CheckBox old=(CheckBox)described("连接路径：局域网");home.perform("device.settings.open",RhActions.obj("uuid","retired-second"));String routes=home.state().getJSONObject("draft").getJSONArray("routeOrder").toString();old.performClick();assertEquals("Old checkbox changed a new route list",routes,home.state().getJSONObject("draft").getJSONArray("routeOrder").toString());
        CheckBox current=(CheckBox)described("连接路径：局域网");current.performClick();assertNotEquals(routes,home.state().getJSONObject("draft").getJSONArray("routeOrder").toString());home.perform("settings.cancel",RhActions.obj());
    }
    @Test public void aRetiredSelectionCallbackCannotChangeTheReplacementDevice()throws Exception{
        secondDevice();profile();Spinner spinner=(Spinner)described(settingLabel("codec"));AdapterView.OnItemSelectedListener callback=spinner.getOnItemSelectedListener();int other=(spinner.getSelectedItemPosition()+1)%spinner.getCount();home.perform("device.settings.open",RhActions.obj("uuid","retired-second"));String codec=home.state().getJSONObject("draft").getString("codec");callback.onItemSelected(spinner,null,other,other);assertEquals("Old selection changed a new codec",codec,home.state().getJSONObject("draft").getString("codec"));
    }
    @Test public void retiredSaveDecisionCannotConfirmADifferentNavigation()throws Exception{
        profile();String saved=RhShared.canonical(RhShared.read(activity));home.perform("settings.input",RhActions.obj("key","bitrateMbps","value","37"));home.goBack();AlertDialog old=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();old.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        home.perform("app.settings.tab",RhActions.obj("tab","about"));AlertDialog current=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertNotSame(old,current);assertTrue(current.isShowing());old.getButton(AlertDialog.BUTTON_POSITIVE).performClick();old.cancel();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("Old save decision resolved the new prompt",current.isShowing());assertEquals("settings",home.state().getString("page"));assertEquals("37",home.state().getJSONObject("settingsInputs").getString("bitrateMbps"));assertEquals(saved,RhShared.canonical(RhShared.read(activity)));current.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals("about",home.state().getString("settingsTab"));assertEquals(saved,RhShared.canonical(RhShared.read(activity)));
    }
    private void seedSavedSyncAccount()throws Exception{
        // Only an already-saved account is needed here; no crypto or network is exercised.
        org.json.JSONObject account=RhActions.obj("url","http://example.test/isolated-dav/","username","fixture-user","ciphertext","fixture-not-decrypted","automatic",false,"intervalMinutes",15);RhShared.write(new java.io.File(activity.getFilesDir(),"webdav-local.json"),account);java.lang.reflect.Field syncField=RhHome.class.getDeclaredField("sync");syncField.setAccessible(true);java.lang.reflect.Field local=RhSync.class.getDeclaredField("local");local.setAccessible(true);local.set(syncField.get(home),account);
    }
    @Test public void changedWebdavAccountExplainsRequiredPasswordWithoutReplacingFocusedInputs()throws Exception{
        seedSavedSyncAccount();home.perform("app.settings.tab",RhActions.obj("tab","sync"));layout(360,800);
        EditText password=edit("密码"),url=edit("WebDAV 目录地址（HTTP / HTTPS）");
        assertEquals("已保存，留空保留",password.getHint().toString());assertTrue(home.state().getBoolean("syncPasswordReusable"));
        url.requestFocus();url.setText("https://another.test/dav/");
        assertSame(url,edit("WebDAV 目录地址（HTTP / HTTPS）"));assertSame(password,edit("密码"));assertTrue(url.hasFocus());
        assertEquals("请填写 WebDAV 密码",password.getHint().toString());assertFalse(home.state().getBoolean("syncPasswordReusable"));
        boolean note=false;for(View view:views(root))if(view instanceof TextView&&"地址或用户名已更改，请重新填写密码。".contentEquals(((TextView)view).getText()))note=true;assertTrue(note);
        button("保存").performClick();assertTrue(home.state().getString("syncError").contains("密码"));assertTrue(home.state().getBoolean("syncDirty"));assertSame(url,edit("WebDAV 目录地址（HTTP / HTTPS）"));assertSame(password,edit("密码"));assertEquals("https://another.test/dav/",url.getText().toString());
        assertEquals("http://example.test/isolated-dav/",home.state().getJSONObject("sync").getString("url"));
        home.perform("sync.cancel",RhActions.obj());assertEquals("已保存，留空保留",edit("密码").getHint().toString());assertTrue(home.state().getBoolean("syncPasswordReusable"));
        EditText user=edit("用户名"),samePassword=edit("密码");user.requestFocus();user.setText("another-user");assertSame(samePassword,edit("密码"));assertTrue(user.hasFocus());assertFalse(home.state().getBoolean("syncPasswordReusable"));assertEquals("请填写 WebDAV 密码",samePassword.getHint().toString());
    }
    @Test public void cancellationBeforeTheShowCallbackCannotAccessARetiredRemovalDialog()throws Exception{
        seedSavedSyncAccount();home.perform("app.settings.tab",RhActions.obj("tab","sync"));home.perform("sync.clear",RhActions.obj());AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertFalse(dialog.isShowing());assertTrue(home.state().isNull("syncClearPrompt"));assertTrue(home.state().getJSONObject("sync").getBoolean("hasPassword"));
    }
    @Test public void retiredRemovalDecisionCannotRemoveTheCurrentSavedAccount()throws Exception{
        seedSavedSyncAccount();
        home.perform("app.settings.tab",RhActions.obj("tab","sync"));home.perform("sync.clear",RhActions.obj());AlertDialog old=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();old.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        home.perform("sync.clear",RhActions.obj());AlertDialog current=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertNotSame(old,current);old.getButton(AlertDialog.BUTTON_POSITIVE).performClick();old.cancel();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("Old remove decision resolved the new prompt",current.isShowing());assertEquals("http://example.test/isolated-dav/",home.state().getJSONObject("sync").getString("url"));assertTrue(home.state().getJSONObject("sync").getBoolean("hasPassword"));current.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(home.state().isNull("syncClearPrompt"));assertTrue(home.state().getJSONObject("sync").getBoolean("hasPassword"));
    }
    @Test public void editorsFromAReplacedHomeCannotChangeTheCurrentDraft()throws Exception{
        profile();EditText retired=edit(settingLabel("bitrateMbps"));RhHome previous=home;
        try{
            home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError();}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}});ComputerDetails d=new ComputerDetails();d.uuid="host";d.name="winserver";d.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);home.update(d);root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);profile();EditText current=edit(settingLabel("bitrateMbps"));current.setText("22");
            retired.setText("64");assertEquals("Old editor reached the current draft","22",home.state().getJSONObject("settingsInputs").getString("bitrateMbps"));previous.dispose();retired.setText("66");assertEquals("22",home.state().getJSONObject("settingsInputs").getString("bitrateMbps"));current.setText("24");assertEquals("24",home.state().getJSONObject("settingsInputs").getString("bitrateMbps"));
        }finally{previous.dispose();}
    }
    @Test public void untouchedSettingsCanSelectADeviceWithoutLeavingAHiddenDraft()throws Exception{
        profile();home.perform("device.select",RhActions.obj("uuid","host"));assertEquals("devices",home.state().getString("page"));assertTrue(home.state().isNull("draft"));assertTrue(home.state().isNull("navigationPrompt"));
    }
    View described(String description){for(View v:views(root))if(v.getContentDescription()!=null&&description.contentEquals(v.getContentDescription()))return v;throw new AssertionError("Missing control "+description);}
    @Test public void routeCheckboxesKeepOnePathAndReorderingHasVisibleBoundaries()throws Exception{
        profile();String before=RhShared.canonical(RhShared.read(activity));
        home.perform("settings.set",RhActions.obj("key","routeOrder","value",new org.json.JSONArray().put("lan").put("relay")));home.perform("settings.tab",RhActions.obj("tab","network"));layout(360,800);
        assertTrue(described("拖动连接路径 局域网 排序").isEnabled());assertTrue(described("拖动连接路径 自建中继 排序").isEnabled());
        home.perform("settings.route.move",RhActions.obj("route","lan","targetRoute","relay","placement","after"));assertEquals("relay",home.state().getJSONObject("draft").getJSONArray("routeOrder").getString(0));
        CheckBox lan=(CheckBox)described("连接路径：局域网");assertTrue(lan.isChecked());lan.performClick();assertEquals(1,home.state().getJSONObject("draft").getJSONArray("routeOrder").length());
        CheckBox relay=(CheckBox)described("连接路径：自建中继");assertTrue(relay.isChecked());assertFalse(relay.isEnabled());assertTrue(described("拖动连接路径 自建中继 排序").isEnabled());
        ((CheckBox)described("连接路径：公网直连")).performClick();assertEquals(2,home.state().getJSONObject("draft").getJSONArray("routeOrder").length());assertTrue(described("连接路径：自建中继").isEnabled());
        button("取消").performClick();assertEquals(before,RhShared.canonical(RhShared.read(activity)));
    }
    @Test public void saveBarAndCategoryNavigationRemainOutsideTheScrollableBody()throws Exception{
        profile();for(int[] size:new int[][]{{360,800},{640,360},{1008,672}}){layout(size[0],size[1]);String sizeLabel=size[0]+"x"+size[1];Button save=button("保存"),back=button("概览");assertFalse("Save in body at "+sizeLabel,inScroll(save));assertFalse("Back in body at "+sizeLabel,inScroll(back));int[] location=new int[2];save.getLocationInWindow(location);assertTrue("Save clipped at "+sizeLabel+": y="+location[1]+", height="+save.getHeight(),location[1]>=0&&location[1]+save.getHeight()<=size[1]);assertTrue("Save too small at "+sizeLabel,save.getHeight()>=48);}
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
        for(View v:views(content()))if(v instanceof EditText||v instanceof Spinner||v instanceof CheckBox)assertFalse("Sync form must be read-only while running",v.isEnabled());
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
            button("概览").performClick();home.perform("navigation.resolve",RhActions.obj("choice","discard"));assertEquals("devices",home.state().getString("page"));assertEquals("host",home.state().getString("selected"));
        }
    }

}
