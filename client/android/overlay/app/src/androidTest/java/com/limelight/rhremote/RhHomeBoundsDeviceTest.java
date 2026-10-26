package com.limelight.rhremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Runs against the installed app's real Android views, including system font scale and insets. */
@RunWith(AndroidJUnit4.class)
public final class RhHomeBoundsDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    private RhHome home;
    private int checked;
    private void action(String id,JSONObject args){instrumentation.runOnMainSync(()->{JSONObject response=RhActions.dispatch(id,args);assertTrue(response.toString(),response.optBoolean("ok"));});instrumentation.waitForIdleSync();}
    private void check(View view){
        if(view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof Button){Button button=(Button)view;View parent=(View)view.getParent();String label=button.getText().toString();int minimum=Math.round(48*view.getResources().getDisplayMetrics().density);
            assertTrue(label+" touch height "+view.getHeight(),view.getHeight()>=minimum);
            assertTrue(label+" left edge",view.getLeft()>=parent.getPaddingLeft());
            assertTrue(label+" right edge",view.getRight()<=parent.getWidth()-parent.getPaddingRight());
            assertTrue(label+" bottom edge",view.getBottom()<=parent.getHeight()-parent.getPaddingBottom());
            if(button.getLayout()!=null)assertTrue(label+" clipped text",button.getLayout().getHeight()+button.getCompoundPaddingTop()+button.getCompoundPaddingBottom()<=button.getHeight()+2);
            checked++;
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)check(((ViewGroup)view).getChildAt(i));
    }
    private void verify(){instrumentation.runOnMainSync(()->check(activity.findViewById(android.R.id.content)));}
    @Test public void everyHomeAndSettingsButtonStaysInsideItsContainer()throws Exception {
        Intent launch=new Intent().setClassName(instrumentation.getTargetContext(),"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity=instrumentation.startActivitySync(launch);instrumentation.waitForIdleSync();
        Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertNotNull(home);
        JSONObject[] state=new JSONObject[1];instrumentation.runOnMainSync(()->{try{state[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue("Configure at least one emulator device before this UI test",state[0].getJSONArray("devices").length()>0);
        String uuid=state[0].getJSONArray("devices").getJSONObject(0).getString("uuid");
        action("devices.open",RhActions.obj());verify();action("device.select",RhActions.obj("uuid",uuid));verify();
        for(String tab:new String[]{"updates","sync","appearance","about"}){action("app.settings.tab",RhActions.obj("tab",tab));verify();}
        action("device.settings.open",RhActions.obj("uuid",uuid));for(String tab:new String[]{"video","audio","input","network","advanced"}){action("settings.tab",RhActions.obj("tab",tab));verify();}
        action("settings.cancel",RhActions.obj());action("devices.filter",RhActions.obj("query","no-matching-device"));verify();action("devices.filter",RhActions.obj("query",""));action("device.select",RhActions.obj("uuid",uuid));
        JSONObject[] finalState=new JSONObject[1];instrumentation.runOnMainSync(()->{try{finalState[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});
        Bundle result=new Bundle();result.putString("stream","RHREMOTE_HOME_LAYOUT "+RhActions.obj("checkedButtons",checked,"fontScale",activity.getResources().getConfiguration().fontScale,"layout",finalState[0].getJSONObject("homeLayout"))+"\n");instrumentation.sendStatus(0,result);
    }
}
