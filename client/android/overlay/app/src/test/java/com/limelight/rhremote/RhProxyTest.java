package com.limelight.rhremote;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhProxyTest.Controls.class})
public class RhProxyTest {
    @org.robolectric.annotation.Implements(value=RhActions.class,isInAndroidSdk=false)
    public static class Controls {
        private static RhActions.Target owner;
        @org.robolectric.annotation.Implementation protected static void initialize(android.content.Context context,RhActions.Target target){owner=target;}
        @org.robolectric.annotation.Implementation protected static void click(String id,JSONObject args){try{owner.perform(id,args);}catch(Exception error){throw new IllegalStateException(error);}}
        @org.robolectric.annotation.Implementation protected static boolean ownsHome(RhActions.Target target){return target==owner;}
    }
    Activity activity;File file;
    private void layout(){View view=activity.getWindow().getDecorView();view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));view.layout(0,0,1080,1600);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}

    @Before public void setup(){activity=Robolectric.buildActivity(Activity.class).setup().get();file=new File(activity.getFilesDir(),"proxy-local.json");new android.util.AtomicFile(file).delete();}
    @After public void cleanup(){new android.util.AtomicFile(file).delete();}
    @Test public void localPreferencesAndScopesSurviveRestart()throws Exception {
        JSONObject value=RhProxy.load(activity);assertFalse(value.getBoolean("enabled"));assertEquals("http",value.getString("protocol"));assertEquals("127.0.0.1",value.getString("host"));assertEquals("7890",value.getString("port"));
        value.put("enabled",true).put("sync",false);RhProxy.configure(activity,value);
        assertEquals("http://127.0.0.1:7890",RhProxy.url(activity,"updates"));assertNull(RhProxy.url(activity,"sync"));assertEquals("http://127.0.0.1:7890",RhProxy.url(activity,"remote"));
        assertTrue(RhProxy.load(activity).getBoolean("enabled"));
    }
    @Test public void malformedAddressesAndPortsDoNotOverwriteSavedPreferences()throws Exception {
        RhProxy.configure(activity,RhProxy.defaults());
        for(String host:new String[]{"http://localhost","host:7890","user@host","a/b","host\r\nX:1",""}){JSONObject value=RhProxy.defaults().put("host",host);try{RhProxy.configure(activity,value);fail(host);}catch(IOException expected){}}
        for(String port:new String[]{"0","-1","65536","7890x",""})try{RhProxy.configure(activity,RhProxy.defaults().put("port",port));fail(port);}catch(IOException expected){}
        assertFalse(RhProxy.load(activity).getBoolean("enabled"));assertEquals("7890",RhProxy.load(activity).getString("port"));
    }
    private RhHome home(){return new RhHome(activity,new RhHome.Actions(){public void connect(com.limelight.nvstream.http.ComputerDetails d){}public void add(String a){}public void advanced(){}public void pair(com.limelight.nvstream.http.ComputerDetails d,String p){}public void cancelPair(){}public void refresh(){}});}
    @Test public void tabProtocolAndFieldActionsSaveTheVisibleSelection()throws Exception {
        RhHome home=home();home.perform("app.settings.tab",RhActions.obj("tab","proxy"));layout();Spinner choice=(Spinner)find(activity.getWindow().getDecorView(),"proxy.edit.protocol");assertNotNull(choice);assertEquals(0,choice.getSelectedItemPosition());
        choice.setSelection(1);layout();assertEquals("https",home.state().getJSONObject("proxyDraft").getString("protocol"));
        home.perform("proxy.edit",RhActions.obj("key","enabled","value",true));home.perform("proxy.edit",RhActions.obj("key","port","value","8443"));home.perform("proxy.save",RhActions.obj());
        assertFalse(home.state().getBoolean("proxyDirty"));assertEquals("https://127.0.0.1:8443",RhProxy.url(activity,"updates"));
        home.perform("proxy.edit",RhActions.obj("key","host","value","changed.invalid"));home.perform("app.settings.tab",RhActions.obj("tab","updates"));home.perform("app.settings.tab",RhActions.obj("tab","proxy"));assertEquals("changed.invalid",home.state().getJSONObject("proxyDraft").getString("host"));home.perform("proxy.cancel",RhActions.obj());assertEquals("127.0.0.1",RhProxy.load(activity).getString("host"));
    }
    private static View find(View view,String tag){if(tag.equals(view.getTag()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),tag);if(result!=null)return result;}return null;}
    private void setProxy(int port)throws Exception {RhProxy.configure(activity,RhProxy.defaults().put("enabled",true).put("port",String.valueOf(port)));}
    @Test public void selectedHttpProxyReceivesTheRealRequestWithoutOriginDns()throws Exception {
        try(ServerSocket proxy=new ServerSocket(0,4,InetAddress.getLoopbackAddress())){
            proxy.setSoTimeout(5000);setProxy(proxy.getLocalPort());CompletableFuture<String> request=CompletableFuture.supplyAsync(()->{try(Socket socket=proxy.accept()){BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String first=reader.readLine();while(!reader.readLine().isEmpty()){}socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));return first;}catch(IOException e){throw new CompletionException(e);}});
            HttpURLConnection connection=RhProxy.open(activity,new URL("http://does-not-resolve.invalid/test"),"sync");connection.setReadTimeout(5000);try{assertEquals(200,connection.getResponseCode());assertEquals("ok",new String(connection.getInputStream().readAllBytes(),StandardCharsets.UTF_8));}finally{connection.disconnect();}
            assertEquals("GET http://does-not-resolve.invalid/test HTTP/1.1",request.get(5,TimeUnit.SECONDS));
        }
    }
    @Test public void davLockAndUnlockBothUseTheSelectedProxy()throws Exception {
        try(ServerSocket proxy=new ServerSocket(0,4,InetAddress.getLoopbackAddress())){
            proxy.setSoTimeout(5000);setProxy(proxy.getLocalPort());CompletableFuture<List<String>> requests=CompletableFuture.supplyAsync(()->{try{List<String> seen=new ArrayList<>();for(int i=0;i<2;i++)try(Socket socket=proxy.accept()){BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));seen.add(reader.readLine());String line;int length=0;while(!(line=reader.readLine()).isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());for(int n=0;n<length;n++)reader.read();String response=i==0?"HTTP/1.1 201 Created\r\nLock-Token: <opaquelocktoken:test>\r\nContent-Length: 0\r\nConnection: close\r\n\r\n":"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n";socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));}return seen;}catch(IOException e){throw new CompletionException(e);}});
            try(RhDavLease lease=RhDavLease.acquire(new URL("http://does-not-resolve.invalid/dav/config.json"),"Basic test",RhProxy.route(activity,"sync"))){assertEquals("(<opaquelocktoken:test>)",lease.condition());}
            List<String> lines=requests.get(5,TimeUnit.SECONDS);assertTrue(lines.get(0),lines.get(0).startsWith("LOCK http://does-not-resolve.invalid/"));assertTrue(lines.get(1),lines.get(1).startsWith("UNLOCK http://does-not-resolve.invalid/"));
        }
    }
}
