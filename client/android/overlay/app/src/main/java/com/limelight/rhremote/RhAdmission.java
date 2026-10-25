package com.limelight.rhremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.limelight.Game;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Owns selection, admission HTTP and pairing until cleanup or the Game handoff. */
public final class RhAdmission extends RhCancellation implements RhActions.Target {
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
    private boolean running,external,cleaning,completed,handoff,gameAdmitted,gameCleanupPending,gameStopping;
    private RhRecovery claimedGame;
    private Intent claimedIntent;
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
            final long preparedAt=RhStatistics.now();
            RhStatistics.event("connection_admission_work_ready",-1,RhActions.obj("attemptId",id,"failed",failed!=null));
            main.post(()->{
                if(preparedAt!=0)RhStatistics.event("connection_admission_ui_queue",-1,RhActions.obj("attemptId",id),preparedAt,RhStatistics.now()-preparedAt);
                running=false;
                if(cancelled||activity.isFinishing()||activity.isDestroyed()){cancel();return;}
                if(failed!=null){fail(message(failed));return;}
                try{continuation.run();if(!handoff&&!external&&!cancelled)fail("连接流程未完成");}
                catch(Exception|LinkageError e){fail(message(e));}
            });
        });}catch(RuntimeException rejected){running=false;fail("连接任务无法启动");}
    }
    public void progress(String stage){
        if(cancelled)return;
        RhStatistics.event("connection_admission_stage",-1,RhActions.obj("phase",stage,"attemptId",id));
        main.post(()->{if(running&&!cancelled){phase=stage;listener.changed(this);}});
    }
    public void handoff(Runnable launch)throws IOException {
        checkpoint();handoff=true;phase="starting";
        try(RhStatistics.Scope trace=new RhStatistics.Scope("admission_ui_launch_state")){listener.changed(this);}
        RhStatistics.event("connection_admission_handoff",-1,RhActions.obj("attemptId",id));
        main.postDelayed(handoffTimeout,15_000);
        try(RhStatistics.Scope trace=new RhStatistics.Scope("admission_activity_launch")){launch.run();}
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
        phase="cancelling";main.removeCallbacks(handoffTimeout);
        RhStatistics.event("connection_admission_cancel",-1,RhActions.obj("attemptId",id));
        cancelRequests();
        if(cancelExternal!=null)abort(cancelExternal);
        listener.changed(this);stopClaimedGame();cleanup();
    }
    private void fail(String message){
        if(completed)return;failure=message;cancel();
    }
    private void cleanup(){
        if(running||external||gameCleanupPending||cleaning||completed||!RhActions.ownsAdmission(this))return;
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
        if(!transferred&&!failure.isEmpty())RhActions.connectionError(message);
        completed=true;phase="complete";main.removeCallbacks(handoffTimeout);
        forgetRequests();
        if(transferred&&claimedIntent!=null)claimedIntent.removeExtra(EXTRA);claimedIntent=null;
        if(claimedGame!=null)claimedGame.clearAdmission(this);claimedGame=null;
        RhActions.endAdmission(this);listener.finished(this,transferred,message);
    }
    private boolean canLaunch(){return handoff&&!gameAdmitted&&!cancelled&&!completed;}
    boolean canAttach(){return handoff&&gameAdmitted&&!cancelled&&!completed;}
    void attached(){gameCleanupPending=false;complete(true,"");}
    boolean matchesClaim(Intent intent){return gameAdmitted&&intent.getLongExtra(EXTRA,0)==id&&uuid.equals(intent.getStringExtra(Game.EXTRA_PC_UUID));}
    void gameReady(RhRecovery game){
        if(completed){game.clearAdmission(this);return;}claimedGame=game;if(cancelled)stopClaimedGame();
    }
    private void stopClaimedGame(){
        if(!cancelled||!gameCleanupPending||claimedGame==null||gameStopping||completed)return;
        gameStopping=true;
        try{claimedGame.stopBeforeAttach(cleaned->{
            Runnable acknowledged=()->{
                if(completed||!gameCleanupPending)return;
                if(!cleaned){failure="串流窗口清理超时，等待清理完成";phase="error";listener.changed(this);return;}
                gameCleanupPending=false;cleanup();
            };
            if(Looper.myLooper()==Looper.getMainLooper())acknowledged.run();else main.post(acknowledged);
        });}catch(RuntimeException|LinkageError e){failure="串流窗口清理失败，请重新启动 RHRemote";phase="error";listener.changed(this);}
    }
    boolean belongsTo(Activity owner){return activity==owner;}
    public static void stamp(Intent intent){
        RhAdmission operation=RhActions.admission();
        if(operation!=null&&operation.canLaunch()&&operation.uuid.equals(intent.getStringExtra(Game.EXTRA_PC_UUID)))intent.putExtra(EXTRA,operation.id);
    }
    public static boolean allowGame(Intent intent){
        RhAdmission operation=RhActions.admission();long requested=intent.getLongExtra(EXTRA,0);
        boolean allowed=requested==0?operation==null:operation!=null&&operation.id==requested&&operation.canLaunch()&&operation.uuid.equals(intent.getStringExtra(Game.EXTRA_PC_UUID));
        if(allowed&&operation!=null){operation.gameAdmitted=true;operation.gameCleanupPending=true;operation.claimedIntent=intent;}
        return allowed;
    }
    private static String message(Throwable error){return error.getMessage()==null?"连接失败":error.getMessage();}
    public JSONObject state(){return RhActions.obj("connected",false,"uuid",uuid,"attemptId",id,"phase",phase,"cancelled",cancelled,
        "workerRunning",running,"pairing",external,"cleanupPending",cleaning||running||external||gameCleanupPending,"error",failure);}
    public JSONObject perform(String action,JSONObject args){
        if(!action.equals("stream.disconnect")&&!action.equals("pair.cancel"))throw new IllegalStateException("正在连接，可取消当前连接");
        cancel();return RhActions.obj("cancelled",true,"cleanupPending",!completed);
    }
}
