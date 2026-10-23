package com.limelight.rhremote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class RhPairingAttemptTest {
    private static final String SALT="00112233445566778899aabbccddeeff";
    private static final String PROOF=new String(new char[512]).replace('\0','a');
    @Test public void idsAreIndependentOfSharedGameStreamIdentity(){
        RhPairingAttempt a=new RhPairingAttempt(),b=new RhPairingAttempt();
        assertTrue(a.id().matches("[0-9a-f]{64}"));assertNotEquals(a.id(),b.id());
    }
    @Test public void cancellationBeforeFirstRequestPreventsLaterPreparationAndExecution()throws Exception {
        RhPairingAttempt a=new RhPairingAttempt("aabb",Runnable::run);AtomicInteger cleanup=new AtomicInteger();
        a.cancel(()->{},(id,salt,signature)->{cleanup.incrementAndGet();return true;});
        try{a.prepare(SALT,PROOF);fail();}catch(IOException expected){}
        try{a.track(()->{});fail();}catch(IOException expected){}
        assertEquals(0,cleanup.get());
    }
    @Test public void cancelStopsOnlyOwnedCallsAndSendsProofOnce()throws Exception {
        RhPairingAttempt a=new RhPairingAttempt("aabb",Runnable::run);AtomicInteger aborted=new AtomicInteger(),calls=new AtomicInteger(),sent=new AtomicInteger();
        a.capability("1");
        a.prepare(SALT,PROOF);a.track(calls::incrementAndGet);
        RhPairingAttempt.Cleanup cleanup=(id,salt,signature)->{assertEquals("aabb",id);assertEquals(SALT,salt);assertEquals(PROOF,signature);sent.incrementAndGet();return true;};
        a.cancel(aborted::incrementAndGet,cleanup);a.cancel(aborted::incrementAndGet,cleanup);
        assertEquals(1,aborted.get());assertEquals(1,calls.get());assertEquals(1,sent.get());
        try{a.track(()->{});fail();}catch(IOException expected){}
    }
    @Test public void delayedCleanupKeepsOldAttemptAndCannotConsumeTheNewOne()throws Exception {
        ArrayList<Runnable> queued=new ArrayList<>();ArrayList<String> delivered=new ArrayList<>();
        RhPairingAttempt old=new RhPairingAttempt("aabb",queued::add);old.prepare(SALT,PROOF);
        old.capability("1");
        old.cancel(()->{},(id,salt,signature)->{delivered.add(id+":"+salt);return true;});
        RhPairingAttempt current=new RhPairingAttempt("ccdd",queued::add);current.prepare(SALT,PROOF);
        queued.get(0).run();
        assertEquals("aabb:"+SALT,delivered.get(0));
        AtomicInteger active=new AtomicInteger();current.track(active::incrementAndGet);
        assertEquals(0,active.get());
    }
    @Test public void legacyHostDoesNotReceiveAnUnknownCancelCommand()throws Exception {
        RhPairingAttempt a=new RhPairingAttempt("aabb",Runnable::run);a.prepare(SALT,PROOF);
        a.cancel(()->{},(id,salt,signature)->{fail("Host did not advertise cancellation support");return false;});
    }
    @Test public void completedPairingIsNeverRevokedByLateCancellation()throws Exception {
        RhPairingAttempt a=new RhPairingAttempt("aabb",Runnable::run);AtomicInteger sent=new AtomicInteger();
        a.prepare(SALT,PROOF);a.complete();
        a.cancel(()->fail("Completed pairing should remain authorized"),(id,salt,signature)->{sent.incrementAndGet();return true;});
        assertEquals(0,sent.get());
    }
    @Test public void cleanupWaitsForTheQueuedSignedCancellation()throws Exception {
        ArrayList<Runnable> queued=new ArrayList<>();RhPairingAttempt a=new RhPairingAttempt("aabb",queued::add);
        a.capability("1");a.prepare(SALT,PROOF);a.cancel(()->{},(id,salt,signature)->true);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{java.util.concurrent.Future<?> done=worker.submit(()->{try{a.awaitCleanup();}catch(InterruptedException e){throw new RuntimeException(e);}});
            try{done.get(100,java.util.concurrent.TimeUnit.MILLISECONDS);fail("Cleanup was reported complete before the signed request ran");}catch(java.util.concurrent.TimeoutException expected){}
            queued.get(0).run();done.get(1,java.util.concurrent.TimeUnit.SECONDS);
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test public void rejectedCleanupExecutorDoesNotLeaveAnUnfinishableWait()throws Exception {
        RhPairingAttempt a=new RhPairingAttempt("aabb",task->{throw new java.util.concurrent.RejectedExecutionException();});a.capability("1");a.prepare(SALT,PROOF);
        try{a.cancel(()->{},(id,salt,signature)->true);fail();}catch(java.util.concurrent.RejectedExecutionException expected){}
        a.awaitCleanup();
    }
    @Test public void transcriptUsesExactUtf8WithoutTrailingNewlineAndBindsSalt()throws Exception {
        RhPairingAttempt a=new RhPairingAttempt("aabb",Runnable::run);
        byte[] message=a.message(SALT);
        assertArrayEquals(("RHREMOTE-PAIR-CANCEL-1\naabb\n"+SALT).getBytes(StandardCharsets.UTF_8),message);
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);java.security.KeyPair keys=generator.generateKeyPair();
        Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(keys.getPrivate());signer.update(message);byte[] signature=signer.sign();
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(keys.getPublic());verifier.update(message);assertTrue(verifier.verify(signature));
        verifier.initVerify(keys.getPublic());verifier.update(a.message("ffeeddccbbaa99887766554433221100"));assertFalse(verifier.verify(signature));
    }
}
