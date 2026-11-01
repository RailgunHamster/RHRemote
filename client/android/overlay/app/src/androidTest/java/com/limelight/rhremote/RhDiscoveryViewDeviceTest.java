package com.limelight.rhremote;

import android.content.Context;
import android.graphics.Rect;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.lang.reflect.*;
import java.security.cert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual native widgets with immutable observations captured from owned protocol peers. */
@RunWith(AndroidJUnit4.class)
public final class RhDiscoveryViewDeviceTest {
    @Test public void actualDiscoveryReportsDriveTheStatusAndOnePrimaryAction()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("discoveryViewRun",""),source=InstrumentationRegistry.getArguments().getString("discoverySourceRun","");org.junit.Assume.assumeFalse("Explicit isolated emulator run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));assertTrue(source.matches("[a-zA-Z0-9_-]{1,80}"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));File evidence=context.getExternalFilesDir("ui-evidence");JSONObject protocol=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(new File(evidence,source),"result.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(context.getPackageName(),protocol.getString("package"));assertEquals(8,protocol.getJSONArray("cases").length());
        X509Certificate certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(android.util.Base64.decode(protocol.getString("publicTestCertificate"),android.util.Base64.DEFAULT)));
        Constructor<RhDiscoveryObservation> constructor=RhDiscoveryObservation.class.getDeclaredConstructor(JSONObject.class,X509Certificate.class,String.class);constructor.setAccessible(true);File folder=new File(evidence,run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhDeviceBrowserLayoutDeviceTest renderer=new RhDeviceBrowserLayoutDeviceTest();renderer.folder=folder;JSONArray cases=new JSONArray();
        String[][] outcomes={{"http-unpaired","未配对","配对","device.pair"},{"https-paired","已配对","连接","device.connect"},{"https-unpaired","需要重新配对","重新配对","device.pair"},{"https-http401","认证失败","重新配对","device.pair"},{"pin-change","证书不符","重新配对","device.pair"},{"stale","需重新检查","连接","device.connect"}};
        for(boolean dark:new boolean[]{false,true})for(float scale:new float[]{1,2})for(int[] size:new int[][]{{360,800},{1008,672}})for(String[] expected:outcomes){
            JSONObject captured=null;String caseId=expected[0].equals("stale")?"https-paired":expected[0];for(int i=0;i<protocol.getJSONArray("cases").length();i++){JSONObject row=protocol.getJSONArray("cases").getJSONObject(i);if(row.getString("case").equals(caseId))captured=row.getJSONObject("report");}assertNotNull(captured);final JSONObject report=new JSONObject(captured.toString());String name=(dark?"dark":"light")+"-"+size[0]+"x"+size[1]+"-font"+(int)(scale*100)+"-"+expected[0];Throwable[] failure={null};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{
                RhDeviceBrowserLayoutDeviceTest.FixtureActivity activity=renderer.new FixtureActivity(context,dark,scale);ComputerDetails target=new ComputerDetails();target.uuid=report.getString("targetUuid");target.name="测试设备";target.manualAddress=new ComputerDetails.AddressTuple("127.0.0.1",47989);target.state=ComputerDetails.State.ONLINE;target.serverCert=report.getBoolean("hasCertificate")?certificate:null;
                JSONObject capturedSettings=RhShared.read(context);if(expected[0].equals("stale")){JSONArray rows=capturedSettings.getJSONArray("devices");boolean found=false;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("uuid").equals(target.uuid)){rows.getJSONObject(i).put("address","127.0.0.2");found=true;}if(!found)rows.put(RhActions.obj("uuid",target.uuid,"name",target.name,"address","127.0.0.2","addresses",new JSONArray(),"displayName","","swapButtons",false));}
                String capturedIntent=RhDiscoveryObservation.intent(activity,capturedSettings,target);JSONObject shared=RhShared.read(context);if(expected[0].equals("stale"))assertNotEquals("The stale fixture must actually have different metadata",capturedIntent,RhDiscoveryObservation.intent(activity,shared,target));target.rhDiscoveryObservation=constructor.newInstance(report,target.serverCert,capturedIntent);
                List<String> sent=new ArrayList<>();RhDeviceBrowser browser=new RhDeviceBrowser(activity,(id,args)->{assertEquals(target.uuid,args.optString("uuid"));sent.add(id);});browser.update(shared,Collections.singletonList(target),target.uuid,"");browser.showDetail(true);renderer.layout(browser,size[0],size[1]);
                Field field=RhDeviceBrowser.class.getDeclaredField("values");field.setAccessible(true);TextView[] values=(TextView[])field.get(browser);renderer.metricViews.clear();renderer.metricViews.addAll(Arrays.asList(values));assertEquals(expected[1],values[4].getText().toString());field=RhDeviceBrowser.class.getDeclaredField("connect");field.setAccessible(true);Button primary=(Button)field.get(browser);assertEquals(expected[2],primary.getText().toString());assertTrue(primary.isEnabled());Rect bounds=new Rect();assertTrue(primary.getGlobalVisibleRect(bounds));assertEquals(primary.getHeight(),bounds.height());JSONArray geometry=new JSONArray();renderer.textGeometry(browser,name,geometry);renderer.capture(browser,name);assertTrue(primary.performClick());assertEquals(Collections.singletonList(expected[3]),sent);cases.put(RhActions.obj("name",name,"protocolCase",caseId,"expectedStatus",expected[1],"primaryAction",expected[3],"layout",browser.layoutState(),"measurements",geometry));
            }catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(name,failure[0]);
        }
        RhShared.write(new File(folder,"result.json"),RhActions.obj("version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,"scope","Actual View.draw and button dispatcher with captured protocol reports injected as discovery observations; stale case changes only the fixture view-model, not disk or WebDAV","protocolSource",source,"cases",cases,"verticalClipping",renderer.clipped,"truncatedMetrics",renderer.truncatedMetrics));assertEquals(48,cases.length());assertEquals(0,renderer.clipped.length());assertEquals(0,renderer.truncatedMetrics.length());
    }
}
