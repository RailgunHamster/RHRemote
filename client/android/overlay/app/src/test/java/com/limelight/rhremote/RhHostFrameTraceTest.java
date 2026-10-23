package com.limelight.rhremote;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RhHostFrameTraceTest {
    Context context;final ArrayList<File> traces=new ArrayList<>();
    @Before public void setup(){context=RuntimeEnvironment.getApplication();RhStatistics.initialize(context);RhStatistics.configure(false);}
    private void awaitFooter(File folder)throws Exception{
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);File file=new File(folder,"native.jsonl");
        do{if(file.isFile()&&new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).contains("\"event\":\"trace_end\""))return;Thread.sleep(10);}while(System.nanoTime()<end);
        fail("Statistics writer did not finish");
    }
    @After public void finish()throws Exception{RhStatistics.finish();for(File file:traces)awaitFooter(file);}
    private void begin()throws Exception{
        RhProfile profile=RhProfile.fromJson("host",RhActions.obj("width",1920,"height",1080,"fps",30,"bitrateMbps",25,"codec","H.264","swapButtons",false,"trackpad",false,"statistics",false));
        RhStatistics.begin(profile);traces.add(new File(RhStatistics.state().getString("latest")));
    }
    @Test public void onlyAnActiveRecordingRequestsHostFramesAndNoLocalPathIsSent()throws Exception{
        assertEquals("",RhStatistics.launchQuery());RhStatistics.configure(true);assertEquals("",RhStatistics.launchQuery());
        begin();String query=RhStatistics.launchQuery();String tag=traces.get(0).getName();
        assertEquals("&rhremoteFrameTrace=1&rhremoteTraceClient="+tag,query);assertFalse(query.contains(context.getFilesDir().getAbsolutePath()));
        RhStatistics.finish();assertEquals("",RhStatistics.launchQuery());
    }
    @Test public void recoveryRecordingsReceiveDistinctCorrelationLabels()throws Exception{
        RhStatistics.configure(true);begin();String first=RhStatistics.launchQuery();RhStatistics.finish();awaitFooter(traces.get(0));
        begin();assertNotEquals(first,RhStatistics.launchQuery());
    }
    @Test public void invalidOrInjectedLabelsCannotBecomeLaunchParameters(){
        for(String tag:new String[]{null,"",".","..","../file","a/b","a\\b","x&appid=2","a b","名字","quote\"","a\nb"})assertEquals("",RhHostFrameTrace.query(true,tag));
        assertEquals("",RhHostFrameTrace.query(false,"valid"));assertEquals("",RhHostFrameTrace.query(true,new String(new char[97]).replace('\0','a')));
        assertTrue(RhHostFrameTrace.query(true,"run-A_1.v2").endsWith("run-A_1.v2"));
    }
    @Test public void oldHostAndRejectedOrInvalidAcksDoNotClaimTraceSuccess(){
        assertEquals("disabled",RhHostFrameTrace.acknowledge(false,"1"));assertEquals("accepted",RhHostFrameTrace.acknowledge(true,"1"));
        assertEquals("not_accepted",RhHostFrameTrace.acknowledge(true,"0"));assertEquals("unsupported",RhHostFrameTrace.acknowledge(true,null));
        assertEquals("unsupported",RhHostFrameTrace.acknowledge(true,""));assertEquals("invalid_ack",RhHostFrameTrace.acknowledge(true,"anything"));
    }
}
