package com.limelight.rhremote;

import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public class RhAppearanceFlowTest {
    Activity activity;RhHome home;
    RhHome.Actions callbacks=new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError();}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}};
    void create(boolean dark){activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);RhShared.theme(activity,dark);home=new RhHome(activity,callbacks);ComputerDetails d=new ComputerDetails();d.uuid="host";d.name="winserver";d.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);home.update(d);}
    @After public void cleanup(){if(home!=null)home.dispose();}
    View find(View root,String label){if(root.getVisibility()!=View.VISIBLE)return null;if(root instanceof Button&&label.contentEquals(((Button)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}return null;}
    Button button(String label){View found=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,found);return (Button)found;}
    @Test public void lightweightNavigationRestoresTheTabDeviceAndSearchInEitherTheme()throws Exception{
        create(false);home.perform("devices.filter",RhActions.obj("query","win"));home.perform("device.select",RhActions.obj("uuid","host"));home.perform("app.settings.tab",RhActions.obj("tab","appearance"));Bundle saved=new Bundle();home.saveNavigation(saved);home.dispose();
        create(true);home.restoreNavigation(saved);assertEquals("global-settings",home.state().getString("page"));assertEquals("appearance",home.state().getString("settingsTab"));assertEquals("host",home.state().getString("selected"));assertEquals("win",home.state().getString("deviceQuery"));assertTrue(home.state().getJSONObject("saved").getBoolean("dark"));
    }
    @Test public void cleanDevicePagesAndTheirCategorySurviveNavigationRestore()throws Exception{
        for(String page:new String[]{"settings","device-info","add"}){
            create(false);
            if(page.equals("settings")){home.perform("device.settings.open",RhActions.obj("uuid","host"));home.perform("settings.tab",RhActions.obj("tab","audio"));}
            else if(page.equals("device-info"))home.perform("device.info.open",RhActions.obj("uuid","host"));
            else home.perform("device.add.open",RhActions.obj());
            Bundle saved=new Bundle();home.saveNavigation(saved);home.dispose();create(true);home.restoreNavigation(saved);
            assertEquals(page,home.state().getString("page"));
            if(page.equals("settings"))assertEquals("audio",home.state().getString("profileTab"));
            home.dispose();
        }
    }
    @Test public void appearanceActionPreservesCleanDeviceContextsAndTogglesOnlyOnce()throws Exception{
        create(false);
        for(String page:new String[]{"settings","device-info","add"}){
            if(page.equals("settings")){home.perform("device.settings.open",RhActions.obj("uuid","host"));home.perform("settings.tab",RhActions.obj("tab","audio"));}
            else if(page.equals("device-info"))home.perform("device.info.open",RhActions.obj("uuid","host"));
            else home.perform("device.add.open",RhActions.obj());
            boolean before=RhShared.dark(activity);home.perform("appearance.toggle",RhActions.obj());
            assertEquals(page,home.state().getString("page"));assertEquals(!before,RhShared.dark(activity));
            if(page.equals("settings"))assertEquals("audio",home.state().getString("profileTab"));
        }
    }
    @Test public void addInputAndFailureAreRetainedInMemoryButNotSerialized()throws Exception{
        create(false);home.perform("device.add.open",RhActions.obj());home.perform("device.add.input",RhActions.obj("address","private-host/path"));
        try{home.perform("device.add.submit",RhActions.obj());fail("invalid address accepted");}catch(java.io.IOException expected){}
        String failure=home.state().getJSONObject("add").getString("error");assertFalse(failure.isEmpty());
        home.perform("appearance.toggle",RhActions.obj());assertEquals("private-host/path",home.state().getJSONObject("add").getString("address"));assertEquals(failure,home.state().getJSONObject("add").getString("error"));
        Bundle navigation=new Bundle();home.saveNavigation(navigation);Object retained=home.retainUiState();assertFalse(navigation.toString().contains("private-host"));assertFalse(navigation.toString().contains(failure));
        home.dispose();create(true);home.restoreNavigation(navigation);assertEquals("",home.state().getJSONObject("add").getString("address"));home.restoreUiState(retained);
        assertEquals("private-host/path",home.state().getJSONObject("add").getString("address"));assertEquals(failure,home.state().getJSONObject("add").getString("error"));
    }
    @Test public void themeChangeProtectsInvalidDraftAndRestoresTheCategoryAfterDiscard()throws Exception{
        create(false);home.perform("device.settings.open",RhActions.obj("uuid","host"));home.perform("settings.tab",RhActions.obj("tab","audio"));home.perform("settings.input",RhActions.obj("key","fps","value","invalid"));
        home.perform("appearance.toggle",RhActions.obj());assertFalse(home.state().isNull("navigationPrompt"));assertFalse(RhShared.dark(activity));
        home.perform("navigation.resolve",RhActions.obj("choice","stay"));assertEquals("invalid",home.state().getJSONObject("settingsInputs").getString("fps"));assertFalse(RhShared.dark(activity));
        home.perform("appearance.toggle",RhActions.obj());try{home.perform("navigation.resolve",RhActions.obj("choice","save"));fail("invalid draft saved");}catch(IllegalArgumentException expected){}
        assertFalse(RhShared.dark(activity));assertEquals("invalid",home.state().getJSONObject("settingsInputs").getString("fps"));assertEquals("fps",home.state().getString("settingsErrorField"));
        home.perform("settings.tab",RhActions.obj("tab","audio"));home.perform("appearance.toggle",RhActions.obj());home.perform("navigation.resolve",RhActions.obj("choice","discard"));
        assertTrue(RhShared.dark(activity));assertEquals("settings",home.state().getString("page"));assertEquals("audio",home.state().getString("profileTab"));assertFalse(home.state().getBoolean("settingsDirty"));assertEquals(30,RhProfile.load(activity,"host").fps);
    }
    @Test public void navigationSnapshotDoesNotSerializeWebdavCredentialsOrFormValues()throws Exception{
        create(false);home.perform("app.settings.tab",RhActions.obj("tab","sync"));home.perform("sync.edit",RhActions.obj("key","url","value","https://private.example/dav"));home.perform("sync.edit",RhActions.obj("key","password","value","fixture-password"));Bundle saved=new Bundle();home.saveNavigation(saved);Bundle navigation=saved.getBundle("rhremote.navigation");assertNotNull(navigation);assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("version","page","tab","selected","profileTab","copyClient","sourcesExpanded","query","detail")),navigation.keySet());assertFalse(saved.toString().contains("fixture-password"));assertFalse(saved.toString().contains("private.example"));
    }
    @Test public void unsupportedNavigationDataCannotOpenAnActionOrDiscardAConfiguration()throws Exception{
        create(false);Bundle saved=new Bundle(),navigation=new Bundle();navigation.putInt("version",1);navigation.putString("page","pair");navigation.putString("tab","unsupported");navigation.putString("selected","unknown");navigation.putString("query","x".repeat(129));saved.putBundle("rhremote.navigation",navigation);home.restoreNavigation(saved);assertEquals("devices",home.state().getString("page"));assertEquals("",home.state().getString("deviceQuery"));assertEquals("updates",home.state().getString("settingsTab"));assertFalse(home.state().getBoolean("pairing"));
    }
    @Test public void disabledSyncButtonsHaveDistinctNativeStatesInBothThemes()throws Exception{
        for(boolean dark:new boolean[]{false,true}){create(dark);home.perform("app.settings.tab",RhActions.obj("tab","sync"));for(String label:new String[]{"立即同步","移除同步设置"}){Button button=button(label);assertFalse(button.isEnabled());assertEquals(android.graphics.Color.parseColor(dark?"#a4b1c8":"#69778e"),button.getCurrentTextColor());assertTrue(button.getTextColors().isStateful());assertTrue(button.getBackground().isStateful());}home.dispose();}
    }
    @Test public void confirmationDialogUsesTheSelectedLightOrDarkTheme()throws Exception{
        for(boolean dark:new boolean[]{false,true}){create(dark);home.perform("device.settings.open",RhActions.obj("uuid","host"));home.perform("settings.input",RhActions.obj("key","fps","value","24"));home.perform("app.settings.tab",RhActions.obj("tab","about"));AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();android.util.TypedValue color=new android.util.TypedValue();assertTrue(dialog.getContext().getTheme().resolveAttribute(android.R.attr.colorBackground,color,true));assertEquals(dark,android.graphics.Color.red(color.data)<128);Button primary=dialog.getButton(AlertDialog.BUTTON_POSITIVE);assertTrue(primary.getBackground().isStateful());android.graphics.drawable.Drawable current=primary.getBackground().getCurrent();assertTrue(current instanceof android.graphics.drawable.GradientDrawable);int fill=((android.graphics.drawable.GradientDrawable)current).getColor().getDefaultColor();assertNotEquals("The save action needs its own emphasis",color.data,fill);assertTrue("Primary action is unreadable",androidx.core.graphics.ColorUtils.calculateContrast(primary.getCurrentTextColor(),fill)>=4.5);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals("24",home.state().getJSONObject("settingsInputs").getString("fps"));home.dispose();}
    }
}
