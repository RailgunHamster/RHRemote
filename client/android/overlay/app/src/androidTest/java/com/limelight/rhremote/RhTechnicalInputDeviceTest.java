package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real PcView editors and application InputConnections; no OS input or remote connection. */
@RunWith(AndroidJUnit4.class)
public final class RhTechnicalInputDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    final String uuid="ui-technical-source113";
    final JSONArray policies=new JSONArray(),issues=new JSONArray(),cases=new JSONArray();
    Activity activity;RhHome home;File folder;
    void main(Runnable action){instrument.runOnMainSync(action);instrument.waitForIdleSync();}
    JSONObject state(){JSONObject[] result={null};main(()->{try{result[0]=home.state();}catch(Exception e){throw new RuntimeException(e);}});return result[0];}
    void action(String id,JSONObject args){main(()->{JSONObject result=RhActions.dispatch(id,args);assertTrue(id,result.optBoolean("ok"));});}
    View find(View view,String label,boolean button){if(view.getVisibility()!=View.VISIBLE)return null;if(button&&view instanceof Button&&label.contentEquals(((Button)view).getText())||!button&&view.getContentDescription()!=null&&label.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label,button);if(found!=null)return found;}return null;}
    EditText editor(String label){EditText[] result={null};main(()->result[0]=(EditText)find(activity.findViewById(android.R.id.content),label,false));assertNotNull(label,result[0]);return result[0];}
    void click(String label){main(()->{View view=find(activity.findViewById(android.R.id.content),label,true);assertNotNull(label,view);assertTrue(label,view.isEnabled());view.performClick();});}
    InputConnection connection(EditText edit,EditorInfo info){assertTrue(edit.requestFocusFromTouch());InputConnection value=edit.onCreateInputConnection(info);assertNotNull(value);return value;}
    void policy(EditText edit,String name,int variation,boolean spellcheck,boolean learningHint){
        main(()->{
            EditorInfo info=new EditorInfo();connection(edit,info);
            int forbidden=InputType.TYPE_TEXT_FLAG_AUTO_CORRECT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_CAP_WORDS|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
            boolean valid=(info.inputType&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_TEXT
                    &&(info.inputType&InputType.TYPE_MASK_VARIATION)==variation&&(info.inputType&forbidden)==0
                    &&edit.isSuggestionsEnabled()==spellcheck
                    &&(!learningHint||(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0);
            // Keep CJK composition/candidates available to the IME; URI/filter already exclude framework spell checking.
            if(variation!=InputType.TYPE_TEXT_VARIATION_PASSWORD)valid&=(info.inputType&InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)==0;
            JSONObject sample=RhActions.obj("field",name,"inputType",info.inputType,"imeOptions",info.imeOptions,"frameworkSuggestions",edit.isSuggestionsEnabled(),"valid",valid);
            policies.put(sample);if(!valid)issues.put(sample);
        });
    }
    void replace(EditText edit,String text){
        main(()->{EditorInfo info=new EditorInfo();InputConnection value=connection(edit,info);assertTrue(value.beginBatchEdit());assertTrue(value.setSelection(0,edit.length()));assertTrue(value.setComposingText(text,1));assertTrue(value.finishComposingText());value.endBatchEdit();assertEquals(text,edit.getText().toString());assertEquals(text.length(),edit.getSelectionStart());});
    }
    void capture(String name)throws Exception{Bitmap[] image={null};main(()->{View decor=activity.getWindow().getDecorView();decor.jumpDrawablesToCurrentState();image[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image[0]));});try(OutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image[0].compress(Bitmap.CompressFormat.PNG,100,output));}finally{image[0].recycle();}}
    void retire()throws Exception{if(activity==null)return;main(()->activity.finish());long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!activity.isDestroyed()){assertTrue("Owned PcView did not retire",System.nanoTime()<until);Thread.sleep(20);}activity=null;home=null;}
    @Test public void technicalEditorsPreserveCompositionCursorSaveAndCancel()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("technicalInputRun","");org.junit.Assume.assumeFalse("Explicit dedicated run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,64}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),"technical-"+run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        RhSessionFlowDeviceTest holder=new RhSessionFlowDeviceTest();holder.folder=folder;String original=RhShared.read(context).toString();
        try(RhSessionStateSnapshot snapshot=new RhSessionStateSnapshot(holder,run)){
            for(boolean dark:new boolean[]{false,true}){
                JSONObject fixture=new JSONObject(original);fixture.put("dark",dark);fixture.getJSONArray("devices").put(RhActions.obj("uuid",uuid,"name","UI technical fixture","displayName","","address","192.0.2.113","addresses",new JSONArray()));RhShared.apply(context,fixture);RhShared.theme(context,dark);
                activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.waitForIdleSync();Field field=activity.getClass().getDeclaredField("rhHome");field.setAccessible(true);home=(RhHome)field.get(activity);assertNotNull(home);
                main(()->{ComputerDetails d=new ComputerDetails();d.uuid=uuid;d.name="UI technical fixture";d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.113",47989);home.update(d);});
                String theme=dark?"dark":"light";
                EditText search=editor("搜索名称或地址");policy(search,"search-"+theme,InputType.TYPE_TEXT_VARIATION_FILTER,false,true);String query="Host😀_fd7a:115c";replace(search,query);assertEquals(query,state().getString("deviceQuery"));assertSame(search,editor("搜索名称或地址"));replace(search,"");
                action("device.add.open",RhActions.obj());EditText add=editor("设备地址");policy(add,"add-address-"+theme,InputType.TYPE_TEXT_VARIATION_URI,false,true);replace(add,"bad address !");main(()->{EditorInfo info=new EditorInfo();InputConnection value=connection(add,info);assertEquals(EditorInfo.IME_ACTION_DONE,info.imeOptions&EditorInfo.IME_MASK_ACTION);assertTrue(value.performEditorAction(EditorInfo.IME_ACTION_DONE));});assertEquals("add",state().getString("page"));assertEquals("bad address !",state().getJSONObject("add").getString("address"));assertFalse(state().getJSONObject("add").optString("error").isEmpty());action("device.add.cancel",RhActions.obj());
                action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.tab",RhActions.obj("tab","network"));
                String ipv6="fd7a:115c:a1e0:ab12:4843:cd96:6723:559b",backup="100.64.113.2, host-113.example.test",relay="https://relay.example.test:33449/";
                EditText primary=editor("首选 IP 或主机名"),backups=editor("备用地址（逗号分隔，随 WebDAV 同步）"),relayEdit=editor("中继服务器");
                policy(primary,"primary-"+theme,InputType.TYPE_TEXT_VARIATION_URI,false,true);policy(backups,"backups-"+theme,InputType.TYPE_TEXT_VARIATION_URI,false,true);policy(relayEdit,"relay-"+theme,InputType.TYPE_TEXT_VARIATION_URI,false,true);
                replace(primary,ipv6);replace(backups,backup);replace(relayEdit,relay);assertEquals(ipv6,state().getString("draftAddress"));assertEquals(backup,state().getString("draftAddresses"));assertEquals(relay,state().getString("draftRelay"));capture("network-"+theme);click("保存");
                JSONObject saved=RhNetwork.host(context,uuid);assertEquals(ipv6,saved.getString("address"));assertEquals("100.64.113.2",saved.getJSONArray("addresses").getString(0));assertEquals("host-113.example.test",saved.getJSONArray("addresses").getString(1));assertEquals(relay,RhProfile.load(context,uuid).relayUrl);
                action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.tab",RhActions.obj("tab","network"));replace(editor("中继服务器"),"https://relay.example.test/discarded");click("取消");assertEquals(relay,RhProfile.load(context,uuid).relayUrl);
                action("device.info.open",RhActions.obj("uuid",uuid));EditText alias=editor("设备名称");policy(alias,"alias-control-"+theme,InputType.TYPE_TEXT_VARIATION_NORMAL,true,false);String aliasValue="设备😀 研发";replace(alias,aliasValue);click("完成");assertEquals(aliasValue,RhShared.alias(context,uuid));
                action("app.settings.tab",RhActions.obj("tab","sync"));EditText url=editor("WebDAV 目录地址（HTTP / HTTPS）"),username=editor("用户名"),password=editor("密码");
                policy(url,"webdav-url-"+theme,InputType.TYPE_TEXT_VARIATION_URI,false,true);policy(username,"username-"+theme,InputType.TYPE_TEXT_VARIATION_NORMAL,true,true);policy(password,"password-control-"+theme,InputType.TYPE_TEXT_VARIATION_PASSWORD,false,false);
                String endpoint="http://example.test:5244/dav/RHRemote%20UI/",user="RH.user+同步@Example.TEST";replace(url,endpoint);int offset=endpoint.indexOf("UI");
                main(()->{EditorInfo info=new EditorInfo();InputConnection value=connection(url,info);assertTrue(value.setSelection(offset,offset+2));assertTrue(value.commitText("Case",1));assertEquals(offset+4,url.getSelectionStart());});endpoint=endpoint.replace("UI/","Case/");assertEquals(endpoint,url.getText().toString());assertSame(url,editor("WebDAV 目录地址（HTTP / HTTPS）"));replace(username,user);replace(password,"fixture-password-113");
                action("sync.edit",RhActions.obj("key","automatic","value",false));click("保存");JSONObject account=state().getJSONObject("sync");assertEquals(endpoint,account.getString("url"));assertEquals(user,account.getString("username"));assertTrue(account.getBoolean("hasPassword"));assertFalse(account.getBoolean("automatic"));capture("saved-sync-"+theme);
                replace(editor("用户名"),"discarded.USER");click("取消");assertEquals(user,state().getJSONObject("sync").getString("username"));
                cases.put(RhActions.obj("theme",theme,"compositionPreserved",true,"cursorPreserved",true,"invalidAddStayed",true,"networkSaveVerified",true,"networkCancelVerified",true,"aliasCompositionPreserved",true,"httpUrlSaved",true,"usernameCaseAndUnicodeSaved",true,"syncCancelVerified",true));retire();
            }
            RhShared.write(new File(folder,"result.json"),RhActions.obj("version",com.limelight.BuildConfig.VERSION_NAME,"passed",issues.length()==0,"policies",policies,"policyIssues",issues,"cases",cases,"actualPcView",true,"applicationInputConnections",true,"physicalImeTyping",false,"streamStarted",false,"syncStarted",false,"updateStarted",false,"systemInputInjected",false,"osScreenshots",false));
            assertEquals("Technical field policy differs; inspect result.json",0,issues.length());
        }
    }
}
