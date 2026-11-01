package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native controls, actual HTTP and Activity recreation on the isolated headless emulator. */
@RunWith(AndroidJUnit4.class)
public class RhSyncFlowDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    Activity activity;RhHome home;
    JSONObject state(){JSONObject[] result={null};instrument.runOnMainSync(()->{try{result[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return result[0];}
    void action(String id,JSONObject args){instrument.runOnMainSync(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(result.toString(),result.optBoolean("ok"));});instrument.waitForIdleSync();}
    View find(View view,String name){if(view.getVisibility()!=View.VISIBLE)return null;if(name.equals(view.getContentDescription())||view instanceof Button&&name.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),name);if(found!=null)return found;}return null;}
    void click(String name){instrument.runOnMainSync(()->{View button=find(activity.findViewById(android.R.id.content),name);assertNotNull(name,button);assertTrue(name,button.isEnabled());android.graphics.Rect visible=new android.graphics.Rect();assertTrue(name,button.getGlobalVisibleRect(visible));assertEquals(name,button.getHeight(),visible.height());assertEquals(name,button.getWidth(),visible.width());assertTrue(button.performClick());});instrument.waitForIdleSync();}
    void idle()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(state().optBoolean("syncBusy")){assertTrue("Sync did not finish",System.nanoTime()<until);Thread.sleep(20);}instrument.waitForIdleSync();}
    void recreate()throws Exception{
        Activity old=activity;instrument.runOnMainSync(old::recreate);Activity[] next={null};long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(next[0]==null){instrument.runOnMainSync(()->{for(Activity value:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(value!=old&&value.getClass().getName().equals("com.limelight.PcView"))next[0]=value;});assertTrue("PcView did not recreate",System.nanoTime()<until);if(next[0]==null)Thread.sleep(20);}
        activity=next[0];Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);instrument.waitForIdleSync();assertEquals("global-settings",state().getString("page"));assertEquals("sync",state().getString("settingsTab"));
    }
    void capture(String name)throws Exception{
        if(!InstrumentationRegistry.getArguments().getString("captureUi","false").equals("true"))return;
        Bitmap[] image={null};instrument.runOnMainSync(()->{View decor=activity.getWindow().getDecorView();image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image[0]));});File folder=activity.getExternalFilesDir("ui-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{image[0].recycle();}
    }
    void evidence(String name,JSONObject result)throws Exception{result.put("width",activity.getWindow().getDecorView().getWidth());result.put("height",activity.getWindow().getDecorView().getHeight());result.put("fontScale",activity.getResources().getConfiguration().fontScale);RhShared.write(new File(activity.getExternalFilesDir("ui-evidence"),name+".json"),result);}
    final class Saved implements AutoCloseable {
        final Context context=instrument.getTargetContext();
        final File account=new File(context.getFilesDir(),"webdav-local.json");
        final byte[] originalAccount=account.exists()?Files.readAllBytes(account.toPath()):null;
        final JSONObject shared=RhShared.read(context);final boolean dark=RhShared.dark(context);
        final SharedPreferences global=context.getSharedPreferences("rhremote.global",0);
        final boolean hadCheck=global.contains("lastUpdateCheck");final long previousCheck=global.getLong("lastUpdateCheck",0);
        Saved()throws Exception{global.edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit();}
        void launch()throws Exception{activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertFalse(RhActions.hasStream());action("app.settings.tab",RhActions.obj("tab","sync"));}
        public void close()throws Exception{
            // Joining the server does not prove the client has delivered its main-thread completion.
            try{Class<?> type=Class.forName("com.limelight.rhremote.RhSyncWork");Field singleton=type.getDeclaredField("shared");singleton.setAccessible(true);Object current=singleton.get(null);java.lang.reflect.Method busy=type.getDeclaredMethod("busy");busy.setAccessible(true);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(current!=null&&(Boolean)busy.invoke(current)){assertTrue("Client sync did not retire before restoring the fixture",System.nanoTime()<until);Thread.sleep(20);}}catch(ClassNotFoundException beforeFix){}
            if(activity!=null)instrument.runOnMainSync(()->activity.finish());instrument.waitForIdleSync();
            RhShared.write(new File(context.getFilesDir(),"rhremote-shared.json"),shared);RhShared.theme(context,dark);
            if(originalAccount==null)new android.util.AtomicFile(account).delete();else RhShared.write(account,new JSONObject(new String(originalAccount,StandardCharsets.UTF_8)));
            if(hadCheck)global.edit().putLong("lastUpdateCheck",previousCheck).commit();else global.edit().remove("lastUpdateCheck").commit();
            // Test-only reset after all requests are joined and the Activity has finished.
            try{Class<?> work=Class.forName("com.limelight.rhremote.RhSyncWork");Field singleton=work.getDeclaredField("shared");singleton.setAccessible(true);singleton.set(null,null);}catch(ClassNotFoundException beforeFix){}
            assertEquals(RhShared.canonical(shared),RhShared.canonical(RhShared.read(context)));
        }
    }
    static final class Fixture implements AutoCloseable {
        final ServerSocket server;final String protocol;
        final ExecutorService workers=Executors.newCachedThreadPool();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),putDone=new CountDownLatch(1);
        final AtomicInteger gets=new AtomicInteger(),puts=new AtomicInteger(),failedHandshakes=new AtomicInteger();
        volatile boolean gate,rejectPut,expectedTlsFailure,resetNextPut;volatile int getStatus;volatile String document;volatile int revision;volatile String hostname="127.0.0.1";
        volatile String authorization="",putCondition="";volatile Throwable failure;
        Fixture()throws IOException{this(new ServerSocket(0,8,InetAddress.getByName("127.0.0.1")),"http");}
        Fixture(ServerSocket listener,String scheme){server=listener;protocol=scheme;workers.execute(()->{try{while(!server.isClosed()){Socket socket=server.accept();workers.execute(()->reply(socket));}}catch(IOException stopped){if(!server.isClosed())failure=stopped;}});}
        String url(){return protocol+"://"+hostname+":"+server.getLocalPort()+"/dav";}
        String etag(){return "\"revision-"+revision+"\"";}
        synchronized void remote(JSONObject value){document=value.toString();revision++;}
        void reply(Socket socket){try(Socket owned=socket){socket.setSoTimeout(10000);InputStream in=socket.getInputStream();String first=line(in);Map<String,String> headers=new HashMap<>();String header;while(!(header=line(in)).isEmpty()){int split=header.indexOf(':');if(split>0)headers.put(header.substring(0,split).toLowerCase(Locale.ROOT),header.substring(split+1).trim());}authorization=headers.get("authorization");
            int status;String body="",tag=null;
            if(first.startsWith("GET ")){gets.incrementAndGet();entered.countDown();if(gate&&!release.await(10,TimeUnit.SECONDS))throw new IOException("GET gate expired");status=first.contains("/redirect-target ")?200:getStatus!=0?getStatus:document==null?404:200;if(status==200){body=document;tag=etag();}}
            else if(first.startsWith("PUT ")){int length=Integer.parseInt(headers.getOrDefault("content-length","0"));assertTrue(length<=2*1024*1024);byte[] bytes=new byte[length];for(int offset=0;offset<length;){int read=in.read(bytes,offset,length-offset);if(read<0)throw new EOFException();offset+=read;}putCondition=document==null?headers.get("if-none-match"):headers.get("if-match");if(rejectPut||!Objects.equals(document==null?"*":etag(),putCondition))status=412;else{JSONObject value=new JSONObject(new String(bytes,StandardCharsets.UTF_8));RhShared.validate(value);remote(value);puts.incrementAndGet();status=204;if(resetNextPut){resetNextPut=false;putDone.countDown();owned.setSoLinger(true,0);return;}}putDone.countDown();}
            else status=405;
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+status+" Fixture\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n"+(tag==null?"":"ETag: "+tag+"\r\n")+(status==302?"Location: "+url()+"/redirect-target\r\n":"")+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }catch(SSLException|EOFException rejectedPeer){if(expectedTlsFailure)failedHandshakes.incrementAndGet();else if(!server.isClosed())failure=rejectedPeer;}catch(Throwable error){if(!server.isClosed())failure=error;}}
        static String line(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(int value;(value=in.read())!=-1;){if(value=='\n')return out.toString("US-ASCII").replace("\r","");if(out.size()>8192)throw new IOException("Header too long");out.write(value);}throw new EOFException();}
        public void close()throws Exception{release.countDown();server.close();workers.shutdown();assertTrue(workers.awaitTermination(12,TimeUnit.SECONDS));if(failure!=null)throw new AssertionError(failure);}
    }
    void configure(Fixture fixture){action("sync.configure",RhActions.obj("url",fixture.url(),"username","ui-sync","password","isolated-fixture-password","automatic",false,"intervalMinutes",15));}
    static final class Tls {
        final SSLContext server=SSLContext.getInstance("TLS"),trusted=SSLContext.getInstance("TLS");
        Tls()throws Exception{
            java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);java.security.KeyPair identity=generator.generateKeyPair();
            org.bouncycastle.asn1.x500.X500Name name=new org.bouncycastle.asn1.x500.X500Name("CN=localhost");
            org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder builder=new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(name,java.math.BigInteger.valueOf(System.currentTimeMillis()),new Date(System.currentTimeMillis()-60000),new Date(System.currentTimeMillis()+3600000),name,identity.getPublic());
            builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,false,new org.bouncycastle.asn1.x509.GeneralNames(new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName,"localhost")));
            java.security.cert.X509Certificate certificate=new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(builder.build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(identity.getPrivate())));
            java.security.KeyStore keys=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());keys.load(null);keys.setKeyEntry("fixture",identity.getPrivate(),"fixture".toCharArray(),new java.security.cert.Certificate[]{certificate});
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"fixture".toCharArray());server.init(km.getKeyManagers(),null,null);
            java.security.KeyStore anchors=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());anchors.load(null);anchors.setCertificateEntry("fixture",certificate);TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(anchors);trusted.init(null,tm.getTrustManagers(),null);
        }
    }

    @Test public void inFlightRequestSurvivesRecreationAndRejectsDuplicateSync()throws Exception{
        try(Saved saved=new Saved();Fixture fixture=new Fixture()){
            saved.launch();configure(fixture);String before=RhShared.canonical(RhShared.read(saved.context));fixture.gate=true;
            try{
                click("立即同步");assertTrue(fixture.entered.await(3,TimeUnit.SECONDS));assertTrue(state().getBoolean("syncBusy"));capture("sync-running-before-recreation");
                recreate();assertTrue("An existing request must remain busy after recreation",state().getBoolean("syncBusy"));
                JSONObject[] duplicate={null};instrument.runOnMainSync(()->duplicate[0]=RhActions.dispatch("sync.run",RhActions.obj()));assertFalse(duplicate[0].toString(),duplicate[0].optBoolean("ok"));assertEquals(1,fixture.gets.get());capture("sync-running-after-recreation");
            }finally{fixture.release.countDown();assertTrue(fixture.putDone.await(8,TimeUnit.SECONDS));idle();}
            assertEquals(1,fixture.gets.get());assertEquals(1,fixture.puts.get());assertEquals("*",fixture.putCondition);assertTrue(state().getJSONObject("sync").getLong("lastSyncMs")>0);assertEquals(before,RhShared.canonical(RhShared.read(saved.context)));assertEquals("同步完成",state().getString("status"));capture("sync-completed-current-page");
            evidence("sync-inflight-flow",RhActions.obj("realRecreations",1,"getRequests",fixture.gets.get(),"putRequests",fixture.puts.get(),"duplicateRejected",true,"configurationPreserved",true,"completedOnCurrentPage",true));
        }
    }
    @Test public void failureAndSavedAccountSurviveRecreationAndNativeRetrySucceeds()throws Exception{
        try(Saved saved=new Saved();Fixture fixture=new Fixture()){
            saved.launch();configure(fixture);byte[] account=Files.readAllBytes(saved.account.toPath());String before=RhShared.canonical(RhShared.read(saved.context));fixture.getStatus=401;
            click("立即同步");idle();String error=state().getString("syncError");assertFalse(error.isEmpty());assertEquals(0,fixture.puts.get());capture("sync-authentication-failed");
            recreate();assertEquals(error,state().getString("syncError"));assertArrayEquals(account,Files.readAllBytes(saved.account.toPath()));assertEquals(before,RhShared.canonical(RhShared.read(saved.context)));capture("sync-error-after-recreation");
            fixture.getStatus=0;click("立即同步");idle();assertEquals("",state().getString("syncError"));assertEquals(2,fixture.gets.get());assertEquals(1,fixture.puts.get());assertTrue(state().getJSONObject("sync").getBoolean("hasPassword"));assertEquals(before,RhShared.canonical(RhShared.read(saved.context)));capture("sync-retry-completed");
            evidence("sync-retry-flow",RhActions.obj("realRecreations",1,"errorPreserved",true,"credentialsPreserved",true,"getRequests",fixture.gets.get(),"putRequests",fixture.puts.get(),"configurationPreserved",true));
        }
    }
    @Test public void conflictReviewSurvivesRecreationAndResolvesTheDisplayedVersion()throws Exception{
        try(Saved saved=new Saved();Fixture fixture=new Fixture()){
            saved.launch();configure(fixture);click("立即同步");idle();assertEquals(1,fixture.puts.get());JSONObject baseline=new JSONObject(fixture.document);JSONArray devices=baseline.getJSONArray("devices");assertTrue(devices.length()>0);String uuid=devices.getJSONObject(0).getString("uuid");
            JSONObject remote=new JSONObject(baseline.toString());remote.getJSONArray("devices").getJSONObject(0).put("displayName","远端名称");fixture.remote(remote);
            JSONObject local=RhShared.read(saved.context);local.getJSONArray("devices").getJSONObject(0).put("displayName","本机名称");RhShared.apply(saved.context,local);String unchanged=RhShared.canonical(RhShared.read(saved.context));
            click("立即同步");idle();JSONArray conflicts=state().getJSONObject("sync").getJSONArray("conflicts");assertTrue(conflicts.toString(),conflicts.length()>0);assertEquals(1,fixture.puts.get());capture("sync-conflict-review");
            recreate();assertEquals(conflicts.toString(),state().getJSONObject("sync").getJSONArray("conflicts").toString());assertEquals(unchanged,RhShared.canonical(RhShared.read(saved.context)));capture("sync-conflict-after-recreation");
            click("冲突项保留远端");idle();assertEquals(2,fixture.puts.get());assertEquals("远端名称",RhShared.read(saved.context).getJSONArray("devices").getJSONObject(0).getString("displayName"));assertEquals(0,state().getJSONObject("sync").getJSONArray("conflicts").length());assertEquals(uuid,RhShared.read(saved.context).getJSONArray("devices").getJSONObject(0).getString("uuid"));capture("sync-conflict-resolved");
            evidence("sync-conflict-flow",RhActions.obj("realRecreations",1,"conflictRetained",true,"explicitRemoteResolution",true,"putRequests",fixture.puts.get(),"conditionalHeader",fixture.putCondition));
        }
    }
    @Test public void actualHttpsRejectsAnUntrustedCertificateAndKeepsConditionalWritesSafe()throws Exception{
        Tls tls=new Tls();SSLSocketFactory previous=HttpsURLConnection.getDefaultSSLSocketFactory();
        try(Saved saved=new Saved();Fixture fixture=new Fixture(tls.server.getServerSocketFactory().createServerSocket(0,8,InetAddress.getByName("127.0.0.1")),"https")){
            saved.launch();fixture.hostname="localhost";configure(fixture);String before=RhShared.canonical(RhShared.read(saved.context));byte[] account=Files.readAllBytes(saved.account.toPath());fixture.expectedTlsFailure=true;
            click("立即同步");idle();assertTrue(state().getString("syncError"),state().getString("syncError").contains("证书"));assertEquals(0,fixture.gets.get());assertEquals(0,fixture.puts.get());assertEquals("",fixture.authorization);assertArrayEquals(account,Files.readAllBytes(saved.account.toPath()));capture("sync-untrusted-certificate");
            recreate();assertTrue(state().getString("syncError").contains("证书"));assertEquals(before,RhShared.canonical(RhShared.read(saved.context)));
            // Only this isolated instrumentation process temporarily trusts its own
            // ephemeral certificate. Hostname checks stay enabled; no OS CA is installed.
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.trusted.getSocketFactory());
            fixture.hostname="127.0.0.1";configure(fixture);byte[] mismatchAccount=Files.readAllBytes(saved.account.toPath());click("立即同步");idle();assertTrue(state().getString("syncError"),state().getString("syncError").contains("证书"));assertEquals(0,fixture.gets.get());assertEquals("",fixture.authorization);assertArrayEquals(mismatchAccount,Files.readAllBytes(saved.account.toPath()));capture("sync-https-hostname-rejected");
            long handshakeDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(fixture.failedHandshakes.get()<2){assertTrue("Server did not retire rejected TLS peers",System.nanoTime()<handshakeDeadline);Thread.sleep(20);}fixture.expectedTlsFailure=false;fixture.hostname="localhost";configure(fixture);
            click("立即同步");idle();assertEquals("",state().getString("syncError"));assertEquals(1,fixture.gets.get());assertEquals(1,fixture.puts.get());assertEquals("*",fixture.putCondition);assertEquals(before,RhShared.canonical(RhShared.read(saved.context)));capture("sync-https-completed");
            long last=state().getJSONObject("sync").getLong("lastSyncMs");String remote=fixture.document;fixture.rejectPut=true;click("立即同步");idle();assertTrue(state().getString("syncError"),state().getString("syncError").contains("412"));assertEquals(fixture.etag(),fixture.putCondition);assertEquals(1,fixture.puts.get());assertEquals(remote,fixture.document);assertEquals(last,state().getJSONObject("sync").getLong("lastSyncMs"));assertEquals(before,RhShared.canonical(RhShared.read(saved.context)));capture("sync-https-precondition-failed");
            fixture.rejectPut=false;fixture.getStatus=302;int requests=fixture.gets.get();click("立即同步");idle();assertTrue(state().getString("syncError"),state().getString("syncError").contains("最终目录"));assertEquals(requests+1,fixture.gets.get());assertEquals(1,fixture.puts.get());assertEquals(remote,fixture.document);capture("sync-https-redirect-refused");
            evidence("sync-https-flow",RhActions.obj("realRecreations",1,"realTls",true,"untrustedCertificateRejected",true,"hostnameMismatchRejected",true,"noAuthSentOnFailedHandshake",true,"explicitFixtureTrustOnly",true,"conditionalPutValidated",true,"preconditionFailurePreservedLocalAndRemote",true,"redirectNotFollowed",true,"getRequests",fixture.gets.get(),"putRequests",fixture.puts.get()));
        }finally{HttpsURLConnection.setDefaultSSLSocketFactory(previous);}
    }
}
