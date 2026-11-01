package com.limelight.rhremote;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Tracks actual probe execution; Future.cancel() alone does not prove exit. */
final class RhProbeWork {
    interface Owner {RhProbeWork probes();}
    private final Set<Call<?>> active=new HashSet<>();
    private Runnable idle;
    synchronized int pending(){return active.size();}
    void whenIdle(Runnable callback){boolean now;synchronized(this){now=active.isEmpty();if(!now)idle=callback;}if(now)callback.run();}
    private void finished(Call<?> call){Runnable callback=null;synchronized(this){active.remove(call);if(active.isEmpty()){callback=idle;idle=null;}}if(callback!=null)callback.run();}
    private static final class Call<T> implements Callable<T> {
        final RhProbeWork owner;final Callable<T> body;final AtomicInteger phase=new AtomicInteger();
        Call(RhProbeWork owner,Callable<T> body){this.owner=owner;this.body=body;synchronized(owner){owner.active.add(this);}}
        public T call()throws Exception{
            if(!phase.compareAndSet(0,1))return null;
            try{return body.call();}finally{phase.set(2);owner.finished(this);}
        }
        void cancelledBeforeStart(){if(phase.compareAndSet(0,2))owner.finished(this);}
    }
    static <T> Future<T> submit(CompletionService<T> queue,Callable<T> body,RhCancellation operation){
        if(!(operation instanceof Owner))return queue.submit(body);
        Call<T> call=new Call<>(((Owner)operation).probes(),body);final Future<T> future;
        try{future=queue.submit(call);}catch(RuntimeException rejected){call.cancelledBeforeStart();throw rejected;}
        return new Future<T>(){
            public boolean cancel(boolean interrupt){boolean cancelled=future.cancel(interrupt);if(cancelled)call.cancelledBeforeStart();return cancelled;}
            public boolean isCancelled(){return future.isCancelled();}
            public boolean isDone(){return future.isDone();}
            public T get()throws InterruptedException,ExecutionException{return future.get();}
            public T get(long timeout,TimeUnit unit)throws InterruptedException,ExecutionException,TimeoutException{return future.get(timeout,unit);}
        };
    }
}
