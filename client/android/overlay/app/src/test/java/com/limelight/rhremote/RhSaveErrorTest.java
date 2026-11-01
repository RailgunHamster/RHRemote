package com.limelight.rhremote;

import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Long save errors must preserve text without hiding fields or the fixed actions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public final class RhSaveErrorTest {
    final RhSettingsViewportTest rig=new RhSettingsViewportTest();
    final String message=("保存失败，请检查目录和账户设置。\n").repeat(80)+"ERROR-END-185";
    @After public void finish(){rig.finish();}
    void error(boolean sync,String value)throws Exception{Field f=RhHome.class.getDeclaredField(sync?"syncError":"formError");f.setAccessible(true);f.set(rig.home,value);Method m=RhHome.class.getDeclaredMethod(sync?"refreshSyncFooter":"refreshProfileFooter");m.setAccessible(true);m.invoke(rig.home);}
    TextView label(boolean sync)throws Exception{return(TextView)rig.field(rig.home,sync?"syncErrorView":"formErrorView");}
    ScrollView details(boolean sync)throws Exception{for(ViewParent p=label(sync).getParent();p instanceof View;p=p.getParent()){if(p instanceof ScrollView)return(ScrollView)p;if(p==rig.field(rig.home,"formFooter"))break;}return null;}
    void open(boolean sync)throws Exception{if(sync){rig.home.perform("app.settings.tab",RhActions.obj("tab","sync"));rig.home.perform("sync.edit",RhActions.obj("key","username","value","unfinished-185"));}else{rig.home.perform("device.settings.open",RhActions.obj("uuid","settings-space"));rig.home.perform("settings.set",RhActions.obj("key","volume","value",51));}}
    @Test public void longErrorsKeepBodyAndSaveVisibleAndCancelKeepsSavedValues()throws Exception{
        for(boolean sync:new boolean[]{false,true})for(float font:new float[]{1,2})for(int[] size:new int[][]{{360,640},{640,360},{1008,640}}){
            rig.setup(font);String saved=RhShared.canonical(RhShared.read(rig.activity));open(sync);error(sync,message);rig.layout(size[0],size[1]);
            ScrollView body=(ScrollView)rig.field(rig.home,"scroll");assertTrue("Body collapsed: "+sync+"/"+font+"/"+size[0]+"x"+size[1]+" height="+body.getHeight(),body.getHeight()>=96);rig.inside((View)rig.field(rig.home,sync?"syncSave":"formSave"));
            ScrollView error=details(sync);assertNotNull("No bounded error viewport",error);assertTrue(error.getHeight()<=80);assertEquals(message,label(sync).getText().toString());
            error.scrollTo(0,100000);assertTrue(error.getScrollY()>0);assertTrue(sync?rig.home.state().getBoolean("syncDirty"):rig.home.state().getBoolean("settingsDirty"));
            Button cancel=sync?(Button)rig.field(rig.home,"syncCancel"):find((View)rig.field(rig.home,"formFooter"),"取消");assertNotNull(cancel);assertTrue(cancel.performClick());assertEquals(saved,RhShared.canonical(RhShared.read(rig.activity)));rig.finish();rig.home=null;rig.activity=null;
        }
    }
    Button find(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){Button v=find(((android.view.ViewGroup)view).getChildAt(i),text);if(v!=null)return v;}return null;}
    @Test public void repeatedMessageKeepsPositionAndNewOrEmptyMessageResetsIt()throws Exception{
        rig.setup(1);open(false);error(false,message);rig.layout(1008,640);ScrollView error=details(false);assertNotNull("Long error is not scrollable",error);error.scrollTo(0,100000);int bottom=error.getScrollY();assertTrue(bottom>0);
        error(false,message);rig.layout(1008,640);assertEquals(bottom,error.getScrollY());error(false,message+" changed");rig.layout(1008,640);assertEquals(0,error.getScrollY());error(false,"");rig.layout(1008,640);assertEquals(View.GONE,error.getVisibility());
    }
    @Test public void nativeKeyboardReadsTheErrorWithoutChangingTheDraft()throws Exception{
        rig.setup(1);open(false);error(false,message);rig.layout(1008,640);ScrollView error=details(false);assertNotNull(error);error.setSmoothScrollingEnabled(false);assertTrue(error.requestFocus());String draft=rig.home.state().getJSONObject("draft").toString();
        for(int key:new int[]{KeyEvent.KEYCODE_PAGE_DOWN,KeyEvent.KEYCODE_MOVE_END}){assertTrue(error.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key)));error.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));org.robolectric.shadows.ShadowLooper.idleMainLooper(300);assertTrue(error.getScrollY()>0);}
        assertTrue(error.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MOVE_HOME)));org.robolectric.shadows.ShadowLooper.idleMainLooper(300);assertEquals(0,error.getScrollY());assertEquals(draft,rig.home.state().getJSONObject("draft").toString());
    }
    @Test public void copyErrorKeepsItsActionsReachableAndCancelPreservesTheTargetDraft()throws Exception{
        for(float font:new float[]{1,2})for(int[] size:new int[][]{{360,640},{640,360},{1008,640}}){
            rig.setup(font);open(false);org.json.JSONObject shared=RhShared.read(rig.activity);RhProfile source=RhProfile.load(rig.activity,"settings-space");source.width=1280;source.height=720;
            shared.getJSONObject("profiles").put("source-185",RhActions.obj("settings-space",source.clientJson()));shared.getJSONObject("clients").put("source-185",RhActions.obj("name","测试平板","platform","android"));RhShared.apply(rig.activity,shared);
            String saved=RhShared.canonical(RhShared.read(rig.activity)),draft=rig.home.state().getJSONObject("draft").toString();rig.home.perform("settings.copy.preview",RhActions.obj("clientId","source-185"));
            ((TextView)rig.field(rig.home,"copyFailure")).setText(message);rig.layout(size[0],size[1]);ScrollView body=(ScrollView)rig.field(rig.home,"scroll");assertTrue("Copy body collapsed",body.getHeight()>=96);
            View footer=(View)rig.field(rig.home,"formFooter");Button apply=find(footer,"复制到草稿"),cancel=find(footer,"取消");assertNotNull(apply);assertNotNull(cancel);rig.inside(apply);rig.inside(cancel);
            ViewParent parent=((TextView)rig.field(rig.home,"copyFailure")).getParent();assertTrue("Copy error not bounded",parent instanceof ScrollView);((ScrollView)parent).scrollTo(0,100000);assertTrue(((ScrollView)parent).getScrollY()>0);
            assertTrue(cancel.performClick());assertEquals(draft,rig.home.state().getJSONObject("draft").toString());assertEquals(saved,RhShared.canonical(RhShared.read(rig.activity)));rig.finish();rig.home=null;rig.activity=null;
        }
    }
}
