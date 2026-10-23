package com.limelight.rhremote;

import android.app.Activity;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Exercises the Java monitor/JNI boundary without loading an ARM library into the JVM. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows=RhTunnelCancellationTest.NativeTransport.class)
public class RhTunnelCancellationTest {
    @Implements(value=RhTunnel.class,isInAndroidSdk=false)
    public static class NativeTransport {
        static CountDownLatch entered,release;static volatile long started,cancelled;static int stops;
        @Implementation protected static String startNative(String key,String peer,String relay,boolean only,long id){
            started=id;entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return "{\"ok\":false,\"error\":\"cancelled\"}";
        }
        @Implementation protected static void cancelStartNative(long id){cancelled=id;release.countDown();}
        @Implementation protected static void stopNative(){stops++;}
    }
    @Test public void cancelDoesNotWaitForTheMonitorHeldByTheJniStartup()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();RhStatistics.initialize(activity);RhStatistics.configure(false);
        NativeTransport.entered=new CountDownLatch(1);NativeTransport.release=new CountDownLatch(1);NativeTransport.started=0;NativeTransport.cancelled=0;NativeTransport.stops=0;
        ReflectionHelpers.setStaticField(RhTunnel.class,"loaded",true);
        RhAdmission operation=new RhAdmission(activity,"host",task->new Thread(task,"test admission").start(),new RhAdmission.Listener(){
            public void changed(RhAdmission op){}public void finished(RhAdmission op,boolean handoff,String message){}
        });
        try{
            operation.start(op->{RhTunnel.start(activity,"peer","http://127.0.0.1:9/",true,op);return ()->fail("Cancelled JNI task launched a session");});
            assertTrue(NativeTransport.entered.await(3,TimeUnit.SECONDS));
            long began=System.nanoTime();operation.cancel();assertTrue("UI cancellation waited for the startup monitor",System.nanoTime()-began<TimeUnit.SECONDS.toNanos(1));
            assertEquals(NativeTransport.started,NativeTransport.cancelled);assertTrue(NativeTransport.cancelled>0);
        }finally{
            NativeTransport.release.countDown();operation.cancel();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(!RhActions.hasStream())break;Thread.sleep(10);}while(System.nanoTime()<until);
            ReflectionHelpers.setStaticField(RhTunnel.class,"loaded",false);
        }
        assertFalse(RhActions.hasStream());assertEquals(1,NativeTransport.stops);
    }
}
