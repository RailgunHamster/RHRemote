package com.limelight.rhremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.limelight.Game;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.jni.MoonBridge;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns cleanup, route resolution and the next Game until its controller attaches. */
public final class RhRecovery extends RhCancellation implements RhActions.Target {
    static final String EXTRA="rhremote.recovery",PROFILE="rhremote.sessionProfile";
    private static final String HANDOFF="rhremote.recoveryHandoff";
    private static final AtomicLong IDS=new AtomicLong();
    // A false callback is a timeout. Only a later true callback proves native/HTTP cleanup.
    public interface Stopper { void stop(Consumer<Boolean> completed); }
    interface Resolver { ComputerDetails resolve(Intent intent,RhProfile profile,JSONArray failed,RhCancellation operation)throws Exception; }
    private final long id=IDS.incrementAndGet();
    private final Activity activity;
    private final String uuid;
    private final RhProfile profile;
    private final Stopper stopper;
    private final Resolver resolver;
    private final Executor worker;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final RhRecoveryPolicy policy;
    private final JSONObject route;
    private RhRecovery predecessor;
    private RhAdmission initialAdmission;
    private volatile boolean retrying,fatal,handoff,cleanupPending,workerRunning,cancelCleanupDone,completed,gameAdmitted,oldStopped;
    private Stopper successorStopper;
    private RhRecovery successor;
    private boolean successorStopping,terminalPending;
    private Intent claimedIntent;
    private String error="";
    private final Runnable watchdog,handoffTimeout=()->failed("串流窗口未能接管连接");

    public RhRecovery(Activity activity,RhProfile profile,Stopper stopper){
        this(activity,profile,stopper,(intent,p,failed,operation)->{
            operation.checkpoint();
            ComputerDetails source=new ComputerDetails();
            source.uuid=intent.getStringExtra(Game.EXTRA_PC_UUID);source.name=intent.getStringExtra(Game.EXTRA_PC_NAME);
            source.httpsPort=intent.getIntExtra(Game.EXTRA_HTTPS_PORT,47984);
            source.manualAddress=new ComputerDetails.AddressTuple(intent.getStringExtra(Game.EXTRA_HOST),intent.getIntExtra(Game.EXTRA_PORT,47989));
            source.activeAddress=source.manualAddress;
            byte[] cert=intent.getByteArrayExtra(Game.EXTRA_SERVER_CERT);
            if(cert==null)throw new IOException("缺少配对证书，请重新配对");
            source.serverCert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
            RhTunnel.stop();operation.checkpoint();
            return RhNetwork.resolve(activity.getApplicationContext(),source,p,RhActions.obj("failedRoutes",failed),operation);
        },task->new Thread(task,"RHRemote reconnect").start());
    }
    RhRecovery(Activity activity,RhProfile profile,Stopper stopper,Resolver resolver,Executor worker){
        this.activity=activity;this.uuid=activity.getIntent().getStringExtra(Game.EXTRA_PC_UUID);
        this.profile=profile;this.stopper=stopper;this.resolver=resolver;this.worker=worker;
        RhRecovery pending=RhActions.recovery();predecessor=pending!=null&&pending.matchesClaim(activity.getIntent())?pending:null;
        RhAdmission entering=RhActions.admission();initialAdmission=entering!=null&&entering.matchesClaim(activity.getIntent())?entering:null;
        // Initialization must finish before this stopper can access Game's connection fields.
        if(predecessor!=null)main.post(()->{
            RhRecovery previous=predecessor;if(previous==null)return;
            if(!RhActions.ownsRecovery(previous)){predecessor=null;return;}
            previous.successorReady(this);
        });
        if(initialAdmission!=null)main.post(()->{
            RhAdmission enteringGame=initialAdmission;if(enteringGame!=null)enteringGame.gameReady(this);
        });
        JSONObject saved=parse(activity.getIntent().getStringExtra(EXTRA));
        long origin=RhStatistics.originUs();if(origin<=0)origin=System.currentTimeMillis()*1000;
        policy=new RhRecoveryPolicy(saved,origin);route=RhNetwork.state();
        watchdog=()->{if(!policy.rendered()&&!cancelled&&!retrying&&!retry(true,"首帧等待超时")){background();failed("首帧等待超时，已停止本轮连接");}};
        main.postDelayed(watchdog,15_000);
        RhStatistics.event("native_attempt",-1,policy.state());
    }
    private static JSONObject parse(String json){try{return json==null?new JSONObject():new JSONObject(json);}catch(Exception e){return new JSONObject();}}
    private void onMain(Runnable action){if(Looper.myLooper()==Looper.getMainLooper())action.run();else main.post(action);}
    public static RhProfile profile(Activity activity,RhProfile fallback){
        String value=activity.getIntent().getStringExtra(PROFILE);
        try{return value==null?fallback:RhProfile.fromJson(activity.getIntent().getStringExtra(Game.EXTRA_PC_UUID),parse(value));}
        catch(Exception e){throw new IllegalArgumentException("重连配置无效",e);}
    }
    public void frame(){if(!cancelled&&!retrying){boolean first=!policy.rendered();policy.frame(SystemClock.elapsedRealtime());if(first)main.removeCallbacks(watchdog);}}
    public boolean stageFailed(int portFlags,int code){return stageFailed("",portFlags,code);}
    public boolean stageFailed(String stage,int portFlags,int code){return retry(RhRecoveryPolicy.networkStage(stage,portFlags,code),"连接阶段失败 ("+code+")");}
    /** Fatal startup errors return to the device list after scoped cleanup, without a modal connection owner. */
    public void handleStageFailure(String stage,int portFlags,int code){
        if(stageFailed(stage,portFlags,code))return;
        RhStatistics.event("connection_stage_failed",-1,RhActions.obj("name",stage,"code",code));
        onMain(()->{RhActions.connectionError("连接失败："+stage+" ("+code+")");background();activity.finish();});
    }
    public boolean terminated(int code){
        if(code==MoonBridge.ML_ERROR_GRACEFUL_TERMINATION){if(!retrying)terminal(code,false);return true;}
        boolean eligible=policy.rendered()||code==MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC||code==MoonBridge.ML_ERROR_NO_VIDEO_FRAME||code==MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION;
        if(code==MoonBridge.ML_ERROR_PROTECTED_CONTENT||code==MoonBridge.ML_ERROR_FRAME_CONVERSION)eligible=false;
        if(!retry(eligible,"串流中断 ("+code+")"))terminal(code,true);
        return true;
    }
    /** Terminal failures retain the cleanup lease and close the obsolete Game. */
    private void terminal(int code,boolean failure){
        onMain(()->{
            if(terminalPending)return;terminalPending=true;
            if(!RhActions.belongsElsewhere(activity)){
                RhStatistics.event("connection_terminated",-1,RhActions.obj("code",code,"retry",false));
                if(failure)RhActions.connectionError("连接已断开 ("+code+")");
                background();
            }
            activity.finish();
        });
    }
    public void interruptForTest(){
        if(!RhStatistics.enabled())throw new IllegalStateException("仅测试模式可注入中断");
        if(!policy.rendered()||retrying||cancelled||completed)throw new IllegalStateException("当前串流不可注入中断");
        RhStatistics.event("test_connection_interrupt",-1,RhActions.obj("synthetic",true,"attemptId",id));
        terminated(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC);
    }
    private synchronized boolean retry(boolean eligible,String message){
        if(completed||handoff||cancelled)return retrying;
        if(!eligible){fatal=true;cancelRequests();main.removeCallbacks(watchdog);if(retrying)main.post(()->failed(message));return false;}
        if(retrying)return true;
        String remote=route.optString("remoteAddress",route.optString("selectedAddress"));
        long delay=policy.failure(SystemClock.elapsedRealtime(),System.currentTimeMillis()*1000,route.optString("route"),remote,true);
        if(delay<0){cancelRequests();main.removeCallbacks(watchdog);return false;}
        retrying=true;cleanupPending=true;RhActions.beginRecovery(this);main.removeCallbacks(watchdog);
        RhStatistics.event("route_media_failed",-1,RhActions.obj("reason",message,"network",route,"recovery",policy.state()));
        main.post(()->{
            if(activity.isFinishing())cancel();
            stopOriginal(delay);
        });
        return true;
    }
    private void stopOriginal(long delay){
        try{stopper.stop(cleaned->onMain(()->{
            if(oldStopped||completed)return;
            if(!cleaned){RhStatistics.event("recovery_cleanup_timeout",-1,RhActions.obj("attemptId",id));if(!cancelled)failed("上一会话清理超时，已停止自动重连");return;}
            oldStopped=true;cleanupPending=false;
            if(cancelled){RhStatistics.finish();release();return;}
            resolveNext(delay);
        }));}catch(RuntimeException|LinkageError e){failed("上一会话清理失败，请重新启动 RHRemote");}
    }
    private void resolveNext(long delay){
        workerRunning=true;
        try{worker.execute(()->{
            try{
                checkpoint();
                try(Watch interrupt=watch(Thread.currentThread()::interrupt)){
                    if(delay>0)Thread.sleep(delay);checkpoint();
                    RhStatistics.finish();RhStatistics.beginAt(profile,policy.originUs);
                    ComputerDetails resolved=resolver.resolve(new Intent(activity.getIntent()),profile,policy.excluded(),this);
                    checkpoint();
                    Intent next=new Intent(activity.getIntent());
                    next.putExtra(Game.EXTRA_HOST,resolved.activeAddress.address);
                    next.putExtra(Game.EXTRA_PORT,resolved.activeAddress.port);
                    next.putExtra(EXTRA,policy.next().toString());next.putExtra(PROFILE,profile.json().toString());next.putExtra(HANDOFF,id);
                    main.post(()->launch(next));
                }
            }catch(Exception|LinkageError e){main.post(()->{if(!cancelled)failed(e.getMessage()==null?"自动重连失败":e.getMessage());});}
            finally{workerRunning=false;main.post(()->{if(cancelled)release();});}
        });}catch(RuntimeException rejected){workerRunning=false;failed("重连任务无法启动");}
    }
    private void launch(Intent next){
        if(cancelled||fatal||activity.isFinishing()||activity.isDestroyed()){cancel();return;}
        handoff=true;main.postDelayed(handoffTimeout,15_000);
        RhStatistics.event("recovery_handoff",-1,RhActions.obj("attemptId",id));
        try{activity.startActivity(next);activity.finish();}
        catch(RuntimeException|LinkageError e){failed("串流窗口未能启动");}
    }
    private void failed(String message){
        if(completed)return;
        error=message;cancel();RhStatistics.finish();
        RhActions.connectionError("连接失败："+message);
        android.widget.Toast.makeText(activity.getApplicationContext(),message,android.widget.Toast.LENGTH_LONG).show();
        activity.finish();
    }
    private synchronized void release(){
        if(completed||cleanupPending||workerRunning||!RhActions.ownsRecovery(this)||!cancelled)return;
        if(!cancelCleanupDone){
            workerRunning=true;
            try{worker.execute(()->{
                try{RhTunnel.stop();RhStatistics.finish();cancelCleanupDone=true;}
                catch(Exception|LinkageError e){main.post(()->error="通道清理失败，请重新启动 RHRemote");}
                finally{workerRunning=false;if(cancelCleanupDone)main.post(this::release);}
            });}catch(RuntimeException rejected){workerRunning=false;error="通道清理任务无法启动";}
        }else{completed=true;clearSuccessor();RhActions.endRecovery(this);}
    }
    public void cancel(){
        if(completed)return;
        cancelRequests();main.removeCallbacks(watchdog);main.removeCallbacks(handoffTimeout);
        onMain(()->{stopSuccessor();release();});
    }
    private void successorReady(RhRecovery next){
        if(completed){next.predecessor=null;return;}successor=next;successorStopper=next.stopper;if(cancelled)stopSuccessor();
    }
    private void clearSuccessor(){if(successor!=null)successor.predecessor=null;successor=null;successorStopper=null;}
    private void stopSuccessor(){
        if(!gameAdmitted||!cancelled||successorStopper==null||successorStopping||completed)return;
        successorStopping=true;
        try{successorStopper.stop(cleaned->onMain(()->{
            if(completed||!cleanupPending)return;
            if(!cleaned){error="新会话清理超时，等待清理完成";return;}
            cleanupPending=false;release();
        }));}catch(RuntimeException|LinkageError e){error="新会话清理失败，请重新启动 RHRemote";}
    }
    public void background(){
        RhAdmission entering=initialAdmission;
        if(entering!=null&&RhActions.ownsAdmission(entering)){entering.gameReady(this);entering.cancel();return;}
        initialAdmission=null;
        RhRecovery previous=predecessor;
        if(previous!=null&&RhActions.ownsRecovery(previous)){previous.successorReady(this);previous.cancel();return;}
        predecessor=null;
        if(handoff||completed)return;
        if(RhActions.belongsElsewhere(activity)){completed=true;main.removeCallbacks(watchdog);return;}
        cancel();
        if(!retrying){retrying=true;cleanupPending=true;RhActions.beginRecovery(this);stopOriginal(0);}
    }
    boolean belongsTo(Activity owner){return activity==owner;}
    void clearAdmission(RhAdmission owner){if(initialAdmission==owner)initialAdmission=null;}
    void stopBeforeAttach(Consumer<Boolean> completed){main.removeCallbacks(watchdog);stopper.stop(completed);}
    private boolean canLaunch(){return handoff&&!gameAdmitted&&!cancelled&&!completed&&oldStopped;}
    boolean canAttach(){return handoff&&gameAdmitted&&!cancelled&&!completed;}
    private boolean matchesClaim(Intent intent){return gameAdmitted&&intent.getLongExtra(HANDOFF,0)==id&&uuid!=null&&uuid.equals(intent.getStringExtra(Game.EXTRA_PC_UUID));}
    void attached(){
        completed=true;cleanupPending=false;main.removeCallbacks(handoffTimeout);forgetRequests();
        if(claimedIntent!=null)claimedIntent.removeExtra(HANDOFF);claimedIntent=null;clearSuccessor();
        RhActions.endRecovery(this);RhStatistics.event("recovery_attached",-1,RhActions.obj("attemptId",id));
    }
    public static boolean allowGame(Intent intent){
        RhRecovery pending=RhActions.recovery();long requested=intent.getLongExtra(HANDOFF,0);
        if(requested==0)return pending==null;
        if(pending==null||pending.id!=requested||!pending.canLaunch()||pending.uuid==null||!pending.uuid.equals(intent.getStringExtra(Game.EXTRA_PC_UUID)))return false;
        pending.gameAdmitted=true;pending.cleanupPending=true;pending.claimedIntent=intent;return true;
    }
    public JSONObject state(){return RhActions.obj("connected",false,"phase",!error.isEmpty()?"error":cancelled?"cancelling":handoff?"starting":"reconnecting","attemptId",id,
        "workerRunning",workerRunning,"cleanupPending",cleanupPending||workerRunning,"error",error,"recovery",policy.state());}
    public JSONObject perform(String action,JSONObject args){if(!action.equals("stream.disconnect"))throw new IllegalStateException("正在重连，可取消当前连接");cancel();activity.finish();return RhActions.obj("cancelled",true);}
}
