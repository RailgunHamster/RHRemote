package com.limelight.rhremote;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import com.limelight.PcView;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerService;
import com.limelight.computers.IdentityManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import java.io.File;
import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

/** Real PcView completion and SQLite persistence, with only pairing traffic replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhPairingPersistenceTest.Http.class,RhPairingPersistenceTest.Pair.class,RhPairingPersistenceTest.Db.class,RhPairingPersistenceTest.NetworkLearning.class})
public class RhPairingPersistenceTest {
    @Implements(value=NvHTTP.class,isInAndroidSdk=false)
    public static class Http {
        @RealObject NvHTTP real;
        @Implementation protected void __constructor__(ComputerDetails.AddressTuple address,int httpsPort,String id,X509Certificate cert,LimelightCryptoProvider crypto){}
        @Implementation protected PairingManager getPairingManager(){return new PairingManager(real,null);}
        @Implementation protected String getServerInfo(boolean online){return "<root status_code=\"200\"/>";}
        @Implementation protected ComputerDetails getComputerDetails(boolean online){
            ComputerDetails d=new ComputerDetails();d.uuid="synced-host";d.name="PC";d.httpsPort=47984;
            d.state=ComputerDetails.State.ONLINE;d.pairState=PairingManager.PairState.PAIRED;return d;
        }
    }
    @Implements(value=RhNetwork.class,isInAndroidSdk=false)
    public static class NetworkLearning {
        @Implementation protected static void learn(Context context,ComputerDetails details,String address){}
    }
    @Implements(value=PairingManager.class,isInAndroidSdk=false)
    public static class Pair {
        static X509Certificate certificate;
        @Implementation protected void __constructor__(NvHTTP http,LimelightCryptoProvider crypto){}
        @Implementation protected PairingManager.PairState pair(String info,String pin){return PairingManager.PairState.PAIRED;}
        @Implementation protected X509Certificate getPairedCert(){return certificate;}
    }
    @Implements(value=ComputerDatabaseManager.class,isInAndroidSdk=false)
    public static class Db {
        @RealObject ComputerDatabaseManager real;
        static boolean rejectWrites,throwOnWrite;static int rejected;
        @Implementation protected boolean updateComputer(ComputerDetails details){
            if(throwOnWrite){rejected++;throw new android.database.sqlite.SQLiteException("Simulated write failure");}
            if(rejectWrites){rejected++;return false;}
            return Shadow.directlyOn(real,ComputerDatabaseManager.class,"updateComputer",ClassParameter.from(ComputerDetails.class,details));
        }
    }
    @Before public void reset(){Db.rejectWrites=false;Db.throwOnWrite=false;Db.rejected=0;Pair.certificate=null;}
    private void sameCertificate(X509Certificate expected,X509Certificate actual){assertTrue("Saved certificate differs",actual!=null&&expected.equals(actual));}
    private void add(ComputerManagerService service,ComputerDetails device)throws Exception{
        Method method=ComputerManagerService.class.getDeclaredMethod("addTuple",ComputerDetails.class);method.setAccessible(true);method.invoke(service,device);
    }
    private boolean poll(ComputerManagerService service,ComputerDetails device,boolean isNew)throws Exception{
        Method method=ComputerManagerService.class.getDeclaredMethod("runPoll",ComputerDetails.class,boolean.class,int.class);
        method.setAccessible(true);return (boolean)method.invoke(service,device,isNew,0);
    }
    private X509Certificate certificate(String name){
        Context context=RuntimeEnvironment.getApplication();File folder=new File(context.getCacheDir(),name);
        assertTrue(folder.isDirectory()||folder.mkdirs());
        return new AndroidCryptoProvider(new ContextWrapper(context){@Override public File getFilesDir(){return folder;}}).getClientCertificate();
    }
    private ComputerDetails device(){
        ComputerDetails d=new ComputerDetails();d.uuid="synced-host";d.name="PC";
        d.manualAddress=new ComputerDetails.AddressTuple("192.168.0.104",47989);d.activeAddress=d.manualAddress;
        d.httpsPort=47984;d.pairState=PairingManager.PairState.NOT_PAIRED;d.state=ComputerDetails.State.ONLINE;return d;
    }
    private ComputerManagerService service(ComputerDatabaseManager database){
        Context context=RuntimeEnvironment.getApplication();
        ComputerManagerService service=Robolectric.buildService(ComputerManagerService.class).get();
        ReflectionHelpers.setField(service,"dbManager",database);
        ReflectionHelpers.setField(service,"idManager",new IdentityManager(context));
        ((AtomicInteger)ReflectionHelpers.getField(service,"dbRefCount")).set(1);return service;
    }
    private void pair(ComputerManagerService service,ComputerDetails device)throws Exception{
        PcView activity=Robolectric.buildActivity(PcView.class).get();
        ReflectionHelpers.setField(activity,"managerBinder",ReflectionHelpers.getField(service,"binder"));
        Method start=PcView.class.getDeclaredMethod("startRhPair",ComputerDetails.class,String.class);start.setAccessible(true);
        start.invoke(activity,device,"1234");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(ReflectionHelpers.getField(activity,"rhPairOperation")==null)return;
            Thread.sleep(10);
        }
        fail("Pair completion did not finish");
    }
    @Test public void syncedOnlyDeviceIsPersistedBeforeAnyPollAndSurvivesReopeningDatabase()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails input=device();
        RhShared.capture(context,Collections.singletonList(input));String shared=RhShared.canonical(RhShared.read(context));
        Pair.certificate=certificate("paired-host");
        ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            ComputerManagerService service=service(db);
            ComputerManagerService.ComputerManagerBinder binder=ReflectionHelpers.getField(service,"binder");
            assertNull(binder.getComputer(input.uuid));assertNull(db.getComputerByUUID(input.uuid));
            pair(service,input);
            ComputerDetails saved=db.getComputerByUUID(input.uuid);assertNotNull("Successful pairing was not immediately persisted",saved);
            sameCertificate(Pair.certificate,saved.serverCert);assertNotNull(binder.getComputer(input.uuid));
            assertEquals(1,((AtomicInteger)ReflectionHelpers.getField(service,"dbRefCount")).get());
            assertEquals("Pair credentials must not enter the portable config",shared,RhShared.canonical(RhShared.read(context)));
        }finally{db.close();}
        ComputerDatabaseManager reopened=new ComputerDatabaseManager(context);
        try{sameCertificate(Pair.certificate,reopened.getComputerByUUID(input.uuid).serverCert);}finally{reopened.close();}
    }
    @Test public void existingDeviceReplacesItsSavedCertificateWithoutWaitingForAnotherPoll()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails old=device();old.serverCert=certificate("old-host");
        Pair.certificate=certificate("new-host");assertFalse(old.serverCert.equals(Pair.certificate));
        ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            assertTrue(db.updateComputer(old));ComputerManagerService service=service(db);
            add(service,old);
            pair(service,new ComputerDetails(old));
            sameCertificate(Pair.certificate,db.getComputerByUUID(old.uuid).serverCert);
        }finally{db.close();}
    }
    @Test public void databaseFailurePreservesBothOldPinAndNativeState()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails old=device();old.serverCert=certificate("old-host");
        Pair.certificate=certificate("new-host");ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            assertTrue(db.updateComputer(old));ComputerManagerService service=service(db);add(service,old);
            Db.rejectWrites=true;pair(service,new ComputerDetails(old));
            assertEquals(1,Db.rejected);sameCertificate(old.serverCert,db.getComputerByUUID(old.uuid).serverCert);
            ComputerManagerService.ComputerManagerBinder binder=ReflectionHelpers.getField(service,"binder");
            sameCertificate(old.serverCert,binder.getComputer(old.uuid).serverCert);
            assertEquals(1,((AtomicInteger)ReflectionHelpers.getField(service,"dbRefCount")).get());
        }finally{db.close();}
    }
    @Test public void incompletePairingProofCannotClearTheOldPin()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails old=device();old.serverCert=certificate("old-host");
        ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            assertTrue(db.updateComputer(old));ComputerManagerService service=service(db);add(service,old);
            Pair.certificate=null;pair(service,new ComputerDetails(old));
            sameCertificate(old.serverCert,db.getComputerByUUID(old.uuid).serverCert);
            ComputerManagerService.ComputerManagerBinder binder=ReflectionHelpers.getField(service,"binder");
            sameCertificate(old.serverCert,binder.getComputer(old.uuid).serverCert);
        }finally{db.close();}
    }
    @Test public void transientProxyIsReplacedWithTheSavedStableAddress()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails input=device();RhShared.capture(context,Collections.singletonList(input));
        String shared=RhShared.canonical(RhShared.read(context));
        input.manualAddress=new ComputerDetails.AddressTuple("127.77.0.2",48089);input.activeAddress=input.manualAddress;
        Pair.certificate=certificate("paired-host");ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            pair(service(db),input);ComputerDetails saved=db.getComputerByUUID(input.uuid);
            assertNotNull(saved);sameCertificate(Pair.certificate,saved.serverCert);assertEquals("192.168.0.104",saved.manualAddress.address);
            assertEquals(47989,saved.manualAddress.port);assertNull(saved.activeAddress);
            assertEquals("127.77.0.2",input.manualAddress.address);assertEquals(shared,RhShared.canonical(RhShared.read(context)));
        }finally{db.close();}
    }
    @Test public void transportOnlyDeviceKeepsItsIdentityWithoutPersistingAProxyAddress()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails input=device();RhShared.capture(context,Collections.singletonList(input));
        String endpoint="rhr:"+String.join("",Collections.nCopies(64,"a"));
        RhShared.saveAddresses(context,input.uuid,endpoint,new org.json.JSONArray());String shared=RhShared.canonical(RhShared.read(context));
        input.manualAddress=new ComputerDetails.AddressTuple("127.77.0.2",48089);input.activeAddress=input.manualAddress;
        Pair.certificate=certificate("paired-host");ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            pair(service(db),input);ComputerDetails saved=db.getComputerByUUID(input.uuid);assertNotNull(saved);
            sameCertificate(Pair.certificate,saved.serverCert);assertNull(saved.manualAddress);assertNull(saved.localAddress);
            assertNull(saved.remoteAddress);assertNull(saved.ipv6Address);assertEquals(endpoint,RhNetwork.metadata(context,saved).getString("address"));
            assertEquals(shared,RhShared.canonical(RhShared.read(context)));
        }finally{db.close();}
    }
    @Test public void lateDiscoverySnapshotsCannotRestoreThePreviousPairingPin()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails old=device();old.serverCert=certificate("old-host");
        ComputerDetails stalePoll=new ComputerDetails(old),staleAdd=new ComputerDetails(old);
        Pair.certificate=certificate("new-host");ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            assertTrue(db.updateComputer(old));ComputerManagerService service=service(db);add(service,old);
            pair(service,new ComputerDetails(old));sameCertificate(Pair.certificate,db.getComputerByUUID(old.uuid).serverCert);
            assertTrue(poll(service,stalePoll,false));
            sameCertificate(Pair.certificate,db.getComputerByUUID(old.uuid).serverCert);sameCertificate(Pair.certificate,stalePoll.serverCert);
            assertNull("Old HTTPS state must be refreshed with the new pin",stalePoll.pairState);
            add(service,staleAdd);
            ComputerManagerService.ComputerManagerBinder binder=ReflectionHelpers.getField(service,"binder");
            sameCertificate(Pair.certificate,binder.getComputer(old.uuid).serverCert);
            assertNull(staleAdd.pairState);assertEquals(1,((AtomicInteger)ReflectionHelpers.getField(service,"dbRefCount")).get());
        }finally{db.close();}
    }
    @Test public void discoveryCannotIntroduceAPinNotPresentInTheLocalDatabase()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails snapshot=device();snapshot.serverCert=certificate("unowned-snapshot");
        // No STUN is required for this fixture: discovery already supplied a remote address.
        snapshot.remoteAddress=new ComputerDetails.AddressTuple("198.51.100.10",47989);
        ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            ComputerManagerService service=service(db);assertTrue(poll(service,snapshot,true));
            ComputerDetails saved=db.getComputerByUUID(snapshot.uuid);assertNotNull(saved);assertNull(saved.serverCert);
            assertNull(snapshot.serverCert);assertNull(snapshot.pairState);
        }finally{db.close();}
    }
    @Test public void latePollDoesNotRecreateARemovedDevice()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails snapshot=device();snapshot.serverCert=certificate("removed-host");
        ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            ComputerManagerService service=service(db);assertFalse(poll(service,snapshot,false));
            assertNull(db.getComputerByUUID(snapshot.uuid));
            assertEquals(1,((AtomicInteger)ReflectionHelpers.getField(service,"dbRefCount")).get());
        }finally{db.close();}
    }
    @Test public void sqliteExceptionIsReportedAsAStorageFailureWithoutChangingThePin()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails old=device();old.serverCert=certificate("old-host");
        Pair.certificate=certificate("new-host");ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            assertTrue(db.updateComputer(old));ComputerManagerService service=service(db);add(service,old);
            ComputerManagerService.ComputerManagerBinder binder=ReflectionHelpers.getField(service,"binder");
            Db.throwOnWrite=true;
            try{binder.saveRhPairing(old,Pair.certificate);fail("Storage exception was hidden");}
            catch(java.io.IOException expected){assertTrue(expected.getCause() instanceof android.database.sqlite.SQLiteException);}
            sameCertificate(old.serverCert,db.getComputerByUUID(old.uuid).serverCert);sameCertificate(old.serverCert,binder.getComputer(old.uuid).serverCert);
            assertEquals(1,((AtomicInteger)ReflectionHelpers.getField(service,"dbRefCount")).get());
        }finally{db.close();}
    }
    @Test public void replacingATransportOnlyRecordClearsItsOldNativeProxyTuple()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ComputerDetails old=device();RhShared.capture(context,Collections.singletonList(old));
        String endpoint="rhr:"+String.join("",Collections.nCopies(64,"a"));RhShared.saveAddresses(context,old.uuid,endpoint,new org.json.JSONArray());
        old.manualAddress=new ComputerDetails.AddressTuple("127.77.0.2",48089);old.activeAddress=old.manualAddress;old.serverCert=certificate("old-host");
        Pair.certificate=certificate("new-host");ComputerDatabaseManager db=new ComputerDatabaseManager(context);
        try{
            assertTrue(db.updateComputer(old));ComputerManagerService service=service(db);add(service,old);pair(service,new ComputerDetails(old));
            ComputerManagerService.ComputerManagerBinder binder=ReflectionHelpers.getField(service,"binder");
            assertNull(db.getComputerByUUID(old.uuid).manualAddress);assertNull(binder.getComputer(old.uuid).manualAddress);
            sameCertificate(Pair.certificate,binder.getComputer(old.uuid).serverCert);
        }finally{db.close();}
    }
}
