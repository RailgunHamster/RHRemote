package com.limelight.rhremote;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi")
public class RhStreamPanelTest {
    private FrameLayout root;
    private RhStreamPanel panel;
    private final List<String> actions=new ArrayList<>();
    private JSONObject arguments;
    private void setup(int width,int height){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        root=new FrameLayout(activity);activity.setContentView(root);
        panel=new RhStreamPanel(activity,root,(id,args)->{actions.add(id);arguments=args;});layout(width,height);panel.setExpanded(true);layout(width,height);
    }
    private void layout(int width,int height){for(int i=0;i<3;i++){root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    private Button button(View view,String title){if(view instanceof Button&&title.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button b=button(((ViewGroup)view).getChildAt(i),title);if(b!=null)return b;}return null;}
    private SeekBar volume(View view){if(view instanceof SeekBar)return (SeekBar)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){SeekBar found=volume(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    @Test public void keyboardVolumeCommitsThroughTheSameActionWithoutEchoingModelRefresh()throws Exception{
        setup(840,560);panel.setTab("audio");panel.update(false,false,false,40,RhActions.obj(),false,"");panel.updateControls(true,false,1,false,"","","");layout(840,560);
        SeekBar slider=volume(root);assertNotNull(slider);slider.setKeyProgressIncrement(5);assertTrue(slider.requestFocusFromTouch());actions.clear();
        assertTrue(slider.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)));
        slider.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(45,slider.getProgress());assertEquals(Collections.singletonList("stream.audio.volume"),actions);assertEquals(45,arguments.getInt("volume"));
        panel.update(false,false,false,65,RhActions.obj(),false,"");assertEquals("Refreshing from the model must not submit a second command",1,actions.size());assertEquals(65,slider.getProgress());
    }
    @Test public void accessibilityVolumeCommitsWithoutARequireTouchRelease()throws Exception{
        setup(840,560);panel.setTab("audio");panel.update(false,false,false,40,RhActions.obj(),false,"");panel.updateControls(true,false,1,false,"","","");layout(840,560);SeekBar slider=volume(root);actions.clear();
        android.os.Bundle value=new android.os.Bundle();value.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,55);
        assertTrue(slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),value));
        assertEquals(55,slider.getProgress());assertEquals(Collections.singletonList("stream.audio.volume"),actions);assertEquals(55,arguments.getInt("volume"));
    }
    @Test public void touchVolumeStillCommitsOnlyOnceOnRelease()throws Exception{
        setup(840,560);panel.setTab("audio");panel.update(false,false,false,40,RhActions.obj(),false,"");panel.updateControls(true,false,1,false,"","","");layout(840,560);SeekBar slider=volume(root);actions.clear();long time=android.os.SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(time,time+action*20,action,slider.getWidth()*(action==MotionEvent.ACTION_DOWN?.4f:.7f),slider.getHeight()/2f,0);try{assertTrue(slider.onTouchEvent(event));}finally{event.recycle();}if(action!=MotionEvent.ACTION_UP)assertTrue("Dragging must not commit on every movement",actions.isEmpty());}
        assertEquals(Collections.singletonList("stream.audio.volume"),actions);assertEquals(slider.getProgress(),arguments.getInt("volume"));assertTrue(slider.getProgress()>40);
    }
    private void assertInside(JSONObject layout,int width,int height)throws Exception{JSONObject p=layout.getJSONObject("panel");assertTrue(p.getInt("x")>=0);assertTrue(p.getInt("y")>=0);assertTrue(p.getInt("x")+p.getInt("width")<=width);assertTrue(p.getInt("y")+p.getInt("height")<=height);}
    @Test public void narrowPortraitAndLandscapeKeepButtonsAccessibleAndHandleFixed()throws Exception{
        setup(360,800);assertEquals(2,panel.layoutState().getInt("columns"));assertInside(panel.layoutState(),360,800);
        JSONObject before=panel.layoutState().getJSONObject("handle");panel.setExpanded(false);layout(360,800);
        assertEquals(before.toString(),panel.layoutState().getJSONObject("handle").toString());
        panel.setExpanded(true);panel.setTab("displays");
        JSONArray monitors=new JSONArray();for(int i=0;i<4;i++)monitors.put(RhActions.obj("id","display-"+i,"name","显示器 "+i,"available",true,"width",1920,"height",1080));
        panel.update(false,false,false,100,RhActions.obj("displays",monitors),false,"");
        layout(640,360);assertInside(panel.layoutState(),640,360);
        assertTrue(panel.layoutState().toString(),panel.layoutState().getBoolean("scrollable"));assertEquals(3,panel.layoutState().getInt("columns"));
        assertTrue(button(root,"自动选屏").getHeight()>=48);
    }
    @Test public void physicalAndVirtualScreensAndTabsKeepTheirActionArguments()throws Exception{
        setup(840,560);panel.setTab("displays");
        JSONArray displays=new JSONArray().put(RhActions.obj("id","physical","name","XG32UQ","available",true,"virtual",false,"width",3840,"height",2160)).put(RhActions.obj("id","virtual-id","virtual",true,"available",true,"width",2520,"height",1680));
        panel.update(false,false,false,100,RhActions.obj("displays",displays,"selected",displays.getJSONObject(0)),false,"");layout(840,560);
        Button physical=button(root,"XG32UQ · 3840×2160  ✓");assertNotNull(physical);physical.performClick();assertEquals("stream.display.select",actions.get(actions.size()-1));assertEquals("physical",arguments.getString("displayId"));
        assertNotNull(button(root,"虚拟屏 · 2520×1680"));button(root,"声音").performClick();assertEquals("stream.controls.tab",actions.get(actions.size()-1));assertEquals("audio",arguments.getString("tab"));
        panel.dispose();assertEquals(0,root.getChildCount());
    }
    @Test public void missingInventoryShowsErrorAndRefreshWithoutInventingAScreen()throws Exception{
        setup(360,800);panel.setTab("displays");panel.update(false,false,false,30,RhActions.obj("error","主机辅助服务未安装"),false,"");layout(360,800);
        assertNotNull(button(root,"刷新屏幕"));button(root,"刷新屏幕").performClick();assertEquals("stream.displays.refresh",actions.get(actions.size()-1));assertNull(button(root,"虚拟屏 · 2520×1680"));
    }
    private boolean inScroll(View view){for(ViewParent parent=view.getParent();parent instanceof View;parent=parent.getParent())if(parent instanceof ScrollView)return true;return false;}
    private int textCount(View view,String value){int count=view instanceof TextView&&((TextView)view).getText().toString().equals(value)?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=textCount(((ViewGroup)view).getChildAt(i),value);return count;}
    @Test public void rawTransportErrorsStayOutOfThePanelAndDarkButtonsUseReadableInk()throws Exception{
        setup(840,560);JSONObject original=RhShared.read(root.getContext());String raw="HTTP 409: {\"error\":\"The selected monitor is unavailable\"}";
        try{RhShared.apply(root.getContext(),new JSONObject(original.toString()).put("dark",true));panel.dispose();panel=new RhStreamPanel(root.getContext(),root,(id,args)->actions.add(id));panel.setExpanded(true);panel.setTab("displays");panel.updateControls(true,false,1,false,"displays","屏幕切换失败",raw);layout(840,560);assertEquals(0,textCount(root,raw));assertEquals(1,textCount(root,"屏幕切换失败"));assertEquals(android.graphics.Color.parseColor("#e1e8f6"),button(root,"刷新屏幕").getCurrentTextColor());assertEquals(View.VISIBLE,button(root,"详情").getVisibility());}finally{RhShared.apply(root.getContext(),original);}
    }
    @Test public void disconnectStaysOutsideScrollInEveryCategoryAndPendingOperationsKeepTheHandleUsable()throws Exception{
        setup(360,800);for(String tab:new String[]{"input","displays","audio"}){panel.setTab(tab);panel.updateControls(true,false,1,true,"displays","正在切换屏幕…","");layout(640,360);Button disconnect=button(root,"断开");assertFalse(inScroll(disconnect));assertTrue(disconnect.isEnabled());assertInside(panel.layoutState(),640,360);disconnect.performClick();assertEquals("stream.disconnect",actions.get(actions.size()-1));}
        panel.setTab("input");assertFalse(button(root,"Ctrl+Alt+Delete").isEnabled());assertTrue(button(root,"收起").isEnabled());button(root,"收起").performClick();assertEquals("stream.controls.toggle",actions.get(actions.size()-1));
    }
    @Test public void monitorRowsKeepFocusDuringUnrelatedUpdatesAndUnavailableRowsStayDisabled()throws Exception{
        setup(840,560);panel.setTab("displays");JSONArray monitors=new JSONArray().put(RhActions.obj("id","active","name","显示器","available",true,"width",1920,"height",1080)).put(RhActions.obj("id","missing","name","离线屏幕","available",false,"width",1920,"height",1080));JSONObject data=RhActions.obj("displays",monitors,"selected",monitors.getJSONObject(0));panel.update(false,false,false,100,data,false,"");Button first=button(root,"显示器 · 1920×1080  ✓"),missing=button(root,"离线屏幕 · 1920×1080  暂不可用");first.requestFocus();panel.update(true,false,false,30,data,false,"");assertSame(first,button(root,"显示器 · 1920×1080  ✓"));assertTrue(first.hasFocus());panel.updateControls(true,true,1,false,"audio","音量 30%","");assertFalse(missing.isEnabled());panel.updateControls(true,true,1,true,"displays","正在读取屏幕…","");assertFalse(first.isEnabled());assertFalse(missing.isEnabled());panel.updateControls(true,true,1,false,"displays","屏幕列表已刷新","");assertTrue(first.isEnabled());assertFalse(missing.isEnabled());
    }
    @Test public void feedbackDetailsStatsAndZoomReflectCurrentStateWithoutDuplicatingOtherGroups()throws Exception{
        setup(360,800);panel.setTab("input");panel.updateControls(true,true,2,false,"input","","快捷键发送失败");layout(360,800);assertTrue(button(root,"统计：开").isSelected());Button details=button(root,"详情");assertEquals(View.VISIBLE,details.getVisibility());details.performClick();assertEquals("stream.menu.open",actions.get(actions.size()-1));assertEquals("feedback",arguments.getString("menu"));panel.setTab("audio");panel.updateControls(true,true,2,false,"input","","快捷键发送失败");assertEquals(View.GONE,details.getVisibility());panel.updateControls(true,false,1,false,"audio","音量 10%","");assertFalse(button(root,"统计：关").isSelected());
    }
}
