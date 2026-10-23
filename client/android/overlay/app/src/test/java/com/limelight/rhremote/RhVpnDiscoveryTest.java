package com.limelight.rhremote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.utils.NetHelper;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhVpnDiscoveryTest.Routes.class,RhVpnDiscoveryTest.Policy.class,RhVpnDiscoveryTest.Stun.class})
public class RhVpnDiscoveryTest {
    @Implements(ConnectivityManager.class)
    public static class Routes extends ShadowConnectivityManager {
        static int processBindings;
        @Implementation @Override protected boolean bindProcessToNetwork(Network network) {
            processBindings++;return super.bindProcessToNetwork(network);
        }
    }
    @Implements(value=NetHelper.class,isInAndroidSdk=false)
    public static class Policy {
        static boolean vpn;
        @Implementation protected static boolean isActiveNetworkVpn(Context context){return vpn;}
    }
    @Implements(value=NvConnection.class,isInAndroidSdk=false)
    public static class Stun {
        static int requests;
        static boolean enableVpnDuringRequest;
        @Implementation protected static String findExternalAddressForMdns(String host,int port) {
            requests++;if(enableVpnDuringRequest)Policy.vpn=true;return "203.0.113.10";
        }
    }
    private ComputerManagerService service;
    private Method populate;
    @Before public void setup()throws Exception {
        Policy.vpn=false;Routes.processBindings=0;Stun.requests=0;Stun.enableVpnDuringRequest=false;
        service=Robolectric.buildService(ComputerManagerService.class).get();
        ConnectivityManager manager=(ConnectivityManager)RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE);
        ShadowConnectivityManager routes=Shadows.shadowOf(manager);routes.clearAllNetworks();
        Network wifi=ReflectionHelpers.callConstructor(Network.class,ClassParameter.from(int.class,71));
        NetworkInfo info=ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,true);
        routes.addNetwork(wifi,info);
        NetworkCapabilities capabilities=new NetworkCapabilities();
        ReflectionHelpers.callInstanceMethod(capabilities,"addTransportType",ClassParameter.from(int.class,NetworkCapabilities.TRANSPORT_WIFI));
        ReflectionHelpers.callInstanceMethod(capabilities,"addCapability",ClassParameter.from(int.class,NetworkCapabilities.NET_CAPABILITY_NOT_VPN));
        routes.setNetworkCapabilities(wifi,capabilities);
        populate=ComputerManagerService.class.getDeclaredMethod("populateExternalAddress",ComputerDetails.class);populate.setAccessible(true);
    }
    @Test public void discoveryNeverRebindsTheAppAwayFromAnActiveVpn()throws Exception {
        Policy.vpn=true;ComputerDetails details=new ComputerDetails();populate.invoke(service,details);
        assertEquals("VPN discovery changed the routing of unrelated requests",0,Routes.processBindings);
        assertEquals("A VPN client's public address must not be assigned to the remote host",0,Stun.requests);
        assertNull(details.remoteAddress);
    }
    @Test public void ordinaryLanDiscoveryStillLearnsItsPublicAddress()throws Exception {
        ComputerDetails details=new ComputerDetails();populate.invoke(service,details);
        assertEquals(0,Routes.processBindings);assertEquals(1,Stun.requests);
        assertEquals("203.0.113.10",details.remoteAddress.address);assertEquals(47989,details.remoteAddress.port);
    }
    @Test public void networkSwitchDuringStunDoesNotSaveAVpnAddressAsTheHostsPublicAddress()throws Exception {
        Stun.enableVpnDuringRequest=true;ComputerDetails details=new ComputerDetails();populate.invoke(service,details);
        assertEquals(0,Routes.processBindings);assertEquals(1,Stun.requests);assertNull(details.remoteAddress);
    }
    @Test public void advertisedHostAddressIsPreserved()throws Exception {
        Policy.vpn=true;ComputerDetails details=new ComputerDetails();
        details.remoteAddress=new ComputerDetails.AddressTuple("198.51.100.10",47989);populate.invoke(service,details);
        assertEquals("198.51.100.10",details.remoteAddress.address);
        assertEquals(0,Routes.processBindings);assertEquals(0,Stun.requests);
    }
}
