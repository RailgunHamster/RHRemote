package com.limelight.rhremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
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
    interface Sleeper {void sleep(long milliseconds)throws InterruptedException;}
    private final long id=IDS.incrementAndGet();
    private final Activity activity;
    private final String uuid;
    private final RhProfile profile;
    private final Stopper stopper;
    private final Resolver resolver;
    private final Executor worker;
    private final Sleeper sleeper;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final RhRecoveryPolicy policy;
    private final JSONObject route;
    private RhRecovery predecessor;
    private RhAdmission initialAdmission;
    private volatile boolean retrying,fatal,handoff,cleanupPending,workerRunning,cancelCleanupDone,completed,gameAdmitted,oldStopped,connectionReady;
    private Stopper successorStopper;
    private RhRecovery successor;
    private boolean successorStopping,terminalPending;
    private Intent claimedIntent;
    private String error="";
    private volatile String initialFailure="";
    private volatile RhLaunchConflict launchConflict=RhLaunchConflict.UNKNOWN;
    private AlertDialog feedbackDialog;
    private TextView feedbackDetail;
    private final Runnable watchdog,handoffTimeout=()->failed("串流窗口未能接管连接");
    private final Runnable networkTimeout=()->failed("网络恢复等待超时，请检查网络后重试");

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
        this(activity,profile,stopper,resolver,worker,Thread::sleep);
    }
    RhRecovery(Activity activity,RhProfile profile,Stopper stopper,Resolver resolver,Executor worker,Sleeper sleeper){
        this.activity=activity;this.uuid=activity.getIntent().getStringExtra(Game.EXTRA_PC_UUID);
        this.profile=profile;this.stopper=stopper;this.resolver=resolver;this.worker=worker;this.sleeper=sleeper;
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
        JSONObject saved=parse(activity.getIntent().getStringExtra(EXTRA));initialFailure=saved.optString("initialFailure");
        long origin=RhStatistics.originUs();if(origin<=0)origin=System.currentTimeMillis()*1000;
        policy=new RhRecoveryPolicy(saved,origin);route=RhNetwork.state();
        watchdog=()->{if(!policy.rendered()&&!cancelled&&!retrying&&!retry(true,"首帧等待超时")){background();failed("首帧等待超时，已停止本轮连接");}};
        // Native establishment retains its own HTTP/RTSP/video deadlines. A Game
        // can exist before negotiation finishes, so it cannot start a decoded
        // frame deadline. Only connectionStarted arms that observation below.
        RhStatistics.event("native_attempt",-1,policy.state());
    }
    /** Native connectionStarted follows PLAY/control setup; duplicate or retired replies do not re-arm it. */
    public synchronized void connected(){
        if(connectionReady||cancelled||completed||retrying||handoff||policy.rendered())return;
        connectionReady=true;
        long timeout=route.optString("route").equals("lan")?15_000:30_000;
        RhStatistics.event("first_frame_wait_begin",-1,RhActions.obj("timeoutMs",timeout,"attemptId",id));
        main.postDelayed(watchdog,timeout);
    }
    private static JSONObject parse(String json){try{return json==null?new JSONObject():new JSONObject(json);}catch(Exception e){return new JSONObject();}}
    private void onMain(Runnable action){if(Looper.myLooper()==Looper.getMainLooper())action.run();else main.post(action);}
    public static RhProfile profile(Activity activity,RhProfile fallback){
        String value=activity.getIntent().getStringExtra(PROFILE);
        try{return value==null?fallback:RhProfile.fromJson(activity.getIntent().getStringExtra(Game.EXTRA_PC_UUID),parse(value));}
        catch(Exception e){throw new IllegalArgumentException("重连配置无效",e);}
    }
    public void frame(){if(!cancelled&&!retrying){boolean first=!policy.rendered();policy.frame(SystemClock.elapsedRealtime());if(first){initialFailure="";launchConflict=RhLaunchConflict.UNKNOWN;main.removeCallbacks(watchdog);RhActions.connectionFrame(activity);}}}
    /** The original NvConnection listener owns this reply; ignore retired startup threads. */
    public synchronized void noteProtocolFailure(int code,String reason){
        if(cancelled||completed||retrying||handoff||policy.rendered())return;
        launchConflict=RhLaunchConflict.from(code,reason);
        RhStatistics.event("connection_http_failed",-1,RhActions.obj("code",code,"hostReason",launchConflict.id));
    }
    private String failureMessage(String stage,int code){return code==409&&launchConflict!=RhLaunchConflict.UNKNOWN?launchConflict.message:stageFailureMessage(stage,code);}
    public boolean stageFailed(int portFlags,int code){return stageFailed("",portFlags,code);}
    public boolean stageFailed(String stage,int portFlags,int code){return retry(RhRecoveryPolicy.networkStage(stage,portFlags,code),failureMessage(stage,code));}
    private static String stageFailureMessage(String stage,int code){
        if(code==409)return "本次连接与主机当前会话状态冲突（409），请稍后重试。";
        String label=code==401||code==403?"主机身份或配对校验失败":stage.equals("RTSP handshake")?"串流协商失败":stage.equals("name resolution")?"主机地址解析失败":stage.equals("video stream establishment")?"视频通道建立失败":stage.equals("audio stream establishment")?"音频通道建立失败":"连接阶段失败";
        return label+"（"+(stage.isEmpty()?"":stage+"，")+"错误码 "+code+"）";
    }
    /** Fatal startup errors return to the device list after scoped cleanup, without a modal connection owner. */
    public synchronized void handleStageFailure(String stage,int portFlags,int code){
        // A conflict delivered by the retired startup thread must not cancel the
        // recovery it already triggered. A successor has its own controller; its
        // first conflict still fails, and authorization errors still stop retry.
        if(retrying&&code==409){RhStatistics.event("connection_stage_callback_ignored",-1,RhActions.obj("name",stage,"portFlags",portFlags,"code",code,"reason","retired_attempt_conflict"));return;}
        RhStatistics.event("connection_stage_failed",-1,RhActions.obj("name",stage,"portFlags",portFlags,"code",code,"hostReason",code==409?launchConflict.id:""));
        if(stageFailed(stage,portFlags,code))return;
        String message=failureMessage(stage,code);
        onMain(()->{RhActions.connectionError("连接失败："+message);background();activity.finish();});
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
                else RhActions.connectionEnded(activity);
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
        if(initialFailure.isEmpty())initialFailure=message;
        retrying=true;cleanupPending=true;RhActions.beginRecovery(this);main.removeCallbacks(watchdog);
        RhStatistics.event("route_media_failed",-1,RhActions.obj("reason",message,"network",route,"recovery",policy.state()));
        main.post(()->{
            if(activity.isFinishing())cancel();
            updateFeedback();
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
            updateFeedback();
            resolveNext(delay);
        }));}catch(RuntimeException|LinkageError e){failed("上一会话清理失败，请重新启动 RHRemote");}
    }
    private void resolveNext(long delay){
        workerRunning=true;
        main.postDelayed(networkTimeout,policy.remainingMs(SystemClock.elapsedRealtime()));
        try{worker.execute(()->{
            try{
                checkpoint();
                try(Watch interrupt=watch(Thread.currentThread()::interrupt)){
                    if(delay>0)Thread.sleep(delay);checkpoint();
                    RhStatistics.finish();RhStatistics.beginAt(profile,policy.originUs);
                    ComputerDetails resolved;int networkRetries=0;
                    while(true){
                        checkpoint();
                        if(policy.remainingMs(SystemClock.elapsedRealtime())<=0)throw new IOException("网络恢复等待超时，请检查网络后重试");
                        try{resolved=resolver.resolve(new Intent(activity.getIntent()),profile,policy.excluded(),this);break;}
                        catch(RhNetwork.Unavailable unavailable){
                            checkpoint();long remaining=policy.remainingMs(SystemClock.elapsedRealtime());
                            if(remaining<=0)throw unavailable;
                            long wait=Math.min(remaining,1000L<<Math.min(networkRetries++,2));
                            RhStatistics.event("recovery_network_retry",-1,RhActions.obj("retry",networkRetries,"waitMs",wait,"remainingMs",remaining,"reason",unavailable.getMessage()));
                            sleeper.sleep(wait);
                        }
                    }
                    checkpoint();
                    if(policy.remainingMs(SystemClock.elapsedRealtime())<=0)throw new IOException("网络恢复等待超时，请检查网络后重试");
                    Intent next=new Intent(activity.getIntent());
                    next.putExtra(Game.EXTRA_HOST,resolved.activeAddress.address);
                    next.putExtra(Game.EXTRA_PORT,resolved.activeAddress.port);
                    next.putExtra(EXTRA,policy.next().put("initialFailure",initialFailure).toString());next.putExtra(PROFILE,profile.json().toString());next.putExtra(HANDOFF,id);
                    main.post(()->launch(next));
                }
            }catch(Exception|LinkageError e){main.post(()->{if(!cancelled)failed(e.getMessage()==null?"自动重连失败":e.getMessage());});}
            finally{main.removeCallbacks(networkTimeout);workerRunning=false;main.post(()->{if(cancelled)release();});}
        });}catch(RuntimeException rejected){main.removeCallbacks(networkTimeout);workerRunning=false;failed("重连任务无法启动");}
    }
    private void launch(Intent next){
        if(cancelled||fatal||activity.isFinishing()||activity.isDestroyed()){cancel();return;}
        handoff=true;main.postDelayed(handoffTimeout,15_000);
        updateFeedback();
        RhStatistics.event("recovery_handoff",-1,RhActions.obj("attemptId",id));
        try{activity.startActivity(next);closeFeedback();activity.finish();}
        catch(RuntimeException|LinkageError e){failed("串流窗口未能启动");}
    }
    private void failed(String message){
        if(completed)return;
        error=initialFailure.isEmpty()||initialFailure.equals(message)?message:"自动恢复未完成："+message+"\n最初失败："+initialFailure;cancel();RhStatistics.finish();
        RhActions.connectionError("连接失败："+error);
        android.widget.Toast.makeText(activity.getApplicationContext(),error,android.widget.Toast.LENGTH_LONG).show();
        closeFeedback();activity.finish();
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
        }else{completed=true;closeFeedback();clearSuccessor();RhActions.endRecovery(this);}
    }
    public void cancel(){
        if(completed)return;
        cancelRequests();main.removeCallbacks(watchdog);main.removeCallbacks(handoffTimeout);main.removeCallbacks(networkTimeout);
        onMain(()->{updateFeedback();stopSuccessor();release();});
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
        closeFeedback();
        RhAdmission entering=initialAdmission;
        if(entering!=null&&RhActions.ownsAdmission(entering)){entering.gameReady(this);entering.cancel();return;}
        initialAdmission=null;
        RhRecovery previous=predecessor;
        if(previous!=null&&RhActions.ownsRecovery(previous)){previous.successorReady(this);previous.cancel();return;}
        predecessor=null;
        if(handoff||completed)return;
        if(RhActions.belongsElsewhere(activity)){completed=true;closeFeedback();main.removeCallbacks(watchdog);return;}
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
        completed=true;cleanupPending=false;closeFeedback();main.removeCallbacks(handoffTimeout);forgetRequests();
        if(claimedIntent!=null)claimedIntent.removeExtra(HANDOFF);claimedIntent=null;clearSuccessor();
        RhActions.endRecovery(this);RhStatistics.event("recovery_attached",-1,RhActions.obj("attemptId",id));
    }
    public static boolean allowGame(Intent intent){
        RhRecovery pending=RhActions.recovery();long requested=intent.getLongExtra(HANDOFF,0);
        if(requested==0)return pending==null;
        if(pending==null||pending.id!=requested||!pending.canLaunch()||pending.uuid==null||!pending.uuid.equals(intent.getStringExtra(Game.EXTRA_PC_UUID)))return false;
        pending.gameAdmitted=true;pending.cleanupPending=true;pending.claimedIntent=intent;return true;
    }
    private String feedbackText(){return cancelled?"正在清理连接":handoff?"正在启动串流":cleanupPending?"正在结束上一会话":"正在检查连接地址";}
    /** The old session panel is disposed during cleanup; this dialog belongs to the recovery lease. */
    private void updateFeedback(){
        if(completed||!retrying||!RhActions.ownsRecovery(this)||activity.isFinishing()||activity.isDestroyed()){closeFeedback();return;}
        if(feedbackDialog==null){
            if(cancelled)return;
            AlertDialog.Builder builder=new AlertDialog.Builder(activity,RhUiStyle.dialogTheme(activity));
            android.content.Context context=builder.getContext();int padding=Math.round(24*context.getResources().getDisplayMetrics().density);
            LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(padding,padding,padding,0);
            feedbackDetail=new TextView(context);feedbackDetail.setTextSize(16);feedbackDetail.setContentDescription("恢复连接阶段");content.addView(feedbackDetail,new LinearLayout.LayoutParams(-1,-2));
            ProgressBar progress=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);LinearLayout.LayoutParams progressLayout=new LinearLayout.LayoutParams(-1,Math.max(1,padding/6));progressLayout.topMargin=padding;content.addView(progress,progressLayout);
            ScrollView scroll=new ScrollView(context);scroll.addView(content);
            AlertDialog dialog=builder.setTitle("正在恢复连接").setView(scroll).setNegativeButton("取消连接",null).setCancelable(false).create();feedbackDialog=dialog;
            dialog.setOnKeyListener((owner,key,event)->{
                if(key!=KeyEvent.KEYCODE_BACK&&key!=KeyEvent.KEYCODE_ESCAPE)return false;
                if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())cancelFromFeedback(dialog);
                return true;
            });
            try{dialog.show();}catch(android.view.WindowManager.BadTokenException unavailable){closeFeedback();RhStatistics.event("recovery_feedback_unavailable",-1,RhActions.obj("attemptId",id));return;}
            RhUiStyle.dialog(dialog,false);
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setContentDescription("取消恢复连接");
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(view->cancelFromFeedback(dialog));
        }
        feedbackDetail.setText(feedbackText());feedbackDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(!cancelled);
    }
    private void cancelFromFeedback(AlertDialog owner){
        if(owner!=feedbackDialog||completed||cancelled||!RhActions.ownsRecovery(this))return;
        RhActions.click("stream.disconnect",RhActions.obj());
    }
    private void closeFeedback(){AlertDialog retired=feedbackDialog;feedbackDialog=null;feedbackDetail=null;if(retired!=null)retired.dismiss();}
    public JSONObject state(){return RhActions.obj("connected",false,"phase",!error.isEmpty()?"error":cancelled?"cancelling":handoff?"starting":"reconnecting","attemptId",id,
        "workerRunning",workerRunning,"cleanupPending",cleanupPending||workerRunning,"error",error,"initialFailure",initialFailure,"recovery",policy.state(),
        "feedback",RhActions.obj("visible",feedbackDialog!=null&&feedbackDialog.isShowing(),"title","正在恢复连接","detail",feedbackText(),"cancelAction","stream.disconnect"));}
    public JSONObject perform(String action,JSONObject args){if(!action.equals("stream.disconnect"))throw new IllegalStateException("正在重连，可取消当前连接");RhActions.connectionStopping(uuid);cancel();closeFeedback();activity.finish();return RhActions.obj("cancelled",true);}
}
