package com.limelight.rhremote;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhDiscoveryRefreshTest {
    static void settled(Object owner,String uuid)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(RhDiscoveryRefresh.pending(owner,uuid)&&System.nanoTime()<end)Thread.sleep(5);
        assertFalse("Refresh ownership leaked",RhDiscoveryRefresh.pending(owner,uuid));
    }
    @Test public void aBlockedDiscoveryLockCannotBlockTheCallerOrQueueDuplicateWork()throws Exception{
        Object owner=new Object();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();Thread caller=Thread.currentThread();AtomicReference<Thread> worker=new AtomicReference<>();
        try{
            RhDiscoveryRefresh.request(owner,Arrays.asList("a","a"),uuid->{calls.incrementAndGet();worker.set(Thread.currentThread());started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Gate expired");}catch(InterruptedException e){throw new AssertionError(e);}done.countDown();});
            assertTrue(started.await(3,TimeUnit.SECONDS));assertNotSame(caller,worker.get());assertTrue(RhDiscoveryRefresh.pending(owner,"a"));
            RhDiscoveryRefresh.request(owner,Collections.singleton("a"),uuid->fail("Duplicate work queued"));
            assertEquals(1,calls.get());assertEquals(1,done.getCount());
        }finally{release.countDown();}
        assertTrue(done.await(3,TimeUnit.SECONDS));settled(owner,"a");assertEquals(1,calls.get());
    }
    @Test public void aFailedTargetDoesNotStrandOtherTargetsOrPreventRetry()throws Exception{
        Object owner=new Object();CountDownLatch completed=new CountDownLatch(1);List<String> visited=Collections.synchronizedList(new ArrayList<>());
        RhDiscoveryRefresh.request(owner,Arrays.asList("a","b"),uuid->{visited.add(uuid);if(uuid.equals("a"))throw new IllegalStateException("Injected invalidation failure");completed.countDown();});
        assertTrue(completed.await(3,TimeUnit.SECONDS));settled(owner,"a");settled(owner,"b");assertEquals(Arrays.asList("a","b"),visited);
        CountDownLatch retry=new CountDownLatch(1);RhDiscoveryRefresh.request(owner,Collections.singleton("a"),uuid->retry.countDown());assertTrue(retry.await(3,TimeUnit.SECONDS));settled(owner,"a");
    }
    @Test public void aRequestSnapshotsItsTargetsAndDoesNotDeduplicateDifferentServices()throws Exception{
        Object first=new Object(),second=new Object();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),both=new CountDownLatch(2);List<String> targets=new ArrayList<>(Collections.singleton("a"));List<String> visited=Collections.synchronizedList(new ArrayList<>());
        try{
            RhDiscoveryRefresh.request(first,targets,uuid->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}visited.add("first:"+uuid);both.countDown();});
            assertTrue(started.await(3,TimeUnit.SECONDS));targets.set(0,"unexpected");
            RhDiscoveryRefresh.request(second,Collections.singleton("a"),uuid->{visited.add("second:"+uuid);both.countDown();});
        }finally{release.countDown();}
        assertTrue(both.await(3,TimeUnit.SECONDS));settled(first,"a");settled(second,"a");assertEquals(Arrays.asList("first:a","second:a"),visited);
    }
    @Test public void theActualDiscoveryBinderCanWaitForItsLockWithoutBlockingTheCaller()throws Exception{
        ComputerManagerService service=Robolectric.buildService(ComputerManagerService.class).get();
        ComputerManagerService.ComputerManagerBinder binder=ReflectionHelpers.getField(service,"binder");
        ComputerDetails first=new ComputerDetails(),second=new ComputerDetails();first.uuid="first";second.uuid="second";first.state=second.state=ComputerDetails.State.ONLINE;
        Class<?> tupleType=Class.forName("com.limelight.computers.PollingTuple");java.lang.reflect.Constructor<?> constructor=tupleType.getDeclaredConstructor(ComputerDetails.class,Thread.class);constructor.setAccessible(true);
        Object tuple=constructor.newInstance(first,null);List<Object> tuples=ReflectionHelpers.getField(service,"pollingTuples");tuples.add(tuple);tuples.add(constructor.newInstance(second,null));
        Object gate=ReflectionHelpers.getField(tuple,"networkLock");CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),entered=new CountDownLatch(1);AtomicReference<Thread> worker=new AtomicReference<>();
        Thread holder=new Thread(()->{synchronized(gate){locked.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}}},"test discovery lock");holder.start();
        try{
            assertTrue(locked.await(3,TimeUnit.SECONDS));RhDiscoveryRefresh.request(binder,Collections.singleton(first.uuid),uuid->{worker.set(Thread.currentThread());entered.countDown();binder.invalidateStateForComputer(uuid);});
            assertTrue(entered.await(3,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(worker.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<end)Thread.yield();
            assertEquals(Thread.State.BLOCKED,worker.get().getState());assertTrue(RhDiscoveryRefresh.pending(binder,first.uuid));assertEquals(ComputerDetails.State.ONLINE,first.state);
            FutureTask<ComputerDetails> unrelated=new FutureTask<>(()->binder.getComputer(second.uuid));Thread reader=new Thread(unrelated,"test unrelated discovery read");reader.setDaemon(true);reader.start();
            assertSame("Waiting for one host must not lock the whole device list",second,unrelated.get(1,TimeUnit.SECONDS));
            AtomicBoolean responsive=new AtomicBoolean();new android.os.Handler(android.os.Looper.getMainLooper()).post(()->responsive.set(true));org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue(responsive.get());
        }finally{release.countDown();holder.join(3000);}
        settled(binder,first.uuid);assertEquals(ComputerDetails.State.UNKNOWN,first.state);assertEquals(ComputerDetails.State.ONLINE,second.state);
    }
    @Test public void invalidatingAnOldTupleCannotChangeTheReplacementForTheSameUuid()throws Exception{
        ComputerManagerService service=Robolectric.buildService(ComputerManagerService.class).get();ComputerManagerService.ComputerManagerBinder binder=ReflectionHelpers.getField(service,"binder");
        Class<?> type=Class.forName("com.limelight.computers.PollingTuple");java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(ComputerDetails.class,Thread.class);constructor.setAccessible(true);
        ComputerDetails old=new ComputerDetails(),replacement=new ComputerDetails();old.uuid=replacement.uuid="same";old.state=replacement.state=ComputerDetails.State.ONLINE;
        Object tuple=constructor.newInstance(old,null),next=constructor.newInstance(replacement,null);List<Object> tuples=ReflectionHelpers.getField(service,"pollingTuples");tuples.add(tuple);Object gate=ReflectionHelpers.getField(tuple,"networkLock");
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),entered=new CountDownLatch(1);AtomicReference<Thread> worker=new AtomicReference<>();
        Thread holder=new Thread(()->{synchronized(gate){locked.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}}},"test replaced tuple lock");holder.start();
        try{
            assertTrue(locked.await(3,TimeUnit.SECONDS));RhDiscoveryRefresh.request(binder,Collections.singleton(old.uuid),uuid->{worker.set(Thread.currentThread());entered.countDown();binder.invalidateStateForComputer(uuid);});assertTrue(entered.await(3,TimeUnit.SECONDS));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(worker.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<end)Thread.yield();assertEquals(Thread.State.BLOCKED,worker.get().getState());
            synchronized(tuples){assertTrue(tuples.remove(tuple));tuples.add(next);}
        }finally{release.countDown();holder.join(3000);}
        settled(binder,old.uuid);assertSame(replacement,binder.getComputer(replacement.uuid));assertEquals(ComputerDetails.State.ONLINE,replacement.state);
    }
}
