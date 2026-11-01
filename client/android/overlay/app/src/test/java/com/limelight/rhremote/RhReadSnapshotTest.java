package com.limelight.rhremote;

import android.content.Context;
import android.view.*;
import android.widget.TextView;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.File;
import java.util.Collections;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

/** One UI read must not combine host facts from opposite sides of an atomic update. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w1200dp-h800dp-mdpi",shadows={RhAdmissionTest.Controls.class,RhReadSnapshotTest.Reads.class})
public final class RhReadSnapshotTest {
    @Implements(value=RhShared.class,isInAndroidSdk=false)
    public static class Reads {
        static JSONObject replacement;static boolean count;static int calls;
        @Implementation protected static JSONObject read(Context context){
            JSONObject value=Shadow.directlyOn(RhShared.class,"read",ClassParameter.from(Context.class,context));
            if(count){calls++;if(replacement!=null){JSONObject next=replacement;replacement=null;try{RhShared.write(new File(context.getFilesDir(),"rhremote-shared.json"),next);}catch(Exception failure){throw new RuntimeException(failure);}}}
            return value;
        }
    }
    private RhAppearanceFlowTest scene;private ComputerDetails device;private JSONObject newer;
    @After public void cleanup(){Reads.count=false;Reads.replacement=null;Reads.calls=0;if(scene!=null){scene.cleanup();scene.activity.finish();}}
    private void create()throws Exception{
        scene=new RhAppearanceFlowTest();scene.create(false);device=new ComputerDetails();device.uuid="host";device.name="winserver";device.manualAddress=new ComputerDetails.AddressTuple("192.0.2.10",47989);
        JSONObject shared=RhShared.read(scene.activity);shared.put("devices",new JSONArray().put(RhActions.obj("uuid","host","name","winserver","address","192.0.2.10","displayName","旧名称","swapButtons",true,"addresses",new JSONArray())));
        JSONObject profile=RhProfile.load(scene.activity,"host").clientJson();profile.put("width",1600).put("height",900);shared.getJSONObject("profiles").put(RhShared.clientId(scene.activity),RhActions.obj("host",profile));RhShared.apply(scene.activity,shared);
        newer=new JSONObject(shared.toString());newer.getJSONArray("devices").getJSONObject(0).put("address","192.0.2.20").put("displayName","新名称").put("swapButtons",false);newer.getJSONObject("profiles").getJSONObject(RhShared.clientId(scene.activity)).getJSONObject("host").put("width",1920).put("height",1080);
    }
    private void arm()throws Exception{Reads.calls=0;Reads.replacement=new JSONObject(newer.toString());Reads.count=true;}
    private boolean hasText(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return true;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)if(hasText(((ViewGroup)root).getChildAt(i),value))return true;return false;}
    @Test public void oneDeviceFrameUsesOneCommittedDocumentAndNextFrameSeesNewData()throws Exception{
        create();RhDeviceBrowser browser=new RhDeviceBrowser(scene.activity,(id,args)->{});scene.activity.setContentView(browser);arm();browser.update(Collections.singletonList(device),"host","");
        assertTrue("Mixed address from a later commit",hasText(browser,"192.0.2.10"));assertTrue("Mixed profile from a later commit",hasText(browser,"1600 × 900"));assertTrue(hasText(browser,"旧名称"));assertEquals("A frame repeatedly reloaded and validated the whole document",1,Reads.calls);
        Reads.calls=0;browser.update(Collections.singletonList(device),"host","");assertTrue(hasText(browser,"192.0.2.20"));assertTrue(hasText(browser,"1920 × 1080"));assertTrue(hasText(browser,"新名称"));assertEquals(1,Reads.calls);
    }
    @Test public void stateQueryKeepsAliasProfileHostFactsAndSavedDocumentConsistent()throws Exception{
        create();scene.home.perform("device.select",RhActions.obj("uuid","host"));arm();JSONObject state=scene.home.state();assertEquals("旧名称",state.getJSONArray("devices").getJSONObject(0).getString("displayName"));assertEquals(1600,state.getJSONObject("saved").getJSONObject("profiles").getJSONObject("host").getInt("width"));assertTrue(state.getJSONObject("saved").getJSONObject("profiles").getJSONObject("host").getBoolean("swapButtons"));assertEquals("旧名称",state.getJSONObject("saved").getJSONArray("devices").getJSONObject(0).getString("displayName"));assertEquals(1,Reads.calls);
        Reads.calls=0;assertEquals("新名称",scene.home.state().getJSONArray("devices").getJSONObject(0).getString("displayName"));assertEquals(1,Reads.calls);
    }
    @Test public void aLaterInvalidDocumentStillFailsInsteadOfUsingACachedSnapshot()throws Exception{
        create();RhDeviceBrowser browser=new RhDeviceBrowser(scene.activity,(id,args)->{});browser.update(Collections.singletonList(device),"host","");JSONObject invalid=new JSONObject(newer.toString());invalid.put("schemaVersion",99);RhShared.write(new File(scene.activity.getFilesDir(),"rhremote-shared.json"),invalid);
        assertThrows(IllegalStateException.class,()->browser.update(Collections.singletonList(device),"host",""));
    }
    @Test public void captureUsesItsSingleCommittedReadForEveryDeviceProfile()throws Exception{
        create();Reads.calls=0;Reads.count=true;JSONObject captured=RhShared.capture(scene.activity,Collections.singletonList(device));Reads.count=false;
        assertEquals("Capture reloaded the complete document for a device already in its snapshot",1,Reads.calls);
        assertEquals("旧名称",captured.getJSONArray("devices").getJSONObject(0).getString("displayName"));assertEquals(1600,captured.getJSONObject("profiles").getJSONObject(RhShared.clientId(scene.activity)).getJSONObject("host").getInt("width"));assertTrue(captured.getJSONArray("devices").getJSONObject(0).getBoolean("swapButtons"));
    }
}
