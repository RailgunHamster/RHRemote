package com.limelight.rhremote;

import android.content.Context;
import android.content.SharedPreferences;
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
    private final SharedPreferences journal;
    private static final String INTERRUPTED="上次同步已中断，传输结果未确认。自动同步已暂停，请手动同步核对";
    final RhSync sync;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean running,interrupted;
    private long operation,revision,lastAttempt;
    private String phase="idle",error="";
    private RhSyncWork(Context application){
        context=application;sync=new RhSync(application);journal=context.getSharedPreferences("rhremote.sync.operations",Context.MODE_PRIVATE);
        try{if(journal.getBoolean("pending",false)||sync.outcomeUncertain()){interrupted=true;phase="interrupted";error=INTERRUPTED;operation=journal.getLong("operationId",0);lastAttempt=journal.getLong("startedAtMs",0);}}
        catch(ClassCastException invalid){interrupted=true;phase="interrupted";error="上次同步状态无法读取，自动同步已暂停，请手动同步核对";}
    }
    synchronized boolean busy(){return running;}
    synchronized long revision(){return revision;}
    synchronized long lastAttempt(){return lastAttempt;}
    synchronized String error(){return error;}
    synchronized String phase(){return phase;}
    synchronized boolean canAutomaticallySync(){return !running&&!interrupted&&!sync.outcomeUncertain();}
    synchronized JSONObject state(){JSONObject value=sync.state();try{value.put("operationId",operation);value.put("phase",phase);value.put("busy",running);value.put("error",error);value.put("lastAttemptMs",lastAttempt);value.put("interrupted",interrupted);value.put("automaticBlocked",interrupted||sync.outcomeUncertain());}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return value;}
    synchronized void attach(Listener value){listener=value;}
    synchronized void detach(Listener value){if(listener==value)listener=null;}
    private void changed(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::changed);return;}
        Listener current;synchronized(this){current=listener;}if(current!=null)current.changed();
    }
    /** Form edits dismiss an old request error; they never discard a conflict snapshot. */
    synchronized void dismissError(){if(!running&&!interrupted&&!error.isEmpty()){error="";if(phase.equals("error"))phase="idle";revision++;}}
    void configured(){
        synchronized(this){if(running)throw new IllegalStateException("同步进行中，请稍后保存");if(!interrupted){error="";phase="idle";lastAttempt=0;}revision++;}
        changed();
    }
    void removed()throws java.io.IOException{
        synchronized(this){if(running)throw new IllegalStateException("同步进行中，请稍后移除");if(!journal.edit().clear().commit())throw new java.io.IOException("同步状态未能清除，请重试");interrupted=false;error="";phase="idle";lastAttempt=0;revision++;}
        changed();
    }
    void start(JSONObject local,String resolution,Executor worker)throws Exception{
        start(local,resolution,false,worker);
    }
    void start(JSONObject local,String resolution,boolean automatic,Executor worker)throws Exception{
        // Freeze exactly the configuration that the user submitted for this operation.
        JSONObject captured=new JSONObject(local.toString());
        boolean recorded;
        synchronized(this){
            if(running)throw new IllegalStateException("同步进行中，请稍后保存");
            if(automatic&&(interrupted||sync.outcomeUncertain()))throw new IllegalStateException(INTERRUPTED);
            long next=operation+1,now=System.currentTimeMillis();
            recorded=journal.edit().putBoolean("pending",true).putLong("operationId",next).putLong("startedAtMs",now).commit();
            if(recorded){running=true;interrupted=false;error="";phase="syncing";operation=next;lastAttempt=now;}
            else{error="无法记录同步任务，请检查存储空间后重试";phase="error";}
            revision++;
        }
        changed();
        if(!recorded)throw new java.io.IOException(error());
        try{worker.execute(()->{
            JSONObject result=null;String failure="",nextPhase="completed";boolean uncertain=false;
            try{result=sync.run(captured,resolution);}catch(Exception invalid){failure=RhSync.failureMessage(invalid);uncertain=sync.state().optBoolean("awaitingLocalApply")||sync.outcomeUncertain();if(sync.outcomeUncertain())failure+="。远端写入结果未确认，自动同步已暂停，请手动同步核对";}
            if(failure.isEmpty()&&result!=null){
                if(result.has("shared")){
                    try{progress("applying");RhShared.apply(context,result.getJSONObject("shared"));}
                    catch(Exception invalid){failure="同步已传输，但本机配置应用失败。自动同步已暂停，请检查存储后重试";uncertain=true;}
                    if(failure.isEmpty())try{progress("recording");sync.applied(result.getJSONObject("shared"));}
                    catch(Exception invalid){failure="配置已应用，但同步完成状态保存失败。自动同步已暂停，请检查存储后重试";uncertain=true;}
                }else{nextPhase="conflict";uncertain=sync.outcomeUncertain();if(uncertain)failure=INTERRUPTED;}
            }
            finish(failure,nextPhase,uncertain);
        });}catch(RuntimeException rejected){
            finish("同步任务未能启动，请重试","error");
            throw rejected;
        }
    }
    private void progress(String stage){synchronized(this){phase=stage;revision++;}changed();}
    private void finish(String failure,String completedPhase){
        finish(failure,completedPhase,false);
    }
    private void finish(String failure,String completedPhase,boolean uncertain){
        boolean recorded=uncertain||journal.edit().putBoolean("pending",false).commit();
        Runnable completed=()->{
            synchronized(this){
                running=false;
                if(uncertain){interrupted=true;error=failure;phase=completedPhase.equals("conflict")?"conflict":"error";}
                else if(!recorded){interrupted=true;error="同步结果的状态未能保存，自动同步已暂停，请手动同步核对";phase="interrupted";}
                else{interrupted=false;error=failure;phase=error.isEmpty()?completedPhase:"error";}
                revision++;
            }
            changed();
        };
        if(Looper.myLooper()==Looper.getMainLooper())completed.run();else main.post(completed);
    }
}
