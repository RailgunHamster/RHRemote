package com.limelight.rhremote;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhWindowsTest {
    static final String SOURCE="rhrwindow:0123456789abcdef0123456789abcdef";
    static JSONObject window(){return RhActions.obj("id",SOURCE,"width",1200,"height",800,"title","Editor","available",true);}
    static String row(String id,String state,String width){return "<Window><id>"+id+"</id><title>A &amp; B</title><application>editor.exe</application><state>"+state+"</state><width>"+width+"</width><height>800</height></Window>";}
    static String list(String rows){return "<root status_code=\"200\"><supported>1</supported>"+rows+"</root>";}
    @Test public void parsesReadyAndUnavailableRowsWithoutNativeHandles()throws Exception{
        JSONArray rows=RhWindows.parse(list(row(SOURCE,"ready","1200")+row("","permission_denied","0"))).getJSONArray("windows");
        assertEquals(2,rows.length());assertEquals("A & B",rows.getJSONObject(0).getString("title"));assertTrue(rows.getJSONObject(0).getBoolean("available"));assertFalse(rows.getJSONObject(1).getBoolean("available"));
    }
    @Test public void canonicalOpaqueSourcesOnly(){assertTrue(RhWindows.valid(SOURCE));for(String value:new String[]{"0x12345",SOURCE+"0",SOURCE.toUpperCase(),"\\\\.\\DISPLAY1","rhrwindow:١١١١١١١١١١١١١١١١١١١١١١١١١١١١١١١١"})assertFalse(value,RhWindows.valid(value));}
    @Test public void malformedResponsesDoNotBecomeDesktopSelection(){
        for(String xml:new String[]{"", "<root status_code=\"401\"/>","<root status_code=\"200\"><supported>0</supported></root>",list(row("0x123","ready","1200")),list(row(SOURCE,"ready","0")),list(row(SOURCE,"ready","20000")),list(row(SOURCE,"ready","1200")+row(SOURCE,"ready","1200")),list("<Window>")})assertThrows(Exception.class,()->RhWindows.parse(xml));
    }
    @Test public void resolutionFitsTheWindowWithoutUpscaling()throws Exception{
        assertArrayEquals(new int[]{1200,800},RhWindows.resolution(window(),1920,1080));assertArrayEquals(new int[]{600,400},RhWindows.resolution(window(),600,1000));
        JSONObject portrait=window().put("width",800).put("height",1200);assertArrayEquals(new int[]{720,1080},RhWindows.resolution(portrait,1920,1080));
        assertThrows(Exception.class,()->RhWindows.resolution(window().put("id","0x123"),1920,1080));
    }
    @Test public void windowLaunchDoesNotPrepareOrSwitchAMonitor()throws Exception{
        RhProfile profile=RhProfile.fromJson("fixture",RhActions.obj("width",1920,"height",1080,"fps",30,"bitrateMbps",8,"codec","HEVC","swapButtons",false,"trackpad",false,"statistics",false,"displayMode","virtual"));
        RhDisplay display=new RhDisplay(profile,(path,body)->{throw new AssertionError("Unexpected monitor API: "+path);});display.setWindow(window());display.prepare();
        assertEquals("&rhrdisplay="+SOURCE,display.launchQuery());assertEquals("window",display.state().getJSONObject("selected").getString("kind"));display.acknowledgeInitial(SOURCE);display.applyInitial();
        assertThrows(Exception.class,()->display.prepareSelection("virtual"));assertThrows(Exception.class,display::refresh);
    }
    @Test public void missingOrDifferentWindowAckAlwaysStopsBeforeStreaming()throws Exception{
        RhDisplay display=new RhDisplay(RhProfile.fromJson("fixture",RhActions.obj("width",1920,"height",1080,"fps",30,"bitrateMbps",8,"codec","HEVC","swapButtons",false,"trackpad",false,"statistics",false)),(path,body)->{throw new AssertionError();});display.setWindow(window());display.prepare();display.launchQuery();
        assertThrows(Exception.class,()->display.acknowledgeInitial(""));assertThrows(Exception.class,display::applyInitial);assertThrows(Exception.class,()->display.acknowledgeInitial("\\\\.\\DISPLAY1"));
    }
}
