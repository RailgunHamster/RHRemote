package com.limelight.rhremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.limelight.Game;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Owns selection, admission HTTP and pairing until cleanup or the Game handoff. */
public final class RhAdmission implements RhActions.Target {
    private static final String EXTRA="rhremote.admission";
    private static final AtomicLong IDS=new AtomicLong();
    public interface Work { Runnable run(RhAdmission operation)throws Exception; }
    public interface Listener {
        void changed(RhAdmission operation);
        void finished(RhAdmission operation,boolean handedOff,String message);
    }
    private final long id=IDS.incrementAndGet();
    private final Activity activity;
    private final String uuid;
    private final Executor worker;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<Runnable> aborters=new ArrayList<>();
    private volatile boolean cancelled;
    private boolean running,external,cleaning,completed,handoff,gameAdmitted;
    private String phase="resolving",failure="",result="";
    private Runnable cancelExternal;
    private final Runnable handoffTimeout=()->fail("串流窗口未能启动");
    public RhAdmission(Activity activity,String uuid,Executor worker,Listener listener){
        this.activity=activity;this.uuid=uuid;this.worker=worker;this.listener=listener;
    }
    public void start(Work work){
        RhActions.beginAdmission(this);running=true;listener.changed(this);
        try{worker.execute(()->{
            Runnable next=null;Throwable error=null;
            try{checkpoint();next=work.run(this);checkpoint();}catch(Exception|LinkageError e){error=e;}
            final Runnable continuation=next;final Throwable failed=error;
            main.post(()->{
                running=false;
                if(cancelled||activity.isFinishing()||activity.isDestroyed()){cancel();return;}
                if(failed!=null){fail(message(failed));return;}
                try{continuation.run();if(!handoff&&!external&&!cancelled)fail("连接流程未完成");}
                catch(Exception|LinkageError e){fail(message(e));}
            });
        });}catch(RuntimeException rejected){running=false;fail("连接任务无法启动");}
    }
    public void checkpoint()throws IOException {
        if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("连接已取消");
    }
    public void progress(String stage){
        if(cancelled)return;
        RhStatistics.event("connection_admission_stage",-1,RhActions.obj("phase",stage,"attemptId",id));
        main.post(()->{if(running&&!cancelled){phase=stage;listener.changed(this);}});
    }
    public final class Watch implements AutoCloseable {
        private final Runnable abort;private boolean closed;
        private Watch(Runnable abort){this.abort=abort;}
        @Override public void close(){synchronized(aborters){if(!closed){closed=true;aborters.remove(abort);}}}
    }
    /** Registration and cancellation share a lock, including cancel-before-register. */
    public Watch watch(Runnable abort){
        boolean stop;synchronized(aborters){stop=cancelled;if(!stop)aborters.add(abort);}
        if(stop)abort(abort);return new Watch(abort);
    }
    private static void abort(Runnable callback){try{callback.run();}catch(Exception|LinkageError ignored){}}
    public void handoff(Runnable launch)throws IOException {
        checkpoint();handoff=true;phase="starting";listener.changed(this);
        RhStatistics.event("connection_admission_handoff",-1,RhActions.obj("attemptId",id));
        main.postDelayed(handoffTimeout,15_000);launch.run();
    }
    public void pair(Runnable begin,Runnable abort)throws IOException {
        checkpoint();external=true;cancelExternal=abort;phase="pairing";listener.changed(this);
        try{begin.run();}catch(RuntimeException|LinkageError e){external=false;cancelExternal=null;throw e;}
    }
    public void pairFinished(String message){
        external=false;cancelExternal=null;result=message;
        if(!cancelled)phase="cleaning";listener.changed(this);cleanup();
    }
    public void cancel(){
        if(completed)return;
        if(cancelled){cleanup();return;}
        cancelled=true;phase="cancelling";main.removeCallbacks(handoffTimeout);
        RhStatistics.event("connection_admission_cancel",-1,RhActions.obj("attemptId",id));
        ArrayList<Runnable> pending;synchronized(aborters){pending=new ArrayList<>(aborters);aborters.clear();}
        for(Runnable callback:pending)abort(callback);
        if(cancelExternal!=null)abort(cancelExternal);
        listener.changed(this);cleanup();
    }
    private void fail(String message){
        if(completed)return;failure=message;cancel();
    }
    private void cleanup(){
        if(running||external||cleaning||completed||!RhActions.ownsAdmission(this))return;
        cleaning=true;
        try{worker.execute(()->{
            Throwable failed=null;
            try(RhStatistics.Scope trace=new RhStatistics.Scope("connection_admission_cleanup")){RhTunnel.stop();}
            catch(Exception|LinkageError e){failed=e;}finally{RhStatistics.finish();}
            final Throwable error=failed;main.post(()->{
                cleaning=false;
                if(error!=null){failure="通道清理失败，请重新启动 RHRemote";phase="error";listener.changed(this);return;}
                complete(false,!failure.isEmpty()?"连接失败："+failure:cancelled?"已取消连接":result);
            });
        });}catch(RuntimeException rejected){cleaning=false;failure="通道清理任务无法启动";phase="error";listener.changed(this);}
    }
    private void complete(boolean transferred,String message){
        completed=true;phase="complete";main.removeCallbacks(handoffTimeout);
        synchronized(aborters){aborters.clear();}
        RhActions.endAdmission(this);listener.finished(this,transferred,message);
    }
    private boolean canLaunch(){return handoff&&!gameAdmitted&&!cancelled&&!completed;}
    boolean canAttach(){return handoff&&gameAdmitted&&!cancelled&&!completed;}
    void attached(){complete(true,"");}
    public static void stamp(Intent intent){
        RhAdmission operation=RhActions.admission();
        if(operation!=null&&operation.canLaunch()&&operation.uuid.equals(intent.getStringExtra(Game.EXTRA_PC_UUID)))intent.putExtra(EXTRA,operation.id);
    }
    public static boolean allowGame(Intent intent){
        RhAdmission operation=RhActions.admission();long requested=intent.getLongExtra(EXTRA,0);
        boolean allowed=requested==0?operation==null:operation!=null&&operation.id==requested&&operation.canLaunch()&&operation.uuid.equals(intent.getStringExtra(Game.EXTRA_PC_UUID));
        if(allowed){if(operation!=null)operation.gameAdmitted=true;intent.removeExtra(EXTRA);} // Subsequent recovery has its own reservation.
        return allowed;
    }
    private static String message(Throwable error){return error.getMessage()==null?"连接失败":error.getMessage();}
    public JSONObject state(){return RhActions.obj("connected",false,"uuid",uuid,"attemptId",id,"phase",phase,"cancelled",cancelled,
        "workerRunning",running,"pairing",external,"cleanupPending",cleaning||running||external,"error",failure);}
    public JSONObject perform(String action,JSONObject args){
        if(!action.equals("stream.disconnect")&&!action.equals("pair.cancel"))throw new IllegalStateException("正在连接，可取消当前连接");
        cancel();return RhActions.obj("cancelled",true,"cleanupPending",!completed);
    }
}
