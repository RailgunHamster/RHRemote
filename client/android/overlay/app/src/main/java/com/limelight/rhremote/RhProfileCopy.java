package com.limelight.rhremote;

import android.content.Context;
import org.json.*;

/** A detached snapshot: inspecting another client's preferences never edits the current draft. */
final class RhProfileCopy {
    static JSONObject preview(Context context,String uuid,String target,String client)throws JSONException {
        if(client.equals(RhShared.clientId(context)))throw new IllegalArgumentException("请选择其他客户端");
        JSONObject shared=RhShared.read(context),group=shared.getJSONObject("profiles").optJSONObject(client);
        if(group==null||!group.has(uuid))throw new IllegalArgumentException("该客户端没有此设备的配置");
        RhProfile profile=RhProfile.fromClientJson(uuid,group.getJSONObject(uuid));
        JSONObject info=shared.getJSONObject("clients").optJSONObject(client);
        String name=info==null?client:info.optString("name",client);if(name.isEmpty())name=client;
        String suffix=client.substring(client.offsetByCodePoints(client.length(),-Math.min(4,client.codePointCount(0,client.length()))));
        JSONArray fields=new JSONArray(),catalog=RhSettings.catalog(context);JSONObject values=profile.json();
        for(int i=0;i<catalog.length();i++){
            JSONObject meta=catalog.getJSONObject(i);String key=meta.getString("key");
            if(key.equals("swapButtons")||!RhSettings.supports(meta,"android"))continue;
            Object value=values.get(key);String display=value instanceof Boolean?((Boolean)value?"开启":"关闭"):String.valueOf(value);
            JSONArray options=meta.optJSONArray("options");if(options!=null)for(int j=0;j<options.length();j++){JSONArray option=options.getJSONArray(j);if(option.get(0).equals(value))display=option.getString(1);}
            String tab=meta.getString("tab"),category=tab.equals("video")?"画面":tab.equals("audio")?"声音":tab.equals("input")?"输入":tab.equals("network")?"网络":"高级";
            fields.put(RhActions.obj("label",category+" · "+meta.getString("label"),"value",display));
        }
        java.util.List<String> routes=new java.util.ArrayList<>();for(String route:profile.routeOrder)routes.add(RhProfile.ROUTE_LABELS[java.util.Arrays.asList(RhProfile.ROUTES).indexOf(route)]);
        fields.put(RhActions.obj("label","网络 · 连接顺序","value",android.text.TextUtils.join(" → ",routes)));
        fields.put(RhActions.obj("label","网络 · 中继服务器","value",profile.relayUrl));
        return RhActions.obj("clientId",client,"sourceName",name+" · "+suffix,"targetUuid",uuid,"targetName",target,"profile",values,"fields",fields);
    }
    private RhProfileCopy(){}
}
