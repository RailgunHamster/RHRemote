package com.limelight.rhremote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;

/** Owns one handshake's ID, cancellation proof and requests, never a private key. */
public final class RhPairingAttempt {
    public interface Cleanup { boolean send(String id,String salt,String signature)throws Exception; }
    private final String id;
    private final Executor executor;
    private final ArrayList<Runnable> calls=new ArrayList<>();
    private String salt,signature;
    private boolean cancelled,complete,supported;
    private CountDownLatch cleanupDone=new CountDownLatch(0);
    public void awaitCleanup()throws InterruptedException {CountDownLatch done;synchronized(this){done=cleanupDone;}done.await();}
    public RhPairingAttempt(){this(UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),r->{Thread t=new Thread(r,"RHRemote pairing cleanup");t.setDaemon(true);t.start();});}
    RhPairingAttempt(String id,Executor executor){this.id=id;this.executor=executor;}
    public String id(){return id;}
    public synchronized void capability(String value){supported="1".equals(value);}
    public byte[] message(String salt)throws IOException {
        if(!salt.matches("[0-9a-fA-F]{32}"))throw new IOException("Invalid pairing salt");
        return ("RHREMOTE-PAIR-CANCEL-1\n"+id+"\n"+salt).getBytes(StandardCharsets.UTF_8);
    }
    public synchronized void prepare(String salt,String signature)throws IOException {
        if(cancelled||complete)throw new IOException("Pairing cancelled");
        message(salt);
        if(signature.length()<128||signature.length()>4096||signature.length()%2!=0||!signature.matches("[0-9a-fA-F]+"))throw new IOException("Invalid pairing proof");
        this.salt=salt;this.signature=signature;
    }
    public synchronized void track(Runnable cancel)throws IOException {
        if(cancelled||complete)throw new IOException("Pairing cancelled");
        calls.add(cancel);
    }
    public synchronized void complete(){complete=true;calls.clear();}
    public void cancel(Runnable abort,Cleanup cleanup){
        final ArrayList<Runnable> active;final String savedSalt,savedSignature;final CountDownLatch done;
        synchronized(this){
            if(cancelled||complete)return;
            cancelled=true;active=new ArrayList<>(calls);calls.clear();savedSalt=supported?salt:null;savedSignature=signature;
            done=cleanupDone=new CountDownLatch(savedSalt==null?0:1);
        }
        try{abort.run();for(Runnable call:active)call.run();}finally{
        if(savedSalt!=null)try{executor.execute(()->{try{
            for(int attempt=0;attempt<3;attempt++){
                try{if(cleanup.send(id,savedSalt,savedSignature))return;}catch(Exception ignored){}
                if(attempt<2)try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            }
        }finally{done.countDown();}});}catch(RuntimeException rejected){done.countDown();throw rejected;}
        }
    }
}
