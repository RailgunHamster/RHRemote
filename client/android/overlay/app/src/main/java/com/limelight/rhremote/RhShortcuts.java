package com.limelight.rhremote;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.limelight.nvstream.input.KeyboardPacket;

public final class RhShortcuts {
    public interface Sender { void send(short key,byte action,byte modifiers); }
    public static JSONArray catalog(Context context)throws Exception {
        try(InputStream in=context.getAssets().open("rhremote-shortcuts.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>32768)throw new IOException("shortcut catalog too large");out.write(b,0,n);}return new JSONArray(new String(out.toByteArray(),StandardCharsets.UTF_8));}
    }
    public static JSONObject find(Context context,String id)throws Exception {JSONArray list=catalog(context);for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id")))return list.getJSONObject(i);throw new IllegalArgumentException("unknown remote shortcut");}
    private static byte flag(int key){return key==16?KeyboardPacket.MODIFIER_SHIFT:key==17?KeyboardPacket.MODIFIER_CTRL:key==18?KeyboardPacket.MODIFIER_ALT:key==91?KeyboardPacket.MODIFIER_META:0;}
    public static void send(JSONObject shortcut,Sender sender)throws Exception {
        if(shortcut.optBoolean("secureAttention"))throw new IllegalArgumentException("secure attention requires the host service");
        JSONArray keys=shortcut.getJSONArray("keys");int attempted=0;byte modifiers=0;
        Exception failure=null;
        try {for(int i=0;i<keys.length();i++){int key=keys.getInt(i);modifiers|=flag(key);attempted++;sender.send((short)(0x8000|key),KeyboardPacket.KEY_DOWN,modifiers);}}
        catch(Exception e){failure=e;}
        finally {for(int i=attempted-1;i>=0;i--){int key=keys.getInt(i);modifiers&=~flag(key);try{sender.send((short)(0x8000|key),KeyboardPacket.KEY_UP,modifiers);}catch(Exception e){if(failure==null)failure=e;}}}
        if(failure!=null)throw failure;
    }
}
