package com.limelight.rhremote;

import android.util.Log;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Waiting for a discovery network lock must never block the View/action dispatcher. */
public final class RhDiscoveryRefresh {
    private static final Map<Object,Map<String,Object>> PENDING=new IdentityHashMap<>();
    private static final ExecutorService WORK=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"RHRemote discovery refresh");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private RhDiscoveryRefresh(){}
    public static void request(Object owner,Collection<String> devices,Consumer<String> invalidate){
        Objects.requireNonNull(owner);Objects.requireNonNull(invalidate);
        List<String> accepted=new ArrayList<>();
        Object token=new Object();
        synchronized(PENDING){
            Map<String,Object> current=PENDING.computeIfAbsent(owner,k->new HashMap<>());
            for(String uuid:new LinkedHashSet<>(devices))if(uuid!=null&&!uuid.isEmpty()&&!current.containsKey(uuid)){current.put(uuid,token);accepted.add(uuid);}
            if(accepted.isEmpty()){if(current.isEmpty())PENDING.remove(owner);return;}
            try{WORK.execute(()->{
                try{for(String uuid:accepted){
                    try{invalidate.accept(uuid);}catch(RuntimeException failure){Log.w("RHRemote","Discovery refresh could not be scheduled",failure);}
                    finally{finished(owner,uuid,token);}
                }}finally{for(String uuid:accepted)finished(owner,uuid,token);}
            });}catch(RejectedExecutionException full){for(String uuid:accepted)finished(owner,uuid,token);throw new IllegalStateException("检查请求过多，请稍后重试",full);}
        }
    }
    private static void finished(Object owner,String uuid,Object token){synchronized(PENDING){Map<String,Object> current=PENDING.get(owner);if(current!=null){current.remove(uuid,token);if(current.isEmpty())PENDING.remove(owner);}}}
    static boolean pending(Object owner,String uuid){synchronized(PENDING){Map<String,Object> current=PENDING.get(owner);return current!=null&&current.containsKey(uuid);}}
}
