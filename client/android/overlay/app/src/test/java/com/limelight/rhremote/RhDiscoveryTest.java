package com.limelight.rhremote;

import com.limelight.computers.ComputerManagerService;
import com.limelight.computers.IdentityManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Exercise the patched real discovery loop, replacing only network responses. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows=RhDiscoveryTest.HttpResponses.class)
public class RhDiscoveryTest {
    @Implements(value=NvHTTP.class,isInAndroidSdk=false)
    public static class HttpResponses {
        static final ConcurrentHashMap<String,String> replies=new ConcurrentHashMap<>();
        static int connectionRequests;
        static boolean strictPin;
        static X509Certificate suppliedCertificate;
        private ComputerDetails.AddressTuple address;
        @Implementation protected void __constructor__(ComputerDetails.AddressTuple address,int httpsPort,
                String uniqueId,X509Certificate certificate,LimelightCryptoProvider crypto) {
            this.address=address;
        }
        @Implementation protected void __constructor__(ComputerDetails.AddressTuple address,int httpsPort,
                String uniqueId,X509Certificate certificate,LimelightCryptoProvider crypto,boolean strict) {
            this.address=address;strictPin=strict;suppliedCertificate=certificate;
        }
        @Implementation protected ComputerDetails getRhConnectionDetails()throws IOException {
            connectionRequests++;
            ComputerDetails result=getComputerDetails(false);result.pairState=PairingManager.PairState.PAIRED;
            result.httpsPort=47984;return result;
        }
        @Implementation protected ComputerDetails getComputerDetails(boolean likelyOnline)throws IOException {
            String uuid=replies.get(address.address);if(uuid==null)throw new IOException("unreachable");
            ComputerDetails result=new ComputerDetails();result.uuid=uuid;result.name="PC";
            result.state=ComputerDetails.State.ONLINE;return result;
        }
    }
    @Test public void staleLanFallsBackToSavedTailnetAndRejectsAnotherHostsUuid()throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();
        ComputerDetails source=new ComputerDetails();source.uuid="expected-host";source.name="PC";
        source.localAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);
        source.manualAddress=source.localAddress;
        RhShared.capture(context,Collections.singletonList(source));
        RhShared.saveAddresses(context,source.uuid,"192.168.0.104",
            new JSONArray().put("100.64.0.1").put("100.64.0.2"));
        HttpResponses.replies.clear();
        HttpResponses.replies.put("100.64.0.1","another-host");
        HttpResponses.replies.put("100.64.0.2",source.uuid);
        ComputerManagerService service=Robolectric.buildService(ComputerManagerService.class).get();
        ReflectionHelpers.setField(service,"idManager",new IdentityManager(context));
        Method poll=ComputerManagerService.class.getDeclaredMethod("parallelPollPc",ComputerDetails.class);
        poll.setAccessible(true);
        ComputerDetails result=(ComputerDetails)poll.invoke(service,source);
        assertNotNull(result);assertEquals(source.uuid,result.uuid);
        assertEquals("100.64.0.2",result.activeAddress.address);
        assertEquals(ComputerDetails.State.ONLINE,result.state);
        HttpResponses.replies.clear();
        HttpResponses.replies.put("100.64.0.1","another-host");
        assertNull("Wrong-host reachability must not mark this device online",poll.invoke(service,source));
    }
    @Test public void coldPairingRecordIsVerifiedBeforeLaunchInsteadOfStartingAnotherPairing()throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();
        ComputerDetails source=new ComputerDetails();source.uuid="paired-host";source.name="PC";
        source.serverCert=com.limelight.binding.PlatformBinding.getCryptoProvider(context).getClientCertificate();
        assertNotNull(source.serverCert);assertNull(source.pairState);assertTrue(RhNetwork.connectInsteadOfPair(source));
        source.activeAddress=new ComputerDetails.AddressTuple("100.64.0.2",47989);source.httpsPort=47984;
        HttpResponses.replies.clear();HttpResponses.replies.put("100.64.0.2",source.uuid);
        HttpResponses.connectionRequests=0;
        ComputerDetails resolved=RhNetwork.refreshConnectionDetails(context,source);
        assertEquals(PairingManager.PairState.PAIRED,resolved.pairState);
        assertSame(source.serverCert,resolved.serverCert);assertTrue(HttpResponses.strictPin);
        assertSame(source.serverCert,HttpResponses.suppliedCertificate);
        assertEquals(source.activeAddress,resolved.activeAddress);assertNull(source.pairState);
        assertSame(resolved,RhNetwork.refreshConnectionDetails(context,resolved));
        assertEquals("Known live pairing must not add another request",1,HttpResponses.connectionRequests);
        HttpResponses.replies.put("100.64.0.2","wrong-host");
        try{RhNetwork.refreshConnectionDetails(context,source);fail("UUID mismatch accepted");}
        catch(IOException expected){assertEquals("uuid_mismatch",expected.getMessage());}
        source.pairState=PairingManager.PairState.NOT_PAIRED;
        assertFalse("A known revoked pairing must still offer pairing",RhNetwork.connectInsteadOfPair(source));
    }
}
