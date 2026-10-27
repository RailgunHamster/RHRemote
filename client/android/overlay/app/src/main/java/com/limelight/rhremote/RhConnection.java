package com.limelight.rhremote;

import org.json.JSONObject;

/** One home-screen connection request; does not own sockets, native code or pairing. */
final class RhConnection {
    String uuid="",name="",phase="",error="",operation="connect";
    long attempt;
    boolean firstFrame,stopping;
    void begin(String uuid,String name){this.uuid=uuid;this.name=name;phase="resolving";error="";firstFrame=false;stopping=false;attempt++;}
    boolean busy(){return java.util.Arrays.asList("resolving","identity","apps","pairing","starting","first-frame","connected","reconnecting","cancelling","cleaning").contains(phase);}
    String title(){switch(phase){
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
    JSONObject state(){return RhActions.obj("uuid",uuid,"name",name,"operation",operation,"attempt",attempt,"phase",phase,"title",title(),"error",error,"firstFrame",firstFrame,"busy",busy());}
}
