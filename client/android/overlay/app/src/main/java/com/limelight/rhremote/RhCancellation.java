package com.limelight.rhremote;

import java.io.IOException;
import java.util.ArrayList;

/** Request lifetime shared by initial admission and media recovery. */
public abstract class RhCancellation {
    protected volatile boolean cancelled;
    private final ArrayList<Runnable> aborters=new ArrayList<>();
    public void checkpoint()throws IOException {
        if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("连接已取消");
    }
    public final class Watch implements AutoCloseable {
        private final Runnable abort;private boolean closed;
        private Watch(Runnable abort){this.abort=abort;}
        @Override public void close(){synchronized(aborters){if(!closed){closed=true;aborters.remove(abort);}}}
    }
    /** Includes cancel-before-register without holding a transport's startup monitor. */
    public Watch watch(Runnable abort){
        boolean stop;synchronized(aborters){stop=cancelled;if(!stop)aborters.add(abort);}
        if(stop)abort(abort);return new Watch(abort);
    }
    protected final void cancelRequests(){
        ArrayList<Runnable> pending;
        synchronized(aborters){cancelled=true;pending=new ArrayList<>(aborters);aborters.clear();}
        for(Runnable callback:pending)abort(callback);
    }
    protected final void forgetRequests(){synchronized(aborters){aborters.clear();}}
    protected static void abort(Runnable callback){try{callback.run();}catch(Exception|LinkageError ignored){}}
}
