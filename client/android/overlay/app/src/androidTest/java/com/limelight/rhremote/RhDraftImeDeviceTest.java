package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real PcView, IME and dialog buttons on the owned emulator. No remote input. */
@RunWith(AndroidJUnit4.class)
public final class RhDraftImeDeviceTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Activity activity;private RhHome home;private File evidence;
    private final Set<WindowInsetsAnimationCompat> animations=new HashSet<>();
    private Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private JSONObject state(){JSONObject[] value={null};instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception error){throw new RuntimeException(error);}});return value[0];}
    private void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(id+": "+result.optString("error"),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    private View find(View view,String tag){if(!view.isShown())return null;if(tag.equals(view.getTag()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),tag);if(found!=null)return found;}return null;}
    private void visible(View view){Rect r=new Rect();assertTrue(view.getGlobalVisibleRect(r));assertTrue(view.getWidth()>0&&view.getHeight()>0);assertEquals(view.getWidth(),r.width());assertEquals(view.getHeight(),r.height());}
    private void click(String tag){instrument.runOnMainSync(()->{View view=find(activity.getWindow().getDecorView(),tag);assertNotNull(tag,view);visible(view);assertTrue(tag,view.isEnabled());assertTrue(tag,view.performClick());});instrument.waitForIdleSync();}
    @SuppressWarnings("unchecked") private EditText number(String key)throws Exception{return ((Map<String,EditText>)field(home,"numberEditors")).get(key);}
    private JSONObject keyboard(){JSONObject[] value={null};instrument.runOnMainSync(()->{WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());value[0]=RhActions.obj("known",insets!=null,"visible",insets!=null&&insets.isVisible(WindowInsetsCompat.Type.ime()),"animations",animations.size(),"windowFocus",activity.hasWindowFocus());});return value[0];}
    private void settled(boolean visible)throws Exception{long end=SystemClock.uptimeMillis()+5000;int stable=0;while(stable<3){JSONObject value=keyboard();stable=value.optBoolean("known")&&value.optBoolean("visible")==visible&&value.optInt("animations")==0&&value.optBoolean("windowFocus")?stable+1:0;assertTrue("IME did not settle to "+visible+": "+value,SystemClock.uptimeMillis()<end);Thread.sleep(25);}instrument.waitForIdleSync();}
    private void capture(String name)throws Exception{Bitmap[] value={null};instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();value[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(value[0]));});try(OutputStream out=new FileOutputStream(new File(evidence,name+".png"))){assertTrue(value[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{value[0].recycle();}}
    private JSONObject bounds(View view){Rect visible=new Rect();boolean shown=view.getGlobalVisibleRect(visible);int[] origin=new int[2];view.getLocationInWindow(origin);return RhActions.obj("shown",shown,"x",origin[0],"y",origin[1],"width",view.getWidth(),"height",view.getHeight(),"visibleX",visible.left,"visibleY",visible.top,"visibleWidth",visible.width(),"visibleHeight",visible.height());}
    private void settledEditor(EditText editor,View save)throws Exception{long end=SystemClock.uptimeMillis()+2000;int stable=0;while(stable<3){boolean[] full={false};instrument.runOnMainSync(()->{Rect a=new Rect(),b=new Rect();full[0]=editor.getGlobalVisibleRect(a)&&save.getGlobalVisibleRect(b)&&editor.getWidth()>0&&editor.getHeight()>0&&a.width()==editor.getWidth()&&a.height()==editor.getHeight()&&b.width()==save.getWidth()&&b.height()==save.getHeight();});stable=full[0]?stable+1:0;assertTrue("Editor or fixed save bar remains clipped",SystemClock.uptimeMillis()<end);Thread.sleep(25);}}

    @Test public void unsavedDecisionKeepsTheEditorOrEndsItsKeyboardAfterNavigation()throws Exception{
        assertTrue("Dedicated emulator required",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Context context=instrument.getTargetContext();String run=InstrumentationRegistry.getArguments().getString("caseId","");assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));evidence=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(evidence.exists());assertTrue(evidence.mkdirs());
        String choice=InstrumentationRegistry.getArguments().getString("draftChoice","");assertTrue(Arrays.asList("stay","discard","save","invalid","invalid-direct","invalid-cross","invalid-hidden").contains(choice));
        JSONObject before=RhShared.read(context);String canonical=RhShared.canonical(before);boolean oldDark=RhShared.dark(context);String theme=InstrumentationRegistry.getArguments().getString("theme",oldDark?"dark":"light");assertTrue(theme.equals("light")||theme.equals("dark"));RhShared.theme(context,theme.equals("dark"));
        SharedPreferences global=context.getSharedPreferences("rhremote.global",0);boolean hadCheck=global.contains("lastUpdateCheck");long oldCheck=global.getLong("lastUpdateCheck",0);global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
        try{
            activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();home=(RhHome)field(activity,"rhHome");
            instrument.runOnMainSync(()->ViewCompat.setWindowInsetsAnimationCallback(activity.getWindow().getDecorView(),new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){@Override public void onPrepare(WindowInsetsAnimationCompat a){if((a.getTypeMask()&WindowInsetsCompat.Type.ime())!=0)animations.add(a);}@Override public WindowInsetsCompat onProgress(WindowInsetsCompat i,List<WindowInsetsAnimationCompat> running){return i;}@Override public void onEnd(WindowInsetsAnimationCompat a){animations.remove(a);}}));
            String uuid=state().getJSONArray("devices").getJSONObject(0).getString("uuid");action("device.settings.open",RhActions.obj("uuid",uuid));
            boolean invalid=choice.startsWith("invalid");String key=invalid?"width":"volume";if(!invalid)action("settings.tab",RhActions.obj("tab","audio"));
            EditText editor=number(key);assertNotNull(editor);JSONObject expected=RhShared.read(context);int oldVolume=RhProfile.load(context,uuid).volume,newVolume=oldVolume==73?74:73;String changed=invalid?"":Integer.toString(newVolume);
            instrument.runOnMainSync(()->{editor.setText(changed);assertTrue(editor.requestFocusFromTouch());editor.requestRectangleOnScreen(new Rect(0,0,editor.getWidth(),editor.getHeight()),true);editor.post(()->((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(editor,0));});settled(true);assertTrue(state().getBoolean("settingsDirty"));
            if(choice.equals("invalid-cross")){action("settings.tab",RhActions.obj("tab","audio"));EditText audio=number("volume");instrument.runOnMainSync(()->{assertTrue(audio.requestFocusFromTouch());audio.post(()->((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(audio,0));});settled(true);}
            if(choice.equals("invalid-hidden")){instrument.runOnMainSync(()->((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0));settled(false);}
            if(choice.equals("invalid-direct")){Button save=(Button)field(home,"formSave");instrument.runOnMainSync(()->{visible(save);assertTrue(save.isEnabled());assertTrue(save.performClick());});instrument.waitForIdleSync();}
            else{click("nav:app.settings.open");assertEquals("unsaved-device",state().getJSONObject("navigationPrompt").getString("kind"));assertEquals("settings",state().getString("page"));
                AlertDialog dialog=(AlertDialog)field(home,"navigationDialog");assertNotNull(dialog);RhShared.write(new File(evidence,"dialog-keyboard.json"),keyboard());
                int button=choice.equals("stay")?AlertDialog.BUTTON_NEUTRAL:choice.equals("discard")?AlertDialog.BUTTON_NEGATIVE:AlertDialog.BUTTON_POSITIVE;
                instrument.runOnMainSync(()->{assertTrue(dialog.isShowing());Button control=dialog.getButton(button);visible(control);assertTrue(control.isEnabled());assertTrue(control.performClick());});instrument.waitForIdleSync();}
            RhShared.write(new File(evidence,"after-decision.json"),RhActions.obj("keyboard",keyboard(),"page",state().optString("page"),"settingsTab",state().optString("settingsTab"),"errorField",state().optString("settingsErrorField"),"choice",choice));
            if(choice.equals("stay")||invalid){
                assertEquals("settings",state().getString("page"));assertEquals(changed,state().getJSONObject("settingsInputs").getString(key));assertTrue(state().isNull("navigationPrompt"));settled(!choice.equals("invalid-hidden"));
                EditText current=number(key);try{settledEditor(current,(View)field(home,"formSave"));}finally{JSONObject homeLayout=state().optJSONObject("homeLayout");JSONObject[] layout={null};instrument.runOnMainSync(()->{try{layout[0]=RhActions.obj("editor",bounds(current),"save",bounds((View)field(home,"formSave")),"home",homeLayout);}catch(Exception error){throw new RuntimeException(error);}});RhShared.write(new File(evidence,"editor-layout.json"),layout[0]);capture("editor-layout");}
                instrument.runOnMainSync(()->{assertTrue("Editor focus must survive the decision",current.hasFocus());visible(current);try{visible((View)field(home,"formSave"));}catch(Exception error){throw new RuntimeException(error);}});
                if(invalid){assertEquals("width",state().getString("settingsErrorField"));assertEquals("video",state().getString("profileTab"));assertFalse(state().getString("settingsError").isEmpty());}
            }else{
                assertEquals("global-settings",state().getString("page"));assertEquals("updates",state().getString("settingsTab"));settled(false);
                instrument.runOnMainSync(()->{View primary=find(activity.getWindow().getDecorView(),"updates.primary");assertNotNull(primary);visible(primary);});
            }
            assertEquals(choice.equals("save")?newVolume:oldVolume,RhProfile.load(context,uuid).volume);
            if(choice.equals("save"))expected.getJSONObject("profiles").getJSONObject(RhShared.clientId(context)).getJSONObject(uuid).put("volume",newVolume);
            assertEquals("Only the explicitly saved value may change",RhShared.canonical(expected),RhShared.canonical(RhShared.read(context)));
            capture("after-decision");RhShared.write(new File(evidence,"result.json"),RhActions.obj("passed",true,"choice",choice,"theme",theme,"keyboard",keyboard(),"actualDialogButton",true,"savedValueVerified",true,"unrelatedSettingsUnchanged",true,"systemInputInjected",false,"remoteSessionStarted",false));
        }finally{
            try{if(activity!=null){instrument.runOnMainSync(()->{((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(),0);ViewCompat.setWindowInsetsAnimationCallback(activity.getWindow().getDecorView(),null);activity.finish();});long end=SystemClock.uptimeMillis()+3000;while(!activity.isDestroyed()){assertTrue("Activity cleanup",SystemClock.uptimeMillis()<end);Thread.sleep(25);}}instrument.waitForIdleSync();}
            finally{RhShared.apply(context,before);RhShared.theme(context,oldDark);if(hadCheck)global.edit().putLong("lastUpdateCheck",oldCheck).commit();else global.edit().remove("lastUpdateCheck").commit();assertEquals(oldDark,RhShared.dark(context));assertEquals("Original shared settings restored",canonical,RhShared.canonical(RhShared.read(context)));}
        }
    }
}
