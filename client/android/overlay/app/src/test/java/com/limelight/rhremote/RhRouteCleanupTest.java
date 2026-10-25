package com.limelight.rhremote;

import android.content.Context;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.IOException;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** The actual resolver must reap a rejected proxy before moving to another route. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhRouteCleanupTest.Transport.class,RhRouteCleanupTest.Probes.class})
public class RhRouteCleanupTest {
    static class Token extends RhCancellation {void cancel(){cancelRequests();}}
    @Implements(value=RhTunnel.class,isInAndroidSdk=false)
    public static class Transport {
        static int starts,stops,handshakes;static boolean active,failStart,failStop,cancelStart,lastOnly;
        @Implementation protected static synchronized JSONObject start(Context c,String peer,String relay,boolean only,RhCancellation operation)throws Exception{
            starts++;boolean reused=active&&lastOnly==only;if(!reused)handshakes++;lastOnly=only;active=true;if(cancelStart){((Token)operation).cancel();operation.checkpoint();}if(failStart)throw new IOException("partial startup failed");
            return RhActions.obj("address","127.77.0.2","portOffset",1000,"path","relay","reused",reused);
        }
        @Implementation protected static synchronized void stop(){stops++;if(failStop)throw new IllegalStateException("cleanup failed");active=false;}
    }
    @Implements(value=RhNetwork.class,isInAndroidSdk=false)
    public static class Probes {
        static int lan,proxy;static boolean rejectProxy,timeoutProxy;
        @Implementation protected static String check(String address,String uuid,int port,RhCancellation operation,int timeoutMs)throws Exception{
            if(operation!=null)operation.checkpoint();
            assertEquals(address.equals("127.77.0.2")?8000:1000,timeoutMs);
            if(address.equals("127.77.0.2")){proxy++;assertTrue(Transport.active);if(rejectProxy)throw new IOException("uuid_mismatch");if(timeoutProxy)throw new java.net.SocketTimeoutException("offline");}
            else{lan++;assertFalse("Previous rejected tunnel survived into a direct route",Transport.active);}
            return address;
        }
    }
    Context context;ComputerDetails device;RhProfile profile;
    @Before public void setup()throws Exception{
        Transport.starts=Transport.stops=Transport.handshakes=Probes.lan=Probes.proxy=0;Transport.active=Transport.failStart=Transport.failStop=Transport.cancelStart=Transport.lastOnly=Probes.rejectProxy=Probes.timeoutProxy=false;
        context=RuntimeEnvironment.getApplication();RhStatistics.initialize(context);RhStatistics.configure(false);
        device=new ComputerDetails();device.uuid="host";device.name="PC";device.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);
        RhShared.capture(context,java.util.Collections.singletonList(device));
        RhShared.saveAddresses(context,"host","rhr:"+String.join("",java.util.Collections.nCopies(64,"a")),new JSONArray().put("192.168.0.104"));
        profile=RhProfile.fromJson("host",RhActions.obj("width",1920,"height",1080,"fps",30,"bitrateMbps",25,"codec","H.264","swapButtons",false,"trackpad",false,"statistics",false));
    }
    @After public void cleanup(){Transport.failStop=false;RhTunnel.stop();RhStatistics.finish();}
    private ComputerDetails resolve(String...routes)throws Exception{profile.routeOrder.clear();java.util.Collections.addAll(profile.routeOrder,routes);return RhNetwork.resolve(context,device,profile,RhActions.obj());}
    @Test public void punchThatBecameARelayIsStoppedBeforeLanFallback()throws Exception{
        assertEquals("192.168.0.104",resolve("punch_direct","lan").activeAddress.address);
        assertEquals(1,Transport.starts);assertEquals(1,Transport.stops);assertEquals(1,Probes.lan);assertEquals(0,Probes.proxy);assertFalse(Transport.active);
    }
    @Test public void failedProxyIdentityIsStoppedBeforeLanFallback()throws Exception{
        Probes.rejectProxy=true;assertEquals("192.168.0.104",resolve("relay","lan").activeAddress.address);
        assertEquals(1,Transport.stops);assertEquals(1,Probes.proxy);assertEquals(1,Probes.lan);assertFalse(Transport.active);
    }
    @Test public void punchFallbackReusesItsAlreadyConnectedRelay()throws Exception{
        assertEquals("127.77.0.2",resolve("punch_direct","relay").activeAddress.address);
        assertEquals(2,Transport.starts);assertEquals(1,Transport.handshakes);assertEquals(0,Transport.stops);assertTrue(Transport.active);assertEquals(1,Probes.proxy);assertFalse(Transport.lastOnly);
        assertTrue(RhNetwork.state().getJSONArray("attempts").getJSONObject(1).getBoolean("reusedTunnel"));
    }
    @Test public void explicitRelayOnlyDoesNotEnableDirectUpgrade()throws Exception{
        assertEquals("127.77.0.2",resolve("relay").activeAddress.address);assertTrue(Transport.lastOnly);assertEquals(1,Transport.handshakes);assertFalse(RhNetwork.state().getJSONArray("attempts").getJSONObject(0).getBoolean("reusedTunnel"));
    }
    @Test public void interveningLanKeepsItsPriorityAndDisposesTheRetainedRelay()throws Exception{
        assertEquals("192.168.0.104",resolve("punch_direct","lan","relay").activeAddress.address);assertEquals(1,Transport.stops);assertEquals(1,Probes.lan);assertEquals(0,Probes.proxy);assertFalse(Transport.active);
    }
    @Test public void retainedRelayStillRequiresTheExpectedHostIdentity()throws Exception{
        Probes.rejectProxy=true;assertEquals("192.168.0.104",resolve("punch_direct","relay","lan").activeAddress.address);assertEquals(1,Transport.handshakes);assertEquals(1,Transport.stops);assertEquals(1,Probes.proxy);assertFalse(Transport.active);
    }
    @Test public void excludedRelayIsNotRetainedAfterPunchFailure()throws Exception{
        profile.routeOrder.clear();java.util.Collections.addAll(profile.routeOrder,"punch_direct","relay","lan");
        JSONArray failures=new JSONArray().put(RhActions.obj("route","relay","address",RhNetwork.addresses(RhNetwork.host(context,"host")).get(0)));
        assertEquals("192.168.0.104",RhNetwork.resolve(context,device,profile,RhActions.obj("failedRoutes",failures)).activeAddress.address);assertEquals(1,Transport.stops);assertFalse(Transport.active);
    }
    @Test public void partialStartupFailureIsReapedBeforeTheNextRoute()throws Exception{
        Transport.failStart=true;assertEquals("192.168.0.104",resolve("relay","lan").activeAddress.address);assertEquals(1,Transport.stops);assertEquals(1,Probes.lan);
    }
    @Test public void failedCleanupStopsResolutionInsteadOfAssumingTheProxyIsGone()throws Exception{
        Transport.failStop=true;try{resolve("punch_direct","lan");fail("Resolution continued after failed cleanup");}catch(IllegalStateException expected){assertEquals("cleanup failed",expected.getMessage());}
        assertEquals(0,Probes.lan);assertTrue(Transport.active);
    }
    @Test public void cancellationDuringStartupCleansWithoutTryingAnotherPath()throws Exception{
        profile.routeOrder.clear();java.util.Collections.addAll(profile.routeOrder,"punch_direct","lan");Transport.cancelStart=true;
        try{RhNetwork.resolve(context,device,profile,RhActions.obj(),new Token());fail("Cancelled route was selected");}catch(IOException expected){assertTrue(expected.getMessage().contains("取消"));}
        assertEquals(1,Transport.stops);assertEquals(0,Probes.lan);assertEquals(0,Probes.proxy);assertFalse(Transport.active);
    }
    @Test public void retainedPunchDoesNotHideARetryableRelayTimeout()throws Exception{
        Probes.timeoutProxy=true;
        try{resolve("punch_direct","relay");fail("Offline relay was accepted");}catch(RhNetwork.Unavailable expected){}
        assertFalse(Transport.active);assertEquals(1,Transport.stops);
    }
    @Test public void wrongIdentityNeverBecomesAWaitForNetwork()throws Exception{
        Probes.rejectProxy=true;
        try{resolve("relay");fail("Wrong identity accepted");}catch(RhNetwork.Unavailable wrong){fail("Identity mismatch was classified as transient");}catch(IOException expected){}
        assertFalse(RhNetwork.unavailable(new javax.net.ssl.SSLHandshakeException("certificate")));
        assertFalse(RhNetwork.unavailable(new IOException(new java.security.cert.CertificateException())));
        assertFalse(RhNetwork.unavailable(new IOException("invalid address")));
    }
}
