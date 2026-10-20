package com.limelight.rhremote;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Shared field catalog drives both native settings and the control API. */
public final class RhSettings {
    private static JSONArray catalog;
    public static synchronized JSONArray catalog(Context c){
        if(catalog==null)try(InputStream in=c.getAssets().open("rhremote-settings.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>65536)throw new IOException("settings catalog too large");out.write(buffer,0,n);}catalog=new JSONArray(new String(out.toByteArray(),StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException("settings catalog unavailable",e);}return catalog;
    }
    public static boolean supports(JSONObject field,String platform){JSONArray list=field.optJSONArray("platforms");if(list==null)return true;for(int i=0;i<list.length();i++)if(platform.equals(list.optString(i)))return true;return false;}
}
