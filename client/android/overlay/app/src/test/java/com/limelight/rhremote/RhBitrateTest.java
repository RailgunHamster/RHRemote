package com.limelight.rhremote;

import com.limelight.preferences.PreferenceConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,shadows=RhRecoveryTest.NoNativeMoonBridge.class)
public class RhBitrateTest {
    @Test public void sharedStartingRateCasesReachEffectiveStreamingPreferences()throws Exception{
        String source;try(InputStream in=getClass().getResourceAsStream("/rhremote-bitrate-cases.json")){
            assertNotNull(in);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;
            while((n=in.read(bytes))!=-1)out.write(bytes,0,n);source=new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
        java.lang.reflect.Field last=RhNetwork.class.getDeclaredField("last");last.setAccessible(true);Object old=last.get(null);
        try{
            JSONArray cases=new JSONArray(source);
            for(int i=0;i<cases.length();i++){
                JSONObject c=cases.getJSONObject(i);
                JSONObject value=RhActions.obj("width",c.optInt("width",1920),"height",c.optInt("height",1080),"fps",30,"bitrateMbps",c.optInt("manual",25),"bitrateAuto",c.optBoolean("auto",true),"remoteBitrateMbps",c.optInt("remoteCap",0),"codec","HEVC","swapButtons",false,"trackpad",false,"statistics",false);
                RhProfile profile=RhProfile.fromJson("host",value);String before=profile.json().toString();
                assertEquals(c.getString("name"),c.getInt("expected"),profile.initialBitrateMbps(c.getString("address"),c.optString("route",null)));
                JSONObject route=RhActions.obj("uuid","host","selectedAddress",c.getString("address"));if(c.has("route"))route.put("route",c.getString("route"));last.set(null,route);
                PreferenceConfiguration effective=new PreferenceConfiguration();profile.applyNetwork(c.getString("address"),effective);
                assertEquals(c.getString("name"),c.getInt("expected")*1000,effective.bitrate);assertEquals(before,profile.json().toString());
            }
            RhProfile p=RhProfile.fromJson("other-host",RhActions.obj("width",1920,"height",1080,"fps",30,"bitrateMbps",25,"bitrateAuto",true,"codec","HEVC","swapButtons",false,"trackpad",false,"statistics",false));
            last.set(null,RhActions.obj("uuid","host","route","lan"));PreferenceConfiguration config=new PreferenceConfiguration();p.applyNetwork("203.0.113.8",config);assertEquals(6000,config.bitrate);
        }finally{last.set(null,old);}
    }
    @Test public void manualSchemaRemainsCompatibleAndAutoIsStrictAndPortable()throws Exception{
        RhProfile manual=RhProfile.fromJson("host",RhActions.obj("width",1920,"height",1080,"fps",30,"bitrateMbps",41,"codec","HEVC","swapButtons",false,"trackpad",false,"statistics",false));
        assertFalse(manual.bitrateAuto);assertFalse(manual.clientJson().has("bitrateAuto"));assertFalse(RhProfile.fromClientJson("host",manual.clientJson()).bitrateAuto);
        JSONObject value=manual.clientJson().put("bitrateAuto",true);RhProfile auto=RhProfile.fromClientJson("host",value);assertTrue(auto.bitrateAuto);assertEquals(41,auto.bitrateMbps);assertTrue(auto.clientJson().getBoolean("bitrateAuto"));
        for(Object invalid:new Object[]{"true",1,JSONObject.NULL})try{RhProfile.fromClientJson("host",new JSONObject(value.toString()).put("bitrateAuto",invalid));fail("Accepted invalid boolean");}catch(IllegalArgumentException expected){}
    }
}
