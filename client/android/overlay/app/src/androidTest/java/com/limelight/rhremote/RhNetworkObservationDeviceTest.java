package com.limelight.rhremote;

import android.content.*;
import android.net.*;
import android.os.*;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.computers.*;
import com.limelight.discovery.DiscoveryService;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual polling lifecycle and Views; replace only mDNS and the remote peer. */
@RunWith(AndroidJUnit4.class)
public final class RhNetworkObservationDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final JSONArray cases=new JSONArray();String uuid;
    static Object field(Object owner,String name)throws Exception{
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(NoSuchFieldException next){}
        throw new NoSuchFieldException(name);
    }
    static final class Gate {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    }
    static final class Host implements AutoCloseable {
        final String uuid;final ServerSocket listener=new ServerSocket();final ExecutorService workers=Executors.newFixedThreadPool(3);
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final List<Gate> gates=new CopyOnWriteArrayList<>();final AtomicReference<Gate> nextGate=new AtomicReference<>();
        final AtomicInteger requests=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();volatile boolean malformed,closing;
        Host(String uuid)throws Exception{
            this.uuid=uuid;listener.bind(new InetSocketAddress("127.0.0.8",47989));listener.setSoTimeout(100);
            workers.execute(()->{while(!closing)try{Socket socket=listener.accept();sockets.add(socket);workers.execute(()->serve(socket));}catch(SocketTimeoutException wait){}catch(Throwable error){if(!closing)failure.compareAndSet(null,error);}});
        }
        Gate holdNext(){Gate value=new Gate();gates.add(value);assertTrue(nextGate.compareAndSet(null,value));return value;}
        void serve(Socket socket){try(Socket owned=socket){
            owned.setSoTimeout(4000);BufferedReader input=new BufferedReader(new InputStreamReader(owned.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();if(first==null)return;
            assertTrue(first,first.startsWith("GET /serverinfo?"));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}requests.incrementAndGet();
            Gate gate=nextGate.getAndSet(null);if(gate!=null){gate.entered.countDown();assertTrue("Owned response gate was not released",gate.release.await(5,TimeUnit.SECONDS));}
            String body=malformed?"<root>":"<root status_code=\"200\"><appversion>7.1.0</appversion><hostname>Timer fixture</hostname><uniqueid>"+uuid+"</uniqueid><HttpsPort>47984</HttpsPort><PairStatus>0</PairStatus><state>SUNSHINE_SERVER_FREE</state></root>";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);owned.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));owned.getOutputStream().write(bytes);owned.getOutputStream().flush();
        }catch(Throwable error){if(!closing)failure.compareAndSet(null,error);}finally{sockets.remove(socket);}}
        @Override public void close()throws Exception{closing=true;for(Gate gate:gates)gate.release.countDown();listener.close();for(Socket socket:sockets)socket.close();workers.shutdownNow();assertTrue(workers.awaitTermination(4,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError("Owned peer failed",failure.get());}
    }
    static final class IsolatedService extends ComputerManagerService {
        final DiscoveryService discovery=new DiscoveryService();final AtomicInteger starts=new AtomicInteger(),stops=new AtomicInteger();ServiceConnection connection;
        final DiscoveryService.DiscoveryBinder discoveryBinder=discovery.new DiscoveryBinder(){
            @Override public void startDiscovery(int period){assertEquals(1000,period);starts.incrementAndGet();}
            @Override public void stopDiscovery(){stops.incrementAndGet();}
        };
        void attach(Context context){attachBaseContext(context);}
        @Override public boolean bindService(Intent intent,ServiceConnection callback,int flags){assertEquals(DiscoveryService.class.getName(),intent.getComponent().getClassName());connection=callback;callback.onServiceConnected(intent.getComponent(),discoveryBinder);return true;}
        @Override public void unbindService(ServiceConnection callback){assertSame(connection,callback);connection=null;}
    }
    JSONObject row()throws Exception{
        JSONObject state=rig.state();JSONArray devices=state.getJSONArray("devices");for(int i=0;i<devices.length();i++)if(devices.getJSONObject(i).optString("uuid").equals(uuid))return devices.getJSONObject(i);throw new AssertionError("Missing owned device");
    }
    JSONObject await(String reason,Predicate<JSONObject> predicate,long timeout)throws Exception{
        long deadline=SystemClock.uptimeMillis()+timeout;JSONObject value=null;
        do{rig.instrument.waitForIdleSync();value=row();if(predicate.test(value))return value;Thread.sleep(20);}while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError(reason+": "+value);
    }
    void capture(String tag,String expectedAction,String expectedLabel,JSONArray stages)throws Exception{
        JSONObject value=row();assertEquals(expectedAction,value.getString("primaryAction"));assertEquals(expectedLabel,value.getString("primaryLabel"));Button[] primary={null};
        rig.main(()->{Object browser=field(rig.home,"browser");primary[0]=(Button)field(browser,"connect");assertEquals(expectedLabel,primary[0].getText().toString());assertTrue(primary[0].isEnabled());});
        rig.visible(primary[0]);rig.capture(tag);stages.put(RhActions.obj("name",tag,"row",value,"generation",RhNetworkObservation.generation()));
    }
    static void stop(ComputerManagerService.ComputerManagerBinder binder,IsolatedService service)throws Exception{
        if(binder==null)return;List<Thread> owned=new ArrayList<>();Object tuples=field(service,"pollingTuples");
        synchronized(tuples){for(Object tuple:(Iterable<?>)tuples){Thread thread=(Thread)field(tuple,"thread");if(thread!=null)owned.add(thread);}}
        binder.stopPolling();for(Thread thread:owned){thread.join(3000);assertFalse("Owned polling thread did not stop",thread.isAlive());}
    }
    void scenario(boolean dark,int width,int font)throws Exception{
        String tag=(dark?"dark":"light")+"-"+width+"-font"+font;JSONArray stages=new JSONArray();List<JSONObject> callbacks=Collections.synchronizedList(new ArrayList<>());
        IsolatedService service=new IsolatedService();ComputerManagerService.ComputerManagerBinder[] binder={null};boolean[] created={false};
        Host host=new Host(uuid);
        try{
            ComputerDetails seed=new ComputerDetails();seed.uuid=uuid;seed.name="Timer fixture";seed.manualAddress=new ComputerDetails.AddressTuple("127.0.0.8",47989);
            ComputerDatabaseManager db=new ComputerDatabaseManager(rig.context);try{assertTrue(db.updateComputer(seed));}finally{db.close();}RhShared.capture(rig.context,Collections.singleton(seed));String saved=RhShared.canonical(RhShared.read(rig.context));
            rig.main(()->{rig.context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();RhUiFixtureActivity.fontScale(font);});
            rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            rig.main(()->{float density=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*density),Math.round(640*density));
                rig.home=new RhHome(rig.activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No stream");}public void add(String address){}public void advanced(){}public void pair(ComputerDetails d,String pin){throw new AssertionError("No pairing");}public void cancelPair(){}public void refresh(){}});rig.home.resumed();
                service.attach(rig.context);service.onCreate();created[0]=true;binder[0]=(ComputerManagerService.ComputerManagerBinder)service.onBind(null);
                binder[0].startPolling(details->{ComputerDetails copy=new ComputerDetails(details);callbacks.add(RhActions.obj("state",copy.state.name(),"sequence",RhDiscoveryObservation.sequence(copy),"generation",RhNetworkObservation.generation(),"atMs",SystemClock.uptimeMillis()));new Handler(Looper.getMainLooper()).post(()->{if(rig.home!=null)rig.home.update(copy);});});
                rig.home.perform("device.select",RhActions.obj("uuid",uuid));
            });
            await("First actual timed poll",value->value.optBoolean("online")&&value.optJSONObject("check")!=null&&value.optJSONObject("check").optString("outcome").equals("unpaired"),5000);capture(tag+"-online","device.pair","配对",stages);
            host.malformed=true;await("Actual retry threshold never published failure",value->value.optJSONObject("check")!=null&&value.optJSONObject("check").optBoolean("backgroundFailure"),10000);capture(tag+"-failed","device.check","检查连接",stages);
            host.malformed=false;await("Timed recovery",value->value.optBoolean("online")&&value.optJSONObject("check")!=null&&value.optJSONObject("check").optString("outcome").equals("unpaired"),5000);capture(tag+"-recovered","device.pair","配对",stages);
            Network active=((ConnectivityManager)rig.context.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();assertNotNull("A current network identity is needed for controlled callbacks",active);
            ConnectivityManager.NetworkCallback network=(ConnectivityManager.NetworkCallback)field(service,"networkCallback");Gate held=host.holdNext();assertTrue(held.entered.await(3,TimeUnit.SECONDS));
            long before=RhDiscoveryObservation.sequence(binder[0].getComputer(uuid));rig.main(()->network.onLost(active));held.release.countDown();
            await("Late old-network response was accepted",value->value.optJSONObject("check")!=null&&value.optJSONObject("check").optBoolean("stale")&&value.optJSONObject("check").optLong("previousObservationId")>before,2500);
            // Pause this owned timer only after its late response was delivered,
            // so drawing the evidence cannot race the following fresh poll.
            stop(binder[0],service);capture(tag+"-late-result","device.check","检查连接",stages);
            rig.main(()->{JSONObject rejected=RhActions.dispatch("device.connect",RhActions.obj("uuid",uuid));assertFalse("Direct API bypassed the visible network recheck: "+rejected,rejected.optBoolean("ok"));assertFalse(RhActions.hasStream());stages.getJSONObject(stages.length()-1).put("connectionApiRejected",true);});
            rig.main(()->{network.onAvailable(active);binder[0].startPolling(details->{ComputerDetails copy=new ComputerDetails(details);callbacks.add(RhActions.obj("state",copy.state.name(),"sequence",RhDiscoveryObservation.sequence(copy),"generation",RhNetworkObservation.generation(),"atMs",SystemClock.uptimeMillis()));new Handler(Looper.getMainLooper()).post(()->{if(rig.home!=null)rig.home.update(copy);});});});
            await("Fresh network observation never recovered",value->value.optBoolean("online")&&value.optJSONObject("check")!=null&&value.optJSONObject("check").optString("outcome").equals("unpaired"),5000);capture(tag+"-network-recovered","device.pair","配对",stages);
            assertEquals(saved,RhShared.canonical(RhShared.read(rig.context)));assertNull(binder[0].getComputer(uuid).serverCert);
            JSONArray events;synchronized(callbacks){events=new JSONArray(callbacks);}
            cases.put(RhActions.obj("name",tag,"stages",stages,"callbacks",events,"requests",host.requests.get(),"discoveryStarts",service.starts.get(),"savedConfigurationUnchanged",true,"savedPinPreserved",true));
        }finally{
            stop(binder[0],service);host.close();rig.main(()->{if(created[0])service.onDestroy();if(rig.home!=null){rig.home.dispose();rig.home=null;}if(rig.activity!=null){rig.activity.finish();rig.activity=null;}RhUiFixtureActivity.fontScale(0);});rig.instrument.waitForIdleSync();
        }
    }
    @Test public void timedDiscoveryAndNetworkChangesUpdateTheActualDevicePage()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("networkObservationRun","");org.junit.Assume.assumeFalse("Dedicated isolated run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        rig.context=rig.instrument.getTargetContext();assertTrue(rig.context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));
        rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());uuid=UUID.randomUUID().toString();
        scenario(false,1008,1);scenario(true,360,2);assertEquals(2,cases.length());
        RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"package",rig.context.getPackageName(),"cases",cases,"scope","Actual service onCreate, binder, timer, database, HTTP and listener-to-View; context-attached service subclass with mDNS boundary replaced; controlled callbacks, not a system network switch","oppoOperated",false,"osInputInjected",false));
    }
}
