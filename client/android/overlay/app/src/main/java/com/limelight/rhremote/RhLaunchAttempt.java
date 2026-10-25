package com.limelight.rhremote;

import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** One actual launch/resume, independent of its possibly cancelled HTTP response. */
public final class RhLaunchAttempt {
    public enum Result { PENDING, UNSUPPORTED, CANCELLED, HANDED_OFF, FINISHED, EXPIRED, FAILED }
    public interface Cleanup { String send(String credential)throws Exception; }
    private static final Pattern SECRET=Pattern.compile("(?i)(rhremoteLaunchToken=)[0-9a-f]{64}");
    private final boolean supported;
    private final String credential;
    private final Cleanup cleanup;
    private final Executor executor;
    private final LongSupplier clock;
    private CountDownLatch done=new CountDownLatch(0);
    private Result result;
    private boolean active,requested;
    private long retryAfter;
    public RhLaunchAttempt(boolean supported,Cleanup cleanup){this(supported,cleanup,r->{Thread thread=new Thread(r,"RHRemote pending launch cleanup");thread.setDaemon(true);thread.start();},()->System.nanoTime()/1000000);}
    RhLaunchAttempt(boolean supported,Cleanup cleanup,Executor executor,LongSupplier clock){
        this.supported=supported;this.cleanup=cleanup;this.executor=executor;this.clock=clock;result=supported?Result.PENDING:Result.UNSUPPORTED;
        if(supported){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder text=new StringBuilder(64);for(byte b:bytes){text.append(Character.forDigit((b>>>4)&15,16));text.append(Character.forDigit(b&15,16));}credential=text.toString();}else credential="";
    }
    public String query(){return supported?"&rhremoteLaunchToken="+credential:"";}
    public synchronized Result result(){return result;}
    /** Native connectionStarted follows successful RTSP PLAY: the pending slot has transferred to this stream. */
    public synchronized void handedOff(){
        if(!supported||acknowledged(result))return;
        result=Result.HANDED_OFF;done.countDown();
    }
    public static boolean acknowledged(Result result){return result==Result.CANCELLED||result==Result.HANDED_OFF||result==Result.FINISHED||result==Result.EXPIRED;}
    public static Result reply(String value){if("cancelled".equals(value))return Result.CANCELLED;if("handed_off".equals(value))return Result.HANDED_OFF;if("finished".equals(value))return Result.FINISHED;if("expired".equals(value))return Result.EXPIRED;return Result.FAILED;}
    public void cancel(){
        final CountDownLatch completion;
        synchronized(this){
            if(!supported||active||acknowledged(result)||(requested&&clock.getAsLong()<retryAfter))return;
            requested=true;active=true;result=Result.PENDING;done=completion=new CountDownLatch(1);
        }
        try{executor.execute(()->{
            Result outcome=Result.FAILED;try{outcome=reply(cleanup.send(credential));}catch(Exception|LinkageError failed){/* Retain ownership; a later wait can retry the same request. */}
            synchronized(this){if(!acknowledged(result))result=outcome;active=false;retryAfter=clock.getAsLong()+1000;}
            completion.countDown();
        });}catch(RuntimeException rejected){synchronized(this){result=Result.FAILED;active=false;retryAfter=clock.getAsLong()+1000;}completion.countDown();}
    }
    /** Unsupported hosts keep the legacy policy; failures never count as an acknowledgement. */
    public boolean await(long timeoutMs)throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,timeoutMs));
        while(true){
            cancel();final CountDownLatch completion;synchronized(this){if(!supported)return true;completion=done;}
            if(!completion.await(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))return false;
            final long backoff;
            synchronized(this){if(acknowledged(result))return true;backoff=Math.max(1,retryAfter-clock.getAsLong());}
            long remaining=deadline-System.nanoTime();if(remaining<=0)return false;
            // A failed HTTPS cancellation is not a negative acknowledgement.
            // Retry the same credential within the caller's existing drain budget.
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(backoff)));
        }
    }
    public static String redact(String value){return value==null?null:SECRET.matcher(value).replaceAll("$1[redacted]");}
}
