package com.limelight.rhremote;

import android.app.*;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public class RhProfileCopyFlowTest {
    Activity activity;RhHome home;View root;
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError();}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}});
        ComputerDetails d=new ComputerDetails();d.uuid="host";d.name="winserver";d.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);home.update(d);
        JSONObject shared=RhShared.capture(activity,Collections.singletonList(d));RhProfile p=RhProfile.load(activity,"host");p.width=1280;p.height=720;p.bitrateMbps=42;p.routeOrder=new ArrayList<>(Arrays.asList("relay","lan"));p.relayUrl="https://relay.example:33449/";
        shared.getJSONObject("profiles").put("pad-1234",RhActions.obj("host",p.clientJson()));shared.getJSONObject("clients").put("pad-1234",RhActions.obj("name","OPPO 平板","platform","android"));RhShared.apply(activity,shared);
        root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);home.perform("device.settings.open",RhActions.obj("uuid","host"));
    }
    @After public void dispose(){home.dispose();}
    List<View> views(View v){List<View> out=new ArrayList<>();if(v.getVisibility()!=View.VISIBLE)return out;out.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)out.addAll(views(((ViewGroup)v).getChildAt(i)));return out;}
    Button button(String title){for(View v:views(root))if(v instanceof Button&&title.contentEquals(((Button)v).getText()))return(Button)v;throw new AssertionError("Missing "+title);}
    void click(String title){button(title).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    String saved()throws Exception{return RhShared.canonical(RhShared.read(activity));}
    void preview()throws Exception{home.perform("settings.tab",RhActions.obj("tab","advanced"));click("从其他客户端复制配置");click("查看配置");assertEquals("profile-copy",home.state().getString("page"));}
    void invalid()throws Exception{home.perform("settings.input",RhActions.obj("key","width","value","bad"));try{home.perform("settings.save",RhActions.obj());fail();}catch(Exception expected){}assertEquals("width",home.state().getString("settingsErrorField"));}
    @Test public void cancelPreviewPreservesErrorsRawInputAndNetworkDraftWithoutWriting()throws Exception{
        home.perform("settings.network.edit",RhActions.obj("key","address","value","draft.example"));invalid();String before=saved(),raw=home.state().getJSONObject("settingsInputs").toString(),error=home.state().getString("settingsError");preview();assertEquals("OPPO 平板 · 1234",home.state().getJSONObject("profileCopy").getString("sourceName"));assertTrue(home.state().getJSONObject("profileCopy").getJSONArray("fields").length()>20);
        assertTrue(home.goBack());assertEquals("settings",home.state().getString("page"));assertEquals("advanced",home.state().getString("profileTab"));assertEquals(raw,home.state().getJSONObject("settingsInputs").toString());assertEquals(error,home.state().getString("settingsError"));assertEquals("width",home.state().getString("settingsErrorField"));assertEquals("draft.example",home.state().getString("draftAddress"));assertEquals(before,saved());
    }
    @Test public void acceptedSnapshotClearsStaleErrorsPreservesHostFieldsAndSavesOnlyOnExplicitSave()throws Exception{
        home.perform("settings.set",RhActions.obj("key","swapButtons","value",true));home.perform("settings.network.edit",RhActions.obj("key","address","value","new.example"));home.perform("settings.input",RhActions.obj("key","displayName","value","工作机"));invalid();String before=saved();preview();
        JSONObject shared=RhShared.read(activity);shared.getJSONObject("profiles").getJSONObject("pad-1234").getJSONObject("host").put("width",2560);RhShared.apply(activity,shared);String afterRefresh=saved();
        click("复制到草稿");JSONObject state=home.state();assertEquals("settings",state.getString("page"));assertEquals(1280,state.getJSONObject("draft").getInt("width"));assertTrue(state.getJSONObject("draft").getBoolean("swapButtons"));assertEquals("",state.getString("settingsErrorField"));assertEquals("",state.getString("settingsError"));assertEquals(0,state.getJSONObject("settingsInputs").length());assertEquals("new.example",state.getString("draftAddress"));assertEquals("工作机",state.getString("draftDisplayName"));assertEquals("https://relay.example:33449/",state.getString("draftRelay"));assertEquals(afterRefresh,saved());assertNotEquals(before,afterRefresh);
        click("保存");assertEquals(1280,RhProfile.load(activity,"host").width);assertEquals(42,RhProfile.load(activity,"host").bitrateMbps);assertTrue(RhProfile.load(activity,"host").swapButtons);assertEquals("工作机",RhShared.alias(activity,"host"));assertEquals("new.example",RhShared.read(activity).getJSONArray("devices").getJSONObject(0).getString("address"));
    }
    @Test public void copyAndCancelOrLegacyActionHaveTheSamePersistenceBoundary()throws Exception{
        for(boolean legacy:new boolean[]{false,true}){String before=saved();invalid();if(legacy)home.perform("settings.copy_from",RhActions.obj("clientId","pad-1234"));else{preview();click("复制到草稿");}assertEquals("",home.state().getString("settingsErrorField"));click("取消");assertEquals(before,saved());home.perform("device.settings.open",RhActions.obj("uuid","host"));}
    }
    @Test public void leavingPreviewCanStayDiscardOrSaveTheOriginalDraftWithoutAcceptingSource()throws Exception{
        for(String choice:new String[]{"stay","discard","save"}){
            home.perform("settings.input",RhActions.obj("key","bitrateMbps","value","37"));preview();home.perform("app.settings.tab",RhActions.obj("tab","about"));home.perform("navigation.resolve",RhActions.obj("choice",choice));
            if(choice.equals("stay")){assertEquals("profile-copy",home.state().getString("page"));home.perform("settings.copy.cancel",RhActions.obj());home.perform("settings.cancel",RhActions.obj());}
            else{assertEquals("global-settings",home.state().getString("page"));assertTrue(home.state().isNull("profileCopy"));assertEquals(choice.equals("save")?37:25,RhProfile.load(activity,"host").bitrateMbps);assertNotEquals(1280,RhProfile.load(activity,"host").width);}
            home.perform("device.settings.open",RhActions.obj("uuid","host"));
        }
    }
    @Test public void invalidSourceAndStaleFormActionsLeaveThePreviewAndDraftIntact()throws Exception{
        home.perform("settings.input",RhActions.obj("key","fps","value","24"));preview();String original=home.state().getJSONObject("profileCopy").toString();
        for(String id:new String[]{"settings.save","settings.cancel","settings.tab","settings.copy_from"}){try{home.perform(id,RhActions.obj("tab","audio","clientId","pad-1234"));fail(id);}catch(IllegalStateException expected){}assertEquals(original,home.state().getJSONObject("profileCopy").toString());}
        for(String client:new String[]{"missing",RhShared.clientId(activity)}){try{home.perform("settings.copy.preview",RhActions.obj("clientId",client));fail(client);}catch(IllegalArgumentException expected){}assertEquals(original,home.state().getJSONObject("profileCopy").toString());assertEquals("24",home.state().getJSONObject("settingsInputs").getString("fps"));}
        home.rebind();assertEquals("profile-copy",home.state().getString("page"));assertNotNull(button("复制到草稿"));
    }
    @Test public void previewFooterRemainsReachableAndIdenticalCopyNeedsNoSave()throws Exception{
        JSONObject shared=RhShared.read(activity);shared.getJSONObject("profiles").getJSONObject("pad-1234").put("host",RhProfile.load(activity,"host").clientJson());RhShared.apply(activity,shared);preview();
        for(int[] size:new int[][]{{360,800},{640,360},{1008,672}}){for(int i=0;i<4;i++){root.requestLayout();root.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));root.layout(0,0,size[0],size[1]);}Button apply=button("复制到草稿");int[] point=new int[2];apply.getLocationInWindow(point);assertTrue(point[1]>=0&&point[1]+apply.getHeight()<=size[1]);for(android.view.ViewParent p=apply.getParent();p instanceof View;p=p.getParent())assertFalse(p instanceof ScrollView);}
        click("复制到草稿");assertFalse(home.state().getBoolean("settingsDirty"));assertEquals("与当前配置相同",home.state().getString("profileCopyNotice"));assertFalse(button("保存").isEnabled());
    }
    @Test public void themeNavigationProtectsTheOriginalDraftAndLeavesNoEmptyPreview()throws Exception{
        boolean dark=RhShared.dark(activity);home.perform("settings.input",RhActions.obj("key","fps","value","24"));preview();home.perform("appearance.toggle",RhActions.obj());assertFalse(home.state().isNull("navigationPrompt"));assertEquals(dark,RhShared.dark(activity));home.perform("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("profile-copy",home.state().getString("page"));home.perform("appearance.toggle",RhActions.obj());home.perform("navigation.resolve",RhActions.obj("choice","discard"));assertEquals("devices",home.state().getString("page"));assertTrue(home.state().isNull("profileCopy"));assertNotEquals(dark,RhShared.dark(activity));assertEquals(30,RhProfile.load(activity,"host").fps);
    }
}
