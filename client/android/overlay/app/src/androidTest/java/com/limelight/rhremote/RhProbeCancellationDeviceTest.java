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

/** Late body-read cancellation on Android's actual HTTP stack, compatible with the immutable 148 APK. */
@RunWith(AndroidJUnit4.class)
public final class RhProbeCancellationDeviceTest {
    @Test public void cancellingAnEstablishedAndroidBodyReadClosesTheSocket()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("probeCancelRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));File folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());JSONArray cases=new JSONArray();
        for(String connection:new String[]{"close","keep-alive"}){
            ExecutorService workers=Executors.newFixedThreadPool(3);RhNetwork.Probe probe=new RhNetwork.Probe();CountDownLatch headers=new CountDownLatch(1);AtomicReference<Socket> accepted=new AtomicReference<>();AtomicReference<Thread> reader=new AtomicReference<>();
            try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                Future<?> server=workers.submit(()->{try(Socket socket=listener.accept()){accepted.set(socket);socket.setSoTimeout(4000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nConnection: "+connection+"\r\nContent-Length: 64\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();headers.countDown();assertEquals(-1,socket.getInputStream().read());}catch(IOException failure){throw new RuntimeException(failure);}});
                Future<?> client=workers.submit(()->{reader.set(Thread.currentThread());try{RhNetwork.check("127.0.0.1","fixture",listener.getLocalPort(),probe,8000);fail("Cancelled body returned success");}catch(Exception expected){}});
                assertTrue(headers.await(3,TimeUnit.SECONDS));Thread.sleep(30);assertFalse(client.isDone());String stack=Arrays.toString(reader.get().getStackTrace());
                long began=SystemClock.elapsedRealtimeNanos();Future<?> cancelled=workers.submit(probe::cancel);client.get(2,TimeUnit.SECONDS);long bodyNs=SystemClock.elapsedRealtimeNanos()-began;cancelled.get(1,TimeUnit.SECONDS);long cancelNs=SystemClock.elapsedRealtimeNanos()-began;server.get(1,TimeUnit.SECONDS);
                cases.put(RhActions.obj("connection",connection,"bodyExitNs",bodyNs,"cancelCallNs",cancelNs,"serverSawEof",true,"readerStackBeforeCancel",stack));
            }finally{Socket owned=accepted.get();if(owned!=null)owned.close();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
        }
        JSONObject result=RhActions.obj("scope","Actual Android HTTP, response headers received and body withheld; two connection header modes","package",context.getPackageName(),"pid",android.os.Process.myPid(),"version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"cases",cases);
        try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"result.json")),"UTF-8")){out.write(result.toString(2));}
    }
}
