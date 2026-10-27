package com.limelight.rhremote;

import android.app.Activity;
import android.os.Looper;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class RhDeviceAddReadinessTest {
    Activity activity;ExecutorService worker;RhDeviceAdd operation;ComputerDetails found;String error;List<String> phases=new ArrayList<>();AtomicBoolean probed=new AtomicBoolean();AtomicReference<Object> backend=new AtomicReference<>();
    @Before public void setup(){activity=Robolectric.buildActivity(Activity.class).setup().get();worker=Executors.newSingleThreadExecutor();operation=new RhDeviceAdd(activity,worker,new RhDeviceAdd.Listener(){public void changed(RhDeviceAdd value){phases.add(value.phase());}public void finished(RhDeviceAdd value,ComputerDetails result,String failure){found=result;error=failure;}});}
    @After public void cleanup()throws Exception{operation.cancel();worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void waitFor(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!condition.getAsBoolean()){Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("Readiness operation did not settle",System.nanoTime()<end);Thread.sleep(2);}Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void start(long budget){operation.start(request->{request.awaitReady(backend::get,budget);probed.set(true);ComputerDetails device=new ComputerDetails();device.uuid="ready-host";device.name="Ready workstation";return device;});}
    @Test public void aReadyBackendDoesNotEnterThePreparationState()throws Exception{backend.set(new Object());start(1000);waitFor(operation::isFinished);assertTrue(probed.get());assertNotNull(found);assertEquals("",error);assertFalse(phases.contains("preparing"));}
    @Test public void delayedReadinessContinuesTheSameRequestOnce()throws Exception{start(1000);waitFor(()->operation.phase().equals("preparing"));assertFalse(probed.get());assertFalse(operation.isFinished());backend.set(new Object());waitFor(operation::isFinished);assertTrue(probed.get());assertNotNull(found);assertEquals("",error);assertTrue(phases.contains("preparing"));assertEquals("probing",operation.phase());}
    @Test public void cancellationBeforeReadinessNeverProbesOrReturnsADevice()throws Exception{start(1000);waitFor(()->operation.phase().equals("preparing"));operation.cancel();backend.set(new Object());waitFor(operation::isFinished);assertFalse(probed.get());assertNull(found);assertTrue(operation.cancelled());}
    @Test public void readinessTimeoutFinishesWithoutStartingTheNetworkProbe()throws Exception{start(30);waitFor(operation::isFinished);assertFalse(probed.get());assertNull(found);assertTrue(error,error.contains("尚未准备好"));}
    @Test public void cancelBeforeStartNeverUsesEvenAnAlreadyReadyBackend()throws Exception{operation.cancel();backend.set(new Object());start(1000);waitFor(operation::isFinished);assertFalse(probed.get());assertNull(found);assertTrue(operation.cancelled());}
}
