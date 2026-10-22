package com.limelight.rhremote;
import java.io.IOException;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class RhDisplayTest {
    private RhProfile profile()throws Exception{return RhProfile.fromJson("host",RhActions.obj("width",2520,"height",1680,"fps",30,"bitrateMbps",25,"codec","HEVC","swapButtons",false,"trackpad",false,"statistics",false));}
    @Test public void automaticAndExplicitPhysicalSelectionUseTheCompanionProtocol()throws Exception{
        RhDisplay display=new RhDisplay(profile(),(path,body)->{
            assertEquals("/v1/display/prepare",path);assertEquals(2520,body.getInt("width"));
            if(body.getString("mode").equals("physical"))assertEquals("physical-screen",body.getString("displayId"));
            else assertEquals("auto",body.getString("mode"));
            return RhActions.obj("ok",true,"result",RhActions.obj("selected",RhActions.obj("id","physical-screen","captureIndex",1),"initialCaptureIndex",1,"displays",new JSONArray()));
        });
        display.prepare();display.select("physical-screen",false);display.applyInitial(); // Already on the initial screen: no JNI key injection.
        assertEquals("physical-screen",display.state().getJSONObject("selected").getString("id"));
    }
    @Test public void refreshKeepsSelectionWithItsFreshCaptureIndex()throws Exception{
        RhDisplay display=new RhDisplay(profile(),(path,body)->path.equals("/v1/displays")
            ? RhActions.obj("result",RhActions.obj("displays",new JSONArray().put(RhActions.obj("id","physical","captureIndex",2,"available",true))))
            : RhActions.obj("result",RhActions.obj("selected",RhActions.obj("id","physical","captureIndex",1))));
        display.select("auto",false);display.refresh();assertEquals(2,display.state().getJSONObject("selected").getInt("captureIndex"));
    }
    @Test public void missingCompanionIsVisibleAndExplicitVirtualModeFails()throws Exception{
        RhProfile profile=profile();RhDisplay display=new RhDisplay(profile,(path,body)->{throw new IOException("主机辅助服务未安装");});
        display.prepare();assertEquals("主机辅助服务未安装",display.state().getString("error"));assertEquals(0,display.state().getJSONArray("displays").length());
        profile.displayMode="virtual";try{display.prepare();fail("Explicit virtual display must not silently fall back");}catch(IOException expected){assertEquals("主机辅助服务未安装",expected.getMessage());}
    }
}
