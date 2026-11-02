package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** One process-wide update operation. Workers never retain an Activity or View. */
final class RhUpdateWork {
    interface Listener { void changed(); }
    private static RhUpdateWork shared;
    static synchronized RhUpdateWork shared(Context context) {
        Context application=context.getApplicationContext();
        if(shared==null||shared.context!=application)shared=new RhUpdateWork(application);
        return shared;
    }
    private final Context context;
    final RhUpdates updates;
    final RhReleaseHistory history;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean running,installReady,installIntent,resumeInstall;
    private boolean cancelRequested;
    private String cancelledAction="";
    private long operation;

    private RhUpdateWork(Context context){this.context=context;updates=new RhUpdates(context);history=new RhReleaseHistory(context,this::changed);}
    synchronized boolean busy(){return running||installReady||resumeInstall;}
    synchronized void setSource(String source){if(busy())throw new IllegalStateException("更新操作正在进行");updates.setSource(source);history.sourceChanged();}
    synchronized boolean awaitingInstall(){return !running&&(installReady||resumeInstall);}
    private String intent(JSONObject value){return new org.json.JSONArray().put(updates.primaryAction()).put(operation).put(value.opt("availableVersion")).put(value.optBoolean("pendingRestart")).put(value.opt("candidateId")).toString();}
    synchronized JSONObject state(){JSONObject value=updates.state();try{value.put("history",history.state());value.put("operationId",operation);value.put("busy",busy());value.put("canCancel",busy()&&!cancelRequested);value.put("cancelRequested",running&&cancelRequested);value.put("awaitingForeground",!running&&(installReady||resumeInstall));if(running&&cancelRequested){value.put("phase","cancelling");value.put("status","正在取消更新…");}value.put("intent",intent(value));}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return value;}
    synchronized void validateIntent(JSONObject args){if(args.has("expectedIntent")&&!(args.opt("expectedIntent") instanceof String&&state().optString("intent").equals(args.opt("expectedIntent"))))throw new IllegalStateException("更新状态已变化，请重新点击当前操作");}
    synchronized void attach(Listener value){listener=value;}
    synchronized boolean detach(Listener value){if(listener!=value)return false;listener=null;return true;}
    private void changed(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::changed);return;}
        Listener current;synchronized(this){current=listener;}if(current!=null)current.changed();
    }
    void start(String id,boolean automatic,Executor worker){
        synchronized(this){
            if(busy())throw new IllegalStateException("更新操作正在进行");
            if(!java.util.Arrays.asList("updates.check","updates.download","updates.install").contains(id))throw new IllegalArgumentException("unknown update action");
            running=true;installIntent=id.equals("updates.install");operation++;cancelRequested=false;cancelledAction="";updates.beginOperation(operation);updates.pending(id);
        }
        changed();
        try{worker.execute(()->{
            boolean ready=false;
            try{
                if(id.equals("updates.check")){updates.check();if(!updates.isCancelled()&&automatic&&!updates.state().isNull("availableVersion"))updates.download();}
                else if(id.equals("updates.install"))ready=updates.prepareInstall();
                else updates.download();
            }catch(Exception error){if(!updates.isCancelled())updates.failure(error.getMessage()==null?"更新操作失败，请重试":error.getMessage());}
            final boolean prepared=ready;
            main.post(()->{synchronized(this){running=false;if(cancelRequested){installReady=false;resumeInstall=false;installIntent=false;updates.cancelled(cancelledAction);}else{installReady=prepared&&installIntent;if(prepared&&!installIntent)updates.deferInstall();}}changed();});
        });}catch(RuntimeException rejected){synchronized(this){running=false;installIntent=false;updates.failure("更新任务未能启动，请重试");}changed();throw rejected;}
    }
    void cancel(long expected){
        synchronized(this){
            if(expected!=operation)throw new IllegalStateException("更新操作已变化，未执行取消");
            if(!busy())throw new IllegalStateException("当前没有可取消的更新操作");
            if(!cancelRequested){cancelRequested=true;cancelledAction=updates.cancellationAction(updates.state().optString("phase").equals("checking")?"updates.check":installIntent?"updates.install":"updates.download");updates.requestCancel(operation);installIntent=false;resumeInstall=false;if(!running){installReady=false;updates.cancelled(cancelledAction);}}
        }
        changed();
    }
    /** Only an actual foreground entry may consume a return from Android's external UI. */
    synchronized void returned(){if(updates.resumeInstallRequest())resumeInstall=true;}
    /** Called on the UI thread by the currently resumed home. Consumes a handoff once. */
    void continueInstall(Activity activity,Executor worker){
        boolean restart=false;
        synchronized(this){
            if(running)return;
            if(resumeInstall){resumeInstall=false;if(!installReady)restart=true;}
            if(!restart){if(!installReady)return;installReady=false;installIntent=false;}
        }
        if(restart){start("updates.install",false,worker);return;}
        try{updates.install(activity);}catch(Exception error){updates.failure(error.getMessage()==null?"系统安装界面未能打开":error.getMessage());}
        changed();
    }
    /** Explicitly closing the home drops its install handoff, while a download may finish. */
    void abandonInstall(){synchronized(this){installIntent=false;resumeInstall=false;if(installReady){installReady=false;updates.deferInstall();}}changed();}
}
