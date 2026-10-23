package com.limelight.rhremote;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhRelayMigrationTest {
    private JSONObject profile(String url)throws Exception {
        return RhProfile.fromJson("host",RhActions.obj("width",2560,"height",1440,"fps",30,
            "bitrateMbps",41,"codec","HEVC","swapButtons",false,"trackpad",false,
            "statistics",false,"audioEnabled",true,"relayUrl",url,
            "routeOrder",new JSONArray().put("relay").put("lan"))).clientJson();
    }
    private JSONObject fixture(String url)throws Exception {
        return RhActions.obj("schemaVersion",2,"dark",false,"devices",new JSONArray(),
            "clients",RhActions.obj("pc",RhActions.obj("name","PC","platform","windows"),
                "pad",RhActions.obj("name","Pad","platform","android")),
            "profiles",RhActions.obj("pc",RhActions.obj("host",profile(url),
                "custom",profile("http://custom.example:33447/")),
                "pad",RhActions.obj("host",profile(RhProfile.LEGACY_DEFAULT_RELAY))));
    }
    private JSONObject host(JSONObject config)throws Exception {
        return config.getJSONObject("profiles").getJSONObject("pc").getJSONObject("host");
    }
    @Test public void migrationBacksUpOnlyCurrentClientAndPreservesLaterHttpChoice()throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        assertTrue(c.getSharedPreferences("rhremote.global",0).edit().clear()
            .putString("clientId","pc").putInt("profileSchemaVersion",2)
            .putBoolean("deviceOrderInitialized",true).commit());
        JSONObject before=fixture(RhProfile.LEGACY_DEFAULT_RELAY);
        File file=new File(c.getFilesDir(),"rhremote-shared.json");
        RhShared.write(file,before);
        JSONObject after=RhShared.read(c);
        JSONObject expected=new JSONObject(host(before).toString()).put("relayUrl",RhProfile.DEFAULT_RELAY);
        assertEquals(RhShared.canonical(expected),RhShared.canonical(host(after)));
        assertEquals(RhShared.canonical(before.getJSONObject("profiles").getJSONObject("pad")),
            RhShared.canonical(after.getJSONObject("profiles").getJSONObject("pad")));
        assertEquals("http://custom.example:33447/",after.getJSONObject("profiles").getJSONObject("pc")
            .getJSONObject("custom").getString("relayUrl"));
        File backup=new File(c.getFilesDir(),"rhremote-shared.pre-https-relay.json");
        byte[] backupBytes=Files.readAllBytes(backup.toPath());
        assertEquals(RhShared.canonical(before),RhShared.canonical(new JSONObject(new String(backupBytes,StandardCharsets.UTF_8))));
        assertEquals(1,c.getSharedPreferences("rhremote.global",0).getInt("relayDefaultsVersion",0));
        assertFalse(after.has("relayDefaultsVersion"));
        host(after).put("relayUrl",RhProfile.LEGACY_DEFAULT_RELAY);
        RhShared.write(file,after);
        assertEquals(RhProfile.LEGACY_DEFAULT_RELAY,host(RhShared.read(c)).getString("relayUrl"));
        assertArrayEquals(backupBytes,Files.readAllBytes(backup.toPath()));
    }
    @Test public void officialUrlOnlyUpgradeMergesInEitherDirection()throws Exception {
        JSONObject old=fixture(RhProfile.LEGACY_DEFAULT_RELAY.substring(0,RhProfile.LEGACY_DEFAULT_RELAY.length()-1));
        JSONObject secure=fixture(RhProfile.DEFAULT_RELAY);
        for(JSONObject[] sides:new JSONObject[][]{{old,secure},{secure,old}}){
            JSONArray conflicts=new JSONArray();
            JSONObject result=RhSync.merge(sides[0],sides[1],null,"review",conflicts);
            assertEquals(0,conflicts.length());
            assertEquals(RhProfile.DEFAULT_RELAY,host(result).getString("relayUrl"));
        }
    }
    @Test public void deliberateHttpEditRelativeToBaseIsPreserved()throws Exception {
        JSONObject old=fixture(RhProfile.LEGACY_DEFAULT_RELAY),secure=fixture(RhProfile.DEFAULT_RELAY);
        JSONArray conflicts=new JSONArray();
        assertEquals(RhProfile.LEGACY_DEFAULT_RELAY,
            host(RhSync.merge(old,secure,secure,"review",conflicts)).getString("relayUrl"));
        assertEquals(0,conflicts.length());
    }
    @Test public void customUrlsAndConcurrentQualityEditsStillConflict()throws Exception {
        JSONObject secure=fixture(RhProfile.DEFAULT_RELAY);
        for(JSONObject changed:new JSONObject[]{fixture("https://custom.example/"),fixture(RhProfile.LEGACY_DEFAULT_RELAY)}){
            if(host(changed).getString("relayUrl").equals(RhProfile.LEGACY_DEFAULT_RELAY))host(changed).put("fps",60);
            JSONArray conflicts=new JSONArray();
            RhSync.merge(secure,changed,null,"review",conflicts);
            assertEquals(1,conflicts.length());
            assertEquals("profiles.pc.host",conflicts.getString(0));
        }
    }
}
