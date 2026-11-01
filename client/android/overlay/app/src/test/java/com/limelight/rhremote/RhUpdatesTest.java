package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** The real feed parser and update page, replacing only HTTP and the local control listener. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhUpdatesTest.Requests.class,RhUpdatesTest.Controls.class})
public class RhUpdatesTest {
    @Implements(value=RhActions.class,isInAndroidSdk=false)
    public static class Controls {
        @Implementation protected static void initialize(Context context,RhActions.Target target){}
    }
    @Implements(value=RhUpdates.class,isInAndroidSdk=false)
    public static class Requests {
        static String feed;static int count;static boolean failFirst;
        @Implementation protected static HttpURLConnection open(String address)throws IOException {
            final boolean failed=feed==null||(failFirst&&count==0);count++;
            return new HttpURLConnection(new URL(address)) {
                @Override public int getResponseCode(){return failed?503:200;}
                @Override public InputStream getInputStream(){return new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8));}
                @Override public void connect(){}
                @Override public void disconnect(){}
                @Override public boolean usingProxy(){return false;}
            };
        }
    }
    Activity activity;RhHome home;RhUpdates updates;
    @Before public void setup() {
        Requests.feed=null;Requests.count=0;Requests.failFirst=false;
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("rhremote.update-source",0).edit().clear().commit();
        home=new RhHome(activity,new RhHome.Actions(){
            public void connect(com.limelight.nvstream.http.ComputerDetails d){}
            public void add(String address){}public void advanced(){}
            public void pair(com.limelight.nvstream.http.ComputerDetails d,String pin){}
            public void cancelPair(){}public void refresh(){}
        });
        updates=ReflectionHelpers.getField(home,"updates");
    }
    private String page()throws Exception {
        home.perform("app.settings.open",RhActions.obj());
        StringBuilder text=new StringBuilder();collect(activity.getWindow().getDecorView(),text);return text.toString();
    }
    private void collect(View view,StringBuilder text) {
        if(view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof TextView)text.append(((TextView)view).getText()).append('\n');
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),text);
    }
    private void feed(long code)throws Exception {
        Requests.feed=RhActions.obj("schemaVersion",1,"android",RhActions.obj("version","0.2.4","versionCode",code,
            "packageName",activity.getPackageName(),"fileName","RHRemote-Android-0.2.4-arm64-v8a.apk",
            "tag","v0.2.4","size",123,"sha256",new String(new char[64]).replace('\0','a'))).toString();
    }
    private long current()throws Exception{return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).getLongVersionCode();}
    @Test public void releaseNotesShowTheirPublicationTime()throws Exception {
        feed(current()+1);JSONObject manifest=new JSONObject(Requests.feed);manifest.getJSONObject("android").put("notes","发布时间：2026-11-01 18:20 UTC+08:00\n\n代理设置").put("publishedAt","2026-11-01T10:20:00Z");Requests.feed=manifest.toString();updates.check();
        assertEquals("2026-11-01T10:20:00Z",updates.state().getString("publishedAt"));assertTrue(page().contains("发布时间：2026-11-01 18:20 UTC+08:00"));
        manifest.getJSONObject("android").put("versionCode",current());Requests.feed=manifest.toString();updates.check();assertTrue(updates.state().isNull("availableVersion"));assertTrue(page().contains("发布时间：2026-11-01 18:20 UTC+08:00"));
    }
    @Test public void beforeCheckingDoesNotRenderJsonNull()throws Exception {
        String text=page();assertTrue(text,text.contains("尚未检查更新"));assertFalse(text,text.contains("null"));
    }
    @Test public void sourceSelectionApiAndViewStayInSync()throws Exception {
        home.perform("updates.source.set",RhActions.obj("source","github"));
        assertTrue(page().contains("首选更新源"));
        android.widget.Spinner choice=ReflectionHelpers.getField(home,"updateSource");
        assertEquals(1,choice.getSelectedItemPosition());
        assertEquals("github",new RhUpdates(activity).state().getString("preferredSource"));
        home.perform("updates.source.set",RhActions.obj("source","cnb"));
        assertEquals(0,choice.getSelectedItemPosition());
    }
    @Test public void changingSourceDuringAnAdmittedUpdateIsRejected()throws Exception {
        java.util.concurrent.atomic.AtomicReference<Runnable> pending=new java.util.concurrent.atomic.AtomicReference<>();
        RhUpdateWork work=ReflectionHelpers.getField(home,"updateWork");
        work.start("updates.check",false,pending::set);
        try{home.perform("updates.source.set",RhActions.obj("source","github"));fail("Source changed during update");}catch(IllegalStateException expected){}
        assertEquals("cnb",updates.state().getString("preferredSource"));
        pending.get().run();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(work.busy());
    }
    @Test public void currentReleaseShowsLatestWithoutNull()throws Exception {
        feed(current());updates.check();assertTrue(updates.state().isNull("availableVersion"));
        String text=page();assertTrue(text,text.contains("已是最新版本"));assertFalse(text,text.contains("null"));assertEquals(1,Requests.count);
    }
    @Test public void higherVersionIsVisibleAndKeepsFallbackSource()throws Exception {
        feed(current()+1);Requests.failFirst=true;updates.check();
        assertEquals("0.2.4",updates.state().getString("availableVersion"));assertEquals(2,Requests.count);
        String text=page();assertTrue(text,text.contains("可用版本 0.2.4"));assertTrue(text,text.contains("发现新版本"));assertFalse(text,text.contains("null"));
    }
    @Test public void unavailableFeedsKeepFailureOnTheUpdatePage()throws Exception {
        try{updates.check();fail("Unavailable feeds must fail");}catch(IOException expected){}
        assertEquals(2,Requests.count);String text=page();assertTrue(text,text.contains("更新失败，请重试。"));assertFalse(text,text.contains("CNB IOException: HTTP 503"));
        home.perform("updates.details.toggle",RhActions.obj());text=page();assertTrue(text,text.contains("CNB IOException: HTTP 503"));assertTrue(text,text.contains("GitHub IOException: HTTP 503"));assertFalse(text,text.contains("null"));
    }
    @Test public void failedRecheckDoesNotTurnThePrimaryActionIntoInstallation()throws Exception {
        feed(current()+1);updates.check();assertEquals("updates.install",updates.primaryAction());
        Requests.feed=null;try{updates.check();fail("Recheck must fail");}catch(IOException expected){}
        assertEquals("0.2.4",updates.state().getString("availableVersion"));assertEquals("updates.check",updates.primaryAction());assertEquals("重新检查",updates.primaryLabel(false));
        feed(current()+1);updates.check();assertEquals("updates.install",updates.primaryAction());assertEquals("下载并安装",updates.primaryLabel(false));
    }
}
