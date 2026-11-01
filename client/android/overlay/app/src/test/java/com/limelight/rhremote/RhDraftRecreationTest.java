package com.limelight.rhremote;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Rebuild the actual Home using the same Bundle and retained-object hooks as PcView. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public final class RhDraftRecreationTest {
    private final RhAppearanceFlowTest fixture=new RhAppearanceFlowTest();
    private Bundle navigation;private Object retained;
    @Before public void setup(){fixture.create(false);}
    @After public void cleanup(){fixture.cleanup();}
    private void recreate()throws Exception{recreate(null);}
    private void recreate(Runnable beforeReplacement)throws Exception{
        navigation=new Bundle();fixture.home.saveNavigation(navigation);retained=fixture.home.retainUiState();if(beforeReplacement!=null)beforeReplacement.run();fixture.home.dispose();
        fixture.activity=Robolectric.buildActivity(Activity.class).setup().get();fixture.activity.setTheme(R.style.RhTheme);fixture.home=new RhHome(fixture.activity,fixture.callbacks);
        ComputerDetails host=new ComputerDetails();host.uuid="host";host.name="winserver";host.manualAddress=new ComputerDetails.AddressTuple("192.0.2.87",47989);fixture.home.update(host);
        fixture.home.restoreNavigation(navigation);fixture.home.restoreUiState(retained);
    }
    private void action(String id,JSONObject arguments)throws Exception{fixture.home.perform(id,arguments);}
    private EditText edit(View view,String label){if(view instanceof EditText&&label.contentEquals(view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText found=edit(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    @Test public void unsavedProfileErrorsAndThePendingDecisionSurviveRecreation()throws Exception{
        action("device.settings.open",RhActions.obj("uuid","host"));action("settings.input",RhActions.obj("key","volume","value","73"));action("settings.input",RhActions.obj("key","width","value",""));
        try{action("settings.save",RhActions.obj());fail("Invalid draft saved");}catch(IllegalArgumentException expected){}
        String saved=RhShared.canonical(RhShared.read(fixture.activity));String error=fixture.home.state().getString("settingsError");assertFalse(error.isEmpty());action("app.settings.tab",RhActions.obj("tab","about"));
        recreate();JSONObject state=fixture.home.state();assertEquals("settings",state.getString("page"));assertEquals("",state.getJSONObject("settingsInputs").getString("width"));assertEquals("73",state.getJSONObject("settingsInputs").getString("volume"));assertTrue(state.getBoolean("settingsDirty"));assertEquals(error,state.getString("settingsError"));assertFalse(state.isNull("navigationPrompt"));assertEquals(saved,RhShared.canonical(RhShared.read(fixture.activity)));
        action("navigation.resolve",RhActions.obj("choice","stay"));action("app.settings.tab",RhActions.obj("tab","about"));action("navigation.resolve",RhActions.obj("choice","discard"));assertEquals("about",fixture.home.state().getString("settingsTab"));fixture.home.restoreUiState(retained);assertEquals("global-settings",fixture.home.state().getString("page"));assertEquals(saved,RhShared.canonical(RhShared.read(fixture.activity)));
    }
    @Test public void anUnsavedDeviceNameSurvivesWithoutChangingTheSavedAlias()throws Exception{
        action("device.info.open",RhActions.obj("uuid","host"));action("device.info.input",RhActions.obj("displayName","Draft workstation"));String saved=RhShared.canonical(RhShared.read(fixture.activity));recreate();assertEquals("Draft workstation",fixture.home.state().getJSONObject("infoDraft").getString("displayName"));assertTrue(fixture.home.state().getBoolean("infoDirty"));assertEquals(saved,RhShared.canonical(RhShared.read(fixture.activity)));action("navigation.back",RhActions.obj());assertFalse(fixture.home.state().isNull("navigationPrompt"));action("navigation.resolve",RhActions.obj("choice","discard"));assertEquals("host",fixture.home.state().getString("selected"));assertEquals(saved,RhShared.canonical(RhShared.read(fixture.activity)));
    }
    @Test public void unsavedWebdavFieldsStayInMemoryAndOutOfTheNavigationBundle()throws Exception{
        action("app.settings.tab",RhActions.obj("tab","sync"));action("sync.edit",RhActions.obj("key","url","value","invalid-private-draft"));action("sync.edit",RhActions.obj("key","username","value","draft-user"));action("sync.edit",RhActions.obj("key","password","value","fixture-only-unsaved-password"));
        try{action("sync.save",RhActions.obj());fail("Invalid URL saved");}catch(Exception expected){}
        String error=fixture.home.state().getString("syncError");assertFalse(error.isEmpty());String saved=RhShared.canonical(RhShared.read(fixture.activity));recreate();
        JSONObject state=fixture.home.state();assertEquals("invalid-private-draft",state.getJSONObject("syncDraft").getString("url"));assertEquals("draft-user",state.getJSONObject("syncDraft").getString("username"));assertEquals("fixture-only-unsaved-password",edit(fixture.activity.findViewById(android.R.id.content),"密码").getText().toString());assertEquals(error,state.getString("syncError"));assertTrue(state.getBoolean("syncDirty"));assertFalse(navigation.toString().contains("private-draft"));assertFalse(navigation.toString().contains("unsaved-password"));assertFalse(state.getJSONObject("syncDraft").has("password"));action("sync.cancel",RhActions.obj());assertFalse(fixture.home.state().getBoolean("syncDirty"));assertEquals(saved,RhShared.canonical(RhShared.read(fixture.activity)));
    }
    @Test public void aRetainedDraftCannotOverwriteAnotherDeviceOrBeReplayedLater()throws Exception{
        action("device.settings.open",RhActions.obj("uuid","host"));action("settings.input",RhActions.obj("key","bitrateMbps","value","37"));Bundle saved=new Bundle();fixture.home.saveNavigation(saved);Object snapshot=fixture.home.retainUiState();fixture.home.dispose();
        fixture.activity=Robolectric.buildActivity(Activity.class).setup().get();fixture.home=new RhHome(fixture.activity,fixture.callbacks);
        for(String uuid:new String[]{"host","other"}){ComputerDetails device=new ComputerDetails();device.uuid=uuid;device.name=uuid;fixture.home.update(device);}saved.getBundle("rhremote.navigation").putString("selected","other");fixture.home.restoreNavigation(saved);fixture.home.restoreUiState(snapshot);
        assertEquals("other",fixture.home.state().getString("selected"));assertFalse(fixture.home.state().getJSONObject("settingsInputs").has("bitrateMbps"));assertFalse(fixture.home.state().getBoolean("settingsDirty"));
        action("device.settings.open",RhActions.obj("uuid","host"));fixture.home.restoreUiState(snapshot);assertFalse(fixture.home.state().getJSONObject("settingsInputs").has("bitrateMbps"));
    }
    @Test public void discardAfterARecreatedAppearanceDecisionDoesNotResurrectTheDraft()throws Exception{
        action("device.settings.open",RhActions.obj("uuid","host"));action("settings.tab",RhActions.obj("tab","audio"));action("settings.input",RhActions.obj("key","volume","value","73"));action("appearance.toggle",RhActions.obj());recreate();assertFalse(fixture.home.state().isNull("navigationPrompt"));action("navigation.resolve",RhActions.obj("choice","discard"));
        assertTrue(RhShared.dark(fixture.activity));assertEquals("settings",fixture.home.state().getString("page"));assertEquals("audio",fixture.home.state().getString("profileTab"));assertFalse(fixture.home.state().getBoolean("settingsDirty"));assertFalse(fixture.home.state().getJSONObject("settingsInputs").has("volume"));
    }
    @Test public void systemBundleAloneNeverRestoresAnUnsubmittedPassword()throws Exception{
        action("app.settings.tab",RhActions.obj("tab","sync"));action("sync.edit",RhActions.obj("key","password","value","fixture-only-unsaved-password"));recreate();action("sync.cancel",RhActions.obj());fixture.home.restoreNavigation(navigation);fixture.home.restoreUiState(null);assertEquals("",edit(fixture.activity.findViewById(android.R.id.content),"密码").getText().toString());assertFalse(fixture.home.state().getBoolean("syncDirty"));
    }
    @Test public void resumingTheRecreatedPageCannotClearARestoredValidationError()throws Exception{
        action("app.settings.tab",RhActions.obj("tab","sync"));action("sync.edit",RhActions.obj("key","url","value","invalid-private-draft"));try{action("sync.save",RhActions.obj());fail("Invalid URL saved");}catch(Exception expected){}
        String error=fixture.home.state().getString("syncError");assertFalse(error.isEmpty());recreate();fixture.home.resumed();assertEquals(error,fixture.home.state().getString("syncError"));
    }
    @Test public void aNewerBackgroundStateWinsOverTheOldValidationError()throws Exception{
        action("app.settings.tab",RhActions.obj("tab","sync"));action("sync.edit",RhActions.obj("key","url","value","invalid-private-draft"));try{action("sync.save",RhActions.obj());fail("Invalid URL saved");}catch(Exception expected){}
        assertFalse(fixture.home.state().getString("syncError").isEmpty());recreate(()->RhSyncWork.shared(fixture.activity).configured());fixture.home.resumed();assertEquals("",fixture.home.state().getString("syncError"));assertEquals("invalid-private-draft",fixture.home.state().getJSONObject("syncDraft").getString("url"));assertTrue(fixture.home.state().getBoolean("syncDirty"));
    }
}
