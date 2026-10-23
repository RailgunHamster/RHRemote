package com.limelight.rhremote;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import com.limelight.Game;
import com.limelight.PcView;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerService;
import com.limelight.computers.IdentityManager;
import com.limelight.nvstream.http.*;
import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Actual patched PcView Desktop admission and ServerHelper Intent; HTTP alone is replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionPcViewTest.Http.class,RhAdmissionTest.Transport.class,RhRecoveryTest.NoNativeMoonBridge.class})
public class RhAdmissionPcViewTest {
    @Implements(value=NvHTTP.class,isInAndroidSdk=false)
    public static class Http {
        static CountDownLatch entered,release;static boolean strict;static AtomicInteger cancelled;
        @Implementation protected void __constructor__(ComputerDetails.AddressTuple a,int port,String id,X509Certificate cert,LimelightCryptoProvider crypto,boolean strictPin){strict=strictPin;}
        @Implementation protected java.util.LinkedList<NvApp> getAppList()throws Exception {entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return new java.util.LinkedList<>(Collections.singletonList(new NvApp("Desktop",1,false)));}
        @Implementation protected void cancelRhRequests(){cancelled.incrementAndGet();}
    }
    PcView activity;ComputerDatabaseManager db;ComputerDetails device;RhAdmission operation;
    RhActions.Target stream=new RhActions.Target(){public JSONObject state(){return RhActions.obj();}public JSONObject perform(String id,JSONObject args){return RhActions.obj();}};
    @Before public void setup()throws Exception{
        Context context=RuntimeEnvironment.getApplication();activity=Robolectric.buildActivity(PcView.class).get();
        RhStatistics.initialize(activity);RhStatistics.configure(false);RhAdmissionTest.Transport.stops=0;RhAdmissionTest.Transport.fail=false;
        Http.entered=new CountDownLatch(1);Http.release=new CountDownLatch(1);Http.cancelled=new AtomicInteger();Http.strict=false;
        db=new ComputerDatabaseManager(context);ComputerManagerService service=Robolectric.buildService(ComputerManagerService.class).get();
        ReflectionHelpers.setField(service,"dbManager",db);ReflectionHelpers.setField(service,"idManager",new IdentityManager(context));
        ((AtomicInteger)ReflectionHelpers.getField(service,"dbRefCount")).set(1);ReflectionHelpers.setField(activity,"managerBinder",ReflectionHelpers.getField(service,"binder"));
        device=new ComputerDetails();device.uuid="host";device.name="PC";device.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);device.activeAddress=device.manualAddress;
        device.state=ComputerDetails.State.ONLINE;device.pairState=PairingManager.PairState.PAIRED;device.serverCert=new AndroidCryptoProvider(context).getClientCertificate();
    }
    private void begin()throws Exception{
        Method prepare=PcView.class.getDeclaredMethod("prepareRhDesktop",ComputerDetails.class,RhAdmission.class);prepare.setAccessible(true);
        operation=new RhAdmission(activity,"host",task->new Thread(task,"test PcView admission").start(),new RhAdmission.Listener(){public void changed(RhAdmission op){}public void finished(RhAdmission op,boolean handoff,String message){}});
        operation.start(op->{Runnable launch=(Runnable)prepare.invoke(activity,device,op);return ()->{try{op.handoff(launch);}catch(Exception e){throw new RuntimeException(e);}};});
        assertTrue(Http.entered.await(3,TimeUnit.SECONDS));
    }
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void awaitPhase(String phase)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);do{idle();if(phase.equals(operation.state().optString("phase")))return;Thread.sleep(10);}while(System.nanoTime()<until);fail(operation.state().toString());
    }
    @After public void cleanup()throws Exception{
        Http.release.countDown();if(operation!=null)operation.cancel();RhActions.detachStream(stream);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);do{idle();if(!RhActions.hasStream())break;Thread.sleep(10);}while(System.nanoTime()<until);
        db.close();RhStatistics.finish();assertFalse("PcView admission leaked",RhActions.hasStream());
    }
    @Test public void cancelledAppListCannotLaunchWhenItsResponseArrivesLate()throws Exception{
        begin();operation.cancel();assertTrue(RhActions.hasStream());assertEquals(1,Http.cancelled.get());assertTrue(Http.strict);
        Http.release.countDown();awaitPhase("complete");assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertEquals(1,RhAdmissionTest.Transport.stops);
    }
    @Test public void selectedDesktopAndPairingIdentityReachTheSingleGameHandoff()throws Exception{
        begin();Http.release.countDown();awaitPhase("starting");Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(intent);
        assertEquals("host",intent.getStringExtra(Game.EXTRA_PC_UUID));assertEquals("192.168.0.104",intent.getStringExtra(Game.EXTRA_HOST));
        assertArrayEquals(device.serverCert.getEncoded(),intent.getByteArrayExtra(Game.EXTRA_SERVER_CERT));assertEquals(1,intent.getIntExtra(Game.EXTRA_APP_ID,0));
        assertTrue(RhAdmission.allowGame(intent));RhActions.attachStream(stream);assertFalse(RhActions.ownsAdmission(operation));assertEquals(0,RhAdmissionTest.Transport.stops);
    }
}
