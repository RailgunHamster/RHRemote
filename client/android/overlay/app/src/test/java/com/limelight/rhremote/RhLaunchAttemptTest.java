package com.limelight.rhremote;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RhLaunchAttemptTest {
    @Test public void oldHostsHaveNoCredentialAndNeverSendCleanup()throws Exception {
        AtomicInteger calls=new AtomicInteger();RhLaunchAttempt old=new RhLaunchAttempt(false,key->{calls.incrementAndGet();return "cancelled";});
        assertEquals("",old.query());old.cancel();assertTrue(old.await(0));assertEquals(0,calls.get());assertEquals(RhLaunchAttempt.Result.UNSUPPORTED,old.result());assertFalse(RhLaunchAttempt.acknowledged(old.result()));
    }
    @Test public void eachActualRequestHasFreshLowercase256BitCredential(){
        Set<String> seen=new HashSet<>();for(int n=0;n<32;n++){String query=new RhLaunchAttempt(true,key->"cancelled").query();assertTrue(query.matches("&rhremoteLaunchToken=[0-9a-f]{64}"));assertTrue(seen.add(query));}
    }
    @Test public void unknownResponseCanBeCancelledAndDrainWaitsForAcknowledgement()throws Exception {
        ArrayDeque<Runnable> jobs=new ArrayDeque<>();AtomicInteger calls=new AtomicInteger();AtomicBoolean originalHttpReturned=new AtomicBoolean();
        RhLaunchAttempt attempt=new RhLaunchAttempt(true,key->{assertFalse(originalHttpReturned.get());calls.incrementAndGet();return "cancelled";},jobs::add,()->1000);
        attempt.cancel();attempt.cancel();assertEquals(1,jobs.size());assertFalse(attempt.await(0));jobs.remove().run();
        assertTrue(attempt.await(0));originalHttpReturned.set(true);attempt.cancel();assertEquals(1,calls.get());assertTrue(jobs.isEmpty());
    }
    @Test public void failedCleanupRetainsOwnershipAndRetriesSameCredentialWithBackoff()throws Exception {
        ArrayDeque<Runnable> jobs=new ArrayDeque<>();AtomicLong clock=new AtomicLong(1000);List<String> keys=new ArrayList<>();
        RhLaunchAttempt attempt=new RhLaunchAttempt(true,key->{keys.add(key);return keys.size()==1?"invalid":"expired";},jobs::add,clock::get);
        attempt.cancel();jobs.remove().run();assertFalse(attempt.await(0));assertEquals(RhLaunchAttempt.Result.FAILED,attempt.result());
        clock.set(1999);attempt.cancel();assertTrue(jobs.isEmpty());clock.set(2000);assertFalse(attempt.await(0));assertEquals(1,jobs.size());jobs.remove().run();
        assertTrue(attempt.await(0));assertEquals(keys.get(0),keys.get(1));assertEquals(RhLaunchAttempt.Result.EXPIRED,attempt.result());
    }
    @Test public void rejectionExceptionsAndUnknownRepliesAreNeverAcknowledged()throws Exception {
        RhLaunchAttempt rejected=new RhLaunchAttempt(true,key->"cancelled",r->{throw new RejectedExecutionException();},()->1000);
        rejected.cancel();assertFalse(rejected.await(0));assertEquals(RhLaunchAttempt.Result.FAILED,rejected.result());
        RhLaunchAttempt failed=new RhLaunchAttempt(true,key->{throw new java.io.IOException("fixture");},Runnable::run,()->1000);
        failed.cancel();assertFalse(failed.await(0));for(String value:Arrays.asList("","success",null))assertEquals(RhLaunchAttempt.Result.FAILED,RhLaunchAttempt.reply(value));
        for(String value:Arrays.asList("cancelled","handed_off","finished","expired"))assertTrue(RhLaunchAttempt.acknowledged(RhLaunchAttempt.reply(value)));
    }
    @Test public void cancellationRunsOffCallerAndConcurrentWaitersShareOneCleanup()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),ack=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();Thread caller=Thread.currentThread();
        RhLaunchAttempt attempt=new RhLaunchAttempt(true,key->{assertNotSame(caller,Thread.currentThread());calls.incrementAndGet();entered.countDown();assertTrue(ack.await(3,TimeUnit.SECONDS));return "handed_off";});
        attempt.cancel();assertTrue(entered.await(3,TimeUnit.SECONDS));assertFalse(attempt.await(0));ack.countDown();assertTrue(attempt.await(3000));assertTrue(attempt.await(0));assertEquals(1,calls.get());
    }
    @Test public void diagnosticUrlAndQtStyleMessagesRedactTheCredential(){
        String key=new RhLaunchAttempt(true,value->"cancelled").query().substring("&rhremoteLaunchToken=".length());
        for(String message:Arrays.asList("https://host/resume?rhremoteLaunchToken="+key+"&appid=1","Error transferring /cancel?RHREMOTELAUNCHTOKEN="+key)){
            String safe=RhLaunchAttempt.redact(message);assertFalse(safe.contains(key));assertTrue(safe.contains("[redacted]"));
        }
        assertNull(RhLaunchAttempt.redact(null));assertEquals("ordinary",RhLaunchAttempt.redact("ordinary"));
    }
    @Test public void confirmedRtspHandoffNeedsNoNetworkCleanupAndCannotBeOverwritten()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        RhLaunchAttempt active=new RhLaunchAttempt(true,key->{calls.incrementAndGet();throw new java.io.IOException("offline");});
        active.handedOff();assertTrue(active.await(0));active.cancel();assertEquals(0,calls.get());
        ArrayDeque<Runnable> jobs=new ArrayDeque<>();
        RhLaunchAttempt racing=new RhLaunchAttempt(true,key->"invalid",jobs::add,()->1000);
        racing.cancel();assertFalse(racing.await(0));racing.handedOff();assertTrue(racing.await(0));
        jobs.remove().run();assertEquals(RhLaunchAttempt.Result.HANDED_OFF,racing.result());assertTrue(racing.await(0));
    }
    @Test public void boundedDrainRetriesAFailedHttpsCancelWithTheSameCredential()throws Exception{
        List<String> keys=Collections.synchronizedList(new ArrayList<>());
        RhLaunchAttempt attempt=new RhLaunchAttempt(true,key->{keys.add(key);if(keys.size()==1)throw new java.net.SocketTimeoutException("TLS timeout");return "cancelled";});
        assertTrue(attempt.await(3000));assertEquals(2,keys.size());assertEquals(keys.get(0),keys.get(1));assertEquals(RhLaunchAttempt.Result.CANCELLED,attempt.result());
    }
    @Test public void unacknowledgedDrainStillTimesOutAndRetainsOwnership()throws Exception{
        RhLaunchAttempt attempt=new RhLaunchAttempt(true,key->"invalid");long began=System.nanoTime();
        assertFalse(attempt.await(120));assertFalse(RhLaunchAttempt.acknowledged(attempt.result()));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<1000);
    }
    @Test public void aCallerCanInterruptItsDrainWaitWithoutFakingAcknowledgement()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();
        RhLaunchAttempt attempt=new RhLaunchAttempt(true,key->{entered.countDown();throw new java.net.SocketTimeoutException();});
        Thread waiter=new Thread(()->{try{attempt.await(3000);}catch(InterruptedException expected){interrupted.set(true);}});
        waiter.start();assertTrue(entered.await(2,TimeUnit.SECONDS));waiter.interrupt();waiter.join(1000);
        assertFalse(waiter.isAlive());assertTrue(interrupted.get());assertFalse(RhLaunchAttempt.acknowledged(attempt.result()));
    }
}
