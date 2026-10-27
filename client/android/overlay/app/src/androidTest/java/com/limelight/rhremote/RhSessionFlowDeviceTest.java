package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.video.MediaCodecHelper;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in real host test. Uses a dedicated emulator and keeps audio disabled throughout. */
@RunWith(AndroidJUnit4.class)
public class RhSessionFlowDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();RhHome home;Activity game;File folder;
    JSONObject homeState(){JSONObject[] state={null};instrument.runOnMainSync(()->{try{state[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return state[0];}
    JSONObject stream(){JSONObject[] state={null};instrument.runOnMainSync(()->state[0]=RhActions.activeState());return state[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    void waitFor(java.util.function.BooleanSupplier ready,int seconds,String label)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!ready.getAsBoolean()){assertTrue(label+": phase="+stream().optString("phase")+", error="+RhActions.lastConnectionError(),System.nanoTime()<end);Thread.sleep(40);}instrument.waitForIdleSync();}
    void stage(String phase,JSONObject detail)throws Exception{detail.put("phase",phase);detail.put("runId",InstrumentationRegistry.getArguments().getString("sessionRun","manual"));RhShared.write(new File(folder,"session-real-stage.json"),detail);}
    View find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
    void click(String label){instrument.runOnMainSync(()->{View control=find(game.findViewById(android.R.id.content),label);assertNotNull(label,control);assertTrue(label,control.isEnabled());control.performClick();});instrument.waitForIdleSync();}
    void capture(String name)throws Exception{if(!InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true"))return;for(int frame=0;frame<2;frame++){CountDownLatch done=new CountDownLatch(1);instrument.runOnMainSync(()->{View decor=game.getWindow().getDecorView();decor.getViewTreeObserver().registerFrameCommitCallback(done::countDown);decor.invalidate();});assertTrue(done.await(3,TimeUnit.SECONDS));}Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
    JSONObject videoPixels()throws Exception{return videoPixels("session-real-video");}
    JSONObject videoPixels(String name)throws Exception{
        SurfaceView[] surface={null};instrument.runOnMainSync(()->surface[0]=game.findViewById(R.id.surfaceView));assertNotNull(surface[0]);Bitmap bitmap=Bitmap.createBitmap(surface[0].getWidth(),surface[0].getHeight(),Bitmap.Config.ARGB_8888);int[] result={-1};int samples=0,nonDark=0,attempts=0;long begin=System.nanoTime(),end=begin+TimeUnit.SECONDS.toNanos(6);
        try{do{CountDownLatch done=new CountDownLatch(1);instrument.runOnMainSync(()->PixelCopy.request(surface[0],bitmap,code->{result[0]=code;done.countDown();},new android.os.Handler(android.os.Looper.getMainLooper())));assertTrue(done.await(3,TimeUnit.SECONDS));samples=nonDark=0;attempts++;if(result[0]==PixelCopy.SUCCESS){for(int y=0;y<bitmap.getHeight();y+=8)for(int x=0;x<bitmap.getWidth();x+=8){int pixel=bitmap.getPixel(x,y);samples++;if(Color.red(pixel)>24||Color.green(pixel)>24||Color.blue(pixel)>24)nonDark++;}}if(samples>0&&nonDark>samples/100)break;Thread.sleep(100);}while(System.nanoTime()<end);if(result[0]==PixelCopy.SUCCESS)try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}return RhActions.obj("pixelCopyResult",result[0],"samples",samples,"nonDark",nonDark,"visibleContent",samples>0&&nonDark>samples/100,"attempts",attempts,"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin));}finally{bitmap.recycle();}
    }
    Activity resumedGame(){Activity[] current={null};instrument.runOnMainSync(()->{for(Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(candidate instanceof Game)current[0]=candidate;});return current[0];}
    void exportOwnTrace(Context context,String directory,int index)throws Exception{
        File trace=new File(directory),root=new File(context.getFilesDir(),"statistics");assertTrue(trace.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator));
        File source=new File(trace,"native.jsonl");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean done=false;
        do{if(source.isFile()){try(BufferedReader reader=new BufferedReader(new FileReader(source))){String line;while((line=reader.readLine())!=null)try{if(new JSONObject(line).optString("event").equals("trace_end"))done=true;}catch(JSONException incomplete){/* The writer may still be finishing its last record. */}}}if(!done)Thread.sleep(40);}while(!done&&System.nanoTime()<end);assertTrue("Test trace did not finish",done);
        File exported=new File(folder,"session-recovery-metadata-"+index+".jsonl");java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(exported)){byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))!=-1){out.write(buffer,0,count);sha.update(buffer,0,count);}}
        StringBuilder digest=new StringBuilder();for(byte value:sha.digest())digest.append(String.format(java.util.Locale.ROOT,"%02x",value&255));RhShared.write(new File(folder,"session-recovery-metadata-"+index+".json"),RhActions.obj("file",exported.getName(),"sha256",digest.toString(),"bytes",exported.length(),"containsAudio",false,"scope","test-created trace metadata"));
    }
    void mouseBackReachesViewWithoutNavigation()throws Exception{
        View[] previous={null},guard={null};java.util.List<Integer> delivered=new java.util.ArrayList<>();
        instrument.runOnMainSync(()->{previous[0]=game.getCurrentFocus();guard[0]=new View(game);guard[0].setFocusableInTouchMode(true);guard[0].setOnKeyListener((view,code,event)->{if(code!=KeyEvent.KEYCODE_BACK||!event.isFromSource(InputDevice.SOURCE_MOUSE))return false;delivered.add(event.getAction());return true;});((ViewGroup)game.findViewById(android.R.id.content)).addView(guard[0],new ViewGroup.LayoutParams(1,1));assertTrue(guard[0].requestFocus());});instrument.waitForIdleSync();
        try{
            long when=android.os.SystemClock.uptimeMillis();for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP})assertTrue(instrument.getUiAutomation().injectInputEvent(new KeyEvent(when,android.os.SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_BACK,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_MOUSE),true));instrument.waitForIdleSync();
            assertEquals("Mouse Back must reach the view tree for Moonlight's right-button handling",java.util.Arrays.asList(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP),delivered);assertTrue(stream().optBoolean("controls"));assertFalse(stream().optBoolean("exitPrompt"));assertTrue(stream().optBoolean("connected"));
        }finally{instrument.runOnMainSync(()->{((ViewGroup)guard[0].getParent()).removeView(guard[0]);if(previous[0]!=null)previous[0].requestFocus();});}
    }
    @Test public void realFirstFrameGroupedControlsAndSystemBackFinishWithoutUnwantedRecovery()throws Exception{
        String address=InstrumentationRegistry.getArguments().getString("sessionHost","");org.junit.Assume.assumeFalse("Explicit real host is required",address.isEmpty());
        Context context=instrument.getTargetContext();folder=context.getExternalFilesDir("ui-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());stage("starting",RhActions.obj());
        Activity activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertFalse(RhActions.hasStream());JSONObject original=RhShared.read(context);String before=RhShared.canonical(original);
        String uuid=InstrumentationRegistry.getArguments().getString("sessionUuid","C3448560-C539-6209-F595-88C1E8D2769B");
        boolean testRecovery=InstrumentationRegistry.getArguments().getString("sessionRecovery","false").equals("true"),originalTracing=RhStatistics.enabled();java.util.List<String> ownTraces=new java.util.ArrayList<>();JSONObject recovery=RhActions.obj("requested",testRecovery,"syntheticFailure",testRecovery,"actualNetworkInterrupted",false);
        boolean software=InstrumentationRegistry.getArguments().getString("sessionDecoder","default").equals("software");java.util.List<String> blacklist=null,previousBlacklist=null;
        if(software){assertTrue("Decoder override is restricted to the dedicated emulator",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Field codecs=MediaCodecHelper.class.getDeclaredField("blacklistedDecoderPrefixes");codecs.setAccessible(true);blacklist=(java.util.List<String>)codecs.get(null);previousBlacklist=new java.util.ArrayList<>(blacklist);blacklist.add("c2.goldfish.");}

        try{
            if(testRecovery)action("statistics.configure",RhActions.obj("enabled",true));
            action("device.add.open",RhActions.obj());action("device.add.input",RhActions.obj("address",address));action("device.add.submit",RhActions.obj());waitFor(()->homeState().optString("page").equals("devices"),15,"Host lookup");
            String target=uuid;java.util.function.BooleanSupplier paired=()->{JSONArray devices=homeState().optJSONArray("devices");for(int i=0;i<devices.length();i++)if(target.equals(devices.optJSONObject(i).optString("uuid")))return devices.optJSONObject(i).optBoolean("paired");return false;};
            boolean canConnect=false;JSONArray discovered=homeState().getJSONArray("devices");for(int i=0;i<discovered.length();i++)if(uuid.equals(discovered.getJSONObject(i).optString("uuid")))canConnect=discovered.getJSONObject(i).optBoolean("canConnect");
            if(!canConnect){
                String pin=String.format(java.util.Locale.ROOT,"%04d",new java.security.SecureRandom().nextInt(10000));action("device.pair",RhActions.obj("uuid",uuid,"pin",pin));stage("pairing",RhActions.obj("pin",pin,"uuid",uuid,"address",address));waitFor(()->!RhActions.hasStream(),55,"Pairing operation completion");assertTrue("Completed pairing must publish a verified result",paired.getAsBoolean());
            }
            action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.set",RhActions.obj("key","resolution","value",new JSONArray().put(1920).put(1080)));for(Object[] value:new Object[][]{{"fps",30},{"codec","H.264"},{"audioEnabled",false},{"gamepadEnabled",false},{"displayMode","auto"},{"statistics",false}})action("settings.set",RhActions.obj("key",value[0],"value",value[1]));action("settings.save",RhActions.obj());
            stage("connecting",RhActions.obj("uuid",uuid,"audioEnabled",false));long begin=System.nanoTime();action("device.connect",RhActions.obj("uuid",uuid));waitFor(()->stream().optBoolean("connected")&&stream().optBoolean("firstFrame"),60,"Real decoded first frame");long firstFrameMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin);
            instrument.runOnMainSync(()->{for(Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(candidate instanceof Game)game=candidate;});assertNotNull("Game Activity is visible",game);assertFalse(stream().optBoolean("audioEnabled"));stage("streaming",RhActions.obj("firstFrameMs",firstFrameMs));
            JSONObject pixels=videoPixels();RhShared.write(new File(folder,"session-real-video-pixels.json"),pixels);click("控制");JSONObject handle=stream().getJSONObject("controlsLayout").getJSONObject("handle");capture("session-real-input");click("屏幕");waitFor(()->!stream().optBoolean("displayBusy"),20,"Screen inventory");JSONObject display=stream().getJSONObject("display");assertTrue(display.getJSONArray("displays").length()>0);capture("session-real-displays");
            click("声音");action("stream.audio.volume",RhActions.obj("volume",30));assertEquals(30,stream().getInt("volume"));assertFalse(stream().optBoolean("audioEnabled"));capture("session-real-audio");
            click("操作");boolean stats=stream().optBoolean("showStats");click(stats?"统计：开":"统计：关");assertEquals(!stats,stream().optBoolean("showStats"));click(!stats?"统计：开":"统计：关");
            boolean trackpad=stream().optBoolean("trackpad");action("stream.mode.toggle",RhActions.obj());assertEquals(!trackpad,stream().optBoolean("trackpad"));action("stream.mode.toggle",RhActions.obj());action("stream.pan.toggle",RhActions.obj());assertTrue(stream().optBoolean("pan"));action("stream.pan.toggle",RhActions.obj());action("input.gesture",RhActions.obj("gesture","pinch","factor",1.25));assertEquals(1.25,stream().getJSONObject("zoom").getDouble("scale"),.01);click("还原缩放");assertEquals(1,stream().getJSONObject("zoom").getDouble("scale"),.01);
            click("快捷键");assertEquals("shortcuts",stream().optString("streamMenu"));instrument.runOnMainSync(()->assertFalse(RhActions.dispatch("stream.shortcut",RhActions.obj("shortcut","rhremote-invalid-shortcut")).optBoolean("ok")));assertEquals("shortcuts",stream().optString("streamMenu"));assertFalse(stream().getJSONObject("controlAction").optString("error").isEmpty());action("stream.menu.close",RhActions.obj());assertEquals("",stream().optString("streamMenu"));waitFor(()->game.hasWindowFocus(),4,"Game window focus after closing menu");capture("session-real-before-back");
            mouseBackReachesViewWithoutNavigation();
            assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));waitFor(()->!stream().optBoolean("controls"),3,"Back collapses controls");assertEquals(handle.toString(),stream().getJSONObject("controlsLayout").getJSONObject("handle").toString());assertTrue(stream().optBoolean("connected"));
            assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));waitFor(()->stream().optBoolean("exitPrompt"),3,"Back asks before disconnecting");instrument.runOnMainSync(()->{AlertDialog dialog=(AlertDialog)exitDialogOf(game);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();});waitFor(()->!stream().optBoolean("exitPrompt"),3,"Continue connection");assertTrue(stream().optBoolean("connected"));
            if(testRecovery){
                ownTraces.add(RhStatistics.state().getString("latest"));Activity previousGame=game;long interrupted=System.nanoTime();stage("reconnecting",RhActions.obj("syntheticFailure",true));action("stream.test.interrupt",RhActions.obj());
                waitFor(()->{Activity current=resumedGame();return current!=null&&current!=previousGame&&stream().optBoolean("connected")&&stream().optBoolean("firstFrame");},100,"Replacement Activity and real first frame after interruption");game=resumedGame();long recoveredMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-interrupted);JSONObject recoveredPixels=videoPixels("session-recovered-video");assertTrue("Recovered video surface is black",recoveredPixels.optBoolean("visibleContent"));assertFalse(stream().optBoolean("audioEnabled"));assertTrue(stream().getJSONObject("controlAction").optString("error").isEmpty());ownTraces.add(RhStatistics.state().getString("latest"));assertNotEquals(ownTraces.get(0),ownTraces.get(1));
                recovery.put("replacementGame",true).put("recoveredFirstFrameMs",recoveredMs).put("videoPixels",recoveredPixels).put("oldFeedbackCleared",true);RhShared.write(new File(folder,"session-real-recovery.json"),recovery);capture("session-real-recovered");
            }
            action("stream.exit.request",RhActions.obj());instrument.runOnMainSync(()->((AlertDialog)exitDialogOf(game)).getButton(AlertDialog.BUTTON_POSITIVE).performClick());waitFor(()->!RhActions.hasStream(),15,"User disconnect cleanup");waitFor(()->homeState().optString("page").equals("devices"),6,"Return to original device");long quietEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<quietEnd){assertFalse("User exit must not trigger recovery",RhActions.hasStream());Thread.sleep(50);}
            if(testRecovery){waitFor(()->!RhStatistics.state().optBoolean("recording"),4,"Test trace ownership cleanup");for(int i=0;i<ownTraces.size();i++)exportOwnTrace(context,ownTraces.get(i),i);}
            RhShared.write(new File(folder,"session-real-flow.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"host",uuid,"firstFrameMs",firstFrameMs,"firstFrameSignal",true,"videoPixels",pixels,"emulatorDecoderOverride",software?"software":"default","audioEnabled",false,"groupedControls",true,"modePanZoomStats",true,"backCollapsedPanel",true,"backContinuePreservedSession",true,"userDisconnectDidNotRecover",true,"screenSelectionChanged",false,"secureAttentionSent",false,"scope","real local streaming and session UI; no network interruption; audio playback disabled and no audio samples stored"));assertTrue("Decoded video surface is black; see pixel evidence",pixels.optBoolean("visibleContent"));stage("passed",RhActions.obj("firstFrameMs",firstFrameMs));
        }finally{
            if(RhActions.hasStream()){action("stream.disconnect",RhActions.obj());waitFor(()->!RhActions.hasStream(),25,"Finally cleanup");}if(testRecovery){waitFor(()->!RhStatistics.state().optBoolean("recording"),4,"Finally trace cleanup");if(RhStatistics.enabled()!=originalTracing)action("statistics.configure",RhActions.obj("enabled",originalTracing));}if(blacklist!=null){blacklist.clear();blacklist.addAll(previousBlacklist);}RhShared.apply(context,original);assertEquals(before,RhShared.canonical(RhShared.read(context)));
        }
    }
    Object exitDialogOf(Activity owner){try{Field field=Game.class.getDeclaredField("rhControls");field.setAccessible(true);Object controls=field.get(owner);Field dialog=RhStreamControls.class.getDeclaredField("exitDialog");dialog.setAccessible(true);return dialog.get(controls);}catch(Exception e){throw new RuntimeException(e);}}
}
