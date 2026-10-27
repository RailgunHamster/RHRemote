package com.limelight.rhremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** One diagnostic operation survives View/Activity recreation without retaining either. */
final class RhDiagnosticWork {
    interface Listener {void changed();}
    private static RhDiagnosticWork shared;
    static synchronized RhDiagnosticWork shared(Context context){
        Context application=context.getApplicationContext();
        if(shared==null||shared.context!=application)shared=new RhDiagnosticWork(application);
        return shared;
    }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean running;
    private long operation;
    private String uuid="",phase="idle",error="",detail="";
    private JSONObject result=new JSONObject();
    private RhDiagnosticWork(Context application){context=application;}
    synchronized boolean busy(){return running;}
    synchronized JSONObject state(){return RhActions.obj("operationId",operation,"uuid",uuid,"busy",running,"phase",phase,"error",error,"detail",detail,"result",copy(result));}
    synchronized void attach(Listener value){listener=value;}
    synchronized void detach(Listener value){if(listener==value)listener=null;}
    private static JSONObject copy(JSONObject source){try{return new JSONObject(source.toString());}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}}
    private void changed(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::changed);return;}
        Listener current;synchronized(this){current=listener;}if(current!=null)current.changed();
    }
    private static String failureMessage(Exception failure,JSONObject attempts){
        boolean mismatch="uuid_mismatch".equals(failure.getMessage());
        org.json.JSONArray rows=attempts.optJSONArray("attempts");if(rows!=null)for(int i=0;i<rows.length();i++)mismatch|="uuid_mismatch".equals(rows.optJSONObject(i).optString("error"));
        if(mismatch)return "设备标识与已保存的设备不符，请检查连接地址";
        if(RhNetwork.unavailable(failure))return "连接地址暂时不可达，请检查网络后重试";
        return failure.getMessage()==null?"诊断连接失败，请重试":failure.getMessage();
    }
    void start(ComputerDetails source,RhProfile profile,JSONObject options,Executor executor)throws Exception{
        // Freeze the submitted device, profile and address draft before the worker is queued.
        ComputerDetails target=new ComputerDetails(source);
        RhProfile capturedProfile=RhProfile.fromJson(target.uuid,profile.json());
        JSONObject captured=copy(RhNetwork.metadata(context,target));
        if(options!=null){if(options.has("address"))captured.put("address",options.getString("address"));if(options.has("addresses"))captured.put("addresses",copy(options).getJSONArray("addresses"));}
        RhNetwork.addresses(captured);
        synchronized(this){if(running)throw new IllegalStateException("连接检查正在进行");running=true;operation++;uuid=target.uuid;phase="running";error="";detail="";result=new JSONObject();}
        changed();
        try {executor.execute(()->{
            JSONObject completed=new JSONObject();String failure="",raw="";
            try {completed=RhNetwork.diagnose(context,target,capturedProfile,captured);}
            catch(Exception invalid){raw=invalid.getMessage()==null?invalid.getClass().getSimpleName():invalid.getMessage();JSONObject attempts=RhNetwork.state();if(target.uuid.equals(attempts.optString("uuid")))completed=attempts;failure=failureMessage(invalid,completed);}
            catch(LinkageError unavailable){failure="诊断组件不可用，请更新应用后重试";raw=unavailable.getClass().getSimpleName();}
            JSONObject snapshot=copy(completed);String message=failure,diagnosticDetail=raw;
            main.post(()->{synchronized(this){running=false;error=message;detail=diagnosticDetail;phase=error.isEmpty()?"completed":"error";result=snapshot;}changed();});
        });} catch(RuntimeException rejected){
            synchronized(this){running=false;phase="error";error="诊断任务未能启动，请重试";detail=rejected.getClass().getSimpleName();}
            changed();throw rejected;
        }
    }
}
