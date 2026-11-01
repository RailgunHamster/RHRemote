package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.*;
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

/** Actual PcView InputConnections and TextView key dispatch; no OS input injector. */
@RunWith(AndroidJUnit4.class)
public final class RhEditorSubmitDeviceTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final String first="ui-editor119-a",second="ui-editor119-b";private final JSONArray cases=new JSONArray();
    private Activity activity;private RhHome home;private File folder;
    private void main(Runnable work){instrument.runOnMainSync(work);instrument.waitForIdleSync();}
    private JSONObject state(){JSONObject[] value={null};main(()->{try{value[0]=home.state();}catch(Exception error){throw new RuntimeException(error);}});return value[0];}
    private void action(String id,JSONObject args){main(()->assertTrue(id,RhActions.dispatch(id,args).optBoolean("ok")));}
    private View find(View root,String label,boolean input){if(root.getVisibility()!=View.VISIBLE)return null;if(input&&root instanceof EditText&&label.contentEquals(root.getContentDescription())||!input&&root instanceof Button&&label.contentEquals(((Button)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View value=find(((ViewGroup)root).getChildAt(i),label,input);if(value!=null)return value;}return null;}
    private EditText editor(String label){EditText[] value={null};main(()->value[0]=(EditText)find(activity.findViewById(android.R.id.content),label,true));assertNotNull(label,value[0]);return value[0];}
    private void text(EditText editor,String value){main(()->{editor.requestRectangleOnScreen(new Rect(0,0,editor.getWidth(),editor.getHeight()),true);assertTrue(editor.requestFocusFromTouch());editor.setText(value);});}
    private InputConnection connection(EditText editor){InputConnection[] value={null};main(()->{assertTrue(editor.requestFocusFromTouch());value[0]=editor.onCreateInputConnection(new EditorInfo());assertNotNull(value[0]);});return value[0];}
    private void ime(InputConnection input,int action){main(()->assertTrue(input.performEditorAction(action)));}
    private EditText add(String value){action("device.add.open",RhActions.obj());EditText editor=editor("设备地址");text(editor,value);return editor;}
    private EditText info(String uuid,String value){action("device.info.open",RhActions.obj("uuid",uuid));EditText editor=editor("设备名称");text(editor,value);return editor;}
    private String saved()throws Exception{return RhShared.canonical(RhShared.read(activity));}
    private String error(){return state().optJSONObject("add").optString("error");}
    private void key(EditText editor,long down,int phase,int code,int flags,int repeat){main(()->editor.dispatchKeyEvent(new KeyEvent(down,down+repeat,phase,code,repeat,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,flags,InputDevice.SOURCE_KEYBOARD)));}
    private void record(String name,String theme){assertFalse(RhActions.hasStream());cases.put(RhActions.obj("name",name,"theme",theme,"passed",true,"source","actual PcView editor","testNetworkLookupStarted",false));}
    private void capture(String name)throws Exception{Bitmap[] bitmap={null};main(()->{View view=activity.findViewById(android.R.id.content);bitmap[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap[0]));});try(OutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(bitmap[0].compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap[0].recycle();}}
    private void exercise(String theme)throws Exception{
        EditText oldAdd=add("old invalid address !");InputConnection retiredAdd=connection(oldAdd);action("device.add.cancel",RhActions.obj());EditText current=add("new invalid address !");String before=saved();ime(retiredAdd,EditorInfo.IME_ACTION_DONE);assertEquals("Retired add completion submitted a replacement form","",error());assertEquals("new invalid address !",state().getJSONObject("add").getString("address"));assertEquals(before,saved());ime(connection(current),EditorInfo.IME_ACTION_DONE);assertFalse(error().isEmpty());assertEquals("editing",state().getJSONObject("add").getString("phase"));record("retired-add",theme);capture("invalid-add-"+theme);
        for(int id:new int[]{EditorInfo.IME_ACTION_NEXT,EditorInfo.IME_ACTION_PREVIOUS,EditorInfo.IME_ACTION_SEARCH,EditorInfo.IME_ACTION_GO,EditorInfo.IME_ACTION_SEND,EditorInfo.IME_NULL}){text(current,"unrelated action address !");ime(connection(current),id);assertEquals("Unrelated action submitted: "+id,"",error());}record("unrelated-actions",theme);
        text(current,"disabled address !");InputConnection disabled=connection(current);EditText target=current;main(()->target.setEnabled(false));ime(disabled,EditorInfo.IME_ACTION_DONE);assertEquals("",error());main(()->target.setEnabled(true));main(home::paused);ime(connection(current),EditorInfo.IME_ACTION_DONE);assertEquals("",error());main(home::resumed);text(current,"");ime(connection(current),EditorInfo.IME_ACTION_DONE);assertEquals("",error());record("disabled-background-and-empty-add",theme);
        text(current,"enter invalid address !");long down=SystemClock.uptimeMillis();key(current,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0,0);assertEquals("Enter down submitted","",error());key(current,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0,1);assertEquals("",error());key(current,down,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0,0);assertFalse(error().isEmpty());text(current,"orphan invalid address !");key(current,down,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0,0);assertEquals("Orphan Enter resubmitted","",error());record("enter-add-once",theme);action("device.add.cancel",RhActions.obj());
        EditText oldName=info(first,"Discarded name");InputConnection retiredName=connection(oldName);action("device.info.cancel",RhActions.obj());EditText name=info(second,"当前设备 "+theme);before=saved();ime(retiredName,EditorInfo.IME_ACTION_DONE);assertEquals("device-info",state().getString("page"));assertEquals(before,saved());assertEquals("当前设备 "+theme,state().getJSONObject("infoDraft").getString("displayName"));ime(connection(name),EditorInfo.IME_ACTION_DONE);assertEquals("当前设备 "+theme,RhShared.alias(activity,second));record("retired-name",theme);
        action("devices.filter",RhActions.obj("query","UI editor"));name=info(first,"before composition");InputConnection composed=connection(name);int composedLength=name.length();main(()->{assertTrue(composed.setSelection(0,composedLength));assertTrue(composed.setComposingText("研发😀 设备",1));assertTrue(composed.finishComposingText());});ime(composed,EditorInfo.IME_ACTION_DONE);assertEquals("研发😀 设备",RhShared.alias(activity,first));assertEquals("devices",state().getString("page"));assertEquals(first,state().getString("selected"));assertEquals("UI editor",state().getString("deviceQuery"));record("soft-name-composition",theme);capture("saved-name-"+theme);
        name=info(first,"Unsaved canceled Enter");before=saved();down=SystemClock.uptimeMillis();key(name,down,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0,0);key(name,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0,0);assertEquals("device-info",state().getString("page"));key(name,down,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,KeyEvent.FLAG_CANCELED,0);assertEquals(before,saved());down=SystemClock.uptimeMillis();key(name,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0,0);main(()->{View back=find(activity.findViewById(android.R.id.content),"返回设备",false);assertNotNull(back);assertTrue(back.requestFocusFromTouch());});connection(name);key(name,down,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0,0);assertEquals(before,saved());down=SystemClock.uptimeMillis();key(name,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0,0);main(home::paused);main(home::resumed);key(name,down,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0,0);assertEquals(before,saved());record("canceled-focus-lost-and-paused-enter",theme);
        down=SystemClock.uptimeMillis();key(name,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_NUMPAD_ENTER,0,0);assertEquals("device-info",state().getString("page"));key(name,down,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_NUMPAD_ENTER,0,0);assertEquals("Unsaved canceled Enter",RhShared.alias(activity,first));assertEquals("devices",state().getString("page"));record("numpad-name",theme);
    }
    @Test public void completionBelongsOnlyToTheCurrentEnabledForm()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("editorSubmitRun","");org.junit.Assume.assumeFalse("Explicit isolated run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,64}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhSessionFlowDeviceTest owner=new RhSessionFlowDeviceTest();owner.folder=folder;
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(owner,run)){
            main(()->{try{JSONObject shared=RhShared.read(context);for(String id:new String[]{first,second})shared.getJSONArray("devices").put(RhActions.obj("uuid",id,"name","UI editor "+id,"displayName","","address","192.0.2.119","addresses",new JSONArray()));RhShared.apply(context,shared);}catch(Exception error){throw new RuntimeException(error);}});
            for(boolean dark:new boolean[]{false,true}){RhShared.theme(context,dark);activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertFalse(state().getJSONObject("sync").optBoolean("automatic"));main(()->{for(String id:new String[]{first,second}){ComputerDetails device=new ComputerDetails();device.uuid=id;device.name="UI editor "+id;device.manualAddress=new ComputerDetails.AddressTuple("192.0.2.119",47989);home.update(device);}});exercise(dark?"dark":"light");Activity closing=activity;main(closing::finish);long until=SystemClock.uptimeMillis()+5000;while(!closing.isDestroyed()){assertTrue(SystemClock.uptimeMillis()<until);Thread.sleep(20);}activity=null;}
            RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",cases.length()==16,"cases",cases,"version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"fontScale",context.getResources().getConfiguration().fontScale,"actualPcView",true,"applicationInputConnections",true,"textViewKeyEvents",true,"physicalImeUsed",false,"systemInputInjected",false,"testNetworkLookupStarted",false,"syncStarted",false,"streamStarted",false));
        }
    }
}
