package com.limelight.rhremote;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class RhAudioSettingsTest {
    @Test public void pcmGainPreservesSignAndDoesNotChangeSource(){short[] input={Short.MIN_VALUE,-1000,0,1000,Short.MAX_VALUE};short[] half=RhAudioRenderer.gain(input,50);assertArrayEquals(new short[]{-16384,-500,0,500,16383},half);assertEquals(Short.MIN_VALUE,input[0]);assertArrayEquals(new short[5],RhAudioRenderer.gain(input,0));assertSame(input,RhAudioRenderer.gain(input,100));}
    @Test public void oldProfilesKeepValuesAndNewOptionsRoundTrip()throws Exception{
        JSONObject old=RhActions.obj("width",2520,"height",1680,"fps",30,"bitrateMbps",25,"codec","HEVC","swapButtons",true,"trackpad",false,"statistics",false);RhProfile migrated=RhProfile.fromJson("host",old);assertFalse(migrated.audioEnabled);assertTrue(migrated.playHostAudio);assertEquals(2520,migrated.width);
        JSONObject next=migrated.json();next.put("audioEnabled",true).put("volume",35).put("audioConfig","5.1-surround").put("hdr",true).put("codec","AV1").put("fps",120).put("width",1680).put("height",2520).put("packetSize",1200);RhProfile p=RhProfile.fromJson("host",next);assertEquals(35,p.volume);assertEquals("5.1-surround",p.audioConfig);assertTrue(p.audioEnabled);assertEquals(1680,p.width);assertEquals(120,p.fps);assertFalse(p.clientJson().has("swapButtons"));
        next.put("volume",101);try{RhProfile.fromJson("host",next);fail("invalid gain accepted");}catch(IllegalArgumentException expected){}
    }
}
