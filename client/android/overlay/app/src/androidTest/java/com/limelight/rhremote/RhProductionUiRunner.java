package com.limelight.rhremote;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Framework-only runner for the real PcView and HTTP dispatcher in an isolated, minified target. */
public final class RhProductionUiRunner extends Instrumentation {
    private static final String PACKAGE="com.rhremote.client.uicheck172";
    private static final String CLIENT="00000000-0190-4000-8000-000000000190";
    private static final String HOST="00000000-0190-4000-8000-000000000001";
    private final long begin=SystemClock.elapsedRealtime();
    private final List<JSONObject> lifecycle=Collections.synchronizedList(new ArrayList<>());
    private final List<JSONObject> samples=Collections.synchronizedList(new ArrayList<>());
    private final JSONArray operations=new JSONArray(),frames=new JSONArray();
    private volatile Activity active;
    private volatile String stage="created";
    private volatile long stageStarted=begin;
    private volatile int created;
    private Bundle arguments;
    private Context context;
    private File folder;
    private String token;
    private Bitmap startupImage;

    private static JSONObject j(Object... values)throws JSONException{
        JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void event(String name){try{lifecycle.add(j("event",name,"ms",SystemClock.elapsedRealtime()-begin));}catch(Exception e){throw new AssertionError(e);}}
    private void stage(String name){stage=name;stageStarted=SystemClock.elapsedRealtime();event(name);}
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    private android.opengl.GLSurfaceView graphicsProbe(View view){
        if(view instanceof android.opengl.GLSurfaceView)return(android.opengl.GLSurfaceView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){android.opengl.GLSurfaceView found=graphicsProbe(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    @Override public void callActivityOnCreate(Activity activity,Bundle state){
        event("activity_create_begin:"+activity.getClass().getName());super.callActivityOnCreate(activity,state);
        if(activity.getClass().getName().equals("com.limelight.PcView")){
            active=activity;final int generation=++created;
            android.opengl.GLSurfaceView probe=graphicsProbe(activity.findViewById(android.R.id.content));
            if(probe!=null)try{frames.put(j("name","graphics-probe","renderMode",probe.getRenderMode(),"generation",generation));}catch(Exception e){throw new AssertionError(e);}
            activity.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
                boolean reported,homeReported,loadingReported;public boolean onPreDraw(){
                    if(!reported){reported=true;event("activity_first_predraw:"+generation);
                        View root=activity.findViewById(android.R.id.content),startup=root.findViewWithTag("rhremote-startup");
                        try{frames.put(j("name","first-predraw","startupIndicator",startup!=null,"generation",generation));}catch(Exception e){throw new AssertionError(e);}
                    }
                    View content=activity.findViewById(android.R.id.content);
                    if(!loadingReported&&content.findViewWithTag("rhremote-startup")!=null){
                        loadingReported=true;event("startup_indicator_predraw:"+generation);
                        try{frames.put(j("name","startup-indicator","generation",generation));}catch(Exception e){throw new AssertionError(e);}
                        if(arguments.getString("captureStartup","false").equals("true")&&startupImage==null&&content.getWidth()>0&&content.getHeight()>0){startupImage=Bitmap.createBitmap(content.getWidth(),content.getHeight(),Bitmap.Config.ARGB_8888);content.draw(new Canvas(startupImage));}
                    }
                    if(!homeReported&&activity.getWindow().getDecorView().findViewWithTag("nav:devices.open")!=null){homeReported=true;event("home_first_predraw:"+generation);}return true;
                }
            });
        }
        event("activity_create_return:"+activity.getClass().getName());
    }
    @Override public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity.getClass().getName().equals("com.limelight.PcView"))active=activity;event("activity_resume:"+activity.getClass().getName());}
    private byte[] bytes(InputStream input,int maximum)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int count;
        while((count=input.read(chunk))!=-1){if(out.size()+count>maximum)throw new IOException("Owned response too large");out.write(chunk,0,count);}return out.toByteArray();
    }
    private JSONObject request(String id,JSONObject args)throws Exception{
        boolean state=id==null;long start=SystemClock.elapsedRealtime();
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:48752/v1/"+(state?"state":"action")).openConnection();
        connection.setConnectTimeout(1500);connection.setReadTimeout(15000);connection.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(!state){byte[] body=j("id",id,"args",args).toString().getBytes(StandardCharsets.UTF_8);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);connection.setRequestProperty("Content-Type","application/json");try(OutputStream output=connection.getOutputStream()){output.write(body);}}
            int status=connection.getResponseCode();JSONObject response;
            try(InputStream input=status<400?connection.getInputStream():connection.getErrorStream()){response=new JSONObject(new String(bytes(input,1024*1024),StandardCharsets.UTF_8));}
            operations.put(j("id",state?"state":id,"httpStatus",status,"ok",response.optBoolean("ok"),"elapsedMs",SystemClock.elapsedRealtime()-start));
            return response;
        }finally{connection.disconnect();}
    }
    private JSONObject state()throws Exception{JSONObject response=request(null,null);if(!response.optBoolean("ok"))throw new IOException("State unavailable");return response.getJSONObject("state");}
    private JSONObject action(String id,JSONObject args)throws Exception{
        check(!id.startsWith("stream.")&&!id.equals("device.connect")&&!id.equals("device.pair")&&!id.startsWith("updates.")&&!id.equals("sync.run"),"Remote or installation operation is outside this run");
        stage(id);JSONObject response=request(id,args);check(response.optBoolean("ok"),"Action failed: "+id+" / "+response.optString("error"));return response;
    }
    private JSONObject compact(JSONObject value)throws Exception{return j("page",value.optString("page"),"selected",value.optString("selected"),"deviceQuery",value.optString("deviceQuery"),"profileTab",value.optString("profileTab"),"settingsTab",value.optString("settingsTab"),"settingsDirty",value.optBoolean("settingsDirty"),"syncDirty",value.optBoolean("syncDirty"),"navigationPrompt",value.opt("navigationPrompt"),"dark",value.getJSONObject("saved").optBoolean("dark"));}
    private void capture(String name)throws Exception{
        waitForIdleSync();Throwable[] failure={null};
        runOnMainSync(()->{try{
            Activity owner=active;check(owner!=null&&!owner.isDestroyed(),"Own activity absent");View root=owner.getWindow().getDecorView();
            check(root.getWidth()>0&&root.getHeight()>0,"Own window has no size");Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
            try(FileOutputStream output=new FileOutputStream(new File(folder,name+".png"))){root.draw(new Canvas(bitmap));check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"Own drawing failed");}finally{bitmap.recycle();}
            frames.put(j("name",name,"width",root.getWidth(),"height",root.getHeight(),"windowFocus",owner.hasWindowFocus(),"owner",owner.getClass().getName()));
        }catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private Button button(View view,String label){if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=button(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private void promptClick(String label)throws Exception{
        stage("prompt_widget:"+label);Throwable[] failure={null};
        runOnMainSync(()->{try{
            Field homeField=RhActions.class.getDeclaredField("home");homeField.setAccessible(true);Object home=((WeakReference<?>)homeField.get(null)).get();check(home!=null,"Own home missing");
            Field dialogField=home.getClass().getDeclaredField("navigationDialog");dialogField.setAccessible(true);Dialog dialog=(Dialog)dialogField.get(home);check(dialog!=null&&dialog.isShowing(),"Owned prompt not visible");
            View root=dialog.getWindow().getDecorView();Button target=button(root,label);check(target!=null&&target.isEnabled(),"Owned prompt button missing: "+label);
            Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);try(FileOutputStream output=new FileOutputStream(new File(folder,"prompt-"+operations.length()+".png"))){root.draw(new Canvas(bitmap));bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}finally{bitmap.recycle();}
            check(target.performClick(),"Owned prompt button rejected click");
        }catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);waitForIdleSync();
    }
    private void seed()throws Exception{
        boolean dark=arguments.getString("startDark","false").equals("true");
        context.getSharedPreferences("rhremote.global",0).edit().putString("clientId",CLIENT).putInt("profileSchemaVersion",2).putBoolean("deviceOrderInitialized",true).putInt("relayDefaultsVersion",1).putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
        JSONArray devices=new JSONArray();for(int i=1;i<=3;i++)devices.put(j("uuid",String.format(Locale.ROOT,"00000000-0190-4000-8000-%012d",i),"name","UI190 "+i,"address","127.0.0."+(7+i)));
        RhShared.apply(context,j("schemaVersion",2,"devices",devices,"clients",j(CLIENT,j("name","Owned UI190","platform","android")),"profiles",j(CLIENT,new JSONObject()),"dark",dark));
    }
    private void exercise()throws Exception{
        stage("real_pcview_start");startActivitySync(new Intent().setClassName(PACKAGE,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));event("start_activity_sync_return");
        stage("await_own_http");long until=SystemClock.uptimeMillis()+20000;JSONObject initial=null;
        while(SystemClock.uptimeMillis()<until){
            File file=new File(context.getFilesDir(),"rhremote-control-token.txt");
            if(file.isFile())try{try(InputStream input=new FileInputStream(file)){token=new String(bytes(input,128),StandardCharsets.US_ASCII).trim();}if(!token.matches("[a-f0-9]{64}")){Thread.sleep(20);continue;}if(initial==null)event("home_token_detected");initial=state();if(initial.getJSONObject("saved").optString("clientId").equals(CLIENT))break;}catch(IOException|JSONException retry){initial=null;}
            Thread.sleep(100);
        }
        check(initial!=null&&initial.getJSONObject("saved").optString("clientId").equals(CLIENT),"Own real client never became ready");
        event("real_home_http_ready");capture("devices-ready");if(arguments.getString("startupOnly","false").equals("true"))return;
        action("devices.open",j());action("devices.filter",j("query","UI190"));action("device.select",j("uuid",HOST));
        action("device.settings.open",j("uuid",HOST));int original=state().getJSONObject("draft").getInt("volume"),savedVolume=original==51?52:51;
        action("settings.set",j("key","volume","value",savedVolume));action("app.settings.open",j());check(!state().isNull("navigationPrompt"),"Dirty form left without a prompt");
        promptClick("继续编辑");check(state().optBoolean("settingsDirty"),"Continue editing lost draft");
        action("app.settings.open",j());action("navigation.resolve",j("choice","discard"));check(!state().optBoolean("settingsDirty"),"Discard kept a dirty draft");
        action("device.settings.open",j("uuid",HOST));check(state().getJSONObject("draft").getInt("volume")==original,"Discard changed saved volume");
        action("settings.set",j("key","volume","value",savedVolume));action("app.settings.open",j());promptClick("保存并继续");
        check(state().getJSONObject("saved").getJSONObject("profiles").getJSONObject(HOST).getInt("volume")==savedVolume,"Save did not persist");
        for(String tab:new String[]{"updates","sync","appearance","about"}){action("app.settings.tab",j("tab",tab));check(state().optString("settingsTab").equals(tab),"Global tab mismatch");capture("settings-"+tab+"-light");}
        action("app.settings.tab",j("tab","sync"));action("sync.edit",j("key","url","value","http://127.0.0.8:5244/dav"));action("app.settings.tab",j("tab","updates"));check(!state().isNull("navigationPrompt"),"Sync draft left without a prompt");
        action("navigation.resolve",j("choice","stay"));action("sync.cancel",j());check(!state().optBoolean("syncDirty"),"Sync cancellation kept dirty state");
        action("app.settings.tab",j("tab","appearance"));JSONObject before=state();boolean expectedDark=!before.getJSONObject("saved").optBoolean("dark");int generation=created;action("appearance.toggle",j());
        JSONObject after=null;until=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<until){try{after=state();if(created>generation&&after.getJSONObject("saved").optBoolean("dark")==expectedDark)break;}catch(Exception transientState){after=null;}Thread.sleep(100);}
        check(after!=null&&created>generation&&after.getJSONObject("saved").optBoolean("dark")==expectedDark,"Theme recreation did not complete");
        check(after.optString("page").equals(before.optString("page"))&&after.optString("settingsTab").equals("appearance"),"Theme lost page");
        check(after.optString("selected").equals(HOST)&&after.optString("deviceQuery").equals("UI190"),"Theme lost device or query");capture("settings-appearance-dark");
        action("devices.open",j());action("device.select",j("uuid",HOST));action("device.settings.open",j("uuid",HOST));
        for(String tab:new String[]{"video","audio","input","network","advanced"}){action("settings.tab",j("tab",tab));check(state().optString("profileTab").equals(tab),"Profile tab mismatch");capture("profile-"+tab+"-dark");}
        action("settings.cancel",j());check(!state().optBoolean("settingsDirty"),"Profile cancellation kept dirty state");capture("devices-dark");
        check(!RhActions.hasStream(),"Unexpected remote session");operations.put(j("finalState",compact(state()),"savedVolume",savedVolume));
    }
    @Override public void onStart(){
        ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();boolean passed=false;Throwable failure=null;Bundle result=new Bundle();
        try{
            context=getTargetContext();check(PACKAGE.equals(context.getPackageName()),"Only the owned namespace may run");check(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"),"Only emulator execution is allowed");
            check((context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0,"Actual target must be Release");
            String name=arguments.getString("productionUiRun","");check(name.matches("[A-Za-z0-9_-]{1,80}"),"Explicit fresh run name required");folder=new File(context.getExternalFilesDir("ui-evidence"),name);check(!folder.exists()&&folder.mkdirs(),"Evidence directory must be fresh");
            try(java.net.ServerSocket unused=new java.net.ServerSocket(48752,1,InetAddress.getByName("127.0.0.1"))){}
            sampler.scheduleAtFixedRate(()->{try{if(SystemClock.elapsedRealtime()-stageStarted>250&&samples.size()<200){Thread main=Looper.getMainLooper().getThread();JSONArray stack=new JSONArray();for(StackTraceElement entry:main.getStackTrace()){if(stack.length()>=20)break;stack.put(entry.toString());}samples.add(j("stage",stage,"ms",SystemClock.elapsedRealtime()-begin,"mainState",main.getState().name(),"stack",stack));}}catch(Throwable ignored){}},100,100,TimeUnit.MILLISECONDS);
            stage("seed_owned_config");seed();exercise();passed=true;result.putString("result","RHREMOTE_PRODUCTION_UI_PASS");
        }catch(Throwable e){failure=e;result.putString("result","RHREMOTE_PRODUCTION_UI_FAIL");result.putString("error",e.toString());}
        finally{
            sampler.shutdownNow();try{sampler.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(folder!=null)try{
                if(startupImage!=null){try(FileOutputStream imageFile=new FileOutputStream(new File(folder,"startup-first-predraw.png"))){check(startupImage.compress(Bitmap.CompressFormat.PNG,100,imageFile),"Startup drawing failed");}finally{startupImage.recycle();startupImage=null;}}
                JSONArray life=new JSONArray(),stacks=new JSONArray();synchronized(lifecycle){for(JSONObject row:lifecycle)life.put(row);}synchronized(samples){for(JSONObject row:samples)stacks.put(row);}
                StringWriter trace=new StringWriter();if(failure!=null)failure.printStackTrace(new PrintWriter(trace));
                JSONObject report=j("passed",passed,"pid",android.os.Process.myPid(),"version",context.getPackageManager().getPackageInfo(PACKAGE,0).versionName,"targetDebuggable",false,"totalRunMs",SystemClock.elapsedRealtime()-begin,"timingOrigin","Instrumentation construction; not a launcher click or video first-frame timestamp","stage",stage,"lifecycle",life,"operations",operations,"mainSamples",stacks,"frames",frames,"failure",trace.toString(),"realPcView",true,"testActivityUsed",false,"remoteSessionStarted",RhActions.hasStream(),"oppoOperated",false,"scope","Real PcView and service initialization; generated own config; production loopback HTTP and own dialog clicks/View.draw; lifecycle predraw is not physical presentation; no remote connection, pairing, WebDAV transfer, install action or OS input");
                try(FileOutputStream output=new FileOutputStream(new File(folder,"result.json"))){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
            }catch(Throwable writeFailure){result.putString("reportError",writeFailure.toString());passed=false;}
            new android.os.Handler(Looper.getMainLooper()).post(()->{if(active!=null&&!active.isFinishing())active.finish();});
        }
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
