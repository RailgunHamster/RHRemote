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
    private final Handler main=new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean running,installReady,installIntent,resumeInstall;
    private long operation;

    private RhUpdateWork(Context context){this.context=context;updates=new RhUpdates(context);}
    synchronized boolean busy(){return running||installReady||resumeInstall;}
    synchronized JSONObject state(){JSONObject value=updates.state();try{value.put("operationId",operation);value.put("busy",busy());value.put("awaitingForeground",!running&&(installReady||resumeInstall));}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return value;}
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
            running=true;installIntent=id.equals("updates.install");operation++;updates.pending(id);
        }
        changed();
        try{worker.execute(()->{
            boolean ready=false;
            try{
                if(id.equals("updates.check")){updates.check();if(automatic&&!updates.state().isNull("availableVersion"))updates.download();}
                else if(id.equals("updates.install"))ready=updates.prepareInstall();
                else updates.download();
            }catch(Exception error){updates.failure(error.getMessage()==null?"更新操作失败，请重试":error.getMessage());}
            final boolean prepared=ready;
            main.post(()->{synchronized(this){running=false;installReady=prepared&&installIntent;if(prepared&&!installIntent)updates.deferInstall();}changed();});
        });}catch(RuntimeException rejected){synchronized(this){running=false;installIntent=false;updates.failure("更新任务未能启动，请重试");}changed();throw rejected;}
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
