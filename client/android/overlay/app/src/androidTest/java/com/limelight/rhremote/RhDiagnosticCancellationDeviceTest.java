package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual home buttons and loopback HTTP; no system input, OS screenshots or remote sessions. */
@RunWith(AndroidJUnit4.class)
public final class RhDiagnosticCancellationDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Context context;Activity activity;RhHome home;File folder;final JSONArray cases=new JSONArray();
    interface Work{void run()throws Exception;}
    void main(Work work){Throwable[] failure={null};instrument.runOnMainSync(()->{try{work.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    final RhHome.Actions actions=new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No remote sessions");}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String p){throw new AssertionError("No pairing");}public void cancelPair(){}public void refresh(){}};
    static final class Plan {
        final CountDownLatch entered=new CountDownLatch(1),closed=new CountDownLatch(1);final boolean automatic;final byte[] body;volatile Socket socket;volatile boolean peerClosed;
        Plan(String uuid,boolean automatic){this.automatic=automatic;body=("<root><uniqueid>"+uuid+"</uniqueid></root>").getBytes(StandardCharsets.UTF_8);}
        void respond()throws IOException{Socket owned=socket;assertNotNull(owned);owned.getOutputStream().write(body);owned.getOutputStream().flush();}
    }
    static final class Server implements AutoCloseable {
        final ServerSocket listener=new ServerSocket();final ExecutorService workers=Executors.newFixedThreadPool(3);final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final BlockingQueue<Plan> plans=new LinkedBlockingQueue<>();final AtomicInteger requests=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();volatile boolean closing;
        Server()throws IOException{listener.bind(new InetSocketAddress("127.0.0.8",47989));workers.execute(()->{while(!closing)try{Socket socket=listener.accept();sockets.add(socket);workers.execute(()->serve(socket));}catch(Exception e){if(!closing)failure.compareAndSet(null,e);}});}
        void serve(Socket socket){Plan plan=null;try(Socket owned=socket){owned.setSoTimeout(4000);BufferedReader input=new BufferedReader(new InputStreamReader(owned.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();if(first==null)return;assertTrue(first,first.startsWith("GET /serverinfo"));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
            plan=plans.poll(2,TimeUnit.SECONDS);assertNotNull("Unexpected serverinfo request",plan);requests.incrementAndGet();plan.socket=owned;owned.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nConnection: close\r\nContent-Length: "+plan.body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));owned.getOutputStream().flush();plan.entered.countDown();if(plan.automatic)plan.respond();else{assertEquals(-1,owned.getInputStream().read());plan.peerClosed=true;}
        }catch(Throwable e){if(!closing)failure.compareAndSet(null,e);}finally{sockets.remove(socket);if(plan!=null)plan.closed.countDown();}}
        @Override public void close()throws Exception{closing=true;listener.close();for(Socket socket:sockets)socket.close();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    JSONObject state(){JSONObject[] result={null};main(()->result[0]=home.state());return result[0];}
    void action(String id,JSONObject args){main(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    Button button(String tag){Button[] result={null};main(()->{result[0]=(Button)activity.findViewById(android.R.id.content).findViewWithTag(tag);assertNotNull(tag,result[0]);assertTrue(result[0] instanceof RhBoundAction.Button);});return result[0];}
    private int visibilityProbe;
    void visible(Button target)throws Exception{
        boolean audit="true".equals(InstrumentationRegistry.getArguments().getString("visibilityAudit",""));JSONArray samples=new JSONArray();
        long end=SystemClock.uptimeMillis()+2000;boolean[] visible={false};String[] requestedGeometry={null};
        do{instrument.waitForIdleSync();main(()->{
            boolean ready=target.isAttachedToWindow()&&target.isShown();StringBuilder geometry=new StringBuilder();View view=target;
            while(view!=null){ready&=!view.isLayoutRequested()&&view.getWidth()>0&&view.getHeight()>0;geometry.append(view.getLeft()).append(',').append(view.getTop()).append(',').append(view.getWidth()).append(',').append(view.getHeight()).append(';');view=view.getParent() instanceof View?(View)view.getParent():null;}
            String current=geometry.toString();boolean requested=false;
            if(ready&&!current.equals(requestedGeometry[0])){requested=target.requestRectangleOnScreen(new Rect(0,0,target.getWidth(),target.getHeight()),true);requestedGeometry[0]=current;}
            Rect bounds=new Rect();visible[0]=ready&&target.getGlobalVisibleRect(bounds)&&bounds.width()==target.getWidth()&&bounds.height()==target.getHeight();
            if(audit)samples.put(visibilitySample(target,ready?"current-layout":"await-layout",requested));
        });if(visible[0])break;Thread.sleep(10);}while(SystemClock.uptimeMillis()<end);
        if(audit)RhShared.write(new File(folder,"visibility-"+(++visibilityProbe)+".json"),RhActions.obj("fullyVisible",visible[0],"samples",samples));
        assertTrue("Button must be fully visible",visible[0]);
    }
    private JSONObject visibilitySample(Button target,String phase,boolean requested){
        Rect bounds=new Rect();boolean visible=target.getGlobalVisibleRect(bounds);JSONArray parents=new JSONArray();View view=target;
        while(view!=null){parents.put(RhActions.obj("type",view.getClass().getSimpleName(),"width",view.getWidth(),"height",view.getHeight(),"layoutRequested",view.isLayoutRequested(),"visibility",view.getVisibility(),"scrollY",view.getScrollY()));view=view.getParent() instanceof View?(View)view.getParent():null;}
        return RhActions.obj("uptimeMs",SystemClock.uptimeMillis(),"phase",phase,"requestAccepted",requested,"buttonLabel",target.getText().toString(),"shown",target.isShown(),"visible",visible,"bounds",bounds.toString(),"width",target.getWidth(),"height",target.getHeight(),"parents",parents);
    }
    void touch(Button target,int action,long down){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,target.getWidth()/2f,target.getHeight()/2f,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{assertTrue(target.dispatchTouchEvent(event));}finally{event.recycle();}}
    void key(Button target,int action,long down){target.dispatchKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_ENTER,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD));}
    void down(Button target,String mode,long when){if(mode.equals("touch"))touch(target,MotionEvent.ACTION_DOWN,when);else{target.setFocusableInTouchMode(true);assertTrue(target.requestFocus());key(target,KeyEvent.ACTION_DOWN,when);}}
    void up(Button target,String mode,long when){if(mode.equals("touch"))touch(target,MotionEvent.ACTION_UP,when);else key(target,KeyEvent.ACTION_UP,when);}
    void idle()throws Exception{long end=SystemClock.uptimeMillis()+3000;while(state().optBoolean("networkBusy")){instrument.waitForIdleSync();if(SystemClock.uptimeMillis()>end)fail(state().toString());Thread.sleep(10);}instrument.waitForIdleSync();}
    void capture(String name)throws Exception{main(()->{View root=activity.findViewById(android.R.id.content);Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);try{root.draw(new Canvas(bitmap));try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}finally{bitmap.recycle();}});}
    void clickPrimary()throws Exception{Button start=button("device.network.diagnose");visible(start);main(()->{assertTrue(start.isEnabled());assertTrue(start.performClick());});instrument.waitForIdleSync();}
    void scenario(Server server,boolean dark,int width,int font,String mode,String kind)throws Exception{
        String uuid="diagnostic-native-host",name=(dark?"dark":"light")+"-"+width+"-font"+font+"-"+mode+"-"+kind;
        main(()->{if(home!=null)home.dispose();context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();home=new RhHome(activity,actions);home.resumed();ComputerDetails source=new ComputerDetails();source.uuid=uuid;source.name="Diagnostic fixture";source.manualAddress=new ComputerDetails.AddressTuple("127.0.0.8",47989);RhShared.capture(context,Collections.singletonList(source));home.update(source);home.perform("device.settings.open",RhActions.obj("uuid",uuid));home.perform("settings.tab",RhActions.obj("tab","network"));home.perform("settings.set",RhActions.obj("key","routeOrder","value",new JSONArray().put("lan")));});instrument.waitForIdleSync();
        String saved=RhShared.canonical(RhShared.read(context));int before=server.requests.get();
        if(kind.equals("edited-start")){
            Button start=button("device.network.diagnose");visible(start);long original=state().getJSONObject("diagnostics").optLong("operationId"),when=SystemClock.uptimeMillis();
            main(()->{down(start,mode,when);home.perform("settings.network.edit",RhActions.obj("key","address","value","127.0.0.9"));up(start,mode,when);});instrument.waitForIdleSync();assertFalse(state().optBoolean("networkBusy"));assertEquals(original,state().getJSONObject("diagnostics").optLong("operationId"));assertEquals(before,server.requests.get());
            action("settings.network.edit",RhActions.obj("key","address","value","127.0.0.8"));Plan fresh=new Plan(uuid,true);server.plans.add(fresh);clickPrimary();idle();assertEquals("completed",state().getJSONObject("diagnostics").getString("phase"));record(server,name,saved,before,1);return;
        }
        Plan first=new Plan(uuid,false);server.plans.add(first);clickPrimary();assertTrue(first.entered.await(2,TimeUnit.SECONDS));Button cancel=button("device.network.cancel");visible(cancel);assertTrue(state().optBoolean("networkBusy"));long original=state().getJSONObject("diagnostics").getLong("operationId"),when=SystemClock.uptimeMillis();
        if(kind.equals("background-cancel")){
            action("settings.cancel",RhActions.obj());action("app.settings.tab",RhActions.obj("tab","about"));Button banner=button("diagnostic.banner.cancel");visible(banner);capture(name+"-about-running");main(()->{down(banner,mode,when);up(banner,mode,when);});idle();assertEquals("cancelled",state().getJSONObject("diagnostics").getString("phase"));assertEquals("about",state().getString("settingsTab"));assertTrue(first.closed.await(1,TimeUnit.SECONDS));assertTrue(first.peerClosed);
            action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.tab",RhActions.obj("tab","network"));record(server,name,saved,before,1);return;
        }
        if(kind.equals("cancel-retry")){
            main(()->{assertTrue(cancel.isEnabled());down(cancel,mode,when);up(cancel,mode,when);});idle();assertTrue(first.closed.await(1,TimeUnit.SECONDS));assertTrue("Cancellation did not close the HTTP socket",first.peerClosed);assertEquals("cancelled",state().getJSONObject("diagnostics").getString("phase"));
            Plan retry=new Plan(uuid,true);server.plans.add(retry);clickPrimary();idle();assertEquals("completed",state().getJSONObject("diagnostics").getString("phase"));assertEquals(before+2,server.requests.get());assertEquals(original+1,state().getJSONObject("diagnostics").getLong("operationId"));
        }else{
            main(()->down(cancel,mode,when));first.respond();idle();Plan next=new Plan(uuid,false);server.plans.add(next);clickPrimary();assertTrue(next.entered.await(2,TimeUnit.SECONDS));visible(cancel);
            main(()->up(cancel,mode,when));instrument.waitForIdleSync();assertTrue(state().optBoolean("networkBusy"));assertFalse("Old held Cancel cancelled the new task",state().getJSONObject("diagnostics").getBoolean("cancelRequested"));
            main(()->assertTrue(cancel.performClick()));idle();assertTrue(next.closed.await(1,TimeUnit.SECONDS));assertTrue(next.peerClosed);assertEquals("cancelled",state().getJSONObject("diagnostics").getString("phase"));assertEquals(before+2,server.requests.get());
        }
        record(server,name,saved,before,2);
    }
    void record(Server server,String name,String saved,int before,int expected)throws Exception{
        Button start=button("device.network.diagnose");visible(start);main(()->assertTrue(start.isEnabled()));assertEquals(saved,RhShared.canonical(RhShared.read(context)));assertFalse(RhActions.inspecting());assertNull(server.failure.get());assertEquals(expected,server.requests.get()-before);
        if(name.endsWith("-edited-start")){Button details=button("device.network.details.toggle");visible(details);main(()->assertTrue(details.performClick()));instrument.waitForIdleSync();visible(details);main(()->{assertEquals("收起详情",details.getText().toString());assertTrue(details.performClick());});instrument.waitForIdleSync();assertEquals(expected,server.requests.get()-before);}
        main(()->cases.put(RhActions.obj("name",name,"localServerInfoRequests",server.requests.get()-before,"networkBusy",home.state().optBoolean("networkBusy"),"diagnostics",home.state().getJSONObject("diagnostics"),"fontScale",activity.getResources().getConfiguration().fontScale,"windowWidth",activity.findViewById(android.R.id.content).getWidth(),"windowHeight",activity.findViewById(android.R.id.content).getHeight())));capture(name);
    }
    @Test public void cancelRetryAndHeldOldCancelUseActualButtonsAndLoopbackRequests()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("diagnosticCancelRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));context=instrument.getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        try(Server server=new Server()){
            for(int width:new int[]{360,1008})for(int font:new int[]{1,2}){
                main(()->RhUiFixtureActivity.fontScale(font));activity=instrument.startActivitySync(new Intent().setComponent(new ComponentName(context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));main(()->{assertEquals((float)font,activity.getResources().getConfiguration().fontScale,0f);float density=activity.getResources().getDisplayMetrics().density;activity.getWindow().setLayout(Math.round(width*density),Math.round((width==360?560:640)*density));});instrument.waitForIdleSync();
                for(boolean dark:new boolean[]{false,true})for(String mode:new String[]{"touch","keyboard"})for(String kind:new String[]{"cancel-retry","held-old-cancel","edited-start","background-cancel"})scenario(server,dark,width,font,mode,kind);
                main(()->{home.dispose();home=null;activity.finish();});instrument.waitForIdleSync();
            }
        }finally{main(()->{if(home!=null){JSONObject work=home.state().getJSONObject("diagnostics");if(work.optBoolean("busy"))home.perform("device.network.cancel",RhActions.obj("operationId",work.optLong("operationId")));home.dispose();}if(activity!=null)activity.finish();RhUiFixtureActivity.fontScale(0);});instrument.waitForIdleSync();}
        assertEquals(64,cases.length());try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"result.json")),"UTF-8")){out.write(RhActions.obj("scope","Actual RhHome controls and owned loopback HTTP; direct View events; app-scoped font configuration","cases",cases,"oppoOperated",false,"osScreenshots",false).toString(2));}
    }
}
