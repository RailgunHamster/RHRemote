package com.limelight.rhremote;

import android.content.*;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.http.ComputerDetails;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Owned attached views with public fixture data; no client initialization or remote operations. */
@RunWith(AndroidJUnit4.class)
public final class RhSaveErrorDeviceTest {
    final RhDiagnosticCancellationDeviceTest rig=new RhDiagnosticCancellationDeviceTest();
    final JSONArray cases=new JSONArray(),images=new JSONArray();
    final String uuid="save-error-185",source="source-185";
    final String message=String.join("\n",Collections.nCopies(80,"保存失败，请检查目录和账户设置。"))+"\nERROR-END-185";
    RhStreamPanel panel;
    Object field(String name)throws Exception{Field f=RhHome.class.getDeclaredField(name);f.setAccessible(true);return f.get(rig.home);}
    void settle()throws Exception{
        long until=SystemClock.uptimeMillis()+3000;boolean[] pending={true};
        do{rig.instrument.waitForIdleSync();rig.main(()->pending[0]=rig.activity.findViewById(android.R.id.content).isLayoutRequested());if(!pending[0])return;Thread.sleep(10);}while(SystemClock.uptimeMillis()<until);fail("Own root did not settle");
    }
    void action(String id,JSONObject args)throws Exception{rig.action(id,args);settle();}
    boolean full(View view){Rect r=new Rect();return view!=null&&view.isShown()&&view.getWidth()>0&&view.getHeight()>0&&view.getGlobalVisibleRect(r)&&r.width()==view.getWidth()&&r.height()==view.getHeight();}
    Button button(View root,String label){if(root instanceof Button&&label.contentEquals(((Button)root).getText()))return(Button)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button result=button(((ViewGroup)root).getChildAt(i),label);if(result!=null)return result;}return null;}
    void start(int width,int height,int font,boolean dark)throws Exception{
        rig.main(()->RhUiFixtureActivity.fontScale(font));rig.activity=rig.instrument.startActivitySync(new Intent().setComponent(new ComponentName(rig.context.getPackageName(),RhUiFixtureActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        rig.main(()->{float d=rig.activity.getResources().getDisplayMetrics().density;rig.activity.getWindow().setLayout(Math.round(width*d),Math.round(height*d));rig.context.getSharedPreferences("rhremote.global",0).edit().putBoolean("dark",dark).putLong("lastUpdateCheck",System.currentTimeMillis()).commit();
            List<ComputerDetails> hosts=new ArrayList<>();String[] names={"winserver","RailgunHamster","claw"};for(int i=0;i<names.length;i++){ComputerDetails host=new ComputerDetails();host.uuid=i==0?uuid:"fixture-185-"+i;host.name=names[i];host.manualAddress=new ComputerDetails.AddressTuple("192.0.2."+(10+i),47989);hosts.add(host);}
            JSONObject shared=RhShared.capture(rig.context,hosts);RhProfile other=RhProfile.load(rig.context,uuid);other.width=1280;other.height=720;shared.getJSONObject("profiles").put(source,RhActions.obj(uuid,other.clientJson()));shared.getJSONObject("clients").put(source,RhActions.obj("name","测试平板","platform","android"));RhShared.apply(rig.context,shared);
            rig.home=new RhHome(rig.activity,rig.actions);for(ComputerDetails host:hosts)rig.home.update(host);
        });settle();
    }
    void stop(){rig.main(()->{if(panel!=null){panel.dispose();panel=null;}if(rig.home!=null){rig.home.dispose();rig.home=null;}if(rig.activity!=null){rig.activity.finish();rig.activity=null;}});rig.instrument.waitForIdleSync();}
    void inject(String kind,String value)throws Exception{
        rig.main(()->{String name=kind.equals("sync")?"syncError":kind.equals("copy")?"copyError":"formError";Field f=RhHome.class.getDeclaredField(name);f.setAccessible(true);f.set(rig.home,value);
            if(kind.equals("copy"))((TextView)field("copyFailure")).setText(value);else{Method refresh=RhHome.class.getDeclaredMethod(kind.equals("sync")?"refreshSyncFooter":"refreshProfileFooter");refresh.setAccessible(true);refresh.invoke(rig.home);}
        });settle();
    }
    void errorCase(String name,String kind)throws Exception{
        if(kind.equals("sync")){action("app.settings.tab",RhActions.obj("tab","sync"));action("sync.edit",RhActions.obj("key","username","value","unfinished-185"));}
        else{action("device.settings.open",RhActions.obj("uuid",uuid));action("settings.set",RhActions.obj("key","volume","value",51));if(kind.equals("copy"))action("settings.copy.preview",RhActions.obj("clientId",source));}
        String saved=RhShared.canonical(RhShared.read(rig.context)),draft=kind.equals("sync")?null:rig.state().getJSONObject("draft").toString();inject(kind,message);
        JSONObject row=RhActions.obj("name",name,"kind",kind,"injectedError",true);ScrollView[] details={null};TextView[] label={null};Button[] cancel={null};boolean[] usable={false};
        rig.main(()->{View footer=(View)field("formFooter");ScrollView body=(ScrollView)field("scroll");label[0]=(TextView)field(kind.equals("sync")?"syncErrorView":kind.equals("copy")?"copyFailure":"formErrorView");
            for(ViewParent p=label[0].getParent();p instanceof View&&p!=footer;p=p.getParent())if(p instanceof ScrollView){details[0]=(ScrollView)p;break;}
            Button primary=button(footer,kind.equals("copy")?"复制到草稿":"保存");cancel[0]=button(footer,"取消");float density=rig.activity.getResources().getDisplayMetrics().density;
            row.put("bodyHeightDp",body.getHeight()/density);row.put("primaryFullyVisible",full(primary));row.put("cancelFullyVisible",full(cancel[0]));row.put("bounded",details[0]!=null);row.put("fullTextPreserved",message.contentEquals(label[0].getText()));
            usable[0]=body.getHeight()/density>=96&&full(primary)&&full(cancel[0])&&details[0]!=null&&message.contentEquals(label[0].getText());if(details[0]!=null){row.put("errorHeightDp",details[0].getHeight()/density);usable[0]&=details[0].getHeight()/density<=80;}
        });rig.capture(name+"-top");
        if(usable[0]){
            rig.main(()->details[0].scrollTo(0,100000));settle();int[] bottom={0};rig.main(()->{bottom[0]=details[0].getScrollY();row.put("endVisible",bottom[0]>0&&bottom[0]+details[0].getHeight()>=label[0].getBottom());assertTrue(row.toString(),row.getBoolean("endVisible"));});rig.capture(name+"-bottom");
            inject(kind,message);rig.main(()->assertEquals("Same error reset reading position",bottom[0],details[0].getScrollY()));
            inject(kind,message+" changed");rig.main(()->assertEquals("New error did not reset",0,details[0].getScrollY()));
            if(name.contains("1008")&&name.contains("font1")){
                rig.main(()->{assertTrue(details[0].requestFocus());assertTrue(details[0].dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MOVE_END)));details[0].dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MOVE_END));});Thread.sleep(350);settle();rig.main(()->assertTrue(details[0].getScrollY()>0));
                rig.main(()->{assertTrue(details[0].dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MOVE_HOME)));details[0].dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MOVE_HOME));});Thread.sleep(350);settle();rig.main(()->assertEquals(0,details[0].getScrollY()));row.put("ownKeyboardVerified",true);
            }
            rig.main(()->{assertTrue(full(cancel[0]));assertTrue(cancel[0].performClick());});settle();assertEquals(saved,RhShared.canonical(RhShared.read(rig.context)));if(kind.equals("copy"))assertEquals(draft,rig.state().getJSONObject("draft").toString());row.put("actualCancelPreservedValues",true);
        }else action(kind.equals("sync")?"sync.cancel":kind.equals("copy")?"settings.copy.cancel":"settings.cancel",RhActions.obj());
        row.put("usable",usable[0]);cases.put(row);RhShared.write(new File(rig.folder,"progress.json"),RhActions.obj("cases",cases));
    }
    void capture(String name,String theme)throws Exception{settle();rig.capture(name);images.put(RhActions.obj("name",name,"file",name+".png","theme",theme,"viewport","wide","fixture",true,"sourceKind","fixture"));}
    void catalog(boolean dark)throws Exception{
        String suffix="-wide-"+(dark?"dark":"light"),theme=dark?"dark":"light";start(1008,640,1,dark);capture("devices"+suffix,theme);
        action("device.settings.open",RhActions.obj("uuid",uuid));for(String tab:new String[]{"video","audio","input","network","advanced"}){action("settings.tab",RhActions.obj("tab",tab));capture("form-"+tab+suffix,theme);}
        action("settings.cancel",RhActions.obj());for(String tab:new String[]{"updates","sync","appearance","about"}){action("app.settings.tab",RhActions.obj("tab",tab));capture("form-"+tab+suffix,theme);}
        rig.main(()->{rig.home.dispose();rig.home=null;FrameLayout root=new FrameLayout(rig.activity);root.setBackgroundColor(Color.rgb(40,48,65));rig.activity.setContentView(root);panel=new RhStreamPanel(rig.activity,root,(id,args)->{throw new AssertionError("Catalogue does not send actions");});panel.setDeviceName("winserver · 布局场景");panel.update(false,false,false,100,RhActions.obj("monitors",new JSONArray(),"activeId",""),false,"");panel.updateControls(true,false,1,false,"","","");panel.setExpanded(true);panel.setTab("input");});capture("panel-input"+suffix,theme);stop();
    }
    @Test public void ownWindowErrorsOrCurrentCatalogue()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("saveErrorRun","");org.junit.Assume.assumeFalse("Dedicated opt-in run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));String mode=InstrumentationRegistry.getArguments().getString("saveErrorMode","errors");assertTrue(mode.equals("errors")||mode.equals("catalog"));
        rig.context=rig.instrument.getTargetContext();assertEquals("com.rhremote.client.uicheck172",rig.context.getPackageName());assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));rig.folder=new File(rig.context.getExternalFilesDir("ui-evidence"),run);assertFalse(rig.folder.exists());assertTrue(rig.folder.mkdirs());
        try{if(mode.equals("catalog")){catalog(false);catalog(true);}else for(int[] size:new int[][]{{360,640},{640,360},{1008,640}})for(int font:new int[]{1,2})for(boolean dark:new boolean[]{false,true}){start(size[0],size[1],font,dark);for(String kind:new String[]{"profile","sync","copy"})errorCase((dark?"dark":"light")+"-"+size[0]+"x"+size[1]+"-font"+font+"-"+kind,kind);stop();}}
        finally{stop();rig.main(()->RhUiFixtureActivity.fontScale(0));}
        String version=rig.context.getPackageManager().getPackageInfo(rig.context.getPackageName(),0).versionName;RhShared.write(new File(rig.folder,"result.json"),RhActions.obj("version",version,"mode",mode,"cases",cases,"images",images,"visualOnly",mode.equals("catalog"),"functionalVerification",!mode.equals("catalog"),"fixture",true,"scope","Own RhUiFixtureActivity and View.draw; controlled errors; public test devices; no remote operations, OS input or screenshots"));
        if(mode.equals("catalog"))assertEquals(22,images.length());else{assertEquals(36,cases.length());for(int i=0;i<cases.length();i++)assertTrue(cases.getJSONObject(i).toString(),cases.getJSONObject(i).getBoolean("usable"));}
    }
}
