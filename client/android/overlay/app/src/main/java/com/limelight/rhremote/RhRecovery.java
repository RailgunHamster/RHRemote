package com.limelight.rhremote;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.function.Consumer;

/** Waits for native cleanup before resolving a new route and recreating Game. */
public final class RhRecovery implements RhActions.Target {
    static final String EXTRA="rhremote.recovery",PROFILE="rhremote.sessionProfile";
    // A false callback reports a timeout. Only a later true callback proves that
    // native and HTTP startup threads have both finished; the reservation stays held.
    public interface Stopper { void stop(Consumer<Boolean> completed); }
    interface Resolver { ComputerDetails resolve(Intent intent,RhProfile profile,JSONArray failed)throws Exception; }
    private final Activity activity;
    private final RhProfile profile;
    private final Stopper stopper;
    private final Resolver resolver;
    private final Executor worker;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final RhRecoveryPolicy policy;
    private final JSONObject route;
    private volatile boolean cancelled,retrying,fatal,handoff,cleanupPending,workerRunning,cancelCleanupDone;
    private String error="";
    private final Runnable watchdog;

    public RhRecovery(Activity activity,RhProfile profile,Stopper stopper){
        this(activity,profile,stopper,(intent,p,failed)->{
            ComputerDetails source=new ComputerDetails();
            source.uuid=intent.getStringExtra(Game.EXTRA_PC_UUID);source.name=intent.getStringExtra(Game.EXTRA_PC_NAME);
            source.httpsPort=intent.getIntExtra(Game.EXTRA_HTTPS_PORT,47984);
            source.manualAddress=new ComputerDetails.AddressTuple(intent.getStringExtra(Game.EXTRA_HOST),intent.getIntExtra(Game.EXTRA_PORT,47989));
            source.activeAddress=source.manualAddress;
            byte[] cert=intent.getByteArrayExtra(Game.EXTRA_SERVER_CERT);
            if(cert==null)throw new IOException("缺少配对证书，请重新配对");
            source.serverCert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
            RhTunnel.stop();
            return RhNetwork.resolve(activity.getApplicationContext(),source,p,RhActions.obj("failedRoutes",failed));
        },task->new Thread(task,"RHRemote reconnect").start());
    }
    RhRecovery(Activity activity,RhProfile profile,Stopper stopper,Resolver resolver,Executor worker){
        this.activity=activity;this.profile=profile;this.stopper=stopper;this.resolver=resolver;this.worker=worker;
        JSONObject saved=parse(activity.getIntent().getStringExtra(EXTRA));
        long origin=RhStatistics.originUs();if(origin<=0)origin=System.currentTimeMillis()*1000;
        policy=new RhRecoveryPolicy(saved,origin);route=RhNetwork.state();
        watchdog=()->{if(!policy.rendered()&&!cancelled&&!retrying&&!retry(true,"首帧等待超时")){background();failed("首帧等待超时，已停止本轮连接");}};
        main.postDelayed(watchdog,15_000);
        RhStatistics.event("native_attempt",-1,policy.state());
    }
    private static JSONObject parse(String json){try{return json==null?new JSONObject():new JSONObject(json);}catch(Exception e){return new JSONObject();}}
    public static RhProfile profile(Activity activity,RhProfile fallback){
        String value=activity.getIntent().getStringExtra(PROFILE);
        try{return value==null?fallback:RhProfile.fromJson(activity.getIntent().getStringExtra(Game.EXTRA_PC_UUID),parse(value));}
        catch(Exception e){throw new IllegalArgumentException("重连配置无效",e);}
    }
    public void frame(){if(!cancelled&&!retrying){boolean first=!policy.rendered();policy.frame(SystemClock.elapsedRealtime());if(first)main.removeCallbacks(watchdog);}}
    public boolean stageFailed(int portFlags,int code){return stageFailed("",portFlags,code);}
    public boolean stageFailed(String stage,int portFlags,int code){return retry(RhRecoveryPolicy.networkStage(stage,portFlags,code),"连接阶段失败 ("+code+")");}
    public boolean terminated(int code){
        if(code==MoonBridge.ML_ERROR_GRACEFUL_TERMINATION)return retrying;
        boolean eligible=policy.rendered()||code==MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC||code==MoonBridge.ML_ERROR_NO_VIDEO_FRAME||code==MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION;
        if(code==MoonBridge.ML_ERROR_PROTECTED_CONTENT||code==MoonBridge.ML_ERROR_FRAME_CONVERSION)eligible=false;
        return retry(eligible,"串流中断 ("+code+")");
    }
    private synchronized boolean retry(boolean eligible,String message){
        if(cancelled)return retrying;
        if(!eligible){fatal=true;cancelled=true;main.removeCallbacks(watchdog);if(retrying)main.post(()->failed(message));return false;}
        if(retrying)return true;
        String remote=route.optString("remoteAddress",route.optString("selectedAddress"));
        long delay=policy.failure(SystemClock.elapsedRealtime(),System.currentTimeMillis()*1000,route.optString("route"),remote,true);
        if(delay<0){cancelled=true;main.removeCallbacks(watchdog);return false;}
        retrying=true;cleanupPending=true;RhActions.beginRecovery(this);main.removeCallbacks(watchdog);
        RhStatistics.event("route_media_failed",-1,RhActions.obj("reason",message,"network",route,"recovery",policy.state()));
        main.post(()->{
            if(activity.isFinishing())cancelled=true;
            stopper.stop(cleaned->{
                cleanupPending=!cleaned;
                if(cancelled){RhStatistics.finish();release();return;}
                if(!cleaned){failed("上一会话清理超时，已停止自动重连");return;}
                workerRunning=true;
                worker.execute(()->{
                    try{
                        if(delay>0)Thread.sleep(delay);
                        if(cancelled)return;
                        RhStatistics.finish();RhStatistics.beginAt(profile,policy.originUs);
                        ComputerDetails resolved=resolver.resolve(new Intent(activity.getIntent()),profile,policy.excluded());
                        if(cancelled)return;
                        Intent next=new Intent(activity.getIntent());
                        next.putExtra(Game.EXTRA_HOST,resolved.activeAddress.address);
                        next.putExtra(Game.EXTRA_PORT,resolved.activeAddress.port);
                        next.putExtra(EXTRA,policy.next().toString());next.putExtra(PROFILE,profile.json().toString());
                        main.post(()->{
                            if(cancelled||fatal||activity.isFinishing())return;
                            handoff=true;RhActions.endRecovery(this);
                            activity.startActivity(next);activity.finish();
                        });
                    }catch(Exception|LinkageError e){main.post(()->{if(!cancelled)failed(e.getMessage()==null?"自动重连失败":e.getMessage());});}
                    finally{workerRunning=false;main.post(()->{if(cancelled)release();});}
                });
            });
        });
        return true;
    }
    private void failed(String message){
        cancelled=true;error=message;main.removeCallbacks(watchdog);RhStatistics.finish();release();
        if(!activity.isFinishing())new AlertDialog.Builder(activity).setTitle("连接失败").setMessage(message).setPositiveButton("确定",(dialog,which)->activity.finish()).setOnCancelListener(dialog->activity.finish()).show();
    }
    private void release(){
        if(cleanupPending||workerRunning||!RhActions.ownsRecovery(this))return;
        if(cancelled&&!cancelCleanupDone){
            workerRunning=true;
            worker.execute(()->{
                try{RhTunnel.stop();RhStatistics.finish();cancelCleanupDone=true;}
                catch(Exception|LinkageError e){main.post(()->error="通道清理失败，请重新启动 RHRemote");}
                finally{workerRunning=false;if(cancelCleanupDone)main.post(this::release);}
            });
        }else RhActions.endRecovery(this);
    }
    public void cancel(){cancelled=true;main.removeCallbacks(watchdog);release();}
    public void background(){
        if(handoff)return;
        cancel();
        if(!retrying){
            retrying=true;cleanupPending=true;RhActions.beginRecovery(this);
            stopper.stop(cleaned->{cleanupPending=!cleaned;if(cleaned){RhStatistics.finish();release();}});
        }
    }
    public JSONObject state(){return RhActions.obj("connected",false,"phase",!error.isEmpty()?"error":cancelled?"cancelling":"reconnecting","error",error,"recovery",policy.state());}
    public JSONObject perform(String id,JSONObject args){if(!id.equals("stream.disconnect"))throw new IllegalStateException("正在重连，可取消当前连接");cancel();activity.finish();return RhActions.obj("cancelled",true);}
}
