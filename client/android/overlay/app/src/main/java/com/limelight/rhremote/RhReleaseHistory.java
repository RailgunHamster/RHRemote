package com.limelight.rhremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Process-owned lazy history. Never owns an Activity or an update install. */
final class RhReleaseHistory {
    private static final int LIMIT=2*1024*1024;
    private static final java.util.concurrent.Executor WORK=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RHRemote release history");t.setDaemon(true);return t;});
    private final Context context;private final Runnable changed;private final Handler main=new Handler(Looper.getMainLooper());
    private JSONArray items=new JSONArray();private final Set<String> opened=new LinkedHashSet<>();private boolean expanded,busy,loaded;private long generation;private String error="",provider="";
    interface Connections{HttpURLConnection open(String url)throws IOException;}
    Connections connections;
    RhReleaseHistory(Context c,Runnable changed){context=c.getApplicationContext();this.changed=changed;connections=url->{HttpURLConnection value=RhProxy.open(context,new URL(url),"updates");value.setConnectTimeout(7000);value.setReadTimeout(10000);return value;};try(InputStream in=context.getAssets().open("rhremote-release-history.json")){items=parse(read(in));}catch(Exception ignored){}}
    static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>LIMIT)throw new IOException("历史记录过大");out.write(buffer,0,n);}return out.toByteArray();}
    private static long[] version(String value){if(!value.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))throw new IllegalArgumentException("历史版本无效");String[] parts=value.split("\\.");return new long[]{Long.parseLong(parts[0]),Long.parseLong(parts[1]),Long.parseLong(parts[2])};}
    static JSONArray parse(byte[] bytes)throws Exception{
        if(bytes.length>LIMIT)throw new IOException("历史记录过大");JSONObject feed=new JSONObject(new String(bytes,StandardCharsets.UTF_8));JSONArray values=feed.getJSONArray("releases");if(feed.getInt("schemaVersion")!=1||values.length()>128)throw new IOException("历史记录格式不支持");
        Set<String> seen=new HashSet<>();List<JSONObject> ordered=new ArrayList<>();for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);String v=value.getString("version");version(v);if(!seen.add(v)||value.getString("notes").getBytes(StandardCharsets.UTF_8).length>65536||value.getString("publishedAt").length()>80)throw new IOException("历史记录条目无效");ordered.add(value);}
        ordered.sort((a,b)->{long[] x=version(a.optString("version")),y=version(b.optString("version"));for(int i=0;i<3;i++){int order=Long.compare(y[i],x[i]);if(order!=0)return order;}return 0;});return new JSONArray(ordered);
    }
    synchronized JSONObject state(){try{return RhActions.obj("expanded",expanded,"busy",busy,"loaded",loaded,"items",expanded?new JSONArray(items.toString()):new JSONArray(),"expandedVersions",new JSONArray(opened),"error",error,"provider",provider);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    void execute(String action,JSONObject args){boolean refresh=false;synchronized(this){
        if(action.equals("updates.history.toggle")){expanded=!expanded;refresh=expanded&&!loaded&&!busy;}
        else if(action.equals("updates.history.entry.toggle")){if(!expanded)throw new IllegalStateException("请先展开历史更新记录");String v=args.optString("version");boolean found=false;for(int i=0;i<items.length();i++)if(items.optJSONObject(i).optString("version").equals(v))found=true;if(!found)throw new IllegalArgumentException("历史版本不存在");if(!opened.remove(v))opened.add(v);}
        else if(action.equals("updates.history.refresh")){if(!expanded)throw new IllegalStateException("请先展开历史更新记录");if(busy)throw new IllegalStateException("历史记录正在加载");refresh=true;}
        else throw new IllegalArgumentException("unknown history action");
    }if(refresh)refresh();notifyChanged();}
    void sourceChanged(){boolean refresh;synchronized(this){generation++;loaded=false;busy=false;provider="";refresh=expanded;}if(refresh)refresh();notifyChanged();}
    private void notifyChanged(){main.post(changed);}
    private void refresh(){final long serial;final String preferred;synchronized(this){if(busy)return;busy=true;error="";serial=++generation;preferred=context.getSharedPreferences("rhremote.update-source",0).getString("preferredSource","cnb");}
        WORK.execute(()->{synchronized(this){if(generation!=serial)return;}JSONArray result=null;String selected="";for(String source:preferred.equals("github")?new String[]{"github","cnb"}:new String[]{"cnb","github"}){synchronized(this){if(generation!=serial)return;}
            HttpURLConnection connection=null;try{String base=source.equals("github")?"https://github.com/RailgunHamster/RHRemote/releases/":"https://cnb.cool/railgunhamster/RHRemote/-/releases/";connection=connections.open(base+"latest/download/releases.history.json");if(connection.getResponseCode()!=200)throw new IOException("历史记录请求失败");try(InputStream in=connection.getInputStream()){result=parse(read(in));}selected=source;break;}catch(Exception unavailable){}finally{if(connection!=null)connection.disconnect();}}
            synchronized(this){if(generation!=serial)return;busy=false;if(result==null){error="历史更新记录暂时无法刷新；显示已保存的记录。";}else{items=result;provider=selected;loaded=true;error="";}}notifyChanged();
        });
    }
}
