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
    @Test public void windowCaptureShowsItsTitleAndDisablesDesktopSelection()throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();root=new FrameLayout(activity);activity.setContentView(root);panel=new RhStreamPanel(activity,root,(id,args)->actions.add(id));
        try{panel.setExpanded(true);panel.setTab("displays");panel.update(false,false,false,50,RhActions.obj("selected",RhActions.obj("kind","window","id",RhWindowsTest.SOURCE,"title","Editor","width",1200,"height",800),"displays",new JSONArray()),false,"");panel.updateControls(true,false,1,false,"displays","","");layout(840,560);
            assertNotNull(text(root,"Editor · 1200×800"));assertFalse(button(root,"窗口串流").isEnabled());View desktop=root.findViewWithTag("stream.display.select");assertNotNull(desktop);assertFalse(desktop.isEnabled());desktop.performClick();assertTrue(actions.isEmpty());
        }finally{panel.dispose();}
    }
    @Test public void firstExpansionKeepsTheWindowPaletteIfTheSavedThemeChanges()throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();boolean original=RhShared.dark(activity);
        try{
            RhShared.theme(activity,false);root=new FrameLayout(activity);activity.setContentView(root);panel=new RhStreamPanel(activity,root,(id,args)->actions.add(id));RhShared.theme(activity,true);panel.setExpanded(true);layout(840,560);
            assertEquals(android.graphics.Color.parseColor("#34415b"),button(root,"直接点击").getCurrentTextColor());assertEquals(android.graphics.Color.parseColor("#5274e8"),volume(root).getThumbTintList().getDefaultColor());panel.dispose();
            panel=new RhStreamPanel(activity,root,(id,args)->actions.add(id));panel.setExpanded(true);layout(840,560);assertEquals(android.graphics.Color.parseColor("#e1e8f6"),button(root,"直接点击").getCurrentTextColor());assertEquals(android.graphics.Color.parseColor("#6f8af1"),volume(root).getThumbTintList().getDefaultColor());assertTrue(actions.isEmpty());
        }finally{if(panel!=null)panel.dispose();RhShared.theme(activity,original);}
    }
    @Test public void hiddenPreparedControlsBindTheLatestModelAndAreReusedWithoutEmittingActions()throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();root=new FrameLayout(activity);activity.setContentView(root);panel=new RhStreamPanel(activity,root,(id,args)->actions.add(id));layout(840,560);
        SeekBar preparedVolume=volume(root);assertNotNull(preparedVolume);assertTrue(panel.layoutState().getBoolean("created"));assertFalse(panel.isExpanded());assertTrue(actions.isEmpty());
        try{
            JSONObject monitor=RhActions.obj("id","original","name","Original Screen","available",true,"width",1920,"height",1080),data=RhActions.obj("displays",new JSONArray().put(monitor),"selected",monitor);
            panel.setDeviceName("Current Device");panel.update(false,false,false,10,data,false,"");panel.update(true,true,true,42,data,false,"");monitor.put("name","Uncommitted Mutation");
            panel.updateControls(true,true,1.2f,false,"audio","Old result","");panel.updateFeedback("audio","Latest result","");panel.updateScrollMode(true);panel.updateFloatingKeyboard(true);panel.updateZoom(1.7f);panel.setTab("audio");
            assertTrue(panel.layoutState().getBoolean("created"));assertEquals(2,root.getChildCount());assertSame(preparedVolume,volume(root));assertFalse(panel.isExpanded());assertTrue(actions.isEmpty());
            panel.setExpanded(true);layout(840,560);assertTrue(panel.layoutState().getBoolean("created"));assertEquals("audio",panel.tab());assertEquals(42,volume(root).getProgress());assertNotNull(text(root,"Latest result"));assertNotNull(text(root,"Current Device"));assertNotNull(button(root,"声音：开"));
            assertTrue(((Switch)root.findViewWithTag("stream.scroll.set")).isChecked());assertTrue(((Switch)root.findViewWithTag("stream.keyboard.floating.set")).isChecked());assertTrue(actions.isEmpty());
            panel.setTab("displays");layout(840,560);assertNotNull(text(root,"缩放 170%"));assertNotNull(text(root,"Original Screen · 1920×1080"));assertNull(text(root,"Uncommitted Mutation · 1920×1080"));
        }finally{panel.dispose();assertEquals(0,root.getChildCount());}
    }
    @Test public void disposingCollapsedPreparedControlsCannotResurrectTheirViewsOrActions()throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();root=new FrameLayout(activity);activity.setContentView(root);panel=new RhStreamPanel(activity,root,(id,args)->actions.add(id));layout(840,560);View handle=panel.controlHandle();panel.dispose();
        panel.setExpanded(true);panel.setTab("audio");panel.updateControls(true,true,2,false,"audio","late","");panel.update(false,false,true,90,RhActions.obj(),false,"");panel.setDeviceName("Late");panel.updateScrollMode(true);panel.updateFloatingKeyboard(true);handle.performClick();
        assertEquals(0,root.getChildCount());assertFalse(panel.layoutState().getBoolean("created"));assertTrue(actions.isEmpty());panel.dispose();
    }
    @Test public void changingTabsRendersTheLatestFeedbackOnlyInItsOwnGroupAndKeepsClearedResultsCleared()throws Exception{
        setup(840,560);panel.updateFeedback("audio","Audio update failed","unavailable");assertNull(text(root,"Audio update failed"));
        panel.setTab("audio");assertNotNull(text(root,"Audio update failed"));panel.setTab("displays");assertNull(text(root,"Audio update failed"));
        panel.setTab("audio");assertNotNull(text(root,"Audio update failed"));panel.updateFeedback("audio","","");assertNull(text(root,"Audio update failed"));
        panel.setTab("input");panel.setTab("audio");assertNull("Cleared result returned after changing tabs",text(root,"Audio update failed"));assertTrue(actions.isEmpty());panel.dispose();
    }
    @Test public void screenTransformsStayInTheScreenPageAndRejectHiddenCallbacks()throws Exception{
        setup(840,560);panel.updateControls(true,false,1,false,"","","");
        Button pan=button(root,"移动画面"),reset=button(root,"还原缩放");
        assertNotNull(pan);assertNotNull(reset);
        assertFalse("Screen transforms must not compete with input controls",pan.isShown());
        assertFalse(reset.isShown());pan.performClick();reset.performClick();assertTrue(actions.isEmpty());
        panel.updateZoom(1.5f);panel.setTab("displays");layout(840,560);
        assertTrue(pan.isShown());assertTrue(reset.isShown());assertTrue(pan.isEnabled());assertTrue(reset.isEnabled());
        TextView zoom=text(root,"缩放 150%");assertNotNull("Screen page must show current scale",zoom);assertTrue(zoom.isShown());
        pan.performClick();reset.performClick();assertEquals(Arrays.asList("stream.pan.toggle","stream.zoom.reset"),actions);
        panel.setTab("audio");layout(840,560);pan.performClick();reset.performClick();assertEquals(2,actions.size());
        panel.setTab("displays");panel.updateControls(true,false,1.5f,true,"displays","正在切换屏幕…","");layout(840,560);
        assertFalse(pan.isEnabled());assertFalse(reset.isEnabled());pan.performClick();reset.performClick();assertEquals(2,actions.size());
        panel.dispose();
    }
    private TextView text(View view,String title){if(view instanceof TextView&&title.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=text(((ViewGroup)view).getChildAt(i),title);if(found!=null)return found;}return null;}
    @Test public void controlHandleKeepsItsTouchTargetWhenWarningsClearAndThePanelCollapses()throws Exception{
        setup(840,560);panel.updateNetworkWarning("画面更新较慢","测试提示");layout(840,560);
        JSONObject before=panel.layoutState().getJSONObject("handle");
        panel.setExpanded(false);panel.updateNetworkWarning("","");layout(840,560);
        assertEquals("Clearing a warning must not move or shrink the toggle",before.toString(),panel.layoutState().getJSONObject("handle").toString());
        panel.setExpanded(true);layout(840,560);
        assertEquals("Opening the panel uses the same touch target",before.toString(),panel.layoutState().getJSONObject("handle").toString());
    }
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
    private String boundsChain(View view){StringBuilder out=new StringBuilder();for(View v=view;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null)out.append(v.getClass().getSimpleName()).append(':').append(v.getWidth()).append('x').append(v.getHeight()).append('/').append(v.getMeasuredWidth()).append('x').append(v.getMeasuredHeight()).append(" visible=").append(v.getVisibility()).append(" layout=").append(v.isLayoutRequested()).append(';');return out.toString();}
    @Test @Config(sdk=35,qualifiers="mdpi") public void systemBarInsetsMoveTheCollapsedHandleWithoutResizingOrReplacingTheRootListener(){
        setup(840,560);panel.setExpanded(false);int[] rootCalls={0};androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{rootCalls[0]++;return insets;});
        androidx.core.view.WindowInsetsCompat shown=new androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(12,20,14,30)).build();
        androidx.core.view.WindowInsetsCompat returned=androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,shown);
        assertEquals(1,rootCalls[0]);assertEquals(20,returned.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top);assertEquals(840,root.getWidth());assertEquals(560,root.getHeight());
        assertEquals(28,((FrameLayout.LayoutParams)button(root,"控制").getLayoutParams()).topMargin);assertFalse(panel.isExpanded());assertTrue(actions.isEmpty());panel.dispose();
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,shown);assertEquals(2,rootCalls[0]);
    }
    @Test @Config(sdk=35,qualifiers="mdpi") public void imeInsetsKeepTheControlHandleReachableThroughShowHideAndRotation(){
        for(int[] size:new int[][]{{360,800,320},{840,360,230}}){
            org.robolectric.RuntimeEnvironment.setQualifiers("w"+size[0]+"dp-h"+size[1]+"dp-mdpi");
            setup(size[0],size[1]);panel.setExpanded(false);layout(size[0],size[1]);int[] rootCalls={0};
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{rootCalls[0]++;return insets;});
            View handle=panel.controlHandle();android.graphics.Rect before=new android.graphics.Rect();assertTrue(handle.getGlobalVisibleRect(before));
            for(boolean ime:new boolean[]{true,false,true,false}){
                androidx.core.view.WindowInsetsCompat insets=new androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(0,24,0,24))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.of(0,0,0,ime?size[2]:0))
                    .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(),ime).build();
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,insets);layout(size[0],size[1]);
                android.graphics.Rect visible=new android.graphics.Rect();assertTrue(handle.getGlobalVisibleRect(visible));
                assertEquals(handle.getWidth(),visible.width());assertEquals(handle.getHeight(),visible.height());
                assertTrue("Handle behind the IME",visible.bottom<=size[1]-(ime?size[2]:24));assertTrue(visible.top>=24);
                assertEquals("Insets must not resize the remote surface",size[1],root.getHeight());
                actions.clear();handle.performClick();assertEquals(Collections.singletonList("stream.controls.toggle"),actions);
            }
            assertEquals(4,rootCalls[0]);panel.dispose();
        }
    }
    @Test public void floatingKeyboardSwitchStaysVisibleAboveEveryControlTab()throws Exception{
        for(int[] size:new int[][]{{360,800},{640,360},{840,560}}){
            org.robolectric.RuntimeEnvironment.setQualifiers("w"+size[0]+"dp-h"+size[1]+"dp-mdpi");
            setup(size[0],size[1]);panel.updateControls(true,false,1,false,"","","");
            for(String tab:new String[]{"input","displays","audio"}){
                panel.setTab(tab);layout(size[0],size[1]);
                Switch toggle=root.findViewWithTag("stream.keyboard.floating.set");assertNotNull(toggle);
                android.graphics.Rect visible=new android.graphics.Rect();assertTrue(tab+"/"+Arrays.toString(size)+": toggle hidden",toggle.getGlobalVisibleRect(visible));
                assertEquals("Toggle must not be clipped horizontally",toggle.getWidth(),visible.width());assertEquals("Toggle must not be clipped vertically",toggle.getHeight(),visible.height());
                assertFalse(tab+": toggle must not require scrolling",inScroll(toggle));
                actions.clear();panel.updateFloatingKeyboard(false);assertTrue(actions.isEmpty());
                toggle.performClick();assertEquals(Collections.singletonList("stream.keyboard.floating.set"),actions);assertTrue(arguments.getBoolean("enabled"));
                panel.updateControls(true,false,1,true,"displays","正在切换屏幕…","");actions.clear();toggle.performClick();assertTrue(actions.isEmpty());
                panel.updateControls(true,false,1,false,"","","");
            }
            panel.dispose();
        }
    }
    @Test public void switchThumbsAndTracksRemainDistinctInBothThemes(){
        for(boolean dark:new boolean[]{false,true}){
            RhShared.theme(org.robolectric.RuntimeEnvironment.getApplication(),dark);setup(360,800);panel.updateControls(true,false,1,false,"","","");
            for(String id:new String[]{"stream.keyboard.floating.set","stream.scroll.set"}){
                Switch toggle=root.findViewWithTag(id);assertNotNull(toggle);assertTrue(toggle.getSwitchPadding()>=8);assertTrue(toggle.getSwitchMinWidth()>=44);
                for(int[] state:new int[][]{{},{android.R.attr.state_checked}}){
                    int thumb=toggle.getThumbTintList().getColorForState(state,0),track=toggle.getTrackTintList().getColorForState(state,0);
                    assertTrue("Switch state is difficult to distinguish: "+dark+"/"+id,androidx.core.graphics.ColorUtils.calculateContrast(thumb,track)>=3);
                }
            }
            panel.dispose();
        }
    }
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
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(time,time+action*20,action,slider.getWidth()*(action==MotionEvent.ACTION_DOWN?.4f:.7f),slider.getHeight()/2f,0);try{assertTrue("Volume touch "+action+", enabled="+slider.isEnabled()+", bounds="+slider.getWidth()+"x"+slider.getHeight()+", panel="+panel.layoutState(),slider.onTouchEvent(event));}finally{event.recycle();}if(action!=MotionEvent.ACTION_UP)assertTrue("Dragging must not commit on every movement",actions.isEmpty());}
        assertEquals(Collections.singletonList("stream.audio.volume"),actions);assertEquals(slider.getProgress(),arguments.getInt("volume"));assertTrue("Volume drag progress="+slider.getProgress()+", "+boundsChain(slider),slider.getProgress()>40);
    }
    @Test public void retiredPanelCallbacksCannotReachTheReplacementPanelActions()throws Exception{
        setup(840,560);panel.updateControls(true,false,1,false,"","","");
        Button oldDisconnect=button(root,"断开"),oldMode=button(root,"直接点击"),oldScroll=button(root,"滚动模式");assertNotNull(oldDisconnect);assertNotNull(oldMode);assertNotNull(oldScroll);
        panel.setTab("audio");layout(840,560);SeekBar oldVolume=volume(root);assertNotNull(oldVolume);long time=android.os.SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,oldVolume.getWidth()*.4f,oldVolume.getHeight()/2f,0);try{assertTrue(oldVolume.onTouchEvent(down));}finally{down.recycle();}
        panel.dispose();panel=new RhStreamPanel(root.getContext(),root,(id,args)->{actions.add(id);arguments=args;});panel.setExpanded(true);panel.updateControls(true,false,1,false,"","","");layout(840,560);actions.clear();
        try{
            oldDisconnect.performClick();oldMode.performClick();oldScroll.performClick();
            MotionEvent up=MotionEvent.obtain(time,time+100,MotionEvent.ACTION_UP,oldVolume.getWidth()*.7f,oldVolume.getHeight()/2f,0);try{oldVolume.onTouchEvent(up);}finally{up.recycle();}
            android.os.Bundle value=new android.os.Bundle();value.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,55);oldVolume.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),value);
            assertTrue("Retired controls dispatched to the shared action target: "+actions,actions.isEmpty());
            button(root,"直接点击").performClick();assertEquals(Collections.singletonList("stream.mode.toggle"),actions);
        }finally{panel.dispose();}
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
        assertTrue(panel.layoutState().toString(),panel.layoutState().getBoolean("scrollable"));assertEquals(2,panel.layoutState().getInt("columns"));
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
