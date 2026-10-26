package com.limelight.rhremote;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.concurrent.Executor;

/** Owns a read-only lookup. The UI commits only after cancellation has been ruled out. */
public final class RhDeviceAdd extends RhCancellation {
    public interface Work {ComputerDetails probe(RhDeviceAdd operation)throws Exception;}
    public interface Listener {void changed(RhDeviceAdd operation);void finished(RhDeviceAdd operation,ComputerDetails device,String error);}
    private final Activity activity;private final Executor worker;private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean running;private volatile boolean completed;
    public RhDeviceAdd(Activity activity,Executor worker,Listener listener){this.activity=activity;this.worker=worker;this.listener=listener;}
    public boolean cancelled(){return cancelled;}
    public boolean isFinished(){return completed;}
    public void cancel(){if(completed||cancelled)return;cancelRequests();listener.changed(this);}
    public void start(Work work){
        running=true;listener.changed(this);
        try{worker.execute(()->{
            ComputerDetails found=null;String failure="";
            try{checkpoint();found=work.probe(this);checkpoint();if(found==null||found.uuid==null||!found.uuid.matches("[A-Za-z0-9-]{1,128}")||found.name==null||found.name.trim().isEmpty())throw new java.io.IOException("主机返回的设备信息无效");}
            catch(Exception|LinkageError error){failure=error.getMessage()==null?"无法访问设备，请检查地址、网络和 Sunshine 状态":error.getMessage();}
            finally{forgetRequests();}
            ComputerDetails result=found;String error=failure;
            main.post(()->{running=false;if(completed)return;completed=true;if(activity.isDestroyed()||activity.isFinishing())cancelled=true;listener.finished(this,result,error);});
        });}catch(RuntimeException rejected){running=false;completed=true;listener.finished(this,null,"查找任务无法启动");}
    }
}
