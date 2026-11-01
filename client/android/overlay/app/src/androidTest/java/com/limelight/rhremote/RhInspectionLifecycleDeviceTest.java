package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual home/dispatcher with an owned blocking child and real loopback preflight. */
@RunWith(AndroidJUnit4.class)
public final class RhInspectionLifecycleDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final BlockingQueue<Runnable> jobs=new LinkedBlockingQueue<>();final JSONArray cases=new JSONArray();Context context;File folder;
    final RhHome.Actions actions=new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No streaming");}public void add(String value){throw new AssertionError();}public void advanced(){}public void pair(ComputerDetails d,String pin){throw new AssertionError("No pairing");}public void cancelPair(){}public void refresh(){}public Executor checkExecutor(){return jobs::add;}};
    static final class Server implements AutoCloseable {
        final ServerSocket socket=new ServerSocket();final ExecutorService worker=Executors.newSingleThreadExecutor();final AtomicInteger requests=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();volatile String uuid;volatile boolean closed;
        Server()throws Exception{socket.setReuseAddress(true);socket.bind(new InetSocketAddress("127.0.0.11",47989));worker.execute(()->{while(!closed)try(Socket peer=socket.accept()){peer.setSoTimeout(2000);BufferedReader input=new BufferedReader(new InputStreamReader(peer.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();assertNotNull(first);assertTrue(first,first.startsWith("GET /serverinfo"));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
            requests.incrementAndGet();byte[] body=("<root status_code=\"200\"><appversion>7.1.0</appversion><uniqueid>"+uuid+"</uniqueid><PairStatus>0</PairStatus><HttpsPort>47984</HttpsPort><state>SUNSHINE_SERVER_FREE</state></root>").getBytes(StandardCharsets.UTF_8);peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/xml\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));peer.getOutputStream().write(body);peer.getOutputStream().flush();
        }catch(Throwable error){if(!closed)failure.compareAndSet(null,error);}});}
        public void close()throws Exception{closed=true;socket.close();worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));assertNull(failure.get());}
    }
    void runJob()throws Exception{Runnable task=jobs.poll(2,TimeUnit.SECONDS);assertNotNull("Owned job missing",task);task.run();rig.instrument.waitForIdleSync();}
    ComputerDetails setup(String uuid,boolean dark)throws Exception{
        ComputerDetails target=new ComputerDetails();target.uuid=uuid;target.name="检查测试设备";target.manualAddress=new ComputerDetails.AddressTuple("127.0.0.11",47989);
        rig.main(()->{if(rig.home!=null)rig.home.dispose();RhDiagnosticWork.shared(context).clearCheck(uuid);context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();RhShared.capture(context,Collections.singletonList(target));RhShared.saveAddresses(context,uuid,"127.0.0.11",new JSONArray());RhProfile profile=RhProfile.load(context,uuid);profile.routeOrder=new ArrayList<>(Collections.singletonList("lan"));RhShared.saveProfile(context,target,profile,"");rig.home=new RhHome(rig.activity,actions);rig.home.resumed();rig.home.update(target);rig.home.perform("device.select",RhActions.obj("uuid",uuid));});rig.instrument.waitForIdleSync();assertTrue(jobs.isEmpty());return target;
    }
    Button primary()throws Exception{if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){Field f=RhHome.class.getDeclaredField("browser");f.setAccessible(true);Object browser=f.get(rig.home);f=RhDeviceBrowser.class.getDeclaredField("connect");f.setAccessible(true);return (Button)f.get(browser);}Button[] result={null};rig.main(()->result[0]=primary());return result[0];}
    JSONObject deviceRow(JSONObject state,String uuid)throws Exception{JSONArray rows=state.getJSONArray("devices");for(int i=0;i<rows.length();i++)if(uuid.equals(rows.getJSONObject(i).getString("uuid")))return rows.getJSONObject(i);throw new AssertionError("Missing fixture device");}
    void start()throws Exception{Button button=primary();try{rig.visible(button);}catch(AssertionError failure){
        rig.capture("start-not-visible");rig.main(()->{Rect bounds=new Rect();boolean visible=button.getGlobalVisibleRect(bounds);JSONArray parents=new JSONArray();View view=button;while(view!=null){parents.put(RhActions.obj("type",view.getClass().getSimpleName(),"width",view.getWidth(),"height",view.getHeight(),"visibility",view.getVisibility(),"scrollX",view.getScrollX(),"scrollY",view.getScrollY()));view=view.getParent() instanceof View?(View)view.getParent():null;}RhShared.write(new File(folder,"start-not-visible.json"),RhActions.obj("state",rig.home.state(),"buttonShown",button.isShown(),"globalVisible",visible,"bounds",bounds.toString(),"parents",parents));});throw failure;
        }rig.main(()->{assertEquals("检查连接",button.getText().toString());assertTrue(button.isEnabled());assertTrue(button.performClick());});rig.instrument.waitForIdleSync();assertTrue(rig.state().optBoolean("networkBusy"));}
    void waiting(String name,boolean dark)throws Exception{
        String uuid="waiting-host";setup(uuid,dark);String saved=RhShared.canonical(RhShared.read(context));start();Object[] flight={null};rig.main(()->{Field f=RhDiagnosticWork.class.getDeclaredField("flight");f.setAccessible(true);flight[0]=f.get(RhDiagnosticWork.shared(context));});
        ExecutorService child=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean exited=new AtomicBoolean();
        try{Future<Integer> future=RhProbeWork.submit(new ExecutorCompletionService<>(child),()->{entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}exited.set(true);return 1;},(RhCancellation)flight[0]);assertTrue(entered.await(2,TimeUnit.SECONDS));
            Button cancel=rig.button("diagnostic.banner.cancel");rig.visible(cancel);rig.main(()->{assertTrue(cancel.isEnabled());assertTrue(cancel.performClick());});assertTrue(future.cancel(true));assertTrue(future.isDone());runJob();
            JSONObject state=rig.state(),work=state.getJSONObject("diagnostics");assertTrue(state.getBoolean("networkBusy"));assertEquals("waiting-probes",work.getString("phase"));assertEquals(1,work.getInt("pendingProbes"));assertFalse(exited.get());assertTrue(RhActions.inspecting());
            rig.main(()->{assertFalse(cancel.isEnabled());assertFalse(primary().isEnabled());LinearLayout banner=(LinearLayout)rig.activity.findViewById(android.R.id.content).findViewWithTag("diagnostic.banner");assertTrue(banner.isShown());TextView text=(TextView)banner.getChildAt(0);assertTrue(text.getText().toString().contains("等待 1 个地址探测结束"));Rect bounds=new Rect();assertTrue(text.getGlobalVisibleRect(bounds));assertEquals(text.getHeight(),bounds.height());});
            rig.main(()->{JSONObject denied=RhActions.dispatch("device.connect",RhActions.obj("uuid",uuid));assertFalse(denied.toString(),denied.optBoolean("ok"));});rig.capture(name+"-waiting");
            release.countDown();runJob();rig.idle();assertTrue(exited.get());assertFalse(RhActions.inspecting());assertEquals("cancelled",rig.state().getJSONObject("diagnostics").getString("phase"));assertEquals(saved,RhShared.canonical(RhShared.read(context)));rig.capture(name+"-cancelled");
            cases.put(RhActions.obj("name",name,"kind","controlled-noninterruptible-child","waiting",work,"futureCancelledBeforeBodyExit",true,"resumedWithoutManualRetry",true,"savedConfigurationUnchanged",true));
        }finally{if(rig.state().getBoolean("networkBusy"))rig.action("device.network.cancel",RhActions.obj("operationId",rig.state().getJSONObject("diagnostics").getLong("operationId")));release.countDown();child.shutdown();assertTrue(child.awaitTermination(3,TimeUnit.SECONDS));if(rig.state().getBoolean("networkBusy")){while(!jobs.isEmpty())runJob();rig.idle();}}
    }
    void stale(Server server,String name,boolean dark)throws Exception{
        String uuid="stale-host";setup(uuid,dark);server.uuid=uuid;int before=server.requests.get();start();rig.main(()->RhShared.saveAddresses(context,uuid,"127.0.0.12",new JSONArray()));String saved=RhShared.canonical(RhShared.read(context));runJob();rig.idle();
        JSONObject row=deviceRow(rig.state(),uuid);assertTrue(row.getJSONObject("check").getBoolean("stale"));assertFalse(row.getJSONObject("check").has("outcome"));assertEquals("device.check",row.getString("primaryAction"));assertEquals("unknown",row.getString("reachabilityStatus"));assertEquals(1,server.requests.get()-before);assertEquals(saved,RhShared.canonical(RhShared.read(context)));
        rig.main(()->{assertEquals("检查连接",primary().getText().toString());assertTrue(primary().isEnabled());});rig.capture(name+"-stale");cases.put(RhActions.obj("name",name,"kind","settings-changed-during-real-loopback-check","row",row,"localRequests",server.requests.get()-before,"savedConfigurationUnchangedAfterChange",true));
    }
    void sidebar(Server server,String name,boolean dark,int width)throws Exception{
        String uuid="sidebar-host";setup(uuid,dark);server.uuid=uuid;start();rig.action("device.settings.open",RhActions.obj("uuid",uuid));rig.action("settings.input",RhActions.obj("key","bitrateMbps","value","31"));EditText[] editor={null};
        rig.main(()->{Field f=RhHome.class.getDeclaredField("numberEditors");f.setAccessible(true);editor[0]=(EditText)((Map<?,?>)f.get(rig.home)).get("bitrateMbps");assertNotNull(editor[0]);assertTrue(editor[0].requestFocusFromTouch());});String inputs=rig.state().getJSONObject("settingsInputs").toString();runJob();rig.idle();JSONObject state=rig.state();assertEquals("settings",state.getString("page"));assertEquals(inputs,state.getJSONObject("settingsInputs").toString());assertEquals("unpaired",deviceRow(state,uuid).getString("pairingStatus"));
        rig.main(()->{assertTrue(editor[0].hasFocus());assertEquals("31",editor[0].getText().toString());if(width==1008){assertEquals("settings-split",rig.home.state().getJSONObject("homeLayout").getJSONObject("browser").getString("mode"));Field f=RhHome.class.getDeclaredField("browser");f.setAccessible(true);Object browser=f.get(rig.home);f=RhDeviceBrowser.class.getDeclaredField("rows");f.setAccessible(true);ViewGroup row=(ViewGroup)((Map<?,?>)f.get(browser)).get(uuid);assertEquals("未配对",((TextView)((ViewGroup)row.getChildAt(1)).getChildAt(2)).getText().toString());}});rig.capture(name+"-sidebar");cases.put(RhActions.obj("name",name,"kind","completion-keeps-profile-editor","mode",state.getJSONObject("homeLayout").getJSONObject("browser").getString("mode"),"draftAndFocusRetained",true));
    }
    @Test public void inspectionWaitingStaleResultsAndSidebarKeepTheirActualControls()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("inspectionLifecycleRun","");org.junit.Assume.assumeFalse("Explicit isolated emulator run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));context=rig.instrument.getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));rig.context=context;folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());rig.folder=folder;
        try(Server server=new Server()){
            for(int width:new int[]{360,1008})for(int font:new int[]{1,2}){
                rig.main(()->RhUiFixtureActivity.fontScale(font));rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*density),Math.round((width==360?560:640)*density));});rig.instrument.waitForIdleSync();
                for(boolean dark:new boolean[]{false,true}){String name=(dark?"dark":"light")+"-"+width+"-font"+font;waiting(name,dark);stale(server,name,dark);sidebar(server,name,dark,width);}
                rig.main(()->{rig.home.dispose();rig.home=null;rig.activity.finish();});rig.instrument.waitForIdleSync();
            }
        }finally{rig.main(()->{if(rig.home!=null)rig.home.dispose();if(rig.activity!=null)rig.activity.finish();RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();}
        assertEquals(24,cases.length());RhShared.write(new File(folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"package",context.getPackageName(),"scope","Actual RhHome and shared action dispatcher; controlled noninterruptible child models DNS, real loopback HTTP for stale results and profile completion; not real DNS or WAN","cases",cases,"images",32,"oppoOperated",false,"osInputInjected",false));
    }
}
