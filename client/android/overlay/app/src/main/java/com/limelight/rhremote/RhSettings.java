package com.limelight.rhremote;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Shared field catalog drives both native settings and the control API. */
public final class RhSettings {
    private static JSONArray catalog;
    public static synchronized JSONArray catalog(Context c){
        if(catalog!=null)return catalog;
        try{
            JSONArray original=new JSONArray(asset(c,"rhremote-settings.json"));
            for(int i=0;i<original.length();i++)if(original.getJSONObject(i).getString("key").equals("bitrateAuto")){catalog=original;return catalog;}
            JSONObject automatic=new JSONObject(asset(c,"rhremote-auto-bitrate.json"));
            JSONArray merged=new JSONArray();boolean inserted=false;
            for(int i=0;i<original.length();i++){
                JSONObject field=original.getJSONObject(i);
                if(field.getString("key").equals("bitrateMbps")){merged.put(automatic);inserted=true;}
                merged.put(field);
            }
            if(!inserted)merged.put(automatic);catalog=merged;return catalog;
        }catch(Exception e){throw new IllegalStateException("settings catalog unavailable",e);}
    }
    private static String asset(Context c,String name)throws IOException{
        try(InputStream in=c.getAssets().open(name)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>65536)throw new IOException("settings catalog too large");out.write(buffer,0,n);}
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    public static boolean supports(JSONObject field,String platform){JSONArray list=field.optJSONArray("platforms");if(list==null)return true;for(int i=0;i<list.length();i++)if(platform.equals(list.optString(i)))return true;return false;}
}
