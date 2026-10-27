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
    @Test public void navigationSnapshotDoesNotSerializeWebdavCredentialsOrFormValues()throws Exception{
        create(false);home.perform("app.settings.tab",RhActions.obj("tab","sync"));home.perform("sync.edit",RhActions.obj("key","url","value","https://private.example/dav"));home.perform("sync.edit",RhActions.obj("key","password","value","fixture-password"));Bundle saved=new Bundle();home.saveNavigation(saved);Bundle navigation=saved.getBundle("rhremote.navigation");assertNotNull(navigation);assertEquals(6,navigation.size());assertFalse(saved.toString().contains("fixture-password"));assertFalse(saved.toString().contains("private.example"));
    }
    @Test public void unsupportedNavigationDataCannotOpenAnActionOrDiscardAConfiguration()throws Exception{
        create(false);Bundle saved=new Bundle(),navigation=new Bundle();navigation.putInt("version",1);navigation.putString("page","pair");navigation.putString("tab","unsupported");navigation.putString("selected","unknown");navigation.putString("query","x".repeat(129));saved.putBundle("rhremote.navigation",navigation);home.restoreNavigation(saved);assertEquals("devices",home.state().getString("page"));assertEquals("",home.state().getString("deviceQuery"));assertEquals("updates",home.state().getString("settingsTab"));assertFalse(home.state().getBoolean("pairing"));
    }
    @Test public void disabledSyncButtonsHaveDistinctNativeStatesInBothThemes()throws Exception{
        for(boolean dark:new boolean[]{false,true}){create(dark);home.perform("app.settings.tab",RhActions.obj("tab","sync"));for(String label:new String[]{"立即同步","移除同步设置"}){Button button=button(label);assertFalse(button.isEnabled());assertEquals(android.graphics.Color.parseColor(dark?"#a4b1c8":"#69778e"),button.getCurrentTextColor());assertTrue(button.getTextColors().isStateful());assertTrue(button.getBackground().isStateful());}home.dispose();}
    }
    @Test public void confirmationDialogUsesTheSelectedLightOrDarkTheme()throws Exception{
        for(boolean dark:new boolean[]{false,true}){create(dark);home.perform("device.settings.open",RhActions.obj("uuid","host"));home.perform("settings.input",RhActions.obj("key","fps","value","24"));home.perform("app.settings.tab",RhActions.obj("tab","about"));AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();android.util.TypedValue color=new android.util.TypedValue();assertTrue(dialog.getContext().getTheme().resolveAttribute(android.R.attr.colorBackground,color,true));assertEquals(dark,android.graphics.Color.red(color.data)<128);assertEquals(android.graphics.Color.parseColor(dark?"#6f8af1":"#5274e8"),dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals("24",home.state().getJSONObject("settingsInputs").getString("fps"));home.dispose();}
    }
}
