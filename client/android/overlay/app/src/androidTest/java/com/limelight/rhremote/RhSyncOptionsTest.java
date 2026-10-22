package com.limelight.rhremote;
import org.json.JSONObject;
import org.junit.Test;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;
public final class RhSyncOptionsTest {
 @Test public void httpEndpointsAreAcceptedWithoutEmbeddedCredentials()throws Exception{
  assertEquals("http://192.168.0.104/dav/rhremote-config-v1.json",RhSync.endpoint("http://192.168.0.104/dav").toString());
  assertEquals("https://example.com/dav/rhremote-config-v1.json",RhSync.endpoint("https://example.com/dav/").toString());
  for(String url:new String[]{"ftp://example.com/dav","http://user:password@example.com/dav","http://example.com/dav?token=x"}){try{RhSync.endpoint(url);fail("invalid endpoint accepted");}catch(java.io.IOException|java.net.URISyntaxException expected){}}
 }
 @Test public void intervalsValidateAndDefaultToFiveMinutes()throws Exception{
  for(int n:new int[]{5,15,30,60})assertEquals(n,RhSync.interval(RhActions.obj("intervalMinutes",n),5));
  assertEquals(5,RhSync.interval(new JSONObject(),0));assertEquals(30,RhSync.interval(new JSONObject(),30));
  for(Object value:new Object[]{0,10,"15",JSONObject.NULL}){try{RhSync.interval(RhActions.obj("intervalMinutes",value),5);fail("invalid interval accepted");}catch(java.io.IOException expected){}}
 }
 @Test public void httpRoundTripPersistsIntervalWithoutTouchingRealAccount()throws Exception{
  Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();String prefix="rhremote-http-test-"+UUID.randomUUID();File dir=new File(base.getCacheDir(),prefix);assertTrue(dir.mkdir());
  Context context=new ContextWrapper(base){@Override public File getFilesDir(){return dir;}@Override public Context getApplicationContext(){return this;}@Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefix+name,mode);}};
  try(ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){
   server.setSoTimeout(10000);final Throwable[] failure={null};
   Thread fixture=new Thread(()->{byte[] document=null;try{for(int n=0;n<4;n++)try(Socket socket=server.accept()){
    socket.setSoTimeout(5000);InputStream input=socket.getInputStream();String request=line(input),header;int length=0;boolean conditional=false;
    while(!(header=line(input)).isEmpty()){if(header.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());if(header.toLowerCase().startsWith("if-match:")||header.toLowerCase().startsWith("if-none-match:"))conditional=true;}
    byte[] body;int code;if(request.startsWith("PUT ")){if(!conditional)throw new IOException("missing conditional write");document=new byte[length];new DataInputStream(input).readFully(document);body=new byte[0];code=204;}else{body=document==null?new byte[0]:document;code=document==null?404:200;}
    OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" Result\r\nETag: \"fixture\"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
   }}catch(Throwable e){failure[0]=e;}},"RHRemote HTTP test fixture");fixture.start();
   RhShared.initialize(context);RhSync sync=new RhSync(context);String address="http://127.0.0.1:"+server.getLocalPort()+"/dav/";
   sync.configure(RhActions.obj("url",address,"username","fixture","password","test-only-placeholder","automatic",false,"intervalMinutes",15));
   assertEquals(15,new RhSync(context).state().getInt("intervalMinutes"));assertFalse(sync.state().toString().contains("test-only-placeholder"));
   JSONObject result=sync.run(RhShared.read(context),"review");assertEquals(0,result.getJSONArray("conflicts").length());
   sync.configure(RhActions.obj("url",address,"username","fixture","automatic",false,"intervalMinutes",60));assertEquals(60,sync.state().getInt("intervalMinutes"));
   result=sync.run(RhShared.read(context),"review");assertEquals(0,result.getJSONArray("conflicts").length());fixture.join(10000);assertFalse(fixture.isAlive());if(failure[0]!=null)throw new AssertionError(failure[0]);
  }finally{for(String name:new String[]{"rhremote.global","rhremote.devices"})base.getSharedPreferences(prefix+name,0).edit().clear().commit();delete(dir);}
 }
 private static String line(InputStream input)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();int b;while((b=input.read())!=-1){if(b=='\n')break;if(b!='\r')out.write(b);if(out.size()>8192)throw new IOException("header too large");}return out.toString("US-ASCII");}
 private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
}
