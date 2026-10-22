package com.limelight.rhremote;
import android.content.Context;
import org.json.*;
import com.limelight.nvstream.jni.MoonBridge;
import java.io.IOException;

public final class RhDisplay {
    interface Request { JSONObject call(String path,JSONObject body)throws Exception; }
    private final Request request;private final RhProfile profile;
    private volatile JSONObject data=RhActions.obj();private volatile String error="";
    public RhDisplay(Context context,String host,byte[] cert,RhProfile profile){this(profile,(path,body)->RhHost.request(context.getApplicationContext(),host,cert,path,body));}
    RhDisplay(RhProfile profile,Request request){this.profile=profile;this.request=request;}
    public JSONObject state(){return RhActions.obj("displays",data.optJSONArray("displays")==null?new JSONArray():data.optJSONArray("displays"),"selected",data.optJSONObject("selected"),"error",error);}
    public void prepare()throws IOException {try(RhStatistics.Scope scope=new RhStatistics.Scope("remote_display_prepare")){select(profile.displayMode,false);}catch(Exception e){error=e.getMessage()==null?"主机屏幕组件不可用":e.getMessage();if(profile.displayMode.equals("virtual"))throw new IOException(error,e);}}
    public JSONObject refresh()throws Exception {try{
        JSONObject previous=data.optJSONObject("selected"),next=request.call("/v1/displays",null).getJSONObject("result");
        JSONArray monitors=next.optJSONArray("displays");
        if(previous!=null&&monitors!=null)for(int i=0;i<monitors.length();i++){JSONObject monitor=monitors.getJSONObject(i);if(monitor.optBoolean("available")&&monitor.optString("id").equals(previous.optString("id"))){next.put("selected",monitor);break;}}
        data=next;error="";return state();
    }catch(Exception e){error=e.getMessage()==null?"主机屏幕组件不可用":e.getMessage();throw e;}}
    public void select(String id,boolean send)throws Exception {
        if(id==null||id.isEmpty()||id.length()>512)throw new IllegalArgumentException("displayId required");
        String mode=id.equals("auto")?"auto":id.equals("virtual")?"virtual":"physical";
        JSONObject result=request.call("/v1/display/prepare",RhActions.obj("mode",mode,"displayId",mode.equals("physical")?id:"","width",profile.width,"height",profile.height)).getJSONObject("result");
        if(send)apply(result.optJSONObject("selected"));data=result;error="";
    }
    public void applyInitial()throws Exception {JSONObject selected=data.optJSONObject("selected");if(selected!=null&&selected.optInt("captureIndex")!=data.optInt("initialCaptureIndex"))apply(selected);}
    public void applyCurrent()throws Exception {apply(data.optJSONObject("selected"));}
    private void apply(JSONObject selected)throws Exception {
        if(selected==null)return;int index=selected.optInt("captureIndex");if(index<1||index>12)throw new IllegalStateException("Sunshine supports monitors 1 through 12");
        RhShortcuts.send(RhActions.obj("keys",new JSONArray().put(17).put(18).put(16).put(111+index)),(key,action,mods)->MoonBridge.sendKeyboardInput(key,action,mods,(byte)0));
        RhStatistics.event("remote_display_select",-1,RhActions.obj("captureIndex",index));
    }
}
