package com.limelight.rhremote;

import android.content.Context;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.*;
import com.limelight.nvstream.http.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** The real parallel discovery coordinator; replace only its network responses. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows=RhDiscoveryFailureFlowTest.Responses.class)
public class RhDiscoveryFailureFlowTest {
    @Implements(value=NvHTTP.class,isInAndroidSdk=false)
    public static class Responses {
        static final Map<String,String> replies=new ConcurrentHashMap<>();
        static volatile CountDownLatch entered,release;static final AtomicInteger active=new AtomicInteger();
        ComputerDetails.AddressTuple address;X509Certificate pin;
        @Implementation protected void __constructor__(ComputerDetails.AddressTuple address,int port,String id,X509Certificate pin,LimelightCryptoProvider crypto,boolean strict){this.address=address;this.pin=pin;assertTrue(strict);}
        @Implementation protected ComputerDetails getRhDiscoveryDetails(boolean likelyOnline)throws IOException{
            active.incrementAndGet();long began=System.nanoTime();try{
                CountDownLatch gate=release;if(gate!=null){entered.countDown();try{if(!gate.await(2,TimeUnit.SECONDS))throw new IOException("Fixture not released");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new InterruptedIOException("Cancelled fixture");}}
                String response=replies.getOrDefault(address.address,"timeout");
                if(response.equals("timeout"))throw new SocketTimeoutException("Fixture timeout");
                if(response.equals("refused"))throw new ConnectException("Fixture refused");
                if(response.equals("dns"))throw new UnknownHostException("Fixture host");
                ComputerDetails value=new ComputerDetails();value.uuid=response;value.name="Fixture";value.state=ComputerDetails.State.ONLINE;value.pairState=pin==null?PairingManager.PairState.NOT_PAIRED:PairingManager.PairState.PAIRED;
                value.rhDiscoveryObservation=RhDiscoveryObservation.result(value,pin,pin==null?"http":"https",pin==null?"unpaired":"paired","",pin!=null,began);return value;
            }finally{active.decrementAndGet();}
        }
    }
    Context context;ComputerDetails target;ComputerManagerService service;Method poll;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();Responses.replies.clear();Responses.entered=null;Responses.release=null;assertEquals(0,Responses.active.get());
        target=new ComputerDetails();target.uuid="discovery-failure-host";target.name="Fixture";target.state=ComputerDetails.State.ONLINE;target.pairState=PairingManager.PairState.PAIRED;target.serverCert=PlatformBinding.getCryptoProvider(context).getClientCertificate();
        target.manualAddress=target.localAddress=new ComputerDetails.AddressTuple("192.0.2.1",47989);RhShared.capture(context,Collections.singleton(target));RhShared.saveAddresses(context,target.uuid,"192.0.2.1",new JSONArray().put("198.51.100.2"));
        service=Robolectric.buildService(ComputerManagerService.class).get();ReflectionHelpers.setField(service,"idManager",new IdentityManager(context));poll=ComputerManagerService.class.getDeclaredMethod("parallelPollPc",ComputerDetails.class);poll.setAccessible(true);
    }
    ComputerDetails runPoll()throws Exception{return (ComputerDetails)poll.invoke(service,target);}
    JSONObject snapshot(){target.state=ComputerDetails.State.OFFLINE;return RhDeviceCheck.snapshot(context,RhShared.read(context),target);}
    @After public void cleanup()throws Exception{if(Responses.release!=null)Responses.release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(Responses.active.get()!=0&&System.nanoTime()<end)Thread.sleep(5);assertEquals(0,Responses.active.get());}
    @Test public void allFailedAddressesKeepReasonsWithoutInvalidatingSavedPairing()throws Exception{
        Responses.replies.put("198.51.100.2","refused");String saved=RhShared.canonical(RhShared.read(context));X509Certificate pin=target.serverCert;
        assertNull(runPoll());JSONObject report=snapshot();assertTrue("All failed background probes lost their failure evidence: "+report,report.optBoolean("backgroundFailure"));
        assertEquals(2,report.getJSONArray("attempts").length());Set<String> reasons=new HashSet<>();for(int i=0;i<2;i++)reasons.add(report.getJSONArray("attempts").getJSONObject(i).getString("reason"));assertEquals(new HashSet<>(Arrays.asList("timeout","connection_failed")),reasons);
        assertFalse(RhDeviceCheck.paired(target,report));assertEquals("unknown",RhDeviceCheck.reachability(target,report));assertEquals("device.connect",RhDeviceCheck.primaryAction(target,report));assertSame(pin,target.serverCert);assertEquals(saved,RhShared.canonical(RhShared.read(context)));assertTrue(RhDeviceCheck.hint(report).contains("超时"));
    }
    @Test public void aWorkingSavedAddressWinsOverEarlierFailures()throws Exception{
        Responses.replies.put("198.51.100.2",target.uuid);ComputerDetails result=runPoll();assertNotNull(result);assertEquals(target.uuid,result.uuid);assertEquals("198.51.100.2",result.activeAddress.address);target.update(result);
        JSONObject report=RhDeviceCheck.snapshot(context,RhShared.read(context),target);assertEquals("paired",report.getString("outcome"));assertFalse(report.optBoolean("backgroundFailure"));assertTrue(RhDeviceCheck.paired(target,report));
    }
    @Test public void failuresFromAnOlderConfigurationAreStale()throws Exception{
        Responses.entered=new CountDownLatch(1);Responses.release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        try{Future<ComputerDetails> result=worker.submit(()->runPoll());assertTrue(Responses.entered.await(1,TimeUnit.SECONDS));RhShared.saveAddresses(context,target.uuid,"192.0.2.9",new JSONArray());Responses.release.countDown();assertNull(result.get(3,TimeUnit.SECONDS));assertTrue(snapshot().getBoolean("stale"));assertFalse(snapshot().has("outcome"));}
        finally{Responses.release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test public void differentFailureReasonsRefreshButTimingsDoNotRebuildRows()throws Exception{
        assertNull(runPoll());String first=RhDiscoveryObservation.signature(target);assertNull(runPoll());assertEquals(first,RhDiscoveryObservation.signature(target));Responses.replies.put("198.51.100.2","dns");assertNull(runPoll());assertNotEquals(first,RhDiscoveryObservation.signature(target));
    }
    @Test public void interruptedPollingDoesNotPublishAnAllFailedConclusion()throws Exception{
        Responses.entered=new CountDownLatch(1);Responses.release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        try{Future<ComputerDetails> result=worker.submit(()->runPoll());assertTrue(Responses.entered.await(1,TimeUnit.SECONDS));assertTrue(result.cancel(true));worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));assertNull(target.rhDiscoveryObservation);}
        finally{Responses.release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test public void anAddressReturningAnotherDeviceIsNeverAccepted()throws Exception{
        Responses.replies.put("192.0.2.1","another-host");assertNull(runPoll());JSONObject report=snapshot();assertEquals("identity_mismatch",report.getString("outcome"));assertTrue(RhDeviceCheck.blocks(report));assertEquals("device.check",RhDeviceCheck.primaryAction(target,report));assertFalse(RhDeviceCheck.canConnect(target,report));
    }
    @Test public void aggregationCannotMakeOldFailuresNewerThanALaterCheck()throws Exception{
        RhDiscoveryFailures cycle=RhDiscoveryFailures.begin(context,target);cycle.register(target.manualAddress);cycle.failed(target.manualAddress,new SocketTimeoutException("Fixture"));
        JSONObject fresh=RhDeviceCheck.report(target.uuid,target.uuid,"https",true,"paired",System.nanoTime());target.rhDiscoveryObservation=cycle.finish();
        assertTrue(snapshot().getLong("observationId")<fresh.getLong("observationId"));
    }
    @Test public void failedHttpFallbackRetainsTheEarlierCertificateProblemWithoutForcingPairing()throws Exception{
        RhDiscoveryFailures cycle=RhDiscoveryFailures.begin(context,target);cycle.register(target.manualAddress);
        javax.net.ssl.SSLHandshakeException secure=new javax.net.ssl.SSLHandshakeException("Fixture pin");secure.initCause(new RhDeviceCheck.PinnedCertificateMismatch());SocketTimeoutException plain=new SocketTimeoutException("Fixture fallback");plain.addSuppressed(secure);
        cycle.failed(target.manualAddress,plain);target.rhDiscoveryObservation=cycle.finish();JSONObject report=snapshot();JSONArray reasons=report.getJSONArray("attempts").getJSONObject(0).getJSONArray("reasons");assertEquals("timeout",reasons.getString(0));assertEquals("certificate_mismatch",reasons.getString(1));assertEquals("unavailable",report.getString("outcome"));
        assertEquals("none",report.getString("authenticationSource"));assertEquals("stored",RhDeviceCheck.status(target,report));assertEquals("已保存配对",RhDeviceCheck.label(target,report));assertFalse(RhDeviceCheck.pairingFailed(report));assertEquals("device.connect",RhDeviceCheck.primaryAction(target,report));
    }
    @Test public void invalidStatusCodesAreCheckedFailuresAndLegacyLargeCodesStillWork()throws Exception{
        NvHTTP client=new NvHTTP(target.manualAddress,0,"fixture",target.serverCert,PlatformBinding.getCryptoProvider(context),true);
        for(String code:new String[]{null,"not-a-code","999999999999999999999999"}){
            String xml="<root"+(code==null?"":" status_code=\""+code+"\"")+"><appversion>7.1.0</appversion></root>";
            try{client.getServerVersion(xml);fail("Invalid status was accepted");}catch(org.xmlpull.v1.XmlPullParserException expected){assertTrue(expected.getMessage().contains("status code"));}
        }
        assertEquals("7.1.0",client.getServerVersion("<root status_code=\"200\"><appversion>7.1.0</appversion></root>"));
        try{client.getServerVersion("<root status_code=\"4294967295\" status_message=\"Invalid\"/>");fail("Legacy error was accepted");}catch(HostHttpResponseException expected){assertEquals(418,expected.getErrorCode());}
    }
    @Test public void failedBackgroundProbesCannotResurrectExplicitlyUnpairedCredentials()throws Exception{
        target.pairState=PairingManager.PairState.NOT_PAIRED;assertNull(runPoll());JSONObject report=snapshot();assertTrue(report.getBoolean("backgroundFailure"));assertEquals("unpaired",report.getString("outcome"));assertEquals("device.check",RhDeviceCheck.primaryAction(target,report));assertEquals("需要重新配对",RhDeviceCheck.label(target,report));assertNotNull(target.serverCert);
    }
    @Test public void failedBackgroundProbesKeepAnEarlierExplicitAuthenticationRejection()throws Exception{
        RhDiagnosticWork work=RhDiagnosticWork.shared(context);Map<String,JSONObject> checks=ReflectionHelpers.getField(work,"checks");
        JSONObject rejected=RhDeviceCheck.report(target.uuid,target.uuid,"https",true,"auth_rejected",System.nanoTime());rejected.put("requestIntent",RhDeviceCheck.intent(context,RhShared.read(context),target));checks.put(target.uuid,rejected);
        assertNull(runPoll());work.observed(target.uuid,RhDiscoveryObservation.sequence(target));JSONObject report=snapshot();assertEquals("auth_rejected",report.getString("outcome"));assertEquals("device.check",RhDeviceCheck.primaryAction(target,report));assertFalse(RhDeviceCheck.paired(target,report));assertTrue(report.getBoolean("backgroundFailure"));assertTrue(RhDeviceCheck.hint(report).contains("超时"));assertTrue(RhDeviceCheck.hint(target,report).contains("请先检查连接"));
        Responses.replies.put("198.51.100.2",target.uuid);ComputerDetails accepted=runPoll();assertNotNull(accepted);target.update(accepted);JSONObject verified=RhDeviceCheck.snapshot(context,RhShared.read(context),target);assertEquals("paired",verified.getString("outcome"));assertTrue(RhDeviceCheck.paired(target,verified));assertEquals("device.connect",RhDeviceCheck.primaryAction(target,verified));
    }
}
