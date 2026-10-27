package com.limelight.rhremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Owns the one in-process sync operation and conflict review, independently of a View. */
final class RhSyncWork {
    interface Listener { void changed(); }
    private static RhSyncWork shared;
    static synchronized RhSyncWork shared(Context context){
        Context application=context.getApplicationContext();
        if(shared==null||shared.context!=application)shared=new RhSyncWork(application);
        return shared;
    }
    private final Context context;
    final RhSync sync;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean running;
    private long operation,revision,lastAttempt;
    private String phase="idle",error="";
    private RhSyncWork(Context application){context=application;sync=new RhSync(application);}
    synchronized boolean busy(){return running;}
    synchronized long revision(){return revision;}
    synchronized long lastAttempt(){return lastAttempt;}
    synchronized String error(){return error;}
    synchronized String phase(){return phase;}
    synchronized JSONObject state(){JSONObject value=sync.state();try{value.put("operationId",operation);value.put("phase",phase);value.put("busy",running);value.put("error",error);value.put("lastAttemptMs",lastAttempt);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return value;}
    synchronized void attach(Listener value){listener=value;}
    synchronized void detach(Listener value){if(listener==value)listener=null;}
    private void changed(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::changed);return;}
        Listener current;synchronized(this){current=listener;}if(current!=null)current.changed();
    }
    /** Form edits dismiss an old request error; they never discard a conflict snapshot. */
    synchronized void dismissError(){if(!running&&!error.isEmpty()){error="";if(phase.equals("error"))phase="idle";revision++;}}
    void configured(){
        synchronized(this){if(running)throw new IllegalStateException("同步进行中，请稍后保存");error="";phase="idle";lastAttempt=0;revision++;}
        changed();
    }
    void start(JSONObject local,String resolution,Executor worker)throws Exception{
        // Freeze exactly the configuration that the user submitted for this operation.
        JSONObject captured=new JSONObject(local.toString());
        synchronized(this){
            if(running)throw new IllegalStateException("同步进行中，请稍后保存");
            running=true;error="";phase="syncing";operation++;revision++;lastAttempt=System.currentTimeMillis();
        }
        changed();
        try{worker.execute(()->{
            JSONObject result=null;String failure="";
            try{result=sync.run(captured,resolution);}catch(Exception invalid){failure=RhSync.failureMessage(invalid);}
            JSONObject completed=result;String networkError=failure;
            main.post(()->{
                String nextError=networkError,nextPhase="completed";
                if(nextError.isEmpty())try{
                    if(completed.has("shared"))RhShared.apply(context,completed.getJSONObject("shared"));
                    else nextPhase="conflict";
                }catch(Exception invalid){nextError="同步已传输，但本机配置应用失败，请重试";}
                synchronized(this){running=false;error=nextError;phase=error.isEmpty()?nextPhase:"error";revision++;}
                changed();
            });
        });}catch(RuntimeException rejected){
            synchronized(this){running=false;error="同步任务未能启动，请重试";phase="error";revision++;}
            changed();throw rejected;
        }
    }
}
