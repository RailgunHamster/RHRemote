package com.limelight.rhremote;

import android.content.Context;
import android.os.Looper;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** A controlled, non-interruptible probe represents an outstanding native DNS call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhAdmissionTest.Transport.class,RhProbeLifetimeTest.Probes.class})
public class RhProbeLifetimeTest {
    @Implements(value=RhNetwork.class,isInAndroidSdk=false)
    public static class Probes {
        static CountDownLatch entered,release,exited;static AtomicBoolean live;
        @Implementation protected static String check(String address,String uuid,int port,RhCancellation operation,int timeout,java.util.function.Consumer<String> evidence)throws Exception{
            live.set(true);entered.countDown();try{boolean done=false;while(!done)try{done=release.await(20,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}operation.checkpoint();if(evidence!=null)evidence.accept("<root status_code=\"200\"><uniqueid>"+uuid+"</uniqueid><appversion>7.1.0</appversion><PairStatus>0</PairStatus></root>");return address;}finally{live.set(false);exited.countDown();}
        }
        @Implementation protected static String check(String address,String uuid,int port,RhCancellation operation,int timeout)throws Exception{return check(address,uuid,port,operation,timeout,null);}
    }
    Context context;RhDiagnosticWork work;ExecutorService executor;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();RhStatistics.initialize(context);RhStatistics.configure(false);work=RhDiagnosticWork.shared(context);executor=Executors.newSingleThreadExecutor();Probes.entered=new CountDownLatch(1);Probes.release=new CountDownLatch(1);Probes.exited=new CountDownLatch(1);Probes.live=new AtomicBoolean();RhAdmissionTest.Transport.fail=false;RhAdmissionTest.Transport.stops=0;}
    void pumpUntil(java.util.function.BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!done.getAsBoolean()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("Owned work did not reach the expected state",done.getAsBoolean());}
    @After public void cleanup()throws Exception{Probes.release.countDown();if(work.busy())work.cancel(work.state().optLong("operationId"));pumpUntil(()->!work.busy());executor.shutdown();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));assertFalse(RhActions.inspecting());}
    @Test public void cancellationCannotReleaseTheInspectionWhileItsProbeStillExecutes()throws Exception{
        ComputerDetails target=new ComputerDetails();target.uuid="owned-probe";target.name="Fixture";target.manualAddress=new ComputerDetails.AddressTuple("192.0.2.10",47989);RhShared.capture(context,Collections.singletonList(target));RhProfile profile=RhProfile.load(context,target.uuid);profile.routeOrder=new ArrayList<>(Collections.singletonList("public_direct"));
        work.startCheck(target,profile,executor);assertTrue(Probes.entered.await(2,TimeUnit.SECONDS));work.cancel(work.state().getLong("operationId"));
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250);while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        assertTrue(Probes.live.get());assertTrue("A cancelled Future was mistaken for an exited probe",work.busy());assertTrue(RhActions.inspecting());assertEquals("Transport was released while a child still ran",0,RhAdmissionTest.Transport.stops);
        Probes.release.countDown();assertTrue(Probes.exited.await(2,TimeUnit.SECONDS));pumpUntil(()->!work.busy());assertEquals("cancelled",work.state().getString("phase"));assertEquals(1,RhAdmissionTest.Transport.stops);
    }
}
