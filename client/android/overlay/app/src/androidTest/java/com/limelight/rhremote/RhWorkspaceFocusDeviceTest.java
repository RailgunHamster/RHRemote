package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
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

/** Real Home navigation and list updates, without host connections or OS input injection. */
@RunWith(AndroidJUnit4.class)
public final class RhWorkspaceFocusDeviceTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final String a="ui-workspace120-a",b="ui-workspace120-b",c="ui-workspace120-c",d="ui-workspace120-d",query="UI workspace120";
    private final JSONArray cases=new JSONArray(),focusEvents=new JSONArray();private Activity activity;private RhHome home;private File folder;private String phase="",currentTheme="";private int rowDetaches;
    private void main(Runnable work){instrument.runOnMainSync(work);instrument.waitForIdleSync();}
    private JSONObject state(){JSONObject[] value={null};main(()->{try{value[0]=home.state();}catch(Exception error){throw new RuntimeException(error);}});return value[0];}
    private void action(String id,JSONObject args){main(()->assertTrue(id,RhActions.dispatch(id,args).optBoolean("ok")));}
    private String name(String uuid){return query+" "+uuid.charAt(uuid.length()-1);}
    private ComputerDetails device(String uuid,boolean online){ComputerDetails value=new ComputerDetails();value.uuid=uuid;value.name=name(uuid);value.manualAddress=new ComputerDetails.AddressTuple("192.0.2.120",47989);value.state=online?ComputerDetails.State.ONLINE:ComputerDetails.State.OFFLINE;return value;}
    private View find(View root,String label){
        if(root.getVisibility()!=View.VISIBLE)return null;
        if(label.equals(root.getTag())||root.getContentDescription()!=null&&label.contentEquals(root.getContentDescription())||root instanceof Button&&label.contentEquals(((Button)root).getText()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View value=find(((ViewGroup)root).getChildAt(i),label);if(value!=null)return value;}
        return null;
    }
    private View view(String label){View result=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,result);return result;}
    private void focus(View target){main(()->{target.requestRectangleOnScreen(new Rect(0,0,target.getWidth(),target.getHeight()),true);assertTrue(target.requestFocusFromTouch());});main(()->assertSame(target,activity.getCurrentFocus()));}
    private void enter(View target){focus(target);long down=SystemClock.uptimeMillis();for(int phase:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP})main(()->target.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),phase,KeyEvent.KEYCODE_ENTER,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD)));}
    private void style(View target){main(()->{assertTrue(target.isEnabled());assertFalse(target.getDefaultFocusHighlightEnabled());Drawable background=target.getBackground();assertTrue("Native focus state missing",background.isStateful());background.setState(new int[]{android.R.attr.state_enabled});Drawable normal=background.getCurrent();background.setState(new int[]{android.R.attr.state_enabled,android.R.attr.state_focused});assertNotSame(normal,background.getCurrent());background.setState(target.getDrawableState());Rect area=new Rect();assertTrue(target.getGlobalVisibleRect(area));assertTrue(area.width()>0&&area.height()>0);});}
    private boolean fullyVisible(View target){Rect area=new Rect();return target.getGlobalVisibleRect(area)&&area.width()==target.getWidth()&&area.height()==target.getHeight();}
    private void expectFullyVisible(View target){main(()->{Rect area=new Rect();target.getGlobalVisibleRect(area);assertTrue("Focused row is clipped: "+area+" vs "+target.getWidth()+"x"+target.getHeight(),fullyVisible(target));});}
    private void arrowTo(View target,int key)throws Exception{
        View[] initial={null};main(()->initial[0]=activity.getCurrentFocus());
        for(int attempt=0;attempt<4;attempt++){
            boolean[] done={false};main(()->done[0]=activity.getCurrentFocus()==target&&fullyVisible(target));if(done[0])return;
            long down=SystemClock.uptimeMillis();for(int event:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP})main(()->activity.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),event,key,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD)));
            long until=SystemClock.uptimeMillis()+700;boolean[] ready={false};while(!ready[0]&&SystemClock.uptimeMillis()<until){main(()->{View actual=activity.getCurrentFocus();assertTrue("Arrow skipped a device row",actual==initial[0]||actual==target);ready[0]=actual==target&&fullyVisible(target);});if(!ready[0])Thread.sleep(20);}
        }
        main(()->assertSame("Arrow could not reach the next device row",target,activity.getCurrentFocus()));expectFullyVisible(target);
    }
    private void browseWithArrows(String theme)throws Exception{
        main(()->{try{JSONObject shared=RhShared.read(activity);for(String suffix:new String[]{"e","f","g","h"}){String id="ui-workspace120-"+suffix;shared.getJSONArray("devices").put(RhActions.obj("uuid",id,"name",name(id),"displayName","","address","192.0.2.120","addresses",new JSONArray()));}RhShared.apply(activity,shared);for(String suffix:new String[]{"e","f","g","h"})home.update(device("ui-workspace120-"+suffix,true));}catch(Exception error){throw new RuntimeException(error);}});
        listContext();String[] order={b,a,c,d,"ui-workspace120-e","ui-workspace120-f","ui-workspace120-g","ui-workspace120-h"};View first=view("选择设备 "+name(order[0]));focus(first);layoutReady(first);expectFullyVisible(first);
        for(int i=1;i<order.length;i++)arrowTo(view("选择设备 "+name(order[i])),KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("Arrows unexpectedly selected a device",a,state().getString("selected"));assertEquals(query,state().getString("deviceQuery"));capture("keyboard-last-"+theme);
        for(int i=order.length-2;i>=0;i--)arrowTo(view("选择设备 "+name(order[i])),KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(a,state().getString("selected"));capture("keyboard-first-"+theme);record("arrows-scroll-through-list-and-return",theme);
    }
    private InputConnection connection(EditText target){focus(target);InputConnection[] value={null};main(()->{value[0]=target.onCreateInputConnection(new EditorInfo());assertNotNull(value[0]);});return value[0];}
    private void layoutReady(View target)throws Exception{long until=SystemClock.uptimeMillis()+3000;boolean[] pending={true};while(pending[0]){main(()->pending[0]=target.isLayoutRequested()||activity.findViewById(android.R.id.content).isLayoutRequested());if(pending[0]){assertTrue("Native layout did not settle",SystemClock.uptimeMillis()<until);Thread.sleep(20);}}}
    private String focusLabel(View target){return target==null?"null":target.getContentDescription()!=null?target.getContentDescription().toString():String.valueOf(target.getTag());}
    private void expectRow(View target,String selected)throws Exception{JSONObject[] detail={null};main(()->{View actual=activity.getCurrentFocus();detail[0]=RhActions.obj("phase",phase,"theme",currentTheme,"focused",target.hasFocus(),"sameFocus",target==actual,"actual",focusLabel(actual),"expected",focusLabel(target),"visible",RhViewOrigin.visibleWithin(activity.findViewById(android.R.id.content),target),"laidOut",target.isLaidOut(),"layoutRequested",target.isLayoutRequested(),"attached",target.isAttachedToWindow(),"detaches",rowDetaches,"events",focusEvents);});RhShared.write(new File(folder,"focus-"+phase+"-"+currentTheme+".json"),detail[0]);assertTrue("Native row focus: "+detail[0],detail[0].getBoolean("sameFocus"));assertEquals(selected,state().getString("selected"));assertEquals(query,state().getString("deviceQuery"));assertEquals("devices",state().getString("page"));style(target);}
    private void listContext()throws Exception{action("devices.open",RhActions.obj());action("device.select",RhActions.obj("uuid",a));if(state().getJSONObject("homeLayout").getJSONObject("browser").optString("mode").equals("detail"))enter(view("返回设备"));assertEquals(a,state().optString("selected"));}
    private void record(String id,String theme){assertFalse(RhActions.hasStream());JSONObject layout=state().optJSONObject("homeLayout");String[] shell={""};main(()->shell[0]=view("nav:devices.open") instanceof Button?"bottom":"sidebar");cases.put(RhActions.obj("name",id,"theme",theme,"width",layout.optInt("width"),"height",layout.optInt("height"),"shell",shell[0],"passed",true,"testHostConnectionStarted",false));}
    private void capture(String name)throws Exception{Bitmap[] bitmap={null};main(()->{View content=activity.findViewById(android.R.id.content);bitmap[0]=Bitmap.createBitmap(content.getWidth(),content.getHeight(),Bitmap.Config.ARGB_8888);content.draw(new Canvas(bitmap[0]));});try(OutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(bitmap[0].compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap[0].recycle();}}
    private void exercise(String theme)throws Exception{
        currentTheme=theme;
        action("devices.open",RhActions.obj());action("devices.filter",RhActions.obj("query",query));EditText search=(EditText)view("搜索名称或地址");InputConnection retired=connection(search);enter(view("nav:app.settings.open"));assertEquals("global-settings",state().getString("page"));main(()->{assertTrue(retired.setSelection(0,search.length()));assertTrue(retired.commitText("retired filter",1));});assertEquals("Hidden search changed another page",query,state().getString("deviceQuery"));enter(view("nav:devices.open"));assertEquals(query,((EditText)view("搜索名称或地址")).getText().toString());record("hidden-search-isolated",theme);
        for(String id:new String[]{"device.add.open","app.settings.open","devices.open"}){View navigation=view("nav:"+id);enter(navigation);style(navigation);main(()->assertTrue(navigation.isSelected()));assertEquals(query,state().getString("deviceQuery"));}record("global-navigation-enter-and-focus",theme);capture("navigation-"+theme);
        listContext();View row=view("选择设备 "+name(b));main(()->{rowDetaches=0;row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View value){}public void onViewDetachedFromWindow(View value){rowDetaches++;}});activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((old,next)->focusEvents.put(RhActions.obj("phase",phase,"from",focusLabel(old),"to",focusLabel(next))));});focus(row);main(()->{phase="status";assertFalse(row.isSelected());home.update(device(a,false));});expectRow(row,a);record("status-keeps-row-focus",theme);
        main(()->{phase="discovery";assertNull("Each theme must start without the new device",find(activity.findViewById(android.R.id.content),"选择设备 "+name(d)));try{JSONObject shared=RhShared.read(activity);shared.getJSONArray("devices").put(RhActions.obj("uuid",d,"name",name(d),"displayName","","address","192.0.2.120","addresses",new JSONArray()));RhShared.apply(activity,shared);home.update(device(d,true));}catch(Exception error){throw new RuntimeException(error);}});layoutReady(row);expectRow(row,a);main(()->assertNotNull(view("选择设备 "+name(d))));record("discovery-keeps-row-focus",theme);
        int[] previousTop={0};main(()->{phase="reorder";previousTop[0]=row.getTop();});action("device.move",RhActions.obj("uuid",b,"direction","up"));layoutReady(row);expectRow(row,a);main(()->assertTrue("The focused row was not actually moved",row.getTop()<previousTop[0]));capture("row-focus-"+theme);expectFullyVisible(row);record("reorder-keeps-row-focus",theme);
        enter(row);assertEquals(b,state().getString("selected"));assertEquals(query,state().getString("deviceQuery"));if(state().getJSONObject("homeLayout").getJSONObject("browser").optString("mode").equals("detail"))enter(view("返回设备"));assertEquals(b,state().getString("selected"));assertEquals(query,state().getString("deviceQuery"));record("device-enter-and-detail-return",theme);
        EditText current=(EditText)view("搜索名称或地址");InputConnection live=connection(current);main(()->{assertTrue(live.setSelection(0,current.length()));assertTrue(live.commitText("no matching workspace120",1));});assertEquals("no matching workspace120",state().getString("deviceQuery"));main(()->{assertSame(current,activity.getCurrentFocus());assertFalse(RhViewOrigin.visibleWithin(activity.findViewById(android.R.id.content),row));});record("current-search-and-empty-state",theme);
        main(()->{assertTrue(live.setSelection(0,current.length()));assertTrue(live.commitText(query,1));home.update(device(c,false));});assertEquals(query,state().getString("deviceQuery"));main(()->assertSame("Refresh stole editor focus",current,activity.getCurrentFocus()));record("refresh-keeps-search-focus",theme);
        browseWithArrows(theme);
    }
    @Test public void workspaceNavigationPreservesVisibleKeyboardContext()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("workspaceFocusRun","");org.junit.Assume.assumeFalse("Explicit isolated run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,64}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhSessionFlowDeviceTest owner=new RhSessionFlowDeviceTest();owner.folder=folder;
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(owner,run)){
            main(()->{try{JSONObject shared=RhShared.read(context);for(String id:new String[]{a,b,c})shared.getJSONArray("devices").put(RhActions.obj("uuid",id,"name",name(id),"displayName","","address","192.0.2.120","addresses",new JSONArray()));RhShared.apply(context,shared);}catch(Exception error){throw new RuntimeException(error);}});
            String fixture=RhShared.read(context).toString();
            for(boolean dark:new boolean[]{false,true}){main(()->{try{JSONObject copy=new JSONObject(fixture);copy.put("dark",dark);RhShared.apply(context,copy);RhShared.theme(context,dark);}catch(Exception error){throw new RuntimeException(error);}});activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertFalse(state().getJSONObject("sync").optBoolean("automatic"));main(()->{for(String id:new String[]{a,b,c})home.update(device(id,true));});exercise(dark?"dark":"light");Activity closing=activity;main(closing::finish);long until=SystemClock.uptimeMillis()+5000;while(!closing.isDestroyed()){assertTrue(SystemClock.uptimeMillis()<until);Thread.sleep(20);}activity=null;}
            RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",cases.length()==18,"cases",cases,"version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"fontScale",context.getResources().getConfiguration().fontScale,"width",context.getResources().getDisplayMetrics().widthPixels,"height",context.getResources().getDisplayMetrics().heightPixels,"actualPcView",true,"nativeViewEnterEvents",true,"applicationInputConnections",true,"readinessFromFixture",true,"physicalKeyboardUsed",false,"systemInputInjected",false,"testHostConnectionStarted",false,"syncStarted",false));
        }
    }
}
