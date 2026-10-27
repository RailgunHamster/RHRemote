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
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual PcView recreation and loopback diagnostics; no system input or screenshots. */
@RunWith(AndroidJUnit4.class)
public class RhDiagnosticFlowDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Activity activity;RhHome home;
    void bind(Activity value)throws Exception{activity=value;Field field=value.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(value);}
    JSONObject state(){JSONObject[] value={null};instrument.runOnMainSync(()->{try{value[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return value[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View view,String label){if(!view.isShown())return null;if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    Button button(String label){View view=find(activity.findViewById(android.R.id.content),label);assertNotNull(label,view);return (Button)view;}
    void click(String label){
        instrument.runOnMainSync(()->{View view=button(label);view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);});instrument.waitForIdleSync();
        instrument.runOnMainSync(()->{View view=button(label);Rect visible=new Rect();assertTrue(label,view.getGlobalVisibleRect(visible));assertEquals(label,view.getHeight(),visible.height());assertEquals(label,view.getWidth(),visible.width());assertTrue(label,view.isEnabled());assertTrue(view.performClick());});instrument.waitForIdleSync();
    }
    void toggle()throws Exception{
        Activity old=activity;boolean dark=RhShared.dark(activity);action("appearance.toggle",RhActions.obj());long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);Activity[] next={null};
        while(next[0]==null){instrument.runOnMainSync(()->{for(Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(candidate!=old&&candidate.getClass().getName().equals("com.limelight.PcView"))next[0]=candidate;});assertTrue("PcView did not recreate",System.nanoTime()<end);if(next[0]==null)Thread.sleep(20);}
        bind(next[0]);instrument.waitForIdleSync();assertEquals(!dark,RhShared.dark(activity));
    }
    void capture(String name)throws Exception{
        instrument.waitForIdleSync();Bitmap[] frame={null};instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();frame[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(frame[0]));});
        File root=activity.getExternalFilesDir("ui-evidence");assertTrue(root.isDirectory()||root.mkdirs());try(OutputStream out=new FileOutputStream(new File(root,name+".png"))){assertTrue(frame[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{frame[0].recycle();}
    }
    void waitForCompletion()throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(state().getBoolean("networkBusy")){assertTrue("Diagnostic did not finish: "+state().getJSONObject("diagnostics"),System.nanoTime()<end);Thread.sleep(25);}instrument.waitForIdleSync();
    }
    static final class Server implements Closeable {
        final ServerSocket socket=new ServerSocket();final ExecutorService workers=Executors.newCachedThreadPool();final AtomicInteger requests=new AtomicInteger();final String uuid;volatile boolean mismatch;
        Server(String id)throws Exception{uuid=id;socket.setReuseAddress(true);socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.6"),47989),8);workers.execute(()->{try{while(!socket.isClosed()){Socket peer=socket.accept();workers.execute(()->reply(peer));}}catch(IOException stopped){}});}
        void reply(Socket peer){try(Socket owned=peer){owned.setSoTimeout(1000);BufferedReader input=new BufferedReader(new InputStreamReader(owned.getInputStream(),StandardCharsets.US_ASCII));String request=input.readLine();if(request==null||!request.contains("/serverinfo"))return;requests.incrementAndGet();byte[] body=("<root><uniqueid>"+(mismatch?"different-device":uuid)+"</uniqueid></root>").getBytes(StandardCharsets.UTF_8);OutputStream out=owned.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}catch(IOException stopped){}}
        public void close()throws IOException{socket.close();workers.shutdownNow();}
    }
    @Test public void queuedDiagnosticSurvivesRecreationAndCompletionPreservesTheCurrentEditor()throws Exception{run(false);}
    @Test public void backgroundIdentityFailureIsRetainedWithoutChangingTheOtherPage()throws Exception{run(true);}
    void run(boolean mismatch)throws Exception{
        Context context=instrument.getTargetContext();assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));assertFalse(RhActions.hasStream());assertFalse("Dedicated UI emulator must not auto-sync",RhSyncWork.shared(context).state().optBoolean("automatic"));
        JSONObject original=RhShared.read(context);String originalCanonical=RhShared.canonical(original),uuid="diagnostic-ui-"+java.util.UUID.randomUUID();boolean dark=RhShared.dark(context);
        SharedPreferences global=context.getSharedPreferences("rhremote.global",0);boolean hadCheck=global.contains("lastUpdateCheck");long previousCheck=global.getLong("lastUpdateCheck",0);global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
        CountDownLatch blocked=new CountDownLatch(2),release=new CountDownLatch(1);Server server=new Server(uuid);server.mismatch=mismatch;
        try{
            JSONObject fixture=new JSONObject(original.toString());fixture.getJSONArray("devices").put(RhActions.obj("uuid",uuid,"name","UI diagnostic","displayName","","swapButtons",false,"address","127.0.0.6"));fixture.getJSONObject("profiles").getJSONObject(RhShared.clientId(context)).put(uuid,RhProfile.load(context,uuid).clientJson());String fixtureCanonical=RhShared.canonical(fixture);RhShared.apply(context,fixture);
            bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();
            action("device.settings.open",RhActions.obj("uuid",uuid));click("网络");
            Field pool=RhHome.class.getDeclaredField("NETWORK");pool.setAccessible(true);ExecutorService executor=(ExecutorService)pool.get(null);
            for(int i=0;i<2;i++)executor.execute(()->{blocked.countDown();try{assertTrue(release.await(25,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue("Could not hold the app's test worker queue",blocked.await(3,TimeUnit.SECONDS));click("诊断连接");assertTrue(state().getBoolean("networkBusy"));long operation=state().getJSONObject("diagnostics").getLong("operationId");
            String tag=mismatch?"diagnostic-failure":"diagnostic-success";capture(tag+"-queued");
            for(int round=0;round<2;round++){toggle();assertEquals("network",state().getString("profileTab"));assertEquals(uuid,state().getString("selected"));assertTrue(state().getBoolean("networkBusy"));assertEquals(operation,state().getJSONObject("diagnostics").getLong("operationId"));instrument.runOnMainSync(()->{assertFalse(button("诊断中…").isEnabled());assertFalse(RhActions.dispatch("device.network.diagnose",RhActions.obj("uuid",uuid)).optBoolean("ok"));});capture(tag+"-recreated-"+round);}
            EditText[] editor={null};String otherStatus;
            if(mismatch){action("settings.cancel",RhActions.obj());action("app.settings.tab",RhActions.obj("tab","about"));otherStatus=state().getString("status");}
            else{otherStatus="";instrument.runOnMainSync(()->{try{Field input=RhHome.class.getDeclaredField("primaryEditor");input.setAccessible(true);editor[0]=(EditText)input.get(home);editor[0].setText("127.0.0.7");editor[0].setSelection(3);}catch(Exception e){throw new RuntimeException(e);}});}
            release.countDown();waitForCompletion();JSONObject diagnostic=state().getJSONObject("diagnostics");assertEquals(operation,diagnostic.getLong("operationId"));assertEquals(1,server.requests.get());
            if(mismatch){assertEquals("error",diagnostic.getString("phase"));assertEquals("about",state().getString("settingsTab"));assertEquals(otherStatus,state().getString("status"));capture(tag+"-other-page");action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.tab",RhActions.obj("tab","network"));}
            else{assertEquals("completed",diagnostic.getString("phase"));assertEquals("127.0.0.6",diagnostic.getJSONObject("result").getString("selectedAddress"));assertEquals("127.0.0.7",state().getString("draftAddress"));instrument.runOnMainSync(()->{try{Field input=RhHome.class.getDeclaredField("primaryEditor");input.setAccessible(true);assertSame(editor[0],input.get(home));assertEquals(3,editor[0].getSelectionStart());}catch(Exception e){throw new RuntimeException(e);}});}
            instrument.runOnMainSync(()->{assertTrue(button("诊断连接").isEnabled());try{Field report=RhHome.class.getDeclaredField("diagnosticReport");report.setAccessible(true);TextView text=(TextView)report.get(home);assertFalse(text.getText().toString().isEmpty());if(mismatch)assertEquals(RhUiStyle.failure(RhShared.dark(context)),text.getCurrentTextColor());text.requestRectangleOnScreen(new Rect(0,0,text.getWidth(),text.getHeight()),true);}catch(Exception e){throw new RuntimeException(e);}});capture(tag+"-result");action("settings.cancel",RhActions.obj());assertTrue("Diagnostic/cancel changed the submitted shared configuration",fixtureCanonical.equals(RhShared.canonical(RhShared.read(context))));
            RhShared.write(new File(activity.getExternalFilesDir("ui-evidence"),tag+".json"),RhActions.obj("recreations",2,"requests",server.requests.get(),"diagnostic",diagnostic,"systemInputInjected",false,"osScreenshots",false,"streamStarted",false));
        }finally{
            release.countDown();try{waitForCompletion();}catch(Exception ignored){}server.close();
            if(activity!=null&&!activity.isDestroyed())instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();RhShared.apply(context,original);assertTrue("The emulator configuration was not restored",originalCanonical.equals(RhShared.canonical(RhShared.read(context))));assertEquals(dark,RhShared.dark(context));
            SharedPreferences.Editor restore=global.edit();if(hadCheck)restore.putLong("lastUpdateCheck",previousCheck);else restore.remove("lastUpdateCheck");restore.commit();
        }
    }
}
