package com.limelight.rhremote;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.lang.reflect.Field;
import java.security.cert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native View rendering of captured protocol outcomes; no window or remote input. */
@RunWith(AndroidJUnit4.class)
public final class RhDeviceCheckViewDeviceTest {
    @Test public void capturedProtocolOutcomesDriveVisibleNativeControls()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("deviceCheckViewRun",""),source=InstrumentationRegistry.getArguments().getString("protocolSourceRun","");org.junit.Assume.assumeFalse("Explicit isolated run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));assertTrue(source.matches("[a-zA-Z0-9_-]{1,80}"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().matches("com\\.rhremote\\.client\\.uicheck[0-9]+"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        File evidence=context.getExternalFilesDir("ui-evidence"),input=new File(new File(evidence,source),"result.json");JSONObject protocol=new JSONObject(new String(java.nio.file.Files.readAllBytes(input.toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(context.getPackageName(),protocol.getString("package"));assertEquals(17,protocol.getJSONArray("cases").length());
        X509Certificate certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(android.util.Base64.decode(protocol.getString("publicTestCertificate"),android.util.Base64.DEFAULT)));
        File folder=new File(evidence,run);assertFalse(folder.exists());assertTrue(folder.mkdirs());RhShared.clientId(context);
        RhDeviceBrowserLayoutDeviceTest renderer=new RhDeviceBrowserLayoutDeviceTest();renderer.folder=folder;JSONArray cases=new JSONArray();
        String[][] outcomes={{"http-unpaired","未配对","配对","device.pair"},{"https-paired","已配对","连接","device.connect"},{"https-unpaired","需要重新配对","重新配对","device.pair"},{"https-http401","认证失败","重新配对","device.pair"},{"pin-change","证书不符","重新配对","device.pair"},{"wrong-uuid","设备标识不符","检查连接","device.check"},{"tls-failure","安全连接失败","连接","device.connect"},{"timeout","检查超时","连接","device.connect"},{"malformed","设备信息无效","检查连接","device.check"},{"cancel-body","状态未确认","连接","device.connect"}};
        for(boolean dark:new boolean[]{false,true})for(float scale:new float[]{1,2})for(int[] size:new int[][]{{360,800},{1008,672}})for(String[] expected:outcomes){
            JSONObject captured=null;for(int i=0;i<protocol.getJSONArray("cases").length();i++){JSONObject row=protocol.getJSONArray("cases").getJSONObject(i);if(row.getString("case").equals(expected[0]))captured=row.getJSONObject("report");}assertNotNull(captured);final JSONObject report=new JSONObject(captured.toString());
            String name=(dark?"dark":"light")+"-"+size[0]+"x"+size[1]+"-font"+(int)(scale*100)+"-"+expected[0];Throwable[] failure={null};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{
                RhDeviceBrowserLayoutDeviceTest.FixtureActivity activity=renderer.new FixtureActivity(context,dark,scale);ComputerDetails target=new ComputerDetails();target.uuid=report.getString("targetUuid");target.name="测试设备";target.manualAddress=new ComputerDetails.AddressTuple("127.0.0.1",47989);target.serverCert=report.getBoolean("hasCertificate")?certificate:null;target.state=ComputerDetails.State.ONLINE;target.pairState=com.limelight.nvstream.http.PairingManager.PairState.PAIRED;
                JSONObject shared=RhShared.read(context);RhDiagnosticWork work=RhDiagnosticWork.shared(activity);assertFalse(work.busy());Field field=RhDiagnosticWork.class.getDeclaredField("checks");field.setAccessible(true);Map<String,JSONObject> cache=(Map<String,JSONObject>)field.get(work);
                JSONObject scoped=new JSONObject(report.toString());scoped.put("operationId",1);scoped.put("requestIntent",RhDeviceCheck.intent(activity,shared,target));JSONObject previous=cache.put(target.uuid,scoped);
                try{
                    List<String> sent=new ArrayList<>();RhDeviceBrowser browser=new RhDeviceBrowser(activity,(id,args)->{assertEquals(target.uuid,args.optString("uuid"));sent.add(id);});browser.update(shared,Collections.singletonList(target),target.uuid,"");browser.showDetail(true);renderer.layout(browser,size[0],size[1]);
                    Field valuesField=RhDeviceBrowser.class.getDeclaredField("values");valuesField.setAccessible(true);TextView[] values=(TextView[])valuesField.get(browser);renderer.metricViews.clear();renderer.metricViews.addAll(Arrays.asList(values));assertEquals(expected[1],values[4].getText().toString());
                    boolean verified=Arrays.asList("https-paired","tls-failure","timeout","cancel-body").contains(expected[0]);assertEquals("Old PAIRED flag leaked through "+expected[0],verified,RhDeviceCheck.paired(target,scoped));int statusColor=expected[0].equals("http-unpaired")?android.graphics.Color.parseColor(dark?"#a4b1c8":"#69778e"):expected[0].equals("https-paired")?android.graphics.Color.parseColor(dark?"#8bc7b1":"#369c81"):RhUiStyle.failure(dark);assertEquals(statusColor,values[4].getCurrentTextColor());Field rowsField=RhDeviceBrowser.class.getDeclaredField("rows");rowsField.setAccessible(true);android.view.ViewGroup row=(android.view.ViewGroup)((Map<?,?>)rowsField.get(browser)).get(target.uuid);TextView rowStatus=(TextView)((android.view.ViewGroup)row.getChildAt(1)).getChildAt(2);assertEquals(expected[1],rowStatus.getText().toString());assertEquals(statusColor,rowStatus.getCurrentTextColor());Field availabilityField=RhDeviceBrowser.class.getDeclaredField("availability");availabilityField.setAccessible(true);boolean unidentified=expected[0].equals("wrong-uuid")||expected[0].equals("malformed");int availabilityColor=android.graphics.Color.parseColor(unidentified?(dark?"#a4b1c8":"#69778e"):(dark?"#8bc7b1":"#369c81"));assertEquals("Reachability must stay separate from pairing failure",availabilityColor,((TextView)availabilityField.get(browser)).getCurrentTextColor());
                    Field primaryField=RhDeviceBrowser.class.getDeclaredField("connect");primaryField.setAccessible(true);Button primary=(Button)primaryField.get(browser);assertEquals(expected[2],primary.getText().toString());assertTrue(primary.isEnabled());Rect visible=new Rect();assertTrue("Primary action outside viewport: "+name,primary.getGlobalVisibleRect(visible));assertEquals(primary.getHeight(),visible.height());
                    JSONArray geometry=new JSONArray();renderer.textGeometry(browser,name,geometry);renderer.capture(browser,name);assertTrue(primary.performClick());assertEquals(Collections.singletonList(expected[3]),sent);
                    cases.put(RhActions.obj("name",name,"protocolCase",expected[0],"expectedStatus",expected[1],"primaryLabel",expected[2],"action",expected[3],"measurements",geometry,"layout",browser.layoutState()));
                }finally{if(previous==null)cache.remove(target.uuid);else cache.put(target.uuid,previous);}
            }catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(name,failure[0]);
        }
        RhShared.write(new File(folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"scope","Actual native View.draw and button dispatcher with captured protocol evidence injected into isolated view-model cache; no actual route task or system input","protocolSource",source,"cases",cases,"verticalClipping",renderer.clipped,"truncatedMetrics",renderer.truncatedMetrics));
        assertEquals(80,cases.length());assertEquals(0,renderer.clipped.length());assertEquals(0,renderer.truncatedMetrics.length());
    }
}
