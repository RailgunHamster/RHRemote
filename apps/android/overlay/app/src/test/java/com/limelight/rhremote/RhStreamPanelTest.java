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
}
