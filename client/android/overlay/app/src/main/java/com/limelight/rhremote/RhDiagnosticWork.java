package com.limelight.rhremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Owns a diagnostic, its cancellable requests and transport cleanup across page recreation. */
final class RhDiagnosticWork {
    interface Listener {void changed();}
    private static RhDiagnosticWork shared;
    private static final Executor ABORT=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"RHRemote diagnostic cancel");thread.setDaemon(true);return thread;});
    static synchronized RhDiagnosticWork shared(Context context){Context application=context.getApplicationContext();if(shared==null||shared.context!=application)shared=new RhDiagnosticWork(application);return shared;}
    private static final class Flight extends RhCancellation implements RhProbeWork.Owner {
        final long id;final Executor executor;JSONObject result=new JSONObject();String error="",detail="";boolean cleanupRunning;
        final RhProbeWork probes=new RhProbeWork();
        public RhProbeWork probes(){return probes;}
        Flight(long id,Executor executor){this.id=id;this.executor=executor;}
        void cancel(){cancelled=true;ABORT.execute(this::cancelRequests);}
        boolean cancelled(){return cancelled;}
        void finished(){forgetRequests();}
    }
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
    private Listener listener;private boolean running;private long operation;private Flight flight;
    private String uuid="",phase="idle",error="",detail="",requestIntent="",mode="diagnose";private JSONObject result=new JSONObject();
    private final java.util.Map<String,JSONObject> checks=new java.util.LinkedHashMap<>();
    private RhDiagnosticWork(Context application){context=application;}
    synchronized boolean busy(){return running;}
    synchronized JSONObject state(){boolean cancelled=flight!=null&&flight.cancelled();return RhActions.obj("operationId",operation,"uuid",uuid,"mode",mode,"busy",running,"phase",phase,"error",error,"detail",detail,"pendingProbes",flight==null?0:flight.probes.pending(),"result",copy(result),"requestIntent",requestIntent,"cancelRequested",running&&cancelled,"canCancel",running&&(!cancelled||phase.equals("cleanup-error")),"cancelAction",phase.equals("cleanup-error")?"cleanup":"cancel");}
    synchronized JSONObject checked(String target,String intent){JSONObject saved=checks.get(target);if(saved==null)return new JSONObject();return intent.equals(saved.optString("requestIntent"))?copy(saved):RhNetworkObservation.expired(saved,saved.optString("requestIntent"),intent);}
    synchronized boolean hasCheck(String target){return checks.containsKey(target);}
    synchronized void clearCheck(String target){checks.remove(target);}
    /** Retire older check evidence only after a newer authentication in the current scope. */
    synchronized boolean observed(ComputerDetails target,JSONObject shared){
        JSONObject saved=checks.get(target.uuid);if(saved==null)return false;
        long sequence=RhDiscoveryObservation.sequence(target);
        if(sequence>saved.optLong("observationId")&&RhDiscoveryObservation.authenticates(context,shared,target)){checks.remove(target.uuid);return true;}
        return observed(target.uuid,sequence);
    }
    /** Background discovery is a different, narrower reachability observation. */
    synchronized boolean observed(String target){return observed(target,0);}
    synchronized boolean observed(String target,long sequence){JSONObject saved=checks.get(target);if(saved==null||!saved.optBoolean("reachabilityCurrent",true)||sequence>0&&saved.optLong("observationId")>sequence)return false;try{saved.put("reachabilityCurrent",false);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return true;}
    synchronized void attach(Listener value){listener=value;}
    synchronized void detach(Listener value){if(listener==value)listener=null;}
    private static JSONObject copy(JSONObject source){try{return new JSONObject(source.toString());}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}}
    static JSONObject capture(Context context,ComputerDetails target,JSONObject options)throws Exception{JSONObject value=copy(RhNetwork.metadata(context,target));if(options!=null){if(options.has("address"))value.put("address",options.getString("address"));if(options.has("addresses"))value.put("addresses",copy(options).getJSONArray("addresses"));}if(!value.has("addresses"))value.put("addresses",new org.json.JSONArray());return value;}
    static String intent(String uuid,RhProfile profile,JSONObject captured){return new org.json.JSONArray().put(uuid).put(captured.optString("address")).put(captured.optJSONArray("addresses")).put(profile.networkMode).put(new org.json.JSONArray(profile.routeOrder)).put(profile.relayUrl).toString();}
    private void changed(){if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::changed);return;}Listener current;synchronized(this){current=listener;}if(current!=null)current.changed();}
    private static String failureMessage(Exception failure,JSONObject attempts){
        boolean mismatch="uuid_mismatch".equals(failure.getMessage());org.json.JSONArray rows=attempts.optJSONArray("attempts");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null)mismatch|="uuid_mismatch".equals(row.optString("error"));}
        if(mismatch)return "设备标识与已保存的设备不符，请检查连接地址";if(RhNetwork.unavailable(failure))return "连接地址暂时不可达，请检查网络后重试";return failure.getMessage()==null?"诊断连接失败，请重试":failure.getMessage();
    }
    private static JSONObject routeFailure(ComputerDetails target,String outcome,long began,JSONObject attempts){
        JSONObject report=RhDeviceCheck.report(target.uuid,"","none",target.serverCert!=null,outcome,began);
        try{report.put("phase","route_selection");report.put("network",attempts);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return report;
    }
    void start(ComputerDetails source,RhProfile profile,JSONObject options,Executor executor)throws Exception{
        start(source,profile,options,executor,false);
    }
    void startCheck(ComputerDetails source,RhProfile profile,Executor executor)throws Exception{
        start(source,profile,null,executor,true);
    }
    private void start(ComputerDetails source,RhProfile profile,JSONObject options,Executor executor,boolean check)throws Exception{
        ComputerDetails target=new ComputerDetails(source);RhProfile capturedProfile=RhProfile.fromJson(target.uuid,profile.json());JSONObject captured=capture(context,target,options);RhNetwork.addresses(captured);
        if(check)captured.put("readOnlyDeviceCheck",true);
        final String capturedIntent=check?RhDeviceCheck.intent(target,capturedProfile,captured):intent(target.uuid,capturedProfile,captured);
        final Flight current;synchronized(this){if(running)throw new IllegalStateException("连接检查正在进行");long next=Math.addExact(operation,1);RhActions.beginInspection(this);running=true;operation=next;current=flight=new Flight(next,executor);uuid=target.uuid;mode=check?"check":"diagnose";phase="running";error="";detail="";result=new JSONObject();requestIntent=capturedIntent;}
        changed();
        try{executor.execute(()->{
            long began=System.nanoTime();
            try{current.checkpoint();
                if(check){ComputerDetails resolved=RhNetwork.resolve(context,target,capturedProfile,captured,current);current.result=copy(RhDeviceCheck.inspect(context,resolved,current));current.result.put("network",RhNetwork.state());}
                else current.result=copy(RhNetwork.diagnose(context,target,capturedProfile,captured,current));
                current.checkpoint();}
            catch(Exception invalid){current.detail=invalid.getMessage()==null?invalid.getClass().getSimpleName():invalid.getMessage();JSONObject attempts=RhNetwork.state();if(target.uuid.equals(attempts.optString("uuid")))current.result=copy(attempts);current.error=failureMessage(invalid,current.result);
                if(check){String outcome=RhNetwork.unavailable(invalid)?"unreachable":"unavailable";org.json.JSONArray rows=current.result.optJSONArray("attempts");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optString("failureKind").equals("identity"))outcome="identity_mismatch";}
                    current.result=routeFailure(target,outcome,began,current.result);}
            }
            catch(LinkageError unavailable){current.error="诊断组件不可用，请更新应用后重试";current.detail=unavailable.getClass().getSimpleName();if(check)current.result=routeFailure(target,"unavailable",began,new JSONObject());}
            cleanup(current);
        });}catch(RuntimeException rejected){synchronized(this){running=false;phase="error";error="诊断任务未能启动，请重试";detail=rejected.getClass().getSimpleName();RhActions.endInspection(this);}changed();throw rejected;}
    }
    void cancel(long expected){
        cancel(expected,null);
    }
    void cancel(long expected,String expectedAction){
        final Flight current;boolean retry;
        synchronized(this){retry=phase.equals("cleanup-error");if(expected!=operation||expectedAction!=null&&!expectedAction.equals(retry?"cleanup":"cancel"))throw new IllegalStateException("诊断任务已变化，未执行取消");if(!running)throw new IllegalStateException("当前没有可取消的诊断");current=flight;if(retry){phase="cleaning";error="";}else if(!current.cancelled()){current.cancel();phase="cancelling";}}
        changed();
        if(retry)try{current.executor.execute(()->cleanup(current));}catch(RuntimeException rejected){synchronized(this){phase="cleanup-error";error="通道清理任务无法启动，请重试";}changed();}
    }
    private void cleanup(Flight current){
        synchronized(this){if(flight!=current||current.cleanupRunning)return;current.cleanupRunning=true;if(!current.cancelled())phase="cleaning";}changed();
        if(current.probes.pending()>0){
            synchronized(this){current.cleanupRunning=false;phase="waiting-probes";}changed();
            current.probes.whenIdle(()->resumeCleanup(current));return;
        }
        String failure="";try{if(!RhActions.ownsInspection(this))throw new IllegalStateException("Diagnostic transport owner changed");RhTunnel.stop();}catch(Exception|LinkageError invalid){failure=invalid.getClass().getSimpleName();}
        final String cleanupFailure=failure;current.finished();
        main.post(()->{synchronized(this){if(flight!=current)return;current.cleanupRunning=false;
            if(!cleanupFailure.isEmpty()){phase="cleanup-error";error="诊断通道清理失败，请重试清理";detail=cleanupFailure;}
            else{running=false;result=copy(current.result);detail=current.detail;error=current.cancelled()?"":current.error;phase=current.cancelled()?"cancelled":error.isEmpty()?"completed":"error";
                if(mode.equals("check")&&!current.cancelled()&&result.has("outcome")){JSONObject saved=copy(result);try{saved.put("operationId",operation);saved.put("requestIntent",requestIntent);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}checks.put(uuid,saved);}
                RhActions.endInspection(this);}}
            changed();});
    }
    private void resumeCleanup(Flight current){
        try{current.executor.execute(()->cleanup(current));}
        catch(RuntimeException rejected){synchronized(this){if(flight!=current||!running)return;phase="cleanup-error";error="通道清理任务无法启动，请重试";}changed();}
    }
}
