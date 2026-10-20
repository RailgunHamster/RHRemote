package com.limelight.rhremote;

import android.content.Context;
import android.content.SharedPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.nvstream.jni.MoonBridge;
import org.json.*;
import java.util.*;

/** Effective preferences for one client installation × Sunshine host. */
public final class RhProfile {
    public int width=2520,height=1680,fps=30,bitrateMbps=25,volume=100,packetSize=1392;
    public boolean swapButtons,trackpad,statistics,hdr,audioEnabled,reverseScroll,onScreenController,fullRange;
    public boolean playHostAudio=true,muteOnFocusLoss=false,absoluteMouse=true,framePacing=true,vsync=true;
    public String codec="HEVC",audioConfig="stereo",captureSysKeys="fullscreen",decoder="hardware",windowMode="windowed";
    public static final String[] KEYS={"width","height","fps","bitrateMbps","codec","swapButtons","trackpad","statistics","hdr","audioEnabled","volume","playHostAudio","audioConfig","muteOnFocusLoss","absoluteMouse","reverseScroll","captureSysKeys","framePacing","vsync","decoder","windowMode","packetSize","onScreenController","fullRange"};
    private final String id;
    private RhProfile(String id){this.id=id==null?"default":id;}
    public static RhProfile load(Context context,String id){
        JSONObject shared=RhShared.read(context),group=shared.optJSONObject("profiles").optJSONObject(RhShared.clientId(context));RhProfile p=new RhProfile(id);
        try{if(group!=null&&group.has(id))p=fromClientJson(id,group.getJSONObject(id));}catch(JSONException e){throw new IllegalStateException("invalid local profile",e);}
        p.swapButtons=RhShared.swap(shared,id);return p;
    }
    static RhProfile loadLocal(Context context,String id){
        RhProfile p=new RhProfile(id);SharedPreferences s=context.getSharedPreferences("rhremote.devices",0);String k=p.id+".";
        p.width=s.getInt(k+"width",2520);p.height=s.getInt(k+"height",1680);p.fps=s.getInt(k+"fps",30);p.bitrateMbps=s.getInt(k+"bitrateMbps",25);
        p.swapButtons=s.getBoolean(k+"swapButtons",false);p.trackpad=s.getBoolean(k+"trackpad",false);p.statistics=s.getBoolean(k+"statistics",false);p.codec=s.getString(k+"codec","HEVC");return p;
    }
    public JSONObject json(){return RhActions.obj("width",width,"height",height,"fps",fps,"bitrateMbps",bitrateMbps,"codec",codec,"swapButtons",swapButtons,"trackpad",trackpad,"statistics",statistics,"hdr",hdr,"audioEnabled",audioEnabled,"volume",volume,"playHostAudio",playHostAudio,"audioConfig",audioConfig,"muteOnFocusLoss",muteOnFocusLoss,"absoluteMouse",absoluteMouse,"reverseScroll",reverseScroll,"captureSysKeys",captureSysKeys,"framePacing",framePacing,"vsync",vsync,"decoder",decoder,"windowMode",windowMode,"packetSize",packetSize,"onScreenController",onScreenController,"fullRange",fullRange);}
    public JSONObject clientJson(){JSONObject p=json();p.remove("swapButtons");return p;}
    public static RhProfile fromClientJson(String id,JSONObject value)throws JSONException{JSONObject p=new JSONObject(value.toString());p.put("swapButtons",false);return fromJson(id,p);}
    public static RhProfile fromJson(String id,JSONObject v)throws JSONException{
        RhProfile p=new RhProfile(id);p.width=v.getInt("width");p.height=v.getInt("height");p.fps=v.getInt("fps");p.bitrateMbps=v.getInt("bitrateMbps");p.codec=v.getString("codec");p.swapButtons=v.getBoolean("swapButtons");p.trackpad=v.getBoolean("trackpad");p.statistics=v.getBoolean("statistics");
        p.hdr=v.optBoolean("hdr");p.audioEnabled=v.optBoolean("audioEnabled");p.volume=v.optInt("volume",100);p.playHostAudio=v.optBoolean("playHostAudio",true);p.audioConfig=v.optString("audioConfig","stereo");p.muteOnFocusLoss=v.optBoolean("muteOnFocusLoss",false);p.absoluteMouse=v.optBoolean("absoluteMouse",true);p.reverseScroll=v.optBoolean("reverseScroll");p.captureSysKeys=v.optString("captureSysKeys","fullscreen");p.framePacing=v.optBoolean("framePacing",true);p.vsync=v.optBoolean("vsync",true);p.decoder=v.optString("decoder","hardware");p.windowMode=v.optString("windowMode","windowed");p.packetSize=v.optInt("packetSize",1392);p.onScreenController=v.optBoolean("onScreenController");p.fullRange=v.optBoolean("fullRange");
        if(p.width<320||p.width>8192||p.height<320||p.height>8192||p.width%2!=0||p.height%2!=0||(long)p.width*p.height>64000000||p.fps<10||p.fps>240||p.bitrateMbps<1||p.bitrateMbps>300||p.volume<0||p.volume>100||p.packetSize<1024||p.packetSize>1472||!Arrays.asList("auto","HEVC","H.264","AV1").contains(p.codec)||!Arrays.asList("stereo","5.1-surround","7.1-surround").contains(p.audioConfig)||!Arrays.asList("never","fullscreen","always").contains(p.captureSysKeys)||!Arrays.asList("auto","hardware","software").contains(p.decoder)||!Arrays.asList("windowed","borderless","fullscreen").contains(p.windowMode))throw new IllegalArgumentException("invalid stream profile");return p;
    }
    public void apply(PreferenceConfiguration p){
        p.width=width;p.height=height;p.fps=fps;p.bitrate=bitrateMbps*1000;p.touchscreenTrackpad=trackpad;p.enablePerfOverlay=statistics;
        p.framePacing=framePacing?PreferenceConfiguration.FRAME_PACING_BALANCED:PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        p.videoFormat="auto".equals(codec)?PreferenceConfiguration.FormatOption.AUTO:"AV1".equals(codec)?PreferenceConfiguration.FormatOption.FORCE_AV1:"H.264".equals(codec)?PreferenceConfiguration.FormatOption.FORCE_H264:PreferenceConfiguration.FormatOption.FORCE_HEVC;
        p.audioConfiguration="5.1-surround".equals(audioConfig)?MoonBridge.AUDIO_CONFIGURATION_51_SURROUND:"7.1-surround".equals(audioConfig)?MoonBridge.AUDIO_CONFIGURATION_71_SURROUND:MoonBridge.AUDIO_CONFIGURATION_STEREO;
        p.playHostAudio=playHostAudio;p.enableHdr=hdr;p.onscreenController=onScreenController;p.fullRange=fullRange;p.absoluteMouseMode=absoluteMouse;
    }
    public String summary(){return width+" × "+height+" · "+fps+" 帧 · "+bitrateMbps+" Mbps";}
}
