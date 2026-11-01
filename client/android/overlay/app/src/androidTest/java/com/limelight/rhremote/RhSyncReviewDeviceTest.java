package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual owned Views and loopback DAV only; never starts discovery, pairing or streaming. */
@RunWith(AndroidJUnit4.class)
public final class RhSyncReviewDeviceTest {
    final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    final Context context=instrument.getTargetContext();Activity activity;RhHome home;File folder;final JSONArray cases=new JSONArray();
    interface Work{void run()throws Exception;}
    void main(Work work){Throwable[] failure={null};instrument.runOnMainSync(()->{try{work.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    JSONObject state(){JSONObject[] result={null};main(()->result[0]=home.state());return result[0];}
    void action(String id,JSONObject args){main(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View root,String text){if(root.getVisibility()!=View.VISIBLE)return null;if(text.equals(root.getContentDescription())||root instanceof Button&&text.contentEquals(((Button)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    View findText(View root,String text){if(root.getVisibility()!=View.VISIBLE)return null;if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findText(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    void reveal(String id){main(()->{View view=find(activity.findViewById(android.R.id.content),id);assertNotNull(id,view);view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);});instrument.waitForIdleSync();}
    void click(String text){main(()->{View view=find(activity.findViewById(android.R.id.content),text);assertNotNull(text,view);view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);});instrument.waitForIdleSync();main(()->{View view=find(activity.findViewById(android.R.id.content),text);Rect visible=new Rect();assertTrue(text,view.isEnabled());assertTrue(text,view.getGlobalVisibleRect(visible));assertEquals(text,view.getHeight(),visible.height());assertTrue(text,view.performClick());});instrument.waitForIdleSync();}
    void idle()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(state().optBoolean("syncBusy")){assertTrue("Sync did not retire",System.nanoTime()<end);Thread.sleep(20);}instrument.waitForIdleSync();assertEquals("",state().optString("syncError"));}
    void capture(String name)throws Exception{
        Bitmap[] image={null};main(()->{View decor=activity.getWindow().getDecorView();image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image[0]));});
        try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{image[0].recycle();}
    }
    JSONObject seed()throws Exception{
        JSONObject shared=RhShared.read(context);String client=RhShared.clientId(context);
        shared.put("devices",new JSONArray().put(RhActions.obj("uuid","review-host","name","工作电脑","displayName","基准名称","address","192.0.2.9","addresses",new JSONArray().put("100.64.0.9"),"swapButtons",false)));
        shared.put("clients",RhActions.obj(client,RhActions.obj("name","当前平板","platform","android"),"fixture-desktop",RhActions.obj("name","台式机","platform","windows")));
        RhProfile profile=RhProfile.load(context,"review-host");profile.bitrateAuto=false;profile.bitrateMbps=25;profile.codec="HEVC";profile.audioEnabled=false;profile.volume=100;
        RhProfile copied=RhProfile.fromClientJson("review-host",profile.clientJson());copied.bitrateAuto=true;copied.bitrateMbps=41;
        shared.put("profiles",RhActions.obj(client,RhActions.obj("review-host",profile.clientJson()),"fixture-desktop",RhActions.obj("review-host",copied.clientJson())));shared.put("dark",false);return shared;
    }
    void open(boolean dark,int width,int font)throws Exception{
        main(()->{RhUiFixtureActivity.fontScale(font);RhShared.theme(context,dark);context.getSharedPreferences("rhremote.global",0).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();});
        activity=instrument.startActivitySync(new Intent().setComponent(new ComponentName(context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(()->{assertEquals((float)font,activity.getResources().getConfiguration().fontScale,0f);float density=activity.getResources().getDisplayMetrics().density;activity.getWindow().setLayout(Math.round(width*density),Math.round(700*density));home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No stream");}public void add(String address){}public void advanced(){}public void pair(ComputerDetails d,String pin){throw new AssertionError("No pairing");}public void cancelPair(){}public void refresh(){}});ComputerDetails host=new ComputerDetails();host.uuid="review-host";host.name="工作电脑";host.manualAddress=new ComputerDetails.AddressTuple("192.0.2.9",47989);home.update(host);});instrument.waitForIdleSync();
    }
    JSONArray measure(JSONObject item)throws Exception{
        JSONArray result=new JSONArray();main(()->{JSONArray fields=item.getJSONArray("fields");for(int f=0;f<fields.length();f++){JSONObject field=fields.getJSONObject(f);for(String side:new String[]{"local","remote"}){String key=item.getString("key")+"|"+field.getString("key");TextView text=(TextView)find(activity.findViewById(android.R.id.content),"sync-review-"+side+"|"+key);assertNotNull(key,text);assertEquals(field.getString(side),text.getText().toString());assertNotNull(text.getLayout());assertTrue(text.getLayout().getLineCount()>=1);for(int i=0;i<text.getLineCount();i++)assertEquals("Ellipsized comparison",0,text.getLayout().getEllipsisCount(i));assertTrue("Clipped text height",text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()>=text.getLayout().getHeight());ViewGroup row=(ViewGroup)text.getParent();assertEquals(2,row.getChildCount());assertTrue("Unequal comparison columns",Math.abs(row.getChildAt(0).getWidth()-row.getChildAt(1).getWidth())<=1);assertTrue("Overlapping columns",row.getChildAt(0).getRight()<=row.getChildAt(1).getLeft());result.put(RhActions.obj("key",field.getString("key"),"side",side,"lines",text.getLineCount(),"height",text.getHeight(),"width",text.getWidth()));}}});return result;
    }
    @Test public void actualConflictChoicesAndCopyPreviewWorkAtBothWidthsAndFontSizes()throws Exception{
        assertTrue("Only the isolated UI fixture package is allowed",context.getPackageName().endsWith(".uicheck158"));folder=context.getExternalFilesDir("review158");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());
        try{for(boolean dark:new boolean[]{false,true})for(int font:new int[]{1,2})for(int width:new int[]{360,840})for(String choice:new String[]{"local","remote"}){
            String tag=(dark?"dark":"light")+"-"+font+"-"+width+"-"+choice;main(()->RhShared.apply(context,seed()));
            try(RhSyncFlowDeviceTest.Fixture fixture=new RhSyncFlowDeviceTest.Fixture()){
                open(dark,width,font);action("app.settings.tab",RhActions.obj("tab","sync"));action("sync.configure",RhActions.obj("url",fixture.url(),"username","review-fixture","password","task-owned-fixture-only","automatic",false,"intervalMinutes",15));click("立即同步");idle();assertEquals(1,fixture.puts.get());
                JSONObject baseline=new JSONObject(fixture.document),remote=new JSONObject(baseline.toString()),local=new JSONObject(baseline.toString());String client=RhShared.clientId(context);
                remote.getJSONArray("devices").getJSONObject(0).put("displayName","远端名称").put("addresses",new JSONArray().put("100.64.0.2"));local.getJSONArray("devices").getJSONObject(0).put("displayName","本机名称").put("addresses",new JSONArray().put("100.64.0.1"));
                JSONObject rp=remote.getJSONObject("profiles").getJSONObject(client).getJSONObject("review-host"),lp=local.getJSONObject("profiles").getJSONObject(client).getJSONObject("review-host");rp.put("bitrateAuto",true).put("codec","H.264").put("audioEnabled",true).put("volume",40).put("routeOrder",new JSONArray().put("relay").put("lan"));lp.put("bitrateMbps",41).put("codec","AV1").put("volume",60).put("routeOrder",new JSONArray().put("lan").put("relay"));fixture.remote(remote);main(()->RhShared.apply(context,local));
                click("立即同步");idle();assertEquals("Conflict uploaded before a choice",1,fixture.puts.get());String firstReview=state().getJSONObject("sync").getString("reviewId");assertFalse(firstReview.isEmpty());JSONObject[] automatic={null};main(()->automatic[0]=home.perform("sync.run",RhActions.obj("automatic",true)));assertTrue(automatic[0].getBoolean("skipped"));assertEquals("sync_conflict",automatic[0].getString("reason"));assertEquals(firstReview,state().getJSONObject("sync").getString("reviewId"));
                click("重新同步");idle();assertEquals(firstReview,state().getJSONObject("sync").getString("reviewId"));View[] oldButton={null};main(()->oldButton[0]=find(activity.findViewById(android.R.id.content),"冲突项保留远端"));assertNotNull(oldButton[0]);rp.put("volume",41);fixture.remote(remote);click("重新同步");idle();String currentReview=state().getJSONObject("sync").getString("reviewId");assertNotEquals(firstReview,currentReview);int gets=fixture.gets.get();main(()->{oldButton[0].performClick();JSONObject rejected=RhActions.dispatch("sync.resolve",RhActions.obj("resolution","remote","reviewId",firstReview));assertFalse("Stale API choice accepted",rejected.optBoolean("ok"));JSONObject malformed=RhActions.dispatch("sync.run",RhActions.obj("automatic","true"));assertFalse("Malformed automatic flag accepted",malformed.optBoolean("ok"));});instrument.waitForIdleSync();assertEquals(gets,fixture.gets.get());assertEquals(1,fixture.puts.get());assertEquals(currentReview,state().getJSONObject("sync").getString("reviewId"));
                JSONArray items=state().getJSONObject("sync").getJSONArray("conflictItems");assertEquals(2,items.length());JSONObject profile=null;for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("key").startsWith("profiles."))profile=items.getJSONObject(i);assertNotNull(profile);assertTrue(profile.getString("local").contains("AV1"));assertTrue(profile.getString("remote").contains("H.264"));assertTrue(profile.getString("remote").contains("25 Mbps（手动值，未启用）"));
                JSONArray measurements=measure(profile);capture(tag+"-review");reveal("sync-review-local|"+profile.getString("key")+"|bitrateMbps");capture(tag+"-bitrate");reveal("sync-review-local|"+profile.getString("key")+"|routeOrder");capture(tag+"-route");
                main(()->{java.lang.reflect.Field field=RhHome.class.getDeclaredField("scroll");field.setAccessible(true);ScrollView scroll=(ScrollView)field.get(home);scroll.scrollTo(0,scroll.getChildAt(0).getHeight());});instrument.waitForIdleSync();capture(tag+"-bottom");
                click(choice.equals("local")?"冲突项保留本机":"冲突项保留远端");idle();assertEquals(2,fixture.puts.get());JSONObject saved=RhShared.read(context);assertEquals(choice.equals("local")?"本机名称":"远端名称",saved.getJSONArray("devices").getJSONObject(0).getString("displayName"));assertEquals(RhShared.canonical(choice.equals("local")?lp:rp),RhShared.canonical(saved.getJSONObject("profiles").getJSONObject(client).getJSONObject("review-host")));assertEquals(0,state().getJSONObject("sync").getJSONArray("conflicts").length());
                action("device.settings.open",RhActions.obj("uuid","review-host"));action("settings.copy.preview",RhActions.obj("clientId","fixture-desktop"));JSONObject preview=state().getJSONObject("profileCopy");boolean marked=false;JSONArray fields=preview.getJSONArray("fields");for(int i=0;i<fields.length();i++)if(fields.getJSONObject(i).optString("key").equals("bitrateMbps")){marked=true;assertEquals("41 Mbps（手动值，未启用）",fields.getJSONObject(i).getString("value"));}assertTrue(marked);main(()->{View text=findText(activity.findViewById(android.R.id.content),"41 Mbps（手动值，未启用）");assertNotNull(text);text.requestRectangleOnScreen(new Rect(0,0,text.getWidth(),text.getHeight()),true);});instrument.waitForIdleSync();capture(tag+"-copy");action("settings.copy.cancel",RhActions.obj());action("settings.cancel",RhActions.obj());
                cases.put(RhActions.obj("name",tag,"review",items,"measurements",measurements,"puts",fixture.puts.get(),"choice",choice,"copyMarked",marked,"automaticDeferred",true,"sameSnapshotIdStable",true,"staleChoiceRejectedBeforeHttp",true,"reviewChanged",!firstReview.equals(currentReview)));
            }finally{main(()->{if(home!=null){home.dispose();home=null;}if(activity!=null){activity.finish();activity=null;}});instrument.waitForIdleSync();}
        }}finally{main(()->RhUiFixtureActivity.fontScale(0));RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",cases.length()==16,"cases",cases,"systemInputInjected",false,"osScreenshotUsed",false,"remoteSessions",0));}
    }
}
