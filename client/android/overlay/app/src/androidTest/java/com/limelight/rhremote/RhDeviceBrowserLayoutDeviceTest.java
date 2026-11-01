package com.limelight.rhremote;

import android.app.Activity;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native device-workspace rendering; no window, service, network or preference writes. */
@RunWith(AndroidJUnit4.class)
public final class RhDeviceBrowserLayoutDeviceTest {
    final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    File folder;
    final JSONArray cases=new JSONArray(),clipped=new JSONArray(),truncatedMetrics=new JSONArray();
    final Set<View> metricViews=Collections.newSetFromMap(new IdentityHashMap<>());
    final class FixtureActivity extends Activity {
        final boolean dark;
        FixtureActivity(Context base,boolean dark,float scale){
            this.dark=dark;Configuration configuration=new Configuration(base.getResources().getConfiguration());
            configuration.densityDpi=160;configuration.fontScale=scale;
            attachBaseContext(new ContextThemeWrapper(base.createConfigurationContext(configuration),R.style.RhTheme));
        }
        @Override public android.content.res.Resources.Theme getTheme(){return getBaseContext().getTheme();}
        @Override public Object getSystemService(String name){return getBaseContext().getSystemService(name);}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            SharedPreferences original=super.getSharedPreferences(name,mode);
            return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                if(method.getName().equals("edit"))throw new AssertionError("Layout fixture attempted to write preferences");
                if(name.equals("rhremote.global")&&method.getName().equals("getBoolean")&&args[0].equals("dark"))return dark;
                try{return method.invoke(original,args);}catch(InvocationTargetException error){throw error.getCause();}
            });
        }
    }
    List<ComputerDetails> devices(){
        List<ComputerDetails> result=new ArrayList<>();
        for(int i=0;i<3;i++){
            ComputerDetails device=new ComputerDetails();device.uuid="ui-browser-font-"+i;
            device.name=i==0?"winserver":i==1?"RailgunHamster":"Claw";
            device.manualAddress=new ComputerDetails.AddressTuple("192.0.2."+(111+i),47989);
            device.state=i==2?ComputerDetails.State.OFFLINE:ComputerDetails.State.ONLINE;
            device.pairState=i==2?PairingManager.PairState.NOT_PAIRED:PairingManager.PairState.PAIRED;result.add(device);
        }
        return result;
    }
    void layout(View root,int width,int height){
        for(int i=0;i<4;i++){force(root);root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}
    }
    void force(View view){view.forceLayout();if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)force(((ViewGroup)view).getChildAt(i));}
    void textGeometry(View view,String name,JSONArray measurements)throws Exception{
        if(view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof TextView){
            TextView text=(TextView)view;
            if(text.length()>0&&text.getLayout()!=null){
                int room=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom();
                int needed=text.getLayout().getHeight();
                boolean ellipsized=false;for(int i=0;i<text.getLayout().getLineCount();i++)ellipsized|=text.getLayout().getEllipsisCount(i)>0;
                JSONObject measure=RhActions.obj("text",text.getText().toString(),"height",text.getHeight(),"contentHeight",room,"textHeight",needed,"fontPixels",text.getTextSize(),"width",text.getWidth(),"button",text instanceof Button);
                measurements.put(measure);
                if(needed>room)clipped.put(RhActions.obj("case",name,"geometry",measure));
                if(metricViews.contains(text)&&ellipsized)truncatedMetrics.put(RhActions.obj("case",name,"geometry",measure));
                if(text instanceof Button){
                    assertTrue("Button target below48dp",text.getHeight()>=48);
                    if(text.getParent() instanceof ViewGroup){ViewGroup parent=(ViewGroup)text.getParent();assertTrue("Button crosses parent left",text.getLeft()>=parent.getPaddingLeft());assertTrue("Button crosses parent right",text.getRight()<=parent.getWidth()-parent.getPaddingRight());assertTrue("Button crosses parent top",text.getTop()>=parent.getPaddingTop());assertTrue("Button crosses parent bottom",text.getBottom()<=parent.getHeight()-parent.getPaddingBottom());}
                }
            }
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)textGeometry(((ViewGroup)view).getChildAt(i),name,measurements);
    }
    void capture(View root,String name)throws Exception{
        Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        try{root.draw(new Canvas(image));try(OutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}}finally{image.recycle();}
    }
    @Test public void deviceTextFitsAtNormalAndMaximumSystemFontSize()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("browserFontRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        for(boolean dark:new boolean[]{false,true})for(float scale:new float[]{1,2})for(int[] size:new int[][]{{360,800},{640,360},{840,560},{1008,672}}){
            String name=(dark?"dark":"light")+"-"+size[0]+"x"+size[1]+"-font"+(int)(scale*100);Throwable[] failure={null};
            instrument.runOnMainSync(()->{try{
                FixtureActivity activity=new FixtureActivity(context,dark,scale);
                RhDeviceBrowser browser=new RhDeviceBrowser(activity,(id,args)->{throw new AssertionError("Rendering triggered an action: "+id);});
                browser.update(devices(),"ui-browser-font-0","");browser.showDetail(true);layout(browser,size[0],size[1]);
                Field field=RhDeviceBrowser.class.getDeclaredField("values");field.setAccessible(true);metricViews.clear();metricViews.addAll(Arrays.asList((TextView[])field.get(browser)));
                JSONArray measurements=new JSONArray();textGeometry(browser,name,measurements);capture(browser,name);
                layout(browser,size[0]>=700?360:840,size[1]);JSONArray resized=new JSONArray();textGeometry(browser,name+"-resized",resized);
                assertEquals("ui-browser-font-0",browser.layoutState().getString("selected"));
                browser.update(devices(),"ui-browser-font-0","");layout(browser,size[0],size[1]);
                JSONArray restored=new JSONArray();textGeometry(browser,name+"-roundtrip",restored);capture(browser,name+"-roundtrip");
                assertEquals("ui-browser-font-0",browser.layoutState().getString("selected"));
                cases.put(RhActions.obj("name",name,"fontScale",activity.getResources().getConfiguration().fontScale,"layout",browser.layoutState(),"measurements",measurements,"resizedMeasurements",resized,"roundtripMeasurements",restored,"resizeTransitions",2));
            }catch(Throwable error){failure[0]=error;}});
            if(failure[0]!=null)throw new AssertionError(name,failure[0]);
        }
        RhShared.write(new File(folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"cases",cases,"verticalClipping",clipped,"truncatedMetrics",truncatedMetrics,"hostOperations",false,"webdavOperations",false,"preferencesWritten",false,"systemInputInjected",false,"rendering","native View.draw without attached Activity"));
        assertEquals("Device workspace clips text; inspect result.json",0,clipped.length());
        assertEquals("Device metrics are ellipsized; inspect result.json",0,truncatedMetrics.length());
    }
    @Test public void unknownStoredAndVerifiedDevicesKeepTheirActionsAndTextConsistent()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("deviceCheckRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));
        assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));Context context=instrument.getTargetContext();
        assertTrue("This check must not replace the normal client",context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));
        folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        // Only the new isolated test package is initialized. Fixture rendering cannot edit it.
        RhShared.clientId(context);JSONObject shared=RhShared.read(context);java.security.cert.X509Certificate certificate=com.limelight.binding.PlatformBinding.getCryptoProvider(context).getClientCertificate();
        for(boolean dark:new boolean[]{false,true})for(float scale:new float[]{1,2})for(int[] size:new int[][]{{360,800},{1008,672}})for(int state=0;state<8;state++){
            final int kind=state;String name=(dark?"dark":"light")+"-"+size[0]+"x"+size[1]+"-font"+(int)(scale*100)+"-state"+state;Throwable[] failure={null};
            instrument.runOnMainSync(()->{try{
                FixtureActivity activity=new FixtureActivity(context,dark,scale);List<String> sent=new ArrayList<>();
                RhDeviceBrowser browser=new RhDeviceBrowser(activity,(id,args)->{assertEquals("ui-check-host",args.optString("uuid"));sent.add(id);});
                ComputerDetails device=new ComputerDetails();device.uuid="ui-check-host";device.name="Claw";device.manualAddress=new ComputerDetails.AddressTuple("192.0.2.10",47989);
                device.state=kind==0?ComputerDetails.State.UNKNOWN:kind==1||kind==5?ComputerDetails.State.OFFLINE:ComputerDetails.State.ONLINE;
                device.pairState=kind==3||kind==6?PairingManager.PairState.PAIRED:kind==4||kind==7?PairingManager.PairState.NOT_PAIRED:null;
                if(kind>=5)device.serverCert=certificate;
                String expected=kind<=2?"device.check":kind==5||kind==6?"device.connect":"device.pair";
                String expectedLabel=kind<=2?"检查连接":kind==5||kind==6?"连接":"配对";
                String expectedStatus=kind<=2?"unknown":kind==5?"stored":kind==6?"verified":"unpaired";
                browser.update(shared,Collections.singletonList(device),device.uuid,"");browser.showDetail(true);layout(browser,size[0],size[1]);
                Field field=RhDeviceBrowser.class.getDeclaredField("connect");field.setAccessible(true);Button primary=(Button)field.get(browser);
                assertEquals(expectedLabel,primary.getText().toString());assertEquals(expectedStatus,RhNetwork.pairingStatus(device));assertTrue(primary.isEnabled());assertTrue(primary.performClick());assertEquals(Collections.singletonList(expected),sent);
                Field availabilityField=RhDeviceBrowser.class.getDeclaredField("availability");availabilityField.setAccessible(true);TextView availability=(TextView)availabilityField.get(browser);
                assertEquals("Device availability badge must stay on one line",1,availability.getLayout().getLineCount());
                int badgeContent=availability.getWidth()-availability.getCompoundPaddingLeft()-availability.getCompoundPaddingRight();
                assertTrue("Badge text layout width "+availability.getLayout().getWidth()+" exceeds drawn content "+badgeContent,availability.getLayout().getWidth()<=badgeContent+1);
                Field pairField=RhDeviceBrowser.class.getDeclaredField("pair");pairField.setAccessible(true);Button pair=(Button)pairField.get(browser);
                if(kind<=2){assertEquals(View.VISIBLE,pair.getVisibility());assertTrue(pair.performClick());assertEquals(Arrays.asList(expected,"device.pair"),sent);}else assertEquals(View.GONE,pair.getVisibility());
                Field valuesField=RhDeviceBrowser.class.getDeclaredField("values");valuesField.setAccessible(true);metricViews.clear();metricViews.addAll(Arrays.asList((TextView[])valuesField.get(browser)));
                JSONArray measurements=new JSONArray();textGeometry(browser,name,measurements);capture(browser,name);
                cases.put(RhActions.obj("name",name,"pairingStatus",RhNetwork.pairingStatus(device),"primaryAction",expected,"sent",new JSONArray(sent),"fontScale",scale,"layout",browser.layoutState(),"measurements",measurements));
            }catch(Throwable error){failure[0]=error;}});
            if(failure[0]!=null)throw new AssertionError(name,failure[0]);
        }
        RhShared.write(new File(folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"package",context.getPackageName(),"cases",cases,"verticalClipping",clipped,"truncatedMetrics",truncatedMetrics,"hostOperations",false,"webdavOperations",false,"normalClientOperated",false,"fixturePreferencesWritten",false,"systemInputInjected",false,"rendering","native View.draw without attached Activity; action boundary recorded"));
        assertEquals("Device check text clips; inspect result.json",0,clipped.length());assertEquals("Device metrics are ellipsized",0,truncatedMetrics.length());
    }
}
