package com.limelight.rhremote;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhNetworkTest {
    private RhProfile profile()throws Exception{return RhProfile.fromJson("host",RhActions.obj("width",2520,"height",1680,"fps",30,"bitrateMbps",25,"codec","HEVC","swapButtons",false,"trackpad",false,"statistics",false));}
    @Test public void tailnetUsesSafePacketsWithoutChangingSavedImageQuality()throws Exception{
        RhProfile p=profile();assertTrue(p.packetSizeAuto);assertEquals(1024,p.effectivePacketSize("100.73.139.109"));assertEquals(1392,p.effectivePacketSize("192.168.0.104"));
        assertEquals(25,p.bitrateMbps);assertEquals(0,p.remoteBitrateMbps);
        JSONObject value=p.clientJson();value.put("packetSizeAuto",false).put("packetSize",1200).put("networkMode","tailscale").put("remoteBitrateMbps",15);
        RhProfile manual=RhProfile.fromClientJson("host",value);assertEquals(1200,manual.effectivePacketSize("100.73.139.109"));assertEquals("tailscale",manual.networkMode);assertEquals(15,manual.remoteBitrateMbps);assertEquals(2520,manual.width);
    }
    @Test public void endpointsAreDeduplicatedBoundedAndCannotContainUrls()throws Exception{
        JSONObject value=RhActions.obj("address","PC","addresses",new JSONArray().put("pc").put("100.73.139.109").put("[fd7a:115c:a1e0::1]"));
        assertEquals(3,RhNetwork.addresses(value).size());
        for(String address:new String[]{"http://pc","pc:47989","pc/path","a@pc"," pc ","pc\nother"})try{RhNetwork.validate(address);fail(address);}catch(IOException expected){}
        JSONArray extra=new JSONArray();for(int i=0;i<8;i++)extra.put("pc"+i);value.put("addresses",extra);
        try{RhNetwork.addresses(value);fail("too many endpoints");}catch(IOException expected){}
    }
    @Test public void sharedEndpointsSurviveNormalizationAlongsideHostFacts()throws Exception{
        JSONObject device=RhActions.obj("uuid","pc","name","PC","address","192.168.0.104","addresses",new JSONArray().put("100.73.139.109"),"swapButtons",true,"displayName","办公电脑");
        JSONObject shared=RhActions.obj("schemaVersion",2,"devices",new JSONArray().put(device),"clients",new JSONObject(),"profiles",new JSONObject(),"dark",false);
        JSONObject saved=RhShared.normalize(shared).getJSONArray("devices").getJSONObject(0);
        assertEquals("100.73.139.109",saved.getJSONArray("addresses").getString(0));assertTrue(saved.getBoolean("swapButtons"));assertEquals("办公电脑",saved.getString("displayName"));
    }
    @Test public void newlyDiscoveredHostCanOpenNetworkSettingsBeforeAnySync()throws Exception{
        android.content.Context c=org.robolectric.RuntimeEnvironment.getApplication();
        com.limelight.nvstream.http.ComputerDetails d=new com.limelight.nvstream.http.ComputerDetails();d.uuid="new-host";d.name="PC";d.manualAddress=new com.limelight.nvstream.http.ComputerDetails.AddressTuple("192.168.0.104",47989);
        assertEquals("192.168.0.104",RhNetwork.metadata(c,d).getString("address"));
        RhShared.capture(c,java.util.Collections.singletonList(d));RhShared.saveAddresses(c,d.uuid,"192.168.0.104",new JSONArray().put("100.73.139.109"));
        assertEquals("100.73.139.109",RhNetwork.host(c,d.uuid).getJSONArray("addresses").getString(0));
    }
}
