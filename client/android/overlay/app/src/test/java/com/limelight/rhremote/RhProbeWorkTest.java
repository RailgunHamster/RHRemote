package com.limelight.rhremote;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RhProbeWorkTest {
    static final class Owner extends RhCancellation implements RhProbeWork.Owner {final RhProbeWork probes=new RhProbeWork();public RhProbeWork probes(){return probes;}}
    @Test public void cancellingAnUnstartedProbeReleasesOnlyItsOwnLease()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch occupied=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean ran=new AtomicBoolean();
        try{Future<?> unrelated=worker.submit(()->{occupied.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(occupied.await(1,TimeUnit.SECONDS));Owner owner=new Owner();Future<Integer> queued=RhProbeWork.submit(new ExecutorCompletionService<>(worker),()->{ran.set(true);return 1;},owner);
            assertEquals(1,owner.probes.pending());assertTrue(queued.cancel(true));assertEquals(0,owner.probes.pending());assertFalse(ran.get());assertFalse("An unrelated task was interrupted",unrelated.isDone());AtomicInteger callbacks=new AtomicInteger();owner.probes.whenIdle(callbacks::incrementAndGet);assertEquals(1,callbacks.get());
        }finally{release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test public void aCancelledRunningFutureDoesNotCompleteTheProbeLifetime()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),idle=new CountDownLatch(1);AtomicInteger callbacks=new AtomicInteger();Owner owner=new Owner();
        try{Future<Integer> future=RhProbeWork.submit(new ExecutorCompletionService<>(worker),()->{entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}return 1;},owner);assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(future.cancel(true));assertTrue(future.isDone());assertEquals(1,owner.probes.pending());
            owner.probes.whenIdle(()->{callbacks.incrementAndGet();idle.countDown();});assertEquals(0,callbacks.get());assertFalse(future.cancel(true));release.countDown();assertTrue(idle.await(1,TimeUnit.SECONDS));assertEquals(0,owner.probes.pending());assertEquals(1,callbacks.get());
        }finally{release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test public void aRejectedProbeSubmissionCannotLeaveTheInterfaceBusy(){
        ExecutorService worker=Executors.newSingleThreadExecutor();worker.shutdown();Owner owner=new Owner();try{RhProbeWork.submit(new ExecutorCompletionService<>(worker),()->1,owner);fail();}catch(RejectedExecutionException expected){}assertEquals(0,owner.probes.pending());AtomicInteger callbacks=new AtomicInteger();owner.probes.whenIdle(callbacks::incrementAndGet);assertEquals(1,callbacks.get());
    }
}
