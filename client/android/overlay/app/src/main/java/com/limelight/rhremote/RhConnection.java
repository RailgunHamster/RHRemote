package com.limelight.rhremote;

import org.json.JSONObject;

/** One home-screen connection request; does not own sockets, native code or pairing. */
final class RhConnection {
    String uuid="",name="",phase="",error="",operation="connect";
    long attempt;
    boolean firstFrame,stopping;
    JSONObject window;
    void begin(String uuid,String name){this.uuid=uuid;this.name=name;phase="resolving";error="";firstFrame=false;stopping=false;window=null;attempt++;}
    boolean busy(){return java.util.Arrays.asList("resolving","identity","apps","windows","pairing","starting","first-frame","connected","reconnecting","cancelling","cleaning").contains(phase);}
    String title(){if(operation.equals("windows")){switch(phase){case "windows":return "读取窗口列表";case "failed":return "读取窗口列表失败";case "cancelling":return "正在取消请求";case "cleaning":return "正在清理请求";case "cancelled":return "已取消";default:break;}}switch(phase){
        case "resolving":return "检查连接地址";case "identity":return "确认配对状态";case "apps":return "读取应用列表";case "pairing":return "等待确认配对";
        case "starting":return "建立串流";case "first-frame":return "等待远程画面";case "paired":return "配对完成";case "connected":return "已连接";case "reconnecting":return "正在恢复连接";
        case "cancelling":return operation.equals("pair")?"正在取消配对":"正在断开";case "cleaning":return operation.equals("pair")?"正在清理配对":"正在清理连接";case "failed":return operation.equals("pair")?"配对失败":"连接失败";case "cancelled":return operation.equals("pair")?"已取消配对":"已取消连接";case "ended":return "已断开";default:return "连接";
    }}
    void stage(JSONObject state){if(uuid.isEmpty()||!busy())return;String current=state.optString("phase");if(!state.optString("error").isEmpty())error=state.optString("error");phase=stopping?"cancelling":current.equals("error")?"cleaning":current;}
    void fail(String message){if(uuid.isEmpty()||stopping)return;error=message;phase="failed";}
    void stop(){if(busy()){stopping=true;phase="cancelling";}}
    void finished(boolean handedOff,String failure){
        if(handedOff){if(!stopping)phase=firstFrame?"connected":"first-frame";return;}
        if(operation.equals("pair")&&!stopping&&failure.isEmpty()){phase="paired";error="";return;}
        if(stopping||failure.isEmpty()){phase=firstFrame?"ended":"cancelled";error="";}
        else fail(failure);
    }
    void frame(){if(!uuid.isEmpty()&&!stopping&&busy()){firstFrame=true;phase="connected";error="";}}
    /** Absence of a retired session is not itself a transport error. */
    void observeIdle(){
        if(!busy())return;
        if(stopping||firstFrame&&error.isEmpty())finished(false,"");
        else fail(error.isEmpty()?"连接在收到远程画面前结束":error);
    }
    /** A graceful native termination cannot erase an already reported failure. */
    void gracefulEnd(){if(busy()&&(stopping||error.isEmpty())){phase="ended";error="";}}
    boolean canRetainResult(){return !uuid.isEmpty()&&!stopping&&java.util.Arrays.asList("failed","ended","paired").contains(phase);}
    /** Immutable, in-process result only. It never holds a request, PIN, socket or Activity. */
    static final class Result {
        final String uuid,name,phase,error,operation,window;final long attempt;final boolean firstFrame;
        private Result(RhConnection source){uuid=source.uuid;name=source.name;phase=source.phase;error=source.error;operation=source.operation;attempt=source.attempt;firstFrame=source.firstFrame;window=source.window==null?"":source.window.toString();}
    }
    Result retainResult(){return canRetainResult()?new Result(this):null;}
    boolean restoreResult(Result result){
        if(result==null||result.uuid.isEmpty()||!java.util.Arrays.asList("failed","ended","paired").contains(result.phase))return false;
        JSONObject selected=null;try{if(!result.window.isEmpty()){selected=new JSONObject(result.window);if(!RhWindows.valid(selected.optString("id")))return false;}}catch(Exception invalid){return false;}
        uuid=result.uuid;name=result.name;phase=result.phase;error=result.error;operation=result.operation;attempt=result.attempt;firstFrame=result.firstFrame;stopping=false;window=selected;return true;
    }
    JSONObject state(){return RhActions.obj("uuid",uuid,"name",name,"operation",operation,"attempt",attempt,"phase",phase,"title",title(),"error",error,"firstFrame",firstFrame,"busy",busy(),"window",window==null?JSONObject.NULL:window);}
}
