package com.limelight.rhremote;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** The same 108 physical keys used by the desktop client. */
final class RhKeyboard {
    private static String document;
    static synchronized JSONArray catalog(Context context)throws Exception{
        if(document==null)try(InputStream input=context.getAssets().open("rhremote-keyboard.json")){
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;
            while((n=input.read(bytes))!=-1){if(output.size()+n>65536)throw new IOException("keyboard catalog too large");output.write(bytes,0,n);}
            document=new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
        return new JSONArray(document);
    }
    static JSONObject find(Context context,String id)throws Exception{
        JSONArray keys=catalog(context);for(int i=0;i<keys.length();i++)if(id.equals(keys.getJSONObject(i).getString("id")))return keys.getJSONObject(i);
        throw new IllegalArgumentException("unknown keyboard key");
    }
    static JSONObject command(JSONObject key,Collection<String> modifiers)throws Exception{
        JSONArray keys=new JSONArray();Set<String> seen=new HashSet<>();
        for(String modifier:modifiers){int code=modifier.equals("shift")?16:modifier.equals("ctrl")?17:modifier.equals("alt")?18:modifier.equals("meta")?91:0;
            if(code==0||!seen.add(modifier))throw new IllegalArgumentException("invalid keyboard modifier");keys.put(code);}
        keys.put(key.getInt("code"));return RhActions.obj("keys",keys,"secureAttention",key.getString("id").equals("delete")&&seen.contains("ctrl")&&seen.contains("alt"));
    }
}
