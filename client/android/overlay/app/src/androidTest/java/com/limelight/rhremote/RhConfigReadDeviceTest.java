package com.limelight.rhremote;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Own synthetic config and debug fixture Activity; no remote client, host, DAV or OS input. */
@RunWith(AndroidJUnit4.class)
public final class RhConfigReadDeviceTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private static final String CLIENT="fixture-client-179";
    private File folder;
    private final List<Activity> fixtureActivities=new ArrayList<>();
    private final JSONArray captureStates=new JSONArray();
    private static final class Corpus {JSONObject document;List<ComputerDetails> devices=new ArrayList<>();}
    private static String hash(String text)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
    private static byte[] bytes(File path)throws Exception{return path.exists()?Files.readAllBytes(path.toPath()):null;}
    private static void restore(File path,byte[] original)throws Exception{if(original!=null){assertTrue(path.getParentFile().exists()||path.getParentFile().mkdirs());Files.write(path.toPath(),original);}else if(path.exists()&&!path.delete())throw new IOException("Own fixture file cleanup failed");}
    private static void preferences(SharedPreferences prefs,Map<String,?> values){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));else throw new AssertionError("Unsupported own preference type");}assertTrue(edit.commit());}
    private Corpus corpus(Context context,int hosts)throws Exception{
        Corpus data=new Corpus();JSONArray devices=new JSONArray();JSONObject clients=new JSONObject(),groups=new JSONObject(),template=RhProfile.load(context,"fixture-default").clientJson();
        for(int i=0;i<hosts;i++){String id="fixture-host-"+i,name="Fixture "+(i+1),address="192.0.2."+(i+1);devices.put(RhActions.obj("uuid",id,"name",name,"address",address,"displayName","Alias "+(i+1),"swapButtons",i%2==0));ComputerDetails d=new ComputerDetails();d.uuid=id;d.name=name;d.state=ComputerDetails.State.UNKNOWN;d.manualAddress=new ComputerDetails.AddressTuple(address,47989);data.devices.add(d);}
        for(int i=0;i<6;i++){String client=i==0?CLIENT:"fixture-client-"+i;clients.put(client,RhActions.obj("name","Fixture client "+i,"platform",i%2==0?"android":"windows"));JSONObject group=new JSONObject();for(int j=0;j<hosts;j++){JSONObject profile=new JSONObject(template.toString());profile.put("width",1600).put("height",900).put("bitrateMbps",6+i);group.put("fixture-host-"+j,profile);}groups.put(client,group);}
        data.document=RhActions.obj("schemaVersion",2,"devices",devices,"clients",clients,"profiles",groups,"dark",false);return data;
    }
    private JSONObject benchmark(Context context,Corpus data)throws Exception{
        File file=new File(context.getFilesDir(),"rhremote-shared.json");RhShared.write(file,data.document);String expected=RhShared.canonical(RhShared.normalize(data.document));for(int i=0;i<2;i++)assertEquals(expected,RhShared.canonical(RhShared.read(context)));
        JSONArray reads=new JSONArray(),captures=new JSONArray();String captured=null;
        for(int i=0;i<5;i++){long begin=SystemClock.elapsedRealtimeNanos();JSONObject read=RhShared.read(context);long elapsed=SystemClock.elapsedRealtimeNanos()-begin;assertEquals(expected,RhShared.canonical(read));reads.put(elapsed/1000000.0);}
        for(int i=0;i<3;i++){RhShared.write(file,data.document);long begin=SystemClock.elapsedRealtimeNanos();JSONObject result=RhShared.capture(context,data.devices);long elapsed=SystemClock.elapsedRealtimeNanos()-begin;String value=RhShared.canonical(result);if(captured==null)captured=value;else assertEquals(captured,value);captures.put(elapsed/1000000.0);}
        return RhActions.obj("hosts",data.devices.size(),"clients",6,"rawBytes",data.document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,"readMs",reads,"captureMs",captures,"readFingerprint",hash(expected),"captureFingerprint",hash(captured),"scope","public synthetic schema; complete read and complete capture including atomic write; no UI present timestamp");
    }
    private static TextView text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=text(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static void layout(FrameLayout root){for(int i=0;i<3;i++){root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(1512,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1008,View.MeasureSpec.EXACTLY));root.layout(0,0,1512,1008);}}
    private void capture(FrameLayout root,String name)throws Exception{layout(root);Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);File output=new File(folder,name+".png");assertFalse(output.exists());try(FileOutputStream file=new FileOutputStream(output)){root.draw(new Canvas(image));captureStates.put(RhActions.obj("name",name,"rootFocused",root.isFocused(),"descendantHasFocus",root.hasFocus(),"defaultFocusHighlightEnabled",root.getDefaultFocusHighlightEnabled(),"rootAlpha",root.getAlpha(),"cornerArgb",image.getPixel(0,0),"bodyArgb",image.getPixel(1200,900)));assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,file));}finally{image.recycle();}}
    private void focusSnapshots(FrameLayout root,String name)throws Exception{
        int[] original={0};instrument.runOnMainSync(()->{original[0]=root.getDescendantFocusability();root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);assertTrue(root.requestFocusFromTouch());assertTrue(root.isFocused());root.setDefaultFocusHighlightEnabled(true);root.invalidate();});instrument.waitForIdleSync();Thread.sleep(250);
        instrument.runOnMainSync(()->{try{capture(root,name+"-focus-on");root.setDefaultFocusHighlightEnabled(false);root.invalidate();}catch(Exception failure){throw new AssertionError(failure);}});instrument.waitForIdleSync();Thread.sleep(250);
        instrument.runOnMainSync(()->{try{capture(root,name+"-focus-off");root.setDescendantFocusability(original[0]);}catch(Exception failure){throw new AssertionError(failure);}});
    }
    private JSONObject renderDuringCommit(Context base,Corpus data,boolean dark)throws Exception{
        RhShared.write(new File(base.getFilesDir(),"rhremote-shared.json"),data.document);JSONObject snapshot=RhShared.read(base);snapshot.getJSONArray("devices").getJSONObject(0).put("displayName","Snapshot while saving");
        assertTrue(base.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).commit());
        Activity activity=instrument.startActivitySync(new Intent().setClassName(base,"com.limelight.rhremote.RhUiFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));fixtureActivities.add(activity);instrument.waitForIdleSync();
        FrameLayout[] root={null};RhDeviceBrowser[] browser={null};List<String> actions=new ArrayList<>();String name=dark?"dark":"light";
        instrument.runOnMainSync(()->{try{root[0]=new FrameLayout(activity);root[0].setFocusableInTouchMode(true);root[0].setDefaultFocusHighlightEnabled(false);root[0].setBackgroundColor(dark?0xff192030:0xffe4eaf4);browser[0]=new RhDeviceBrowser(activity,(id,args)->actions.add(id));root[0].addView(browser[0],new FrameLayout.LayoutParams(-1,-1));activity.setContentView(root[0]);browser[0].update(data.document,data.devices,"fixture-host-0","");layout(root[0]);root[0].requestFocusFromTouch();
            capture(root[0],name+"-before");}catch(Exception failure){throw new AssertionError(failure);}});
        if(InstrumentationRegistry.getArguments().getString("configFocusProbe","false").equals("true"))focusSnapshots(root[0],name);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();ScheduledExecutorService watchdog=Executors.newScheduledThreadPool(2);AtomicReference<Thread> writerThread=new AtomicReference<>();AtomicReference<JSONObject> stack=new AtomicReference<>();
        ComputerDetails added=new ComputerDetails();added.uuid="fixture-added";added.name="Added fixture";added.manualAddress=new ComputerDetails.AddressTuple("192.0.2.200",47989);
        Future<ComputerDetails> write=worker.submit(()->RhShared.addDevice(base,added,verified->{writerThread.set(Thread.currentThread());entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Own commit guard expired");return verified;}));
        boolean[] held={false},profileVisible={false};long[] renderMs={-1};ScheduledFuture<?> releaseTask=null,sampleTask=null;
        try{
            assertTrue("Own writer did not reach its commit callback",entered.await(10,TimeUnit.SECONDS));releaseTask=watchdog.schedule(release::countDown,750,TimeUnit.MILLISECONDS);
            sampleTask=watchdog.schedule(()->{Thread main=Looper.getMainLooper().getThread();JSONArray frames=new JSONArray(),writer=new JSONArray();for(StackTraceElement frame:main.getStackTrace()){if(frames.length()<20)frames.put(frame.toString());}Thread owner=writerThread.get();if(owner!=null)for(StackTraceElement frame:owner.getStackTrace()){if(writer.length()<12)writer.put(frame.toString());}stack.set(RhActions.obj("mainState",main.getState().name(),"mainFrames",frames,"knownWriterFrames",writer));},150,TimeUnit.MILLISECONDS);
            instrument.runOnMainSync(()->{long begin=SystemClock.elapsedRealtime();browser[0].update(snapshot,data.devices,"fixture-host-0","");layout(root[0]);renderMs[0]=SystemClock.elapsedRealtime()-begin;held[0]=release.getCount()>0;TextView title=text(root[0],"Snapshot while saving"),size=text(root[0],"1600 × 900");profileVisible[0]=title!=null&&size!=null&&title.isShown()&&size.isShown();try{capture(root[0],name+"-during");}catch(Exception failure){throw new AssertionError(failure);}});
        }finally{release.countDown();if(releaseTask!=null)releaseTask.cancel(false);if(sampleTask!=null)sampleTask.cancel(false);try{write.get(5,TimeUnit.SECONDS);}finally{worker.shutdownNow();watchdog.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));assertTrue(watchdog.awaitTermination(5,TimeUnit.SECONDS));}}
        JSONObject committed=RhShared.read(base);assertEquals(4,committed.getJSONArray("devices").length());instrument.runOnMainSync(()->{browser[0].update(committed,data.devices,"fixture-host-0","");try{capture(root[0],name+"-after");}catch(Exception failure){throw new AssertionError(failure);}root[0].removeAllViews();});
        assertTrue("Native browser mixed snapshot fields",profileVisible[0]);assertTrue("Rendering emitted a product action",actions.isEmpty());
        return RhActions.obj("dark",dark,"renderedWhileCommitHeld",held[0],"renderAndLayoutMs",renderMs[0],"releaseGuardMs",750,"snapshotFieldsVisible",profileVisible[0],"actionsEmitted",actions.size(),"mainSample",stack.get()==null?JSONObject.NULL:stack.get(),"scope","real native device browser on main looper with a controlled own AddCommit hold; not a natural latency estimate or pixel-present measurement");
    }
    @Test public void freshReadsAndProvidedUiSnapshotKeepTheirContractsWithoutGlobalIdentityWait()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("configReadRun","");org.junit.Assume.assumeFalse("Explicit owned run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));Context base=instrument.getTargetContext();assertEquals("com.rhremote.client.uicheck172",base.getPackageName());assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        folder=new File(base.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());File shared=new File(base.getFilesDir(),"rhremote-shared.json"),backup=new File(shared+".bak"),global=new File(base.getDataDir(),"shared_prefs/rhremote.global.xml"),globalBackup=new File(global+".bak");
        SharedPreferences prefs=base.getSharedPreferences("rhremote.global",0);Map<String,?> originalPreferences=new HashMap<>(prefs.getAll());byte[] original=bytes(shared),originalBackup=bytes(backup),originalGlobal=bytes(global),originalGlobalBackup=bytes(globalBackup);JSONObject report=RhActions.obj("version",base.getPackageManager().getPackageInfo(base.getPackageName(),0).versionName,"hostOperations",false,"webdavOperations",false,"systemInputInjected",false,"osScreenshots",false,"callerDataPublicFixture",true);boolean passed=false;
        try{
            assertTrue(prefs.edit().putString("clientId",CLIENT).putInt("profileSchemaVersion",2).putBoolean("deviceOrderInitialized",true).putInt("relayDefaultsVersion",1).putBoolean("dark",false).commit());
            JSONArray performance=new JSONArray(),rendering=new JSONArray();for(int hosts:new int[]{3,12})performance.put(benchmark(base,corpus(base,hosts)));report.put("performance",performance);
            for(boolean dark:new boolean[]{false,true})rendering.put(renderDuringCommit(base,corpus(base,3),dark));report.put("rendering",rendering);passed=true;for(int i=0;i<rendering.length();i++)passed&=rendering.getJSONObject(i).getBoolean("renderedWhileCommitHeld");report.put("passed",passed);
        }finally{instrument.runOnMainSync(()->{for(Activity fixture:fixtureActivities)if(!fixture.isFinishing()&&!fixture.isDestroyed())fixture.finish();});instrument.waitForIdleSync();preferences(prefs,originalPreferences);restore(shared,original);restore(backup,originalBackup);restore(global,originalGlobal);restore(globalBackup,originalGlobalBackup);report.put("captureStates",captureStates);report.put("ownFilesRestored",Arrays.equals(original,bytes(shared))&&Arrays.equals(originalGlobal,bytes(global)));RhShared.write(new File(folder,"result.json"),report);}
        assertTrue("Native device rendering waited for an unrelated config commit: "+report.optJSONArray("rendering"),passed);
    }
}
