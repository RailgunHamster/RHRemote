package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhDiagnosticWorkTest.Network.class})
public class RhDiagnosticWorkTest {
    @Implements(value=RhNetwork.class,isInAndroidSdk=false)
    public static class Network {
        static Context context;static String uuid;static JSONObject options,result;static int width,calls;static Exception failure;
        @Implementation protected static JSONObject diagnose(Context c,ComputerDetails source,RhProfile profile,JSONObject captured)throws Exception{
            context=c;uuid=source.uuid;options=captured;width=profile.width;calls++;
            if(failure!=null)throw failure;return result;
        }
    }
    Activity activity;RhDiagnosticWork work;ComputerDetails device;RhProfile profile;List<Runnable> queued;
    @Before public void setup(){
        activity=Robolectric.buildActivity(Activity.class).setup().get();work=RhDiagnosticWork.shared(activity);queued=new ArrayList<>();
        device=new ComputerDetails();device.uuid="host";device.name="workstation";device.manualAddress=new ComputerDetails.AddressTuple("192.0.2.1",47989);profile=RhProfile.load(activity,"host");
        Network.calls=0;Network.failure=null;Network.result=RhActions.obj("uuid","host","selectedAddress","192.0.2.1");
    }
    void complete(){queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void queuedWorkFreezesItsInputsAndUsesOnlyApplicationContext()throws Exception{
        JSONObject options=RhActions.obj("address","192.0.2.2","addresses",new JSONArray().put("100.64.0.1"));
        int submitted=profile.width;work.start(device,profile,options,queued::add);profile.width=1280;device.uuid="changed";options.put("address","192.0.2.9");options.getJSONArray("addresses").put("100.64.0.9");complete();
        assertSame(activity.getApplicationContext(),Network.context);assertEquals("host",Network.uuid);assertEquals(submitted,Network.width);assertEquals("192.0.2.2",Network.options.getString("address"));assertEquals(1,Network.options.getJSONArray("addresses").length());
        Network.result.put("selectedAddress","mutated");assertEquals("192.0.2.1",work.state().getJSONObject("result").getString("selectedAddress"));
        work.state().getJSONObject("result").put("selectedAddress","also-mutated");assertEquals("192.0.2.1",work.state().getJSONObject("result").getString("selectedAddress"));
    }
    @Test public void theLatestListenerReceivesOneCompletionAndOldDetachCannotRetireIt()throws Exception{
        int[] oldCalls={0},newCalls={0};RhDiagnosticWork.Listener old=()->oldCalls[0]++,next=()->newCalls[0]++;
        work.attach(old);work.start(device,profile,null,queued::add);long operation=work.state().getLong("operationId");
        RhDiagnosticWork current=RhDiagnosticWork.shared(Robolectric.buildActivity(Activity.class).setup().get());assertSame(work,current);current.attach(next);work.detach(old);
        try{current.start(device,profile,null,queued::add);fail("Duplicate accepted");}catch(IllegalStateException expected){}
        assertEquals(1,queued.size());assertEquals(operation,current.state().getLong("operationId"));complete();assertEquals(1,oldCalls[0]);assertEquals(1,newCalls[0]);assertEquals(1,Network.calls);assertEquals("completed",current.state().getString("phase"));
    }
    @Test public void failureSurvivesReattachmentAndExplicitRetryClearsTheOldResult()throws Exception{
        Network.failure=new IOException("uuid_mismatch");work.start(device,profile,null,queued::add);complete();assertFalse(work.busy());assertEquals("error",work.state().getString("phase"));assertTrue(RhDiagnosticWork.shared(activity).state().getString("error").contains("设备标识"));assertEquals("uuid_mismatch",work.state().getString("detail"));
        Network.failure=null;work.start(device,profile,null,queued::add);assertEquals("",work.state().getString("error"));assertEquals(0,work.state().getJSONObject("result").length());complete();assertEquals(2,Network.calls);assertEquals(2,work.state().getLong("operationId"));assertEquals("completed",work.state().getString("phase"));
    }
    @Test public void executorRejectionReleasesAdmissionAndAllowsRetry()throws Exception{
        try{work.start(device,profile,null,task->{throw new RejectedExecutionException();});fail("Rejection hidden");}catch(RejectedExecutionException expected){}
        assertFalse(work.busy());assertEquals("error",work.state().getString("phase"));assertFalse(work.state().getString("error").isEmpty());assertEquals(0,Network.calls);
        work.start(device,profile,null,queued::add);complete();assertEquals(1,Network.calls);
    }
}
