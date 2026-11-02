package com.limelight.rhremote;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.*;
import android.widget.Button;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real category buttons rebuild their own header; keyboard focus must follow that control. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w1200dp-h800dp-mdpi",shadows=RhAdmissionTest.Controls.class)
public final class RhCategoryFocusTest {
    private final RhAppearanceFlowTest fixture=new RhAppearanceFlowTest();
    @After public void cleanup(){fixture.cleanup();}
    private void layout(){View root=fixture.activity.findViewById(android.R.id.content);for(int i=0;i<3;i++){root.measure(View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,1200,800);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private void activate(String label){Button old=fixture.button(label);assertTrue(old.requestFocusFromTouch());assertTrue(old.hasFocus());assertTrue(old.performClick());layout();Button current=fixture.button(label);assertNotSame("Category header should have been rebuilt",old,current);if(!current.hasFocus()){View focused=fixture.activity.getCurrentFocus();boolean accepted=current.requestFocusFromTouch();fail(label+" lost keyboard focus after activation; selected="+current.isSelected()+", focusable="+current.isFocusable()+", previous="+(focused==null?"null":focused.getClass().getName()+"/"+focused.getTag())+", afterLayoutRequest="+accepted);}assertTrue(current.isSelected());assertFalse(old.hasFocus());}
    private View described(View v,String name){if(name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())&&v.getVisibility()==View.VISIBLE)return v;if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++){View found=described(((android.view.ViewGroup)v).getChildAt(i),name);if(found!=null)return found;}return null;}
    @Test public void deviceCategoriesKeepKeyboardFocusAndUnsavedInputs()throws Exception{
        for(boolean dark:new boolean[]{false,true}){fixture.create(dark);fixture.home.perform("devices.filter",RhActions.obj("query","win"));fixture.home.perform("device.settings.open",RhActions.obj("uuid","host"));fixture.home.perform("settings.input",RhActions.obj("key","fps","value","24"));String saved=RhShared.canonical(RhShared.read(fixture.activity));layout();
            for(String label:new String[]{"音频","输入","网络","高级","画面"}){activate(label);assertEquals("host",fixture.home.state().getString("selected"));assertEquals("win",fixture.home.state().getString("deviceQuery"));assertEquals("24",fixture.home.state().getJSONObject("settingsInputs").getString("fps"));assertEquals(saved,RhShared.canonical(RhShared.read(fixture.activity)));}fixture.home.dispose();}
    }
    @Test public void applicationCategoriesKeepKeyboardFocusThroughTheWholeStrip()throws Exception{
        for(boolean dark:new boolean[]{false,true}){fixture.create(dark);fixture.home.perform("app.settings.tab",RhActions.obj("tab","updates"));layout();for(String label:new String[]{"配置同步","外观","关于","更新"})activate(label);fixture.home.dispose();}
    }
    @Test public void aBlockedCategoryDoesNotTakeFocusFromTheUnsavedDialog()throws Exception{
        fixture.create(false);fixture.home.perform("app.settings.tab",RhActions.obj("tab","sync"));fixture.home.perform("sync.edit",RhActions.obj("key","url","value","http://fixture.example.test/dav/"));layout();Button about=fixture.button("关于");assertTrue(about.requestFocusFromTouch());about.performClick();layout();AlertDialog dialog=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertTrue(dialog.isShowing());assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).hasFocus());assertEquals("sync",fixture.home.state().getString("settingsTab"));assertTrue(fixture.home.state().getBoolean("syncDirty"));dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
    }
    @Test public void anUnfocusedCategoryActivationDoesNotCreateKeyboardFocus()throws Exception{
        fixture.create(false);fixture.home.perform("app.settings.tab",RhActions.obj("tab","updates"));layout();Button about=fixture.button("关于");assertFalse(about.hasFocus());about.performClick();layout();assertFalse("Touch/API activation should not create a new keyboard focus",fixture.button("关于").hasFocus());
    }
    @Test public void choosingAnotherControlBeforeLayoutWinsOverCategoryRestoration()throws Exception{
        fixture.create(false);fixture.home.perform("app.settings.tab",RhActions.obj("tab","updates"));layout();Button about=fixture.button("关于");assertTrue("Original category focus",about.requestFocusFromTouch());about.performClick();View root=fixture.activity.findViewById(android.R.id.content);View navigation=described(root,"添加电脑");assertNotNull(navigation);assertNotSame("Choose a different control from the automatic fallback",root.findFocus(),navigation);assertTrue("Replacement navigation request: "+navigation,navigation.requestFocusFromTouch());layout();assertTrue("Explicit new focus was lost",navigation.hasFocus());assertFalse("Category stole explicit navigation focus",fixture.button("关于").hasFocus());
    }
    @Test public void leavingBeforeTheNewHeaderIsLaidOutDoesNotRestoreItsFocus()throws Exception{
        fixture.create(false);fixture.home.perform("app.settings.tab",RhActions.obj("tab","updates"));layout();Button about=fixture.button("关于");assertTrue(about.requestFocusFromTouch());about.performClick();Button retired=fixture.button("关于");fixture.home.perform("devices.open",RhActions.obj());layout();assertEquals("devices",fixture.home.state().getString("page"));assertFalse(RhViewOrigin.visibleWithin(fixture.activity.findViewById(android.R.id.content),retired));assertFalse(retired.hasFocus());
    }
    private void focusBackground(Button button){android.graphics.drawable.Drawable background=button.getBackground();assertTrue(button.getText()+" has no keyboard focus drawable",background.isStateful());background.setState(new int[]{android.R.attr.state_enabled});android.graphics.drawable.Drawable normal=background.getCurrent();background.setState(new int[]{android.R.attr.state_enabled,android.R.attr.state_focused});assertNotSame(button.getText()+" has an invisible focus state",normal,background.getCurrent());background.setState(button.getDrawableState());}
    @Test public void categoryAndPresetFocusStylesSurviveSelectionRefresh()throws Exception{
        for(boolean dark:new boolean[]{false,true}){fixture.create(dark);fixture.home.perform("app.settings.tab",RhActions.obj("tab","updates"));layout();for(String name:new String[]{"更新","配置同步","外观","关于"})focusBackground(fixture.button(name));fixture.home.perform("device.settings.open",RhActions.obj("uuid","host"));layout();for(String name:new String[]{"画面","音频","输入","网络","高级","720P","1080P"})focusBackground(fixture.button(name));fixture.button("720P").performClick();for(String name:new String[]{"720P","1080P"})focusBackground(fixture.button(name));fixture.home.dispose();}
    }
}
