package com.limelight.rhremote;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class RhRoutePolicyTest {
    private JSONObject fixture()throws Exception{return new JSONObject("{\"width\":1920,\"height\":1080,\"fps\":30,\"bitrateMbps\":25,\"codec\":\"HEVC\",\"swapButtons\":false,\"trackpad\":false,\"statistics\":false}");}
    @Test public void customOrderAndDisabledRoutesSurviveSerialization()throws Exception{
        JSONObject input=fixture().put("routeOrder",new JSONArray().put("relay").put("lan")).put("relayUrl","https://relay.example.com");
        RhProfile profile=RhProfile.fromJson("host",input);assertEquals("relay",profile.routeOrder.get(0));assertFalse(profile.routeOrder.contains("public_direct"));
        RhProfile restored=RhProfile.fromJson("host",profile.json());assertEquals(profile.routeOrder,restored.routeOrder);assertEquals(profile.relayUrl,restored.relayUrl);
    }
    @Test public void invalidPolicyCannotDisableEverythingOrEmbedCredentials()throws Exception{
        try{RhProfile.fromJson("host",fixture().put("routeOrder",new JSONArray()));fail();}catch(IllegalArgumentException expected){}
        try{RhProfile.fromJson("host",fixture().put("routeOrder",new JSONArray().put("lan").put("lan")));fail();}catch(IllegalArgumentException expected){}
        try{RhProfile.fromJson("host",fixture().put("relayUrl","https://user:password@relay.example.com"));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void tunnelAlwaysUsesConservativePacketsWithoutLoadingJni()throws Exception{
        String address="rhr:"+new String(new char[64]).replace('\0','a');RhNetwork.validate(address);assertEquals("transport",RhNetwork.kind(address));
        RhProfile profile=RhProfile.fromJson("host",fixture().put("packetSizeAuto",false).put("packetSize",1472));assertEquals(1024,profile.effectivePacketSize("127.77.0.2"));
    }
}
