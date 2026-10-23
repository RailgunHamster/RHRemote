package com.limelight.rhremote;

import com.limelight.computers.ComputerManagerService;
import com.limelight.computers.IdentityManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvHTTP;
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
        private ComputerDetails.AddressTuple address;
        @Implementation protected void __constructor__(ComputerDetails.AddressTuple address,int httpsPort,
                String uniqueId,X509Certificate certificate,LimelightCryptoProvider crypto) {
            this.address=address;
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
}
