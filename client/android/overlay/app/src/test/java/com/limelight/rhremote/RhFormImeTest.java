package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows=RhAdmissionTest.Controls.class)
public final class RhFormImeTest {
    public static class FocusedActivity extends Activity {@Override public boolean hasWindowFocus(){return true;}}
    private Activity activity;private RhHome home;private InputMethodManager imm;
    @Before public void setup(){activity=Robolectric.buildActivity(FocusedActivity.class).setup().get();activity.setTheme(R.style.RhTheme);home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError();}public void add(String address){throw new AssertionError("No lookup");}public void advanced(){}public void pair(ComputerDetails d,String pin){throw new AssertionError();}public void cancelPair(){}public void refresh(){}});imm=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);}
    @After public void cleanup(){home.dispose();activity.finish();}
    private EditText editor(View view){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof EditText)return(EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText result=editor(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}return null;}
    private void openKeyboard(){EditText edit=editor(activity.findViewById(android.R.id.content));assertNotNull(edit);edit.requestFocusFromTouch();imm.showSoftInput(edit,0);assertTrue(Shadows.shadowOf(imm).isSoftInputVisible());}
    @Test public void cancellingTheAddFormEndsItsKeyboard()throws Exception{home.perform("device.add.open",RhActions.obj());openKeyboard();home.perform("device.add.cancel",RhActions.obj());assertEquals("devices",home.state().getString("page"));assertFalse(Shadows.shadowOf(imm).isSoftInputVisible());}
    @Test public void editingAndFailedValidationKeepTheKeyboardAndInput()throws Exception{home.perform("device.add.open",RhActions.obj());openKeyboard();home.perform("device.add.input",RhActions.obj("address","bad address !"));assertTrue(Shadows.shadowOf(imm).isSoftInputVisible());try{home.perform("device.add.submit",RhActions.obj());fail("Invalid address accepted");}catch(java.io.IOException expected){}assertTrue(Shadows.shadowOf(imm).isSoftInputVisible());assertEquals("bad address !",home.state().getJSONObject("add").getString("address"));}
    @Test public void aDraftDecisionKeepsTheKeyboardUntilNavigationActuallyCompletes()throws Exception{home.perform("app.settings.tab",RhActions.obj("tab","sync"));openKeyboard();home.perform("sync.edit",RhActions.obj("key","url","value","https://example.invalid/dav"));home.perform("app.settings.tab",RhActions.obj("tab","updates"));assertFalse(home.state().isNull("navigationPrompt"));assertTrue(Shadows.shadowOf(imm).isSoftInputVisible());home.perform("navigation.resolve",RhActions.obj("choice","stay"));assertTrue(Shadows.shadowOf(imm).isSoftInputVisible());home.perform("app.settings.tab",RhActions.obj("tab","updates"));home.perform("navigation.resolve",RhActions.obj("choice","discard"));assertEquals("updates",home.state().getString("settingsTab"));assertFalse(Shadows.shadowOf(imm).isSoftInputVisible());}
}
