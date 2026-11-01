package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.lang.reflect.Field;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native category controls and Enter events; no OS input injector or host connection. */
@RunWith(AndroidJUnit4.class)
public final class RhCategoryFocusDeviceTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final String uuid="ui-category-focus118";private final JSONArray cases=new JSONArray(),guards=new JSONArray();
    private Activity activity;private RhHome home;private File folder;
    private void main(Runnable work){instrument.runOnMainSync(work);instrument.waitForIdleSync();}
    private JSONObject state(){JSONObject[] value={null};main(()->{try{value[0]=home.state();}catch(Exception error){throw new RuntimeException(error);}});return value[0];}
    private void action(String id,JSONObject args){main(()->assertTrue(id,RhActions.dispatch(id,args).optBoolean("ok")));}
    private View find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
    private Button button(String label){Button value=(Button)find(activity.findViewById(android.R.id.content),label);assertNotNull(label,value);return value;}
    private String label(View view){return view==null?"null":view instanceof TextView?((TextView)view).getText().toString():view.getContentDescription()==null?view.getClass().getSimpleName():view.getContentDescription().toString();}
    private void capture(String name)throws Exception{Bitmap[] bitmap={null};main(()->{View view=activity.findViewById(android.R.id.content);bitmap[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap[0]));});try(OutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(bitmap[0].compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap[0].recycle();}}
    private void activate(String title,String tab,String scope,String theme)throws Exception{
        Button[] old={null};main(()->{old[0]=button(title);assertTrue(old[0].isEnabled());old[0].requestRectangleOnScreen(new Rect(0,0,old[0].getWidth(),old[0].getHeight()),true);assertTrue(old[0].requestFocusFromTouch());assertTrue(old[0].hasFocus());});
        String saved=RhShared.canonical(RhShared.read(activity));
        for(int phase:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP})main(()->{long now=SystemClock.uptimeMillis();assertTrue("Button Enter not handled",old[0].dispatchKeyEvent(new KeyEvent(now,now,phase,KeyEvent.KEYCODE_ENTER,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD)));});
        JSONObject current=state();assertEquals(tab,current.getString(scope.equals("device")?"profileTab":"settingsTab"));assertEquals(saved,RhShared.canonical(RhShared.read(activity)));
        JSONObject[] focus={null};main(()->{Button active=button(title);assertTrue("Category focus needs a visible native state",active.getBackground().isStateful());View actual=activity.getCurrentFocus();focus[0]=RhActions.obj("scope",scope,"tab",tab,"theme",theme,"focused",active.hasFocus(),"actualFocus",label(actual),"replacement",active!=old[0],"selected",active.isSelected(),"statefulFocusBackground",true);});RhShared.write(new File(folder,scope+"-"+tab+"-"+theme+"-focus.json"),focus[0]);
        assertTrue("Category lost native focus: "+focus[0],focus[0].getBoolean("focused"));assertTrue(focus[0].getBoolean("replacement"));assertTrue(focus[0].getBoolean("selected"));
        if(scope.equals("device")){assertEquals(uuid,current.getString("selected"));assertEquals("UI category",current.getString("deviceQuery"));assertEquals("24",current.getJSONObject("settingsInputs").getString("fps"));}
        main(()->{Button active=button(title);for(int direction:new int[]{View.FOCUS_FORWARD,View.FOCUS_BACKWARD}){View next=active.focusSearch(direction);assertNotNull("Native focus traversal has no next control",next);assertTrue(RhViewOrigin.visibleWithin(activity.findViewById(android.R.id.content),next));assertTrue(next.isEnabled());assertNotSame(active,next);}});
        capture(scope+"-"+tab+"-"+theme);cases.put(RhActions.obj("scope",scope,"tab",tab,"theme",theme,"replacementFocused",true,"buttonEnterUsed",true,"nativeFocusSearchBothDirections",true,"savedConfigurationUnchanged",true,"draftAndSelectionRetained",scope.equals("device")));
    }
    private View navigation(){return navigation("devices.open");}
    private View navigation(String id){View value=activity.findViewById(android.R.id.content).findViewWithTag("nav:"+id);assertNotNull(value);return value;}
    private void guardChecks(String theme)throws Exception{
        String saved=RhShared.canonical(RhShared.read(activity));
        action("app.settings.tab",RhActions.obj("tab","updates"));View[] moved={null};
        main(()->{Button about=button("关于");assertTrue(about.requestFocusFromTouch());about.performClick();moved[0]=navigation("device.add.open");assertNotSame(activity.getCurrentFocus(),moved[0]);assertTrue(moved[0].requestFocusFromTouch());});
        main(()->{assertSame("Explicit newer focus must win",moved[0],activity.getCurrentFocus());assertFalse(button("关于").hasFocus());});guards.put(RhActions.obj("theme",theme,"kind","explicit-focus-wins","passed",true));
        action("app.settings.tab",RhActions.obj("tab","updates"));main(()->{moved[0]=navigation();assertTrue(moved[0].requestFocusFromTouch());assertFalse(button("关于").hasFocus());button("关于").performClick();});
        main(()->{assertSame(moved[0],activity.getCurrentFocus());assertFalse(button("关于").hasFocus());});guards.put(RhActions.obj("theme",theme,"kind","unfocused-activation","passed",true));
        action("app.settings.tab",RhActions.obj("tab","updates"));Button[] retired={null};main(()->{Button about=button("关于");assertTrue(about.requestFocusFromTouch());about.performClick();retired[0]=button("关于");assertTrue(RhActions.dispatch("devices.open",RhActions.obj()).optBoolean("ok"));});
        assertEquals("devices",state().getString("page"));main(()->{assertFalse(RhViewOrigin.visibleWithin(activity.findViewById(android.R.id.content),retired[0]));assertFalse(retired[0].hasFocus());});guards.put(RhActions.obj("theme",theme,"kind","retired-header","passed",true));
        action("app.settings.tab",RhActions.obj("tab","sync"));action("sync.edit",RhActions.obj("key","url","value","http://fixture.example.test/dav/"));main(()->{Button about=button("关于");assertTrue(about.requestFocusFromTouch());about.performClick();});
        Field field=RhHome.class.getDeclaredField("navigationDialog");field.setAccessible(true);AlertDialog prompt=(AlertDialog)field.get(home);assertNotNull(prompt);main(()->{assertTrue(prompt.isShowing());assertTrue(prompt.getButton(AlertDialog.BUTTON_NEUTRAL).hasFocus());assertFalse(button("关于").isSelected());prompt.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();});assertTrue(state().isNull("navigationPrompt"));main(()->{assertFalse(prompt.isShowing());button("取消").performClick();});
        assertTrue(state().isNull("navigationPrompt"));assertFalse(state().getBoolean("syncDirty"));assertEquals(saved,RhShared.canonical(RhShared.read(activity)));guards.put(RhActions.obj("theme",theme,"kind","unsaved-prompt","passed",true));
    }
    @Test public void activatingCategoriesKeepsTheNativeKeyboardContext()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("categoryFocusRun","");org.junit.Assume.assumeFalse("Explicit isolated run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,64}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhSessionFlowDeviceTest owner=new RhSessionFlowDeviceTest();owner.folder=folder;
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(owner,run)){
            main(()->{try{JSONObject shared=RhShared.read(context);shared.getJSONArray("devices").put(RhActions.obj("uuid",uuid,"name","UI category fixture","displayName","","address","192.0.2.118","addresses",new JSONArray()));RhShared.apply(context,shared);}catch(Exception error){throw new RuntimeException(error);}});
            for(boolean dark:new boolean[]{false,true}){
                RhShared.theme(context,dark);activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertFalse(state().getJSONObject("sync").optBoolean("automatic"));main(()->{ComputerDetails device=new ComputerDetails();device.uuid=uuid;device.name="UI category fixture";device.manualAddress=new ComputerDetails.AddressTuple("192.0.2.118",47989);home.update(device);});String theme=dark?"dark":"light";
                action("devices.filter",RhActions.obj("query","UI category"));action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.input",RhActions.obj("key","fps","value","24"));
                String[] ids={"audio","input","network","advanced","video"},titles={"音频","输入","网络","高级","画面"};for(int i=0;i<ids.length;i++)activate(titles[i],ids[i],"device",theme);
                action("settings.cancel",RhActions.obj());action("app.settings.tab",RhActions.obj("tab","updates"));ids=new String[]{"sync","appearance","about","updates"};titles=new String[]{"配置同步","外观","关于","更新"};for(int i=0;i<ids.length;i++)activate(titles[i],ids[i],"application",theme);
                guardChecks(theme);
                Activity closing=activity;main(closing::finish);long until=SystemClock.uptimeMillis()+5000;while(!closing.isDestroyed()){assertTrue(SystemClock.uptimeMillis()<until);Thread.sleep(20);}activity=null;
            }
            RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",cases.length()==18&&guards.length()==8,"cases",cases,"guards",guards,"version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"fontScale",context.getResources().getConfiguration().fontScale,"actualPcView",true,"physicalKeyboardUsed",false,"systemInputInjected",false,"tabEventsInjected",false,"streamStarted",false,"syncStarted",false));
        }
    }
}
