package com.limelight.rhremote;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses only APIs already in immutable preview.152 for a full inspection comparison. */
@RunWith(AndroidJUnit4.class)
public final class RhInspectionLatencyDeviceTest {
    @Test public void measureTheActualReadOnlyInspectionFastPath()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("inspectionLatencyRun","");org.junit.Assume.assumeFalse("Explicit isolated emulator comparison required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        File folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());String uuid="inspection-latency";ComputerDetails target=new ComputerDetails();target.uuid=uuid;target.name="Fixture";target.manualAddress=new ComputerDetails.AddressTuple("127.0.0.13",47989);RhShared.capture(context,Collections.singletonList(target));RhProfile profile=RhProfile.load(context,uuid);profile.routeOrder=new ArrayList<>(Collections.singletonList("lan"));RhShared.saveProfile(context,target,profile,"");
        JSONObject configuration=RhShared.read(context);int devices=configuration.getJSONArray("devices").length(),profiles=configuration.getJSONObject("profiles").getJSONObject(RhShared.clientId(context)).length();assertEquals("Use fresh isolated fixture data",1,devices);assertEquals("Use one matching client profile",1,profiles);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream apk=new FileInputStream(context.getApplicationInfo().sourceDir)){byte[] buffer=new byte[65536];int count;while((count=apk.read(buffer))!=-1)digest.update(buffer,0,count);}StringBuilder apkSha=new StringBuilder();for(byte b:digest.digest())apkSha.append(String.format(Locale.ROOT,"%02X",b&255));
        AtomicInteger requests=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();ExecutorService server=Executors.newSingleThreadExecutor(),jobs=Executors.newSingleThreadExecutor();ServerSocket listener=new ServerSocket();listener.setReuseAddress(true);listener.bind(new InetSocketAddress("127.0.0.13",47989));
        server.execute(()->{while(!listener.isClosed())try(Socket peer=listener.accept()){peer.setSoTimeout(2000);BufferedReader input=new BufferedReader(new InputStreamReader(peer.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();assertTrue(first,first!=null&&first.startsWith("GET /serverinfo"));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
            requests.incrementAndGet();byte[] body=("<root status_code=\"200\"><appversion>7.1.0</appversion><uniqueid>"+uuid+"</uniqueid><PairStatus>0</PairStatus><HttpsPort>47984</HttpsPort></root>").getBytes(StandardCharsets.UTF_8);peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));peer.getOutputStream().write(body);peer.getOutputStream().flush();
        }catch(Throwable error){if(!listener.isClosed())failure.compareAndSet(null,error);}});
        RhDiagnosticWork work=RhDiagnosticWork.shared(context);JSONArray times=new JSONArray();
        try{for(int trial=0;trial<25;trial++){
            long began=SystemClock.elapsedRealtimeNanos();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{work.startCheck(target,profile,jobs);}catch(Exception error){throw new AssertionError(error);}});
            long deadline=SystemClock.uptimeMillis()+5000;while(work.busy()&&SystemClock.uptimeMillis()<deadline)Thread.sleep(1);assertFalse("Inspection did not settle",work.busy());long elapsed=SystemClock.elapsedRealtimeNanos()-began;assertEquals("completed",work.state().getString("phase"));assertEquals("unpaired",work.state().getJSONObject("result").getString("outcome"));times.put(elapsed);
        }}finally{listener.close();jobs.shutdown();server.shutdown();assertTrue(jobs.awaitTermination(3,TimeUnit.SECONDS));assertTrue(server.awaitTermination(3,TimeUnit.SECONDS));}
        assertNull(failure.get());assertEquals(25,requests.get());List<Long> warm=new ArrayList<>();for(int i=1;i<times.length();i++)warm.add(times.getLong(i));Collections.sort(warm);
        RhShared.write(new File(folder,"result.json"),RhActions.obj("scope","Actual full read-only inspection with one owned loopback HTTP request, no UI or stream; cold-process and warm measurements","package",context.getPackageName(),"version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"apkSha256",apkSha.toString(),"fixtureDevices",devices,"fixtureProfiles",profiles,"configurationBytes",configuration.toString().getBytes(StandardCharsets.UTF_8).length,"pid",android.os.Process.myPid(),"coldNs",times.getLong(0),"medianNs",warm.get(warm.size()/2),"p95Ns",warm.get((int)Math.ceil(warm.size()*.95)-1),"timesNs",times,"requests",requests.get()));
    }
}
