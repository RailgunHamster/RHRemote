package com.limelight.rhremote;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Same protocol call on immutable baseline and current isolated APKs; no Activity or remote host. */
@RunWith(AndroidJUnit4.class)
public final class RhProbeLatencyDeviceTest {
    @Test public void recordColdAndWarmLoopbackProbeLatency()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("probeLatencyRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        File folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());final int warmups=8,iterations=40,total=1+warmups+iterations;String uuid="latency-fixture";AtomicInteger requests=new AtomicInteger();ExecutorService worker=Executors.newSingleThreadExecutor();JSONArray samples=new JSONArray();long cold;
        try(ServerSocket listener=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){Future<?> server=worker.submit(()->{try{for(int i=0;i<total;i++)try(Socket socket=listener.accept()){
            socket.setSoTimeout(4000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();assertNotNull(first);assertTrue(first.startsWith("GET /serverinfo"));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
            byte[] body=("<root><uniqueid>"+uuid+"</uniqueid></root>").getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();requests.incrementAndGet();
        }}catch(Exception failure){throw new RuntimeException(failure);}});
            cold=probe(uuid,listener.getLocalPort());for(int i=0;i<warmups;i++)probe(uuid,listener.getLocalPort());for(int i=0;i<iterations;i++)samples.put(probe(uuid,listener.getLocalPort()));server.get(5,TimeUnit.SECONDS);assertEquals(total,requests.get());
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        long[] sorted=new long[iterations];for(int i=0;i<iterations;i++)sorted[i]=samples.getLong(i);Arrays.sort(sorted);
        JSONObject result=RhActions.obj("scope","Loopback serverinfo probe only; first call and warmed samples; not a complete connection or frame test","package",context.getPackageName(),"pid",android.os.Process.myPid(),"version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"coldNs",cold,"medianNs",sorted[iterations/2],"p95Ns",sorted[(int)Math.ceil(iterations*.95)-1],"warmups",warmups,"samplesNs",samples,"serverRequests",requests.get());
        try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"result.json")),"UTF-8")){out.write(result.toString(2));}
    }
    long probe(String uuid,int port)throws Exception{long began=SystemClock.elapsedRealtimeNanos();assertEquals("127.0.0.1",RhNetwork.check("127.0.0.1",uuid,port,new RhNetwork.Probe(),1000));return SystemClock.elapsedRealtimeNanos()-began;}
}
