package com.limelight.rhremote;

import android.content.Context;
import java.util.Map;
import org.json.*;

/** Raw numeric input survives category changes until the user commits the form. */
final class RhSettingsDraft {
    static final class Invalid extends IllegalArgumentException {
        final String key,tab;
        Invalid(String key,String tab,String message){super(message);this.key=key;this.tab=tab;}
    }
    static RhProfile validate(Context context,String uuid,RhProfile profile,Map<String,String> inputs,String relay)throws Exception {
        JSONObject value=profile.json();JSONArray catalog=RhSettings.catalog(context);
        for(int i=0;i<catalog.length();i++){
            JSONObject field=catalog.getJSONObject(i);if(!field.getString("type").equals("number"))continue;
            String key=field.getString("key"),label=field.getString("label"),tab=field.getString("tab");
            String text=inputs.containsKey(key)?inputs.get(key):String.valueOf(value.getInt(key));int number;
            try{number=Integer.parseInt(text.trim());}catch(NumberFormatException invalid){throw new Invalid(key,tab,label+"：请输入整数");}
            int min=field.getInt("min"),max=field.getInt("max");if(number<min||number>max)throw new Invalid(key,tab,label+"：范围为 "+min+"–"+max);
            if((key.equals("width")||key.equals("height"))&&number%2!=0)throw new Invalid(key,tab,label+"必须为偶数");value.put(key,number);
        }
        value.put("relayUrl",relay.trim());
        if((long)value.getInt("width")*value.getInt("height")>64000000)throw new Invalid("width","video","画面总像素不能超过 6400 万");
        try{return RhProfile.fromJson(uuid,value);}catch(Exception invalid){throw new Invalid("relayUrl","network",invalid.getMessage()==null?"连接设置无效":invalid.getMessage());}
    }
}
