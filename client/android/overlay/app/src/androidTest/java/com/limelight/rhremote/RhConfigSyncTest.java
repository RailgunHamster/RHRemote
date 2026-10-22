package com.limelight.rhremote;
import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RhConfigSyncTest {
    private JSONObject profile(int width,int height)throws Exception {return RhActions.obj("width",width,"height",height,"fps",30,"bitrateMbps",25,"codec","HEVC","trackpad",false,"statistics",false);}
    private JSONObject sample()throws Exception {
        return RhActions.obj("schemaVersion",2,"devices",new JSONArray().put(RhActions.obj("uuid","self","name","PC","address","192.168.0.100","swapButtons",true)),
            "clients",RhActions.obj("pc",RhActions.obj("name","PC","platform","windows"),"pad",RhActions.obj("name","OPPO","platform","android")),
            "profiles",RhActions.obj("pc",RhActions.obj("self",profile(1920,1080)),"pad",RhActions.obj("self",profile(2520,1680))),"dark",false);
    }
    @Test public void clientsKeepDifferentResolutionsAndOmittedSelfSurvives()throws Exception {
        JSONObject base=sample(),local=new JSONObject(base.toString()),remote=new JSONObject(base.toString());remote.put("devices",new JSONArray());remote.getJSONObject("profiles").getJSONObject("pad").getJSONObject("self").put("fps",60);local.getJSONObject("profiles").getJSONObject("pc").getJSONObject("self").put("bitrateMbps",40);
        JSONArray conflicts=new JSONArray();JSONObject merged=RhSync.merge(local,remote,base,"review",conflicts);RhShared.validate(merged);assertEquals(0,conflicts.length());assertEquals("self",merged.getJSONArray("devices").getJSONObject(0).getString("uuid"));assertEquals(1920,merged.getJSONObject("profiles").getJSONObject("pc").getJSONObject("self").getInt("width"));assertEquals(2520,merged.getJSONObject("profiles").getJSONObject("pad").getJSONObject("self").getInt("width"));
    }
    @Test public void deviceOrderSyncConflictsAndNewHostsSurvive()throws Exception {
        JSONObject base=sample();JSONArray rows=base.getJSONArray("devices");for(String id:new String[]{"b","c"})rows.put(RhActions.obj("uuid",id,"name",id,"address","local"));
        JSONObject local=new JSONObject(base.toString()),remote=new JSONObject(base.toString());JSONArray l=local.getJSONArray("devices"),r=remote.getJSONArray("devices");Object first=l.get(0);l.put(0,l.get(1));l.put(1,first);
        JSONArray conflicts=new JSONArray();JSONObject merged=RhSync.merge(local,base,base,"review",conflicts);assertEquals(0,conflicts.length());assertEquals("b",merged.getJSONArray("devices").getJSONObject(0).getString("uuid"));
        Object second=r.get(1);r.put(1,r.get(2));r.put(2,second);conflicts=new JSONArray();RhSync.merge(local,remote,base,"review",conflicts);assertEquals("deviceOrder",conflicts.getString(0));
        merged=RhSync.merge(local,remote,base,"remote",new JSONArray());assertEquals("c",merged.getJSONArray("devices").getJSONObject(1).getString("uuid"));r.put(RhActions.obj("uuid","new","name","New","address","local"));
        merged=RhSync.merge(local,remote,base,"local",new JSONArray());assertEquals(4,merged.getJSONArray("devices").length());assertEquals("b",merged.getJSONArray("devices").getJSONObject(0).getString("uuid"));
    }
    @Test public void movingDevicesPersistsWithoutChangingProfiles()throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();String prefix="rhremote-order-test-"+UUID.randomUUID();File dir=new File(base.getCacheDir(),prefix);assertTrue(dir.mkdir());
        Context context=new ContextWrapper(base){@Override public File getFilesDir(){return dir;}@Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefix+name,mode);}};
        try{
            java.util.List<com.limelight.nvstream.http.ComputerDetails> devices=new java.util.ArrayList<>();for(String id:new String[]{"a","b","c"}){com.limelight.nvstream.http.ComputerDetails d=new com.limelight.nvstream.http.ComputerDetails();d.uuid=id;d.name=id;d.manualAddress=new com.limelight.nvstream.http.ComputerDetails.AddressTuple("127.0.0.1",47989);devices.add(d);}
            String before=RhShared.canonical(RhShared.capture(context,devices).getJSONObject("profiles"));RhShared.move(context,devices,"c","up");RhShared.move(context,devices,"c","up");
            assertEquals("c",RhShared.ordered(context,devices).get(0).uuid);assertEquals("c",RhShared.capture(context,devices).getJSONArray("devices").getJSONObject(0).getString("uuid"));
            assertEquals(before,RhShared.canonical(RhShared.read(context).getJSONObject("profiles")));try{RhShared.move(context,devices,"c","bad");fail("invalid direction accepted");}catch(IllegalArgumentException expected){}
        }finally{for(String name:new String[]{"rhremote.devices","rhremote.global"})base.getSharedPreferences(prefix+name,0).edit().clear().commit();delete(dir);}
    }
    @Test public void sameClientHostConflictRequiresExplicitChoice()throws Exception {
        JSONObject base=sample(),local=new JSONObject(base.toString()),remote=new JSONObject(base.toString());local.getJSONObject("profiles").getJSONObject("pad").getJSONObject("self").put("bitrateMbps",15);remote.getJSONObject("profiles").getJSONObject("pad").getJSONObject("self").put("bitrateMbps",40);JSONArray conflicts=new JSONArray();RhSync.merge(local,remote,base,"review",conflicts);assertEquals("profiles.pad.self",conflicts.getString(0));JSONObject resolved=RhSync.merge(local,remote,base,"remote",new JSONArray());assertEquals(40,resolved.getJSONObject("profiles").getJSONObject("pad").getJSONObject("self").getInt("bitrateMbps"));
    }
    @Test public void samePlatformDevicesStillHaveSeparateProfiles()throws Exception {
        JSONObject left=sample(),right=sample();right.getJSONObject("clients").put("pad2",RhActions.obj("name","Second OPPO","platform","android"));right.getJSONObject("profiles").put("pad2",RhActions.obj("self",profile(1920,1080)));JSONArray conflicts=new JSONArray();JSONObject merged=RhSync.merge(left,right,null,"review",conflicts);assertEquals(0,conflicts.length());assertEquals(2520,merged.getJSONObject("profiles").getJSONObject("pad").getJSONObject("self").getInt("width"));assertEquals(1920,merged.getJSONObject("profiles").getJSONObject("pad2").getJSONObject("self").getInt("width"));
    }
    @Test public void legacyRemoteProfileBecomesCopySource()throws Exception {
        JSONObject p=profile(3840,2160);p.put("swapButtons",true);JSONObject old=RhActions.obj("schemaVersion",1,"devices",new JSONArray().put(RhActions.obj("uuid","self","name","PC","address","local")),"profiles",RhActions.obj("self",p),"dark",false);JSONObject migrated=RhShared.normalize(old);assertEquals(2,migrated.getInt("schemaVersion"));assertEquals(3840,migrated.getJSONObject("profiles").getJSONObject("legacy-v1").getJSONObject("self").getInt("width"));assertTrue(migrated.getJSONArray("devices").getJSONObject(0).getBoolean("swapButtons"));
    }
    @Test public void privateRuntimeFieldsAreRejected()throws Exception {
        JSONObject config=sample();config.getJSONArray("devices").getJSONObject(0).put("isSelf",true);try{RhShared.validate(config);fail("runtime flags must not enter portable schema");}catch(java.io.IOException expected){}
        config=sample();config.getJSONObject("profiles").getJSONObject("pc").getJSONObject("self").put("swapButtons",true);try{RhShared.validate(config);fail("host facts must not be duplicated in client profiles");}catch(java.io.IOException expected){}
    }
    @Test public void localMigrationKeepsPreferencesAndOwnIdentityAcrossSync()throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();String prefix="rhremote-sync-test-"+UUID.randomUUID();File dir=new File(base.getCacheDir(),prefix);assertTrue(dir.mkdir());
        Context context=new ContextWrapper(base){@Override public File getFilesDir(){return dir;}@Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefix+name,mode);}};
        try {
            context.getSharedPreferences("rhremote.devices",0).edit().putInt("self.width",1920).putInt("self.height",1080).putInt("self.fps",60).putBoolean("self.swapButtons",true).commit();
            RhShared.write(new File(dir,"rhremote-shared.json"),RhActions.obj("schemaVersion",1,"devices",new JSONArray().put(RhActions.obj("uuid","self","name","PC","address","local")),"profiles",RhActions.obj("self",RhProfile.loadLocal(context,"self").json()),"dark",false));
            RhShared.initialize(context);String id=RhShared.clientId(context);assertEquals(1920,RhProfile.load(context,"self").width);assertEquals(60,RhProfile.load(context,"self").fps);
            JSONObject shared=RhShared.read(context);shared.getJSONObject("clients").put("foreign",RhActions.obj("name","Other tablet","platform","android"));shared.getJSONObject("profiles").put("foreign",RhActions.obj("self",profile(3840,2160)));RhShared.apply(context,shared);
            assertEquals(id,RhShared.clientId(context));assertEquals(1920,RhProfile.load(context,"self").width);assertEquals(60,RhProfile.load(context,"self").fps);assertTrue(RhProfile.load(context,"self").swapButtons);assertEquals(3840,RhShared.read(context).getJSONObject("profiles").getJSONObject("foreign").getJSONObject("self").getInt("width"));
        } finally {for(String name:new String[]{"rhremote.devices","rhremote.global"})base.getSharedPreferences(prefix+name,0).edit().clear().commit();delete(dir);}
    }
    private void delete(File f){File[] files=f.listFiles();if(files!=null)for(File child:files)delete(child);f.delete();}
}
