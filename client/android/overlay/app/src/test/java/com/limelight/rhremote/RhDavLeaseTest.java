package com.limelight.rhremote;

import android.app.Activity;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real TCP, HttpURLConnection and OkHttp; only stored-password decryption is a fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhDavLeaseTest.Password.class})
public class RhDavLeaseTest {
    @Implements(value=RhSync.class,isInAndroidSdk=false)
    public static class Password {@Implementation protected String password(JSONObject account){return "owned-test-password";}}
    Activity activity;JSONObject original;
    @Before public void setup()throws Exception {
        activity=Robolectric.buildActivity(Activity.class).setup().get();original=RhShared.read(activity);
        activity.getSharedPreferences("rhremote.sync.operations",0).edit().clear().commit();
        java.lang.reflect.Field f=RhSyncWork.class.getDeclaredField("shared");f.setAccessible(true);f.set(null,null);
    }
    private RhSync configure(Peer peer,JSONObject base)throws Exception {
        JSONObject metadata=RhActions.obj("url",peer.url.substring(0,peer.url.lastIndexOf('/')),"username","owned-test","ciphertext","fixture-only","automatic",false,"lastSyncMs",1);
        if(base!=null)metadata.put("base",base);
        RhShared.write(new File(activity.getFilesDir(),"webdav-local.json"),metadata);
        return new RhSync(activity);
    }
    private static final String TOKEN="<1793160000000000000>";
    static final class Peer implements AutoCloseable {
        final ServerSocket server;final Thread thread;final String url;
        final List<String> methods=Collections.synchronizedList(new ArrayList<>());
        final List<String> conditions=Collections.synchronizedList(new ArrayList<>());
        volatile boolean stop,locked,classOne,busy,failUnlock,failPut,materialize,malformed;
        volatile byte[] document;volatile Throwable failure;int revision;
        Peer(JSONObject initial)throws Exception {
            document=initial==null?null:initial.toString().getBytes(StandardCharsets.UTF_8);server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(100);url="http://127.0.0.1:"+server.getLocalPort()+"/dav/rhremote-config-v1.json";
            thread=new Thread(this::serve,"owned WebDAV protocol peer");thread.start();
        }
        void serve(){
            try{while(!stop){Socket accepted;try{accepted=server.accept();}catch(SocketTimeoutException retry){continue;}try(Socket socket=accepted){socket.setSoTimeout(5000);handle(socket);}}}
            catch(Throwable invalid){if(!stop)failure=invalid;}
        }
        void handle(Socket socket)throws Exception {
            BufferedInputStream input=new BufferedInputStream(socket.getInputStream());String first=line(input);String method=first.split(" ")[0];Map<String,String> headers=new HashMap<>();
            while(true){String header=line(input);if(header.isEmpty())break;int colon=header.indexOf(':');assertTrue(colon>0);headers.put(header.substring(0,colon).toLowerCase(Locale.ROOT),header.substring(colon+1).trim());}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));assertTrue(length>=0&&length<2*1024*1024);byte[] request=new byte[length];new DataInputStream(input).readFully(request);
            assertTrue(headers.getOrDefault("authorization","").startsWith("Basic "));methods.add(method);
            int code=200;byte[] body=new byte[0];Map<String,String> outgoing=new LinkedHashMap<>();
            switch(method){
                case "OPTIONS":outgoing.put("DAV",classOne?"1":"1, 2");break;
                case "LOCK":if(locked||busy)code=423;else{locked=true;outgoing.put("Lock-Token",malformed?"<bad> <two>":TOKEN);if(materialize&&document==null){document=new byte[0];code=201;}}break;
                case "GET":if(document==null)code=404;else{body=document;outgoing.put("ETag","\"revision-"+revision+"\"");}break;
                case "PUT":
                    conditions.add(headers.get("if"));
                    if(!classOne&&(!locked||!("("+TOKEN+")").equals(headers.get("if"))))code=423;
                    else if(failPut)code=412;else{document=request;revision++;code=201;}break;
                case "UNLOCK":assertEquals(TOKEN,headers.get("lock-token"));if(failUnlock)code=503;else{locked=false;code=204;}break;
                default:throw new AssertionError("Unexpected method "+method);
            }
            StringBuilder response=new StringBuilder("HTTP/1.1 ").append(code).append(" Result\r\nConnection: close\r\nContent-Length: ").append(body.length).append("\r\n");for(Map.Entry<String,String> entry:outgoing.entrySet())response.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");response.append("\r\n");
            OutputStream output=socket.getOutputStream();output.write(response.toString().getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
        }
        static String line(InputStream input)throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;while((value=input.read())!=-1){if(value=='\n')break;bytes.write(value);if(bytes.size()>16384)throw new IOException("Oversized HTTP test header");}if(value==-1&&bytes.size()==0)throw new EOFException();String line=new String(bytes.toByteArray(),StandardCharsets.US_ASCII);return line.endsWith("\r")?line.substring(0,line.length()-1):line;
        }
        @Override public void close()throws Exception {stop=true;server.close();thread.join(5000);assertFalse("Protocol peer did not stop",thread.isAlive());if(failure!=null)throw new AssertionError(failure);}
    }
    @Test public void classTwoUsesTheTokenAndDoesNotAdvanceSuccessBeforeLocalApplication()throws Exception {
        try(Peer peer=new Peer(original)){
            RhSync sync=configure(peer,original);JSONObject result=sync.run(original,"review");assertTrue(result.has("shared"));assertEquals(Arrays.asList("OPTIONS","LOCK","GET","PUT","UNLOCK"),peer.methods);assertFalse(peer.locked);assertEquals("("+TOKEN+")",peer.conditions.get(0));assertEquals(1,sync.state().getLong("lastSyncMs"));
            RhShared.apply(activity,result.getJSONObject("shared"));sync.applied(result.getJSONObject("shared"));assertTrue(sync.state().getLong("lastSyncMs")>1);
            sync.run(original,"review");assertEquals(1,Collections.frequency(peer.methods,"OPTIONS"));assertEquals(2,Collections.frequency(peer.methods,"LOCK"));assertFalse(peer.locked);
        }
    }
    @Test public void classOneKeepsTheConditionalWritePathWithoutLockRequests()throws Exception {
        try(Peer peer=new Peer(original)){peer.classOne=true;RhSync sync=configure(peer,original);sync.run(original,"review");assertEquals(Arrays.asList("OPTIONS","GET","PUT"),peer.methods);assertNull(peer.conditions.get(0));}
    }
    @Test public void aLockCreatedEmptyResourceCanBeInitialized()throws Exception {
        try(Peer peer=new Peer(null)){peer.materialize=true;RhSync sync=configure(peer,null);sync.run(original,"review");RhShared.validate(new JSONObject(new String(peer.document,StandardCharsets.UTF_8)));assertFalse(peer.locked);assertEquals(1,Collections.frequency(peer.methods,"UNLOCK"));}
    }
    @Test public void busyOrRejectedWritesKeepTheOriginalBaseAndReleaseOwnedLocks()throws Exception {
        for(boolean busy:new boolean[]{true,false})try(Peer peer=new Peer(original)){
            peer.busy=busy;peer.failPut=!busy;RhSync sync=configure(peer,original);
            try{sync.run(original,"review");fail("Rejected operation reported success");}catch(IOException expected){String message=RhSync.failureMessage(expected);assertTrue(message,message.contains(busy?"另一台设备":"重新同步"));}
            assertEquals(1,sync.state().getLong("lastSyncMs"));assertFalse(sync.state().getBoolean("awaitingLocalApply"));assertEquals(busy?0:1,Collections.frequency(peer.methods,"UNLOCK"));
        }
    }
    @Test public void anUnlockFailureAfterPutPausesAutomaticSyncAndSurvivesAColdOwner()throws Exception {
        try(Peer peer=new Peer(original)){
            peer.failUnlock=true;configure(peer,original);RhSyncWork work=RhSyncWork.shared(activity);List<Runnable> queued=new ArrayList<>();work.start(original,"review",queued::add);queued.remove(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(work.busy());assertTrue(work.error(),work.error().contains("未能释放"));assertTrue(work.state().getBoolean("automaticBlocked"));assertEquals(1,work.state().getLong("lastSyncMs"));assertEquals(RhShared.canonical(original),RhShared.canonical(RhShared.read(activity)));
            java.lang.reflect.Field f=RhSyncWork.class.getDeclaredField("shared");f.setAccessible(true);f.set(null,null);assertEquals("interrupted",RhSyncWork.shared(activity).phase());assertEquals(1,Collections.frequency(peer.methods,"UNLOCK"));
        }
    }
    @Test public void malformedHeaderNeverBecomesAnOutgoingConditionOrPublicErrorValue()throws Exception {
        try(Peer peer=new Peer(original)){
            peer.malformed=true;RhSync sync=configure(peer,original);
            try{sync.run(original,"review");fail("Malformed token accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("凭据"));assertFalse(expected.getMessage().contains("<bad>"));}
            assertEquals(Arrays.asList("OPTIONS","LOCK"),peer.methods);assertFalse(sync.state().getBoolean("awaitingLocalApply"));
        }
    }
}
