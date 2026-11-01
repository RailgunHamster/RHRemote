package com.limelight.rhremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native result-page fixtures; no host connection, pairing or network interruption is claimed. */
public final class RhConnectionErrorDeviceTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final String uuid="ui-connection-error85";
    private Activity activity;
    private RhHome home;
    private File folder;
    private void main(Runnable action){instrument.runOnMainSync(action);instrument.waitForIdleSync();}
    private void bind(Activity value)throws Exception{
        activity=value;Field field=RhActions.class.getDeclaredField("home");field.setAccessible(true);home=(RhHome)((WeakReference<?>)field.get(null)).get();assertNotNull(home);
    }
    private Activity resumed(){final Activity[] value={null};main(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a.getClass().getName().equals("com.limelight.PcView"))value[0]=a;});return value[0];}
    private JSONObject state(){final JSONObject[] value={null};main(()->{try{value[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return value[0];}
    private View root(){return ((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);}
    private View find(View view,String label,boolean description){
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(description&&label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())||!description&&view instanceof Button&&label.contentEquals(((Button)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label,description);if(found!=null)return found;}return null;
    }
    private JSONObject buttonBounds(String label)throws Exception{
        final JSONObject[] result={null};main(()->{Button button=(Button)find(root(),label,false);assertNotNull(label,button);Rect visible=new Rect();assertTrue(label,button.getGlobalVisibleRect(visible));assertEquals(label,button.getWidth(),visible.width());assertEquals(label,button.getHeight(),visible.height());assertTrue(label,button.isEnabled());assertTrue(label,button.getHeight()>=48*button.getResources().getDisplayMetrics().density);result[0]=RhActions.obj("label",label,"x",visible.left,"y",visible.top,"width",visible.width(),"height",visible.height(),"enabled",true);});return result[0];
    }
    private void capture(String name)throws Exception{
        final Bitmap[] frame={null};main(()->{View view=root();frame[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(frame[0]));});try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(frame[0].compress(Bitmap.CompressFormat.PNG,100,out));}finally{frame[0].recycle();}
    }
    private String fingerprint(Context context)throws Exception{return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(RhShared.canonical(RhShared.read(context)).getBytes(java.nio.charset.StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP);}
    @Test public void anActualUuidMismatchReachesTheFailurePageWithoutAnOldError()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("connectionErrorWireRun","");org.junit.Assume.assumeFalse("Explicit dedicated emulator run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhSessionFlowDeviceTest holder=new RhSessionFlowDeviceTest();holder.folder=folder;JSONArray cases=new JSONArray();
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(holder,run)){
            main(()->{try{JSONObject shared=RhShared.read(context);shared.getJSONArray("devices").put(RhActions.obj("uuid",uuid,"name","UI result fixture","displayName","","address","127.0.0.1","addresses",new JSONArray()));RhShared.apply(context,shared);}catch(Exception e){throw new RuntimeException(e);}});
            for(boolean dark:new boolean[]{false,true}){
                main(()->{try{RhShared.theme(context,dark);RhShared.capture(context,java.util.Collections.emptyList());}catch(Exception e){throw new RuntimeException(e);}});bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();
                main(()->{try{home.perform("device.settings.open",RhActions.obj("uuid",uuid));home.perform("settings.set",RhActions.obj("key","routeOrder","value",new JSONArray().put("lan")));home.perform("settings.network.edit",RhActions.obj("key","address","value","127.0.0.1"));home.perform("settings.save",RhActions.obj());RhActions.connectionError("此前失败：仅用于验证新请求清除旧结果");}catch(Exception e){throw new RuntimeException(e);}});
                java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.atomic.AtomicReference<Throwable> serverError=new java.util.concurrent.atomic.AtomicReference<>();java.net.ServerSocket server=new java.net.ServerSocket();server.setReuseAddress(true);server.bind(new java.net.InetSocketAddress("127.0.0.1",47989));server.setSoTimeout(5000);
                Thread responder=new Thread(()->{try(java.net.Socket socket=server.accept()){socket.setSoTimeout(2000);java.io.BufferedReader in=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII));String line;while((line=in.readLine())!=null&&!line.isEmpty()){}requests.incrementAndGet();byte[] xml="<root><uniqueid>other-ui-fixture</uniqueid></root>".getBytes(java.nio.charset.StandardCharsets.UTF_8);java.io.OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+xml.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.write(xml);out.flush();}catch(Throwable e){serverError.set(e);}},"UI identity response fixture");responder.start();
                try{
                    main(()->{Button pair=(Button)find(root(),"配对",false);assertNotNull("Fixture must use its actual unpaired-device action",pair);pair.requestRectangleOnScreen(new Rect(0,0,pair.getWidth(),pair.getHeight()),true);});instrument.waitForIdleSync();buttonBounds("配对");main(()->assertTrue(((Button)find(root(),"配对",false)).performClick()));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                    JSONObject connection;do{JSONObject current=state();connection=current.getJSONObject("connection");if(connection.optString("phase").equals("failed")&&!current.getJSONObject("navigation").optBoolean("locked"))break;assertTrue("Lookup did not return its failure: "+connection,System.nanoTime()<end);Thread.sleep(30);}while(true);
                    assertEquals("pair",connection.getString("operation"));assertTrue(connection.toString(),connection.getString("error").contains("设备标识"));assertFalse(connection.getString("error").contains("此前失败"));assertFalse(connection.getString("error").contains("不可达或"));assertEquals("连接失败："+connection.getString("error"),RhActions.lastConnectionError());assertEquals(1,requests.get());responder.join(1000);assertFalse(responder.isAlive());assertNull(serverError.get());buttonBounds("返回设备");buttonBounds("重试配对");capture("wire-identity-"+(dark?"dark":"light"));main(()->assertTrue(((Button)find(root(),"返回设备",false)).performClick()));assertEquals(uuid,state().getString("selected"));
                    cases.put(RhActions.obj("dark",dark,"actualViewAction",true,"serverinfoRequests",requests.get(),"uuidMismatchRejected",true,"oldErrorCleared",true,"pairRequests",0,"streamRequests",0,"returnedToDevice",true));
                }finally{server.close();responder.join(3000);assertFalse("Fixture listener leaked",responder.isAlive());}
            }
            main(RhActions::clearConnectionError);RhShared.write(new File(folder,"connection-error-wire-result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"passed",true,"cases",cases,"realLoopbackHttp",true,"sunshineOperations",false,"systemInputInjected",false));
        }
    }
    @Test public void failedResultRetainsItsErrorAcrossRecreationAndReturnsToTheSelectedDevice()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("connectionErrorRun","");org.junit.Assume.assumeFalse("Explicit dedicated emulator run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhSessionFlowDeviceTest holder=new RhSessionFlowDeviceTest();holder.folder=folder;JSONArray cases=new JSONArray();
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(holder,run)){
            main(()->{try{JSONObject shared=RhShared.read(context);shared.getJSONArray("devices").put(RhActions.obj("uuid",uuid,"name","UI result fixture","displayName","","address","192.0.2.85","addresses",new JSONArray()));RhShared.apply(context,shared);}catch(Exception e){throw new RuntimeException(e);}});
            for(boolean dark:new boolean[]{false,true})for(String scene:new String[]{"component","identity","exhausted"}){
                // Normalize the new fixture's local profile and current theme before
                // measuring navigation; ordinary discovery captures the same fields.
                main(()->{try{RhShared.theme(context,dark);RhShared.capture(context,java.util.Collections.emptyList());}catch(Exception e){throw new RuntimeException(e);}});bind(instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)));instrument.waitForIdleSync();
                JSONArray attempts=new JSONArray();if(!scene.equals("exhausted"))attempts.put(RhActions.obj("failureKind",scene,"retryable",false));String reason=RhNetwork.resolutionFailure(attempts,scene.equals("exhausted")?1:0).getMessage();String message="连接失败：自动恢复未完成："+reason+"\n最初失败：串流协商失败（RTSP handshake，错误码 -1）";
                String original=fingerprint(context);main(()->{try{home.perform("devices.filter",RhActions.obj("query","UI result"));home.perform("device.select",RhActions.obj("uuid",uuid));Field c=RhHome.class.getDeclaredField("connection");c.setAccessible(true);((RhConnection)c.get(home)).begin(uuid,"UI result fixture");Field page=RhHome.class.getDeclaredField("pageId");page.setAccessible(true);page.set(home,"connection");RhActions.connectionError(message);}catch(Exception e){throw new RuntimeException(e);}});
                assertEquals(message,state().getJSONObject("connection").getString("error"));Activity old=activity;main(old::recreate);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);Activity replacement;
                while((replacement=resumed())==null||replacement==old){assertTrue("Replacement PcView missing",System.nanoTime()<end);Thread.sleep(30);}bind(replacement);assertEquals("connection",state().getString("page"));assertEquals(message,state().getJSONObject("connection").getString("error"));assertEquals("failed",state().getJSONObject("connection").getString("phase"));
                final TextView[] error={null};main(()->{error[0]=(TextView)find(root(),"连接错误",true);assertNotNull(error[0]);assertEquals(message.substring(5),error[0].getText().toString());assertNotNull(error[0].getLayout());for(int line=0;line<error[0].getLayout().getLineCount();line++)assertEquals("Error text was ellipsized",0,error[0].getLayout().getEllipsisCount(line));Rect lastLine=new Rect(0,Math.max(0,error[0].getHeight()-error[0].getLineHeight()),error[0].getWidth(),error[0].getHeight());error[0].requestRectangleOnScreen(lastLine,true);});Thread.sleep(100);instrument.waitForIdleSync();
                main(()->{Rect visible=new Rect();assertTrue(error[0].getLocalVisibleRect(visible));assertEquals("Last error line is clipped",error[0].getHeight(),visible.bottom);assertTrue("Last error line is not reachable",visible.height()>=error[0].getLineHeight());});
                JSONObject back=buttonBounds("返回设备"),retry=buttonBounds("重试连接");String name=scene+"-"+(dark?"dark":"light");capture(name);main(()->{Button button=(Button)find(root(),"返回设备",false);assertNotNull(button);assertTrue(button.performClick());});assertEquals("devices",state().getString("page"));assertEquals(uuid,state().getString("selected"));assertEquals("UI result",state().getString("deviceQuery"));assertEquals(original,fingerprint(context));
                cases.put(RhActions.obj("name",name,"error",message,"recreated",true,"back",back,"retry",retry,"backWorked",true,"selectionRetained",true,"queryRetained",true,"configurationUnchanged",true));
            }
            main(RhActions::clearConnectionError);RhShared.write(new File(folder,"connection-error-result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"passed",true,"cases",cases,"errorStateFixtures",true,"realRecoveryFailure",false,"hostOperations",false,"webdavOperations",false,"systemInputInjected",false));
        }
    }
}
