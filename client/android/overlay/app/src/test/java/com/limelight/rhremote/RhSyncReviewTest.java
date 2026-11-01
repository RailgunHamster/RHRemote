package com.limelight.rhremote;

import android.content.Context;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhSyncReviewTest {
    final Context context=RuntimeEnvironment.getApplication();
    JSONObject config()throws Exception{
        JSONObject shared=RhShared.read(context);shared.put("devices",new JSONArray().put(RhActions.obj("uuid","host.with.dots","name","Workstation","address","192.0.2.1")));
        shared.getJSONObject("clients").put("pc.with.dots",RhActions.obj("name","台式机","platform","windows"));shared.getJSONObject("profiles").put("pc.with.dots",RhActions.obj("host.with.dots",RhProfile.load(context,"host.with.dots").clientJson()));return shared;
    }
    JSONObject review(JSONObject left,JSONObject right,String key)throws Exception{return RhSync.review(context,left,right,new JSONArray().put(key)).getJSONObject(0);}
    @Test public void everyClientSettingHasAVisibleDifferenceIncludingForeignPlatformFields()throws Exception{
        JSONObject left=config(),profile=left.getJSONObject("profiles").getJSONObject("pc.with.dots").getJSONObject("host.with.dots");profile.put("bitrateAuto",false);
        JSONArray catalog=RhSettings.catalog(context);List<String> keys=new ArrayList<>();profile.keys().forEachRemaining(keys::add);
        for(String key:keys){
            Object value=profile.get(key),next=null;
            if(value instanceof Boolean)next=!(Boolean)value;
            else if(value instanceof Number)next=key.equals("volume")?99:((Number)value).intValue()+(key.equals("width")||key.equals("height")?2:1);
            else if(key.equals("relayUrl"))next="https://relay.example.test/";
            else if(value instanceof String){for(int i=0;i<catalog.length();i++){JSONObject field=catalog.getJSONObject(i);if(!key.equals(field.optString("key")))continue;JSONArray options=field.getJSONArray("options");for(int j=0;j<options.length();j++)if(!options.getJSONArray(j).get(0).equals(value)){next=options.getJSONArray(j).get(0);break;}}}
            else if(value instanceof JSONArray){JSONArray source=(JSONArray)value,rotated=new JSONArray();for(int i=0;i<source.length();i++)rotated.put(source.get((i+1)%source.length()));next=rotated;}
            assertNotNull("No alternative for "+key,next);JSONObject remote=new JSONObject(left.toString());JSONObject candidate=remote.getJSONObject("profiles").getJSONObject("pc.with.dots").getJSONObject("host.with.dots");candidate.put(key,next);RhProfile.fromClientJson("host.with.dots",candidate);
            JSONObject item=review(left,remote,"profiles.pc.with.dots.host.with.dots");assertNotEquals(key+" hidden by the summary",item.getString("local"),item.getString("remote"));assertNotEquals(key,"无差异",item.getString("local"));
        }
    }
    @Test public void backupsPlaceholderNamesAndMissingNodesHaveUnambiguousValues()throws Exception{
        JSONObject left=config(),right=new JSONObject(left.toString());right.getJSONArray("devices").getJSONObject(0).put("addresses",new JSONArray().put("100.64.0.2"));JSONObject item=review(left,right,"devices.host.with.dots");
        assertEquals("备用地址：未设置",item.getString("local"));assertTrue(item.getString("remote").contains("100.64.0.2"));
        right=new JSONObject(left.toString());right.getJSONArray("devices").getJSONObject(0).put("displayName","未设置");item=review(left,right,"devices.host.with.dots");assertNotEquals(item.getString("local"),item.getString("remote"));assertTrue(item.getString("remote").contains("“未设置”"));
        right.put("devices",new JSONArray());assertEquals("未设置",review(left,right,"devices.host.with.dots").getString("remote"));right.put("profiles",new JSONObject());assertEquals("未设置",review(left,right,"profiles.pc.with.dots.host.with.dots").getString("remote"));
    }
    @Test public void copyPreviewSeparatesTheSavedManualRateFromAutomaticMode()throws Exception{
        RhProfile profile=RhProfile.load(context,"host");profile.bitrateAuto=true;profile.bitrateMbps=41;
        for(String platform:new String[]{null,"windows","android"}){
            JSONArray fields=RhProfileCopy.fields(context,profile,platform);Set<String> keys=new HashSet<>();boolean found=false;
            for(int i=0;i<fields.length();i++){JSONObject field=fields.getJSONObject(i);assertTrue(keys.add(field.getString("key")));if(field.getString("key").equals("bitrateMbps")){found=true;assertEquals("41 Mbps（手动值，未启用）",field.getString("value"));}}
            assertTrue(found);
        }
    }
}
