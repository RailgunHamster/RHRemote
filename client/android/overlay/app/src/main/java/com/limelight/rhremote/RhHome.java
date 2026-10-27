package com.limelight.rhremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.util.*;

/** Native RHRemote shell. Device browsing and session admission use the same action dispatcher. */
public final class RhHome implements RhActions.Target {
    private final RhBackNavigation systemBack;
    public interface Actions { void connect(ComputerDetails d); void add(String address); void advanced(); void pair(ComputerDetails d,String pin);void cancelPair();void refresh();
        default Runnable prepareConnect(ComputerDetails d,RhAdmission operation)throws Exception{return ()->connect(d);}
        default void setDiscoveryPaused(boolean paused){}
        default ComputerDetails inspectAdd(String address,RhDeviceAdd operation)throws Exception{throw new java.io.IOException("设备查找暂不可用");}
        default ComputerDetails commitAdd(ComputerDetails verified)throws Exception{return verified;}
    }
    private long lastSyncAttempt=0;private final Activity activity;private final Actions actions;
    private final LinearLayout root,content,bar,body,sidebar,compactHeader,formHeader,formFooter;private final TextView status;
    private final FrameLayout workspace;
    private final RhDeviceBrowser browser;
    private final Map<String,View> navigation=new HashMap<>();
    private String browserUuid="",deviceQuery="";
    private String infoUuid="",infoName="",infoOriginal="",infoError="";private EditText infoEditor;private TextView infoFailure,infoNotice;private Button infoReset;
    private int shellBucket=-1;
    private final RelativeLayout empty;
    private final ScrollView scroll;
    private final LinkedHashMap<String,ComputerDetails> computers=new LinkedHashMap<>();
    private final HashMap<String,String> signatures=new HashMap<>();
    private final int page,surface,soft,ink,muted,line,blue,blueSoft,purple,purpleSoft,green;
    private boolean profileVisible;private String statusScope="";
    private String pageId="devices",settingsTab="updates",renderedProfileTab="";
    private RhProfile draft;private ComputerDetails selected;
    private RhDeviceAdd addOperation;private String addAddress="",addError="";
    private EditText addEditor;private Button addSubmit,addCancel;private TextView addFailure,addProgressText;private LinearLayout addProgress;
    private String draftDisplayName="",draftAddress="",draftAddresses="",draftRelay="";private EditText displayNameEditor,primaryEditor,backupEditor,relayEditor;
    private boolean networkBusy=false;
    private RhAdmission admission;
    private final RhConnection connection=new RhConnection();
    private final Runnable connectionTick=this::refreshConnection;
    private String profileTab="video";private final Map<String,EditText> numberEditors=new HashMap<>();
    private final Map<String,String> numberInputs=new LinkedHashMap<>();
    private final Map<String,View> profileControls=new HashMap<>();
    private final Map<String,String[]> selectKeys=new HashMap<>();
    private final Map<String,Button> presets=new HashMap<>();
    private String draftBaseline="",formError="",formErrorField="";
    private boolean bindingProfile;
    private TextView formNotice,formErrorView;private Button formSave;
    private org.json.JSONObject pendingNavigation;private AlertDialog navigationDialog;
    private boolean profileSourcesExpanded;
    private org.json.JSONObject copyPreview;private String copyNotice="",copyError="";private TextView copyFailure;
    private final RhSync sync;private boolean syncBusy;
    private boolean syncClearPending;private AlertDialog syncClearDialog;
    private org.json.JSONObject syncDraft;private String syncError="";
    private TextView syncNotice,syncErrorView;private Button syncSave,syncCancel;

    private final RhUpdates updates;private boolean updatesBusy;
    private boolean resumeInstall;
    private TextView updateStatus,updateProvider,updateBytes,updateFailureDetails;
    private boolean updateDetails;private Button updateDetailsToggle;
    private ProgressBar updateProgress;
    private Button updatePrimary;
    private TextView updateVersion;
    private final android.os.Handler updateUi=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable updateTick=this::refreshUpdateUi;
    private boolean pairing;private String pairPin="";
    private static final java.util.concurrent.ExecutorService NETWORK=java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final java.util.concurrent.ExecutorService CONNECT=java.util.concurrent.Executors.newSingleThreadExecutor(task->new Thread(task,"RHRemote admission"));
    public RhHome(Activity a,Actions callbacks) {
        activity=a;actions=callbacks;sync=new RhSync(a);updates=new RhUpdates(a);systemBack=new RhBackNavigation(a,this::goBack);
        boolean dark=RhShared.dark(a);
        page=c(dark?"#151b29":"#f6f7fa");surface=c(dark?"#1b2232":"#ffffff");soft=c(dark?"#202a3e":"#f8faff");
        ink=c(dark?"#e1e8f6":"#34415b");muted=c(dark?"#a4b1c8":"#69778e");line=c(dark?"#303c53":"#e5eaf4");
        blue=c(dark?"#6f8af1":"#5274e8");blueSoft=c(dark?"#253452":"#edf2ff");purple=c(dark?"#ba9aef":"#8b5be2");purpleSoft=c(dark?"#332845":"#f4efff");green=c(dark?"#8bc7b1":"#2e795f");
        a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surface));
        a.getWindow().getDecorView().setBackgroundColor(surface);
        a.getWindow().setStatusBarColor(page);a.getWindow().setNavigationBarColor(surface);
        WindowCompat.getInsetsController(a.getWindow(),a.getWindow().getDecorView()).setAppearanceLightStatusBars(!dark);
        WindowCompat.getInsetsController(a.getWindow(),a.getWindow().getDecorView()).setAppearanceLightNavigationBars(!dark);
        root=column();root.setBackgroundColor(surface);
        WindowCompat.setDecorFitsSystemWindows(a.getWindow(),false);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{Insets safe=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());root.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
        compactHeader=row();compactHeader.setPadding(dp(16),dp(8),dp(16),dp(8));
        ImageView logo=new ImageView(a);logo.setImageResource(R.drawable.rh_icon);LinearLayout.LayoutParams logoParams=new LinearLayout.LayoutParams(dp(32),dp(32));logoParams.setMarginEnd(dp(12));compactHeader.addView(logo,logoParams);
        compactHeader.addView(text("RHRemote",19,ink,true),new LinearLayout.LayoutParams(0,-1,1));compactHeader.addView(text("Moonlight",12,muted,false));root.addView(compactHeader,new LinearLayout.LayoutParams(-1,dp(64)));
        body=row();body.setGravity(Gravity.TOP);sidebar=column();sidebar.setPadding(dp(12),dp(16),dp(12),dp(16));sidebar.setBackground(bg(surface,line,0));body.addView(sidebar,new LinearLayout.LayoutParams(dp(72),-1));
        LinearLayout stage=column();formHeader=column();formHeader.setBackgroundColor(surface);formHeader.setVisibility(View.GONE);stage.addView(formHeader);workspace=new FrameLayout(a);
        scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(pageId.equals("settings")||pageId.equals("global-settings")||pageId.equals("add")||pageId.equals("profile-copy"))formPadding(r-l);});
        content=column();content.setPadding(dp(24),dp(24),dp(24),dp(24));scroll.addView(content);
        workspace.addView(scroll,new FrameLayout.LayoutParams(-1,-1));browser=new RhDeviceBrowser(a,this::invoke);workspace.addView(browser,new FrameLayout.LayoutParams(-1,-1));stage.addView(workspace,new LinearLayout.LayoutParams(-1,0,1));formFooter=column();formFooter.setBackgroundColor(surface);formFooter.setVisibility(View.GONE);stage.addView(formFooter);
        status=text("",12,muted,false);status.setPadding(dp(16),dp(8),dp(16),dp(8));status.setVisibility(View.GONE);stage.addView(status);
        body.addView(stage,new LinearLayout.LayoutParams(0,-1,1));root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        bar=row();bar.setGravity(Gravity.CENTER);bar.setPadding(dp(12),dp(8),dp(12),dp(8));bar.setBackground(bg(surface,line,0));
        String[] navIds={"devices.open","device.add.open","app.settings.open"},navLabels={"设备","添加","设置"};
        for(int i=0;i<navIds.length;i++){final String id=navIds[i];Button b=button(navLabels[i],false,()->invoke(id,RhActions.obj()));b.setTag("nav:"+id);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);if(i>0)lp.setMarginStart(dp(8));bar.addView(b,lp);}root.addView(bar);
        empty=new RelativeLayout(a); // Compatibility with the upstream activity's discovery placeholder.
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->applyShell(r-l-root.getPaddingLeft()-root.getPaddingRight()));
        a.setContentView(root);ViewCompat.requestApplyInsets(root);applyShell(dp(a.getResources().getConfiguration().screenWidthDp));RhActions.initialize(a,this);restoreSharedDevices();showDevices();root.post(this::resumed);
        final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());handler.postDelayed(new Runnable(){public void run(){if(activity.isDestroyed())return;if(!RhActions.hasStream()){if(sync.state().optBoolean("automatic")&&!syncBusy&&draft==null&&infoUuid.isEmpty()&&addOperation==null&&!pageId.equals("add")&&!syncDirty()&&pendingNavigation==null&&!syncClearPending&&sync.state().optJSONArray("conflicts").length()==0&&System.currentTimeMillis()-Math.max(lastSyncAttempt,sync.state().optLong("lastSyncMs"))>=sync.state().optInt("intervalMinutes",5)*60000L){lastSyncAttempt=System.currentTimeMillis();invoke("sync.run",RhActions.obj("automatic",true));}long last=activity.getSharedPreferences("rhremote.global",0).getLong("lastUpdateCheck",0);if(!updatesBusy&&System.currentTimeMillis()-last>21600000){activity.getSharedPreferences("rhremote.global",0).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).apply();startUpdate("updates.check",true);}}handler.postDelayed(this,30000);}},15000);
    }
    /** Only navigation identifiers are serialized; form data and credentials stay out of the Bundle. */
    public void saveNavigation(android.os.Bundle state){
        android.os.Bundle navigation=new android.os.Bundle();navigation.putInt("version",1);
        navigation.putString("page",pageId.equals("global-settings")?"global-settings":"devices");
        navigation.putString("tab",settingsTab);navigation.putString("selected",selected==null?browserUuid:selected.uuid);
        navigation.putString("query",deviceQuery);navigation.putBoolean("detail",browser.isDetailOpen());state.putBundle("rhremote.navigation",navigation);
    }
    public void restoreNavigation(android.os.Bundle state){
        if(state==null)return;android.os.Bundle navigation=state.getBundle("rhremote.navigation");if(navigation==null||navigation.getInt("version")!=1)return;
        String query=navigation.getString("query",""),uuid=navigation.getString("selected",""),tab=navigation.getString("tab","updates");
        if(query.codePointCount(0,query.length())<=128)deviceQuery=query;
        if(computers.containsKey(uuid))browserUuid=uuid;
        if(Arrays.asList("updates","sync","appearance","about").contains(tab))settingsTab=tab;
        renderDevices(false);browser.showDetail(navigation.getBoolean("detail"));
        if(navigation.getString("page","").equals("global-settings")){pageId="global-settings";showGlobal();}
    }
    public RelativeLayout emptyView(){return empty;}
    private int c(String hex){return Color.parseColor(hex);}private int dp(float v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private GradientDrawable bg(int color,int border,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    private TextView text(String value,int size,int color,boolean medium){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);if(medium)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private Button button(String title,boolean primary,Runnable action){Button b=new Button(activity);b.setText(title);b.setTextSize(14);b.setAllCaps(false);b.setStateListAnimator(null);b.setElevation(0);b.setTypeface(Typeface.create("sans-serif-medium",0));RhUiStyle.button(b,primary?Color.WHITE:ink,muted,bg(primary?blue:surface,primary?0:line,8),bg(soft,line,8),bg(primary?blue:surface,primary?ink:blue,8));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setOnClickListener(v->action.run());b.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(48)));return b;}
    private Button tab(String title,boolean selected,Runnable action){Button b=button(title,false,action);b.setSelected(selected);b.setTextColor(selected?blue:muted);b.setBackground(bg(selected?blueSoft:surface,0,8));return b;}
    private RhActionRow actionRow(){RhActionRow row=new RhActionRow(activity);row.setPadding(0,dp(8),0,dp(8));return row;}
    private void applyShell(int width){int bucket=width>=dp(1180)?2:width>=dp(840)?1:0;if(bucket==shellBucket)return;shellBucket=bucket;boolean wide=bucket>0;sidebar.setVisibility(wide?View.VISIBLE:View.GONE);compactHeader.setVisibility(wide?View.GONE:View.VISIBLE);bar.setVisibility(wide?View.GONE:View.VISIBLE);sidebar.getLayoutParams().width=dp(bucket==2?184:72);sidebar.removeAllViews();navigation.clear();
        LinearLayout brand=row();ImageView icon=new ImageView(activity);icon.setImageResource(R.drawable.rh_icon);brand.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));if(bucket==2){LinearLayout names=column();names.setPadding(dp(10),0,0,0);names.addView(text("RHRemote",18,ink,true));names.addView(text("Moonlight",11,muted,false));brand.addView(names);}sidebar.addView(brand,new LinearLayout.LayoutParams(-1,dp(68)));
        nav("远程设备",R.drawable.rh_monitor,"devices.open",bucket==2);nav("添加电脑",R.drawable.rh_plus,"device.add.open",bucket==2);sidebar.addView(new View(activity),new LinearLayout.LayoutParams(-1,0,1));nav("设置",R.drawable.rh_settings,"app.settings.open",bucket==2);nav("主题",R.drawable.rh_moon,"appearance.toggle",bucket==2);if(bucket==2){TextView version=text("v"+com.limelight.BuildConfig.VERSION_NAME+"\nMoonlight · GPL v3",11,muted,false);version.setPadding(dp(10),dp(20),0,0);sidebar.addView(version);}content.setPadding(dp(wide?24:16),dp(24),dp(wide?24:16),dp(24));refreshNavigation();}
    private void nav(String title,int icon,String id,boolean labels){LinearLayout item=row();item.setGravity(labels?Gravity.CENTER_VERTICAL:Gravity.CENTER);item.setPadding(dp(10),0,dp(10),0);ImageView image=new ImageView(activity);image.setImageResource(icon);image.setColorFilter(id.equals("devices.open")?blue:muted);item.addView(image,new LinearLayout.LayoutParams(dp(22),dp(22)));if(labels){TextView label=text(title,14,ink,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMarginStart(dp(12));item.addView(label,p);}item.setContentDescription(title);item.setTag("nav:"+id);item.setFocusable(true);item.setOnClickListener(v->invoke(id,RhActions.obj()));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.bottomMargin=dp(8);sidebar.addView(item,p);navigation.put(id,item);}
    private void refreshNavigation(){
        if(!statusScope.equals(pageId+(pageId.equals("global-settings")?"/"+settingsTab:"")))status.setVisibility(View.GONE);
        String destination=pageId.equals("global-settings")?"app.settings.open":pageId.equals("add")?"device.add.open":"devices.open";
        for(Map.Entry<String,View> item:navigation.entrySet()){
            boolean chosen=item.getKey().equals(destination);View view=item.getValue();view.setSelected(chosen);view.setBackground(bg(chosen?blueSoft:surface,0,8));
            ViewGroup row=(ViewGroup)view;for(int i=0;i<row.getChildCount();i++){View child=row.getChildAt(i);if(child instanceof ImageView)((ImageView)child).setColorFilter(chosen?blue:muted);else if(child instanceof TextView)((TextView)child).setTextColor(chosen?blue:ink);}
        }
        for(int i=0;i<bar.getChildCount();i++){View child=bar.getChildAt(i);if(child instanceof Button){boolean chosen=("nav:"+destination).equals(child.getTag());child.setSelected(chosen);((Button)child).setTextColor(chosen?blue:muted);child.setBackground(bg(chosen?blueSoft:surface,chosen?0:line,8));}}
    }
    private void showContent(){browser.setVisibility(View.GONE);scroll.setVisibility(View.VISIBLE);formHeader.setVisibility(View.GONE);formFooter.setVisibility(View.GONE);scroll.setBackgroundColor(surface);content.setBackgroundColor(surface);refreshNavigation();}
    private void heading(String title,String sub){TextView h=text(title,26,ink,true);h.setPadding(0,0,0,dp(18));content.addView(h);if(!sub.isEmpty()){TextView s=text(sub,13,muted,false);s.setPadding(0,0,0,dp(20));content.addView(s);}}
    public void update(ComputerDetails d){String signature=d.name+"|"+d.state+"|"+d.pairState+"|"+RhProfile.load(activity,d.uuid).summary();String old=signatures.put(d.uuid,signature);computers.put(d.uuid,d);if(!profileVisible&&!signature.equals(old))renderDevices(false);}
    public void message(String value){statusScope=pageId+(pageId.equals("global-settings")?"/"+settingsTab:"");if(!copyError.isEmpty()&&copyError.equals(value)||pageId.equals("device-info")&&!infoError.isEmpty()&&infoError.equals(value)||draft!=null&&!formError.isEmpty()&&formError.equals(value)||syncDraft!=null&&!syncError.isEmpty()&&syncError.equals(value)||pageId.equals("add")&&!addError.isEmpty()&&addError.equals(value))value="";status.setText(value==null?"":value);status.setVisibility(value==null||value.isEmpty()?View.GONE:View.VISIBLE);}
    public boolean goBack(){if(syncClearPending){invoke("sync.clear.resolve",RhActions.obj("choice","cancel"));return true;}if(pendingNavigation!=null){invoke("navigation.resolve",RhActions.obj("choice","stay"));return true;}if(admission!=null){invoke("stream.disconnect",RhActions.obj());return true;}if(pairing){invoke("pair.cancel",RhActions.obj());return true;}if(pageId.equals("add")){invoke("device.add.cancel",RhActions.obj());return true;}if(profileVisible||pageId.equals("global-settings")){invoke("navigation.back",RhActions.obj());return true;}if(browser.isDetailOpen()){invoke("navigation.back",RhActions.obj());return true;}return false;}
    public void dispose(){systemBack.close();if(addOperation!=null){RhDeviceAdd retired=addOperation;addOperation=null;retired.cancel();}if(admission!=null)admission.cancel();if(navigationDialog!=null)navigationDialog.dismiss();if(syncClearDialog!=null)syncClearDialog.dismiss();updateUi.removeCallbacks(updateTick);updateUi.removeCallbacks(connectionTick);}
    public void rebind(){activity.setContentView(root);ViewCompat.requestApplyInsets(root);if(admission!=null){if(pairing&&!admission.state().optBoolean("cancelled"))showPair();else showAdmission(admission);}else if(pageId.equals("connection"))showConnection();else if(pageId.equals("global-settings"))showGlobal();else if(pageId.equals("device-info"))showInfo();else if(pageId.equals("profile-copy"))showCopyPreview();else if(draft!=null)showProfile(selected,draft);else if(pageId.equals("add"))showAdd();else renderDevices(false);if(pendingNavigation!=null)showNavigationPrompt();}
    public void resumed(){if(pageId.equals("connection"))refreshConnection();if(updates.resumeInstallRequest())resumeInstall=true;if(resumeInstall&&!updatesBusy){resumeInstall=false;startUpdate("updates.install");}else if(pageId.equals("global-settings")&&settingsTab.equals("updates"))refreshUpdateControls();}
    private void refreshUpdateUi(){if(activity.isDestroyed())return;if(pageId.equals("global-settings")&&settingsTab.equals("updates"))refreshUpdateControls();if(updatesBusy)updateUi.postDelayed(updateTick,300);}
    public void showDevices(){if(admission!=null){showAdmission(admission);return;}pageId="devices";discardDraft();selected=null;browser.showDetail(false);renderDevices(true);message("");}
    private void returnToDevice(){String uuid=selected==null?browserUuid:selected.uuid;showDevices();browserUuid=uuid;browser.showDetail(true);renderDevices(false);}
    private boolean connectionBusy(){return connection.busy()||RhActions.hasStream();}
    void connectionFailure(String failure){connection.fail(failure);if(pageId.equals("connection"))showConnection();}
    void connectionStopping(String uuid){if(connection.uuid.equals(uuid)){connection.stop();if(pageId.equals("connection"))showConnection();}}
    void connectionFrame(String uuid){if(connection.uuid.equals(uuid)){connection.frame();if(pageId.equals("connection"))showConnection();}}
    private void refreshConnection(){
        updateUi.removeCallbacks(connectionTick);if(activity.isDestroyed()||!pageId.equals("connection"))return;
        org.json.JSONObject active=RhActions.activeState();String before=connection.phase;boolean wasBusy=connectionBusy();
        if(admission==null&&connection.busy()){
            if(RhActions.hasStream()){
                if(active.optBoolean("firstFrame"))connection.frame();
                else if(active.optString("phase").equals("reconnecting"))connection.phase="reconnecting";
            }else if(!connection.stopping)connection.fail("远程连接已结束");
            else connection.finished(false,"");
        }
        if(!wasBusy||!before.equals(connection.phase)||!RhActions.hasStream()){
            if(connection.stopping&&!connectionBusy()){returnToDevice();message(connection.title());return;}
            showConnection();
        }
        if(connectionBusy())updateUi.postDelayed(connectionTick,300);
    }
    private void showAdmission(RhAdmission operation){connection.stage(operation.state());showConnection();}
    private void showConnection(){
        pageId="connection";profileVisible=true;showContent();content.removeAllViews();formHeader.removeAllViews();formFooter.removeAllViews();formHeader.setVisibility(View.VISIBLE);formFooter.setVisibility(View.VISIBLE);
        boolean pairFlow=connection.operation.equals("pair");TextView title=text(pairFlow?"配对":"连接",23,ink,true);formHeader.addView(title);heading(connection.name,"");
        TextView stage=text(connection.title(),16,connection.phase.equals("failed")?c("#b04452"):blue,true);stage.setContentDescription(pairFlow?"配对阶段":"连接阶段");content.addView(stage);
        if(connectionBusy()&&!connection.phase.equals("connected")){ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);LinearLayout.LayoutParams progressParams=new LinearLayout.LayoutParams(-1,dp(4));progressParams.topMargin=dp(20);content.addView(progress,progressParams);}
        if(!pairFlow){label("1  检查地址与配对");label("2  建立串流");label("3  显示远程画面");}
        if(!connection.error.isEmpty()){String detail=connection.error.startsWith("连接失败：")?connection.error.substring(5):connection.error;TextView error=text(detail,14,c("#b04452"),false);error.setContentDescription("连接错误");error.setPadding(0,dp(20),0,dp(8));content.addView(error);}
        RhActionRow footer=actionRow();
        if(connectionBusy()){
            boolean cleaning=connection.stopping||connection.phase.equals("cancelling")||connection.phase.equals("cleaning")||connection.phase.equals("failed");
            Button cancel=button(cleaning?"正在清理…":pairFlow?"取消配对":connection.phase.equals("connected")?"断开连接":"取消连接",false,()->invoke(pairFlow?"pair.cancel":"stream.disconnect",RhActions.obj()));cancel.setEnabled(!cleaning);footer.addView(cancel);
        }else{
            footer.addView(button("返回设备",false,()->invoke("connection.dismiss",RhActions.obj())));
            if(connection.phase.equals("failed"))footer.addView(button(connection.operation.equals("pair")?"重试配对":"重试连接",true,()->invoke("connection.retry",RhActions.obj())));
        }
        LinearLayout aligned=row();aligned.setGravity(Gravity.END);aligned.addView(footer,new LinearLayout.LayoutParams(-2,-2));formFooter.addView(aligned);formPadding(scroll.getWidth());refreshNavigation();
    }
    private void beginAdmission(ComputerDetails target,boolean connect,String pin){
        RhActions.clearConnectionError();browserUuid=target.uuid;selected=target;String alias=RhShared.alias(activity,target.uuid);connection.begin(target.uuid,alias.isEmpty()?target.name:alias);connection.operation=connect?"connect":"pair";
        RhProfile profile=RhProfile.load(activity,target.uuid);networkBusy=true;
        admission=new RhAdmission(activity,target.uuid,CONNECT,new RhAdmission.Listener(){
            public void changed(RhAdmission operation){if(activity.isDestroyed())return;actions.setDiscoveryPaused(true);if(!operation.state().optString("phase").equals("pairing"))showAdmission(operation);}
            public void finished(RhAdmission operation,boolean handedOff,String result){if(admission!=operation)return;admission=null;networkBusy=false;pairing=false;pairPin="";
                String error=operation.state().optString("error");if(error.isEmpty()&&operation.state().optBoolean("cancelled"))connection.stop();connection.finished(handedOff,error);
                if(!activity.isDestroyed()){
                    if(handedOff||!error.isEmpty()){showConnection();updateUi.postDelayed(connectionTick,300);}
                    else{returnToDevice();if(!result.isEmpty())message(result);}
                    actions.setDiscoveryPaused(false);
                }}
        });
        admission.start(operation->{
            if(connect)RhStatistics.begin(profile);
            operation.progress("resolving");ComputerDetails routed=RhNetwork.resolve(activity,new ComputerDetails(target),profile,null,operation);
            operation.progress("identity");ComputerDetails resolved=RhNetwork.refreshConnectionDetails(activity,routed,operation);
            if(!connect||resolved.pairState!=PairingManager.PairState.PAIRED||resolved.serverCert==null)return ()->{update(resolved);connection.operation="pair";pairPin=pin;pairing=true;showPair();try{operation.pair(()->actions.pair(resolved,pin),actions::cancelPair);}catch(java.io.IOException e){throw new IllegalStateException(e);}};
            Runnable launch=actions.prepareConnect(resolved,operation);operation.checkpoint();
            return ()->{try(RhStatistics.Scope trace=new RhStatistics.Scope("admission_ui_update")){update(resolved);}try{operation.handoff(launch);}catch(java.io.IOException e){throw new IllegalStateException(e);}};
        });
    }
    private void renderDevices(boolean resetScroll){
        profileVisible=false;formHeader.setVisibility(View.GONE);formFooter.setVisibility(View.GONE);scroll.setVisibility(View.GONE);browser.setVisibility(View.VISIBLE);refreshNavigation();
        List<ComputerDetails> list=RhShared.ordered(activity,computers.values());
        if(!computers.containsKey(browserUuid))browserUuid=list.isEmpty()?"":list.get(0).uuid;
        browser.update(list,browserUuid,deviceQuery);
    }
    private void label(String value){TextView t=text(value,13,muted,false);t.setPadding(0,dp(15),0,dp(7));content.addView(t);}
    private void choices(String[] values,String selected,java.util.function.Consumer<String> onSelect){RhActionRow group=actionRow();for(String value:values){Button b=tab(value,value.equals(selected),()->onSelect.accept(value));presets.put(value,b);group.addView(b);}content.addView(group);}
    private ArrayAdapter<String> options(String[] values){
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_item,values){
            private View style(View view,boolean popup){TextView label=(TextView)view;label.setTextColor(ink);label.setTextSize(15);label.setMinHeight(dp(48));label.setGravity(Gravity.CENTER_VERTICAL);label.setPadding(dp(12),dp(8),dp(12),dp(8));if(popup)label.setBackgroundColor(surface);return label;}
            @Override public View getView(int position,View reused,android.view.ViewGroup parent){return style(super.getView(position,reused,parent),false);}
            @Override public View getDropDownView(int position,View reused,android.view.ViewGroup parent){return style(super.getDropDownView(position,reused,parent),true);}
        };adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return adapter;
    }
    private CheckBox check(String label,boolean checked,java.util.function.Consumer<Boolean> change){CheckBox b=new CheckBox(activity);b.setText(label);b.setTextColor(ink);b.setTextSize(14);b.setChecked(checked);b.setMinHeight(dp(48));b.setButtonTintList(android.content.res.ColorStateList.valueOf(blue));b.setOnCheckedChangeListener((v,state)->change.accept(state));content.addView(b);return b;}
    public void showProfile(ComputerDetails d){copyPreview=null;copyNotice="";copyError="";numberInputs.clear();formError="";formErrorField="";draftBaseline="";renderedProfileTab="";pageId="settings";browserUuid=d.uuid;selected=d;draft=RhProfile.load(activity,d.uuid);draftDisplayName=RhShared.alias(activity,d.uuid);try{org.json.JSONObject host=RhNetwork.metadata(activity,d);draftAddress=host.getString("address");org.json.JSONArray extra=host.optJSONArray("addresses");List<String> addresses=new ArrayList<>();if(extra!=null)for(int i=0;i<extra.length();i++)addresses.add(extra.getString(i));draftAddresses=android.text.TextUtils.join(", ",addresses);}catch(Exception missing){draftAddress="";draftAddresses="";}draftRelay=draft.relayUrl;profileSourcesExpanded=false;draftBaseline=draftSnapshot();showProfile(d,draft);scroll.post(()->scroll.scrollTo(0,0));}
    private void showProfile(ComputerDetails d,RhProfile p){
        int restoreScroll=profileTab.equals(renderedProfileTab)?scroll.getScrollY():0;renderedProfileTab=profileTab;
        copyFailure=null;numberEditors.clear();profileControls.clear();selectKeys.clear();presets.clear();displayNameEditor=null;primaryEditor=null;backupEditor=null;relayEditor=null;
        profileVisible=true;showContent();content.removeAllViews();org.json.JSONObject shared=RhShared.read(activity);String currentClient=RhShared.clientId(activity);
        buildProfileChrome(d);scroll.setBackgroundColor(page);content.setBackgroundColor(page);formPadding(scroll.getWidth());
        if(profileTab.equals("advanced")){
        org.json.JSONObject groups=shared.optJSONObject("profiles"),clients=shared.optJSONObject("clients");
        int sourceCount=0;for(Iterator<String> it=groups.keys();it.hasNext();){String client=it.next();org.json.JSONObject group=groups.optJSONObject(client);if(!client.equals(currentClient)&&group!=null&&group.has(d.uuid))sourceCount++;}
        Button sources=tab("从其他客户端复制配置",profileSourcesExpanded,()->invoke("settings.sources.toggle",RhActions.obj()));sources.setEnabled(sourceCount>0);sources.setAlpha(sourceCount>0?1f:0.45f);content.addView(sources);if(sourceCount==0)label("此设备还没有其他客户端的配置");if(profileSourcesExpanded) for(Iterator<String> it=groups.keys();it.hasNext();){String client=it.next();org.json.JSONObject group=groups.optJSONObject(client);if(client.equals(currentClient)||group==null||!group.has(d.uuid))continue;org.json.JSONObject source=group.optJSONObject(d.uuid),info=clients.optJSONObject(client);String name=info==null?client:info.optString("name");label(name+" · "+client.substring(Math.max(0,client.length()-4))+" · "+source.optInt("width")+" × "+source.optInt("height")+" · "+source.optInt("fps")+" 帧");Button inspect=button("查看配置",false,()->invoke("settings.copy.preview",RhActions.obj("clientId",client)));inspect.setTag("copy-source:"+client);inspect.setContentDescription("查看 "+name+" 的配置");content.addView(inspect);}
        copyFailure=text(copyError,13,c("#b04452"),false);copyFailure.setVisibility(copyError.isEmpty()?View.GONE:View.VISIBLE);content.addView(copyFailure);
        }
        if(profileTab.equals("network"))try {
            label(RhNetwork.savedSummary(activity,d));label("按路径顺序尝试所有已保存地址，内网地址不会限制外网连接。");
            primaryEditor=field("首选 IP 或主机名",draftAddress,false);watch(primaryEditor,"settings.network.edit","address");
            backupEditor=field("备用地址（逗号分隔，随 WebDAV 同步）",draftAddresses,false);watch(backupEditor,"settings.network.edit","addresses");
            Button diagnose=button(networkBusy?"诊断中…":"诊断连接",false,()->invoke("device.network.diagnose",RhActions.obj("uuid",d.uuid)));diagnose.setEnabled(!networkBusy);content.addView(diagnose);
            label("连接顺序");java.util.ArrayList<String> routes=new java.util.ArrayList<>(p.routeOrder);for(String route:RhProfile.ROUTES)if(!routes.contains(route))routes.add(route);
            for(String route:routes){boolean enabled=p.routeOrder.contains(route);int index=java.util.Arrays.asList(RhProfile.ROUTES).indexOf(route);label(RhProfile.ROUTE_LABELS[index]);RhActionRow controls=actionRow();controls.addView(button(enabled?"启用":"停用",enabled,()->invoke("settings.route.toggle",RhActions.obj("route",route))));if(enabled){controls.addView(button("↑",false,()->invoke("settings.route.move",RhActions.obj("route",route,"direction","up"))));controls.addView(button("↓",false,()->invoke("settings.route.move",RhActions.obj("route",route,"direction","down"))));}content.addView(controls);}
            content.addView(button("恢复默认顺序",false,()->set("routeOrder",new org.json.JSONArray(java.util.Arrays.asList(RhProfile.ROUTES)))));
            relayEditor=field("中继服务器",draftRelay,false);watch(relayEditor,"settings.network.edit","relayUrl");
            label("按已启用的顺序尝试，失败后自动回退。配置随 WebDAV 同步。");label(d.uuid.equals(RhNetwork.state().optString("uuid"))?RhNetwork.summary(RhNetwork.state()):"尚未诊断");
        }catch(Exception e){label("连接地址读取失败："+e.getMessage());}
        if(profileTab.equals("video"))choices(new String[]{"720P","1080P","1440P","平板","4K"},p.width==1280&&p.height==720?"720P":p.width==1920&&p.height==1080?"1080P":p.width==2560&&p.height==1440?"1440P":p.width==2520&&p.height==1680?"平板":p.width==3840&&p.height==2160?"4K":"",v->{int w=v.equals("720P")?1280:v.equals("1080P")?1920:v.equals("1440P")?2560:v.equals("平板")?2520:3840;int h=w==1280?720:w==1920?1080:w==2560?1440:w==2520?1680:2160;set("resolution",new org.json.JSONArray().put(w).put(h));});
        try{org.json.JSONArray fields=RhSettings.catalog(activity);org.json.JSONObject values=p.json();for(int i=0;i<fields.length();i++){
            org.json.JSONObject f=fields.getJSONObject(i);if(!profileTab.equals(f.getString("tab"))||!RhSettings.supports(f,"android"))continue;String key=f.getString("key"),type=f.getString("type"),title=f.getString("label");
            if(type.equals("bool")){profileControls.put(key,check(title,values.getBoolean(key),v->{if(!bindingProfile)set(key,v);}));}
            else if(type.equals("number")){EditText edit=field(title,numberInputs.containsKey(key)?numberInputs.get(key):String.valueOf(values.getInt(key)),false);edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);numberEditors.put(key,edit);profileControls.put(key,edit);watch(edit,"settings.input",key);}
            else if(type.equals("select")){label(title);org.json.JSONArray options=f.getJSONArray("options");String[] labels=new String[options.length()],keys=new String[options.length()];int index=0;for(int j=0;j<options.length();j++){keys[j]=options.getJSONArray(j).getString(0);labels[j]=options.getJSONArray(j).getString(1);if(keys[j].equals(values.getString(key)))index=j;}Spinner spinner=new Spinner(activity);ArrayAdapter<String> adapter=options(labels);spinner.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surface));spinner.setAdapter(adapter);spinner.setSelection(index,false);selectKeys.put(key,keys);profileControls.put(key,spinner);spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long row){if(!bindingProfile&&draft!=null&&!keys[position].equals(draft.json().optString(key)))set(key,keys[position]);}});content.addView(spinner,new LinearLayout.LayoutParams(-1,dp(48)));}
            if(!f.optString("help").isEmpty())label(f.optString("help"));
        }}catch(org.json.JSONException e){throw new IllegalStateException("invalid settings catalog",e);}
        if(profileTab.equals("audio"))label("串流中的声音开关位于控制栏；这里设置下次连接的声音。");
        refreshProfileControls();scroll.post(()->{scroll.scrollTo(0,restoreScroll);if(!formError.isEmpty()){View invalid=profileControls.get(formErrorField);if(formErrorField.equals("address"))invalid=primaryEditor;else if(formErrorField.equals("relayUrl"))invalid=relayEditor;else if(formErrorField.equals("displayName"))invalid=displayNameEditor;if(invalid!=null){invalid.requestFocus();invalid.requestRectangleOnScreen(new android.graphics.Rect(0,0,invalid.getWidth(),invalid.getHeight()),false);}}});
    }
    private void showCopyPreview(){
        pageId="profile-copy";profileVisible=true;showContent();content.removeAllViews();formHeader.removeAllViews();formFooter.removeAllViews();formHeader.setVisibility(View.VISIBLE);formFooter.setVisibility(View.VISIBLE);
        formHeader.addView(text("复制配置",22,ink,true));TextView caption=text(copyPreview.optString("targetName"),13,muted,false);caption.setSingleLine(true);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);formHeader.addView(caption);
        scroll.setBackgroundColor(page);content.setBackgroundColor(page);formPadding(scroll.getWidth());
        copyDetail("来源客户端",copyPreview.optString("sourceName"));copyDetail("目标设备",copyPreview.optString("targetName"));label("将替换当前草稿的连接参数。设备地址和主机鼠标设置沿用当前值。");
        org.json.JSONArray fields=copyPreview.optJSONArray("fields");for(int i=0;i<fields.length();i++){org.json.JSONObject field=fields.optJSONObject(i);copyDetail(field.optString("label"),field.optString("value"));}
        copyFailure=text(copyError,13,c("#b04452"),false);copyFailure.setVisibility(copyError.isEmpty()?View.GONE:View.VISIBLE);formFooter.addView(copyFailure);
        LinearLayout commands=row();commands.setGravity(Gravity.END);commands.addView(button("取消",false,()->invoke("settings.copy.cancel",RhActions.obj())));Button apply=button("复制到草稿",true,()->invoke("settings.copy.apply",RhActions.obj()));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(48));lp.setMarginStart(dp(8));commands.addView(apply,lp);formFooter.addView(commands);scroll.post(()->scroll.scrollTo(0,0));
    }
    private void copyDetail(String title,String value){label(title);TextView detail=text(value,15,ink,false);detail.setTextIsSelectable(true);content.addView(detail,new LinearLayout.LayoutParams(-1,-2));}
    private org.json.JSONObject performCopy(String id,org.json.JSONObject args)throws Exception{
        try{
            if(draft==null||selected==null)throw new IllegalStateException("请先打开连接设置");
            if(id.equals("settings.copy.preview")||id.equals("settings.copy_from")){
                if(!pageId.equals("settings")&&!pageId.equals("profile-copy"))throw new IllegalStateException("请先打开连接设置");
                org.json.JSONObject snapshot=RhProfileCopy.preview(activity,selected.uuid,RhShared.displayName(activity,selected.uuid,selected.name),args.getString("clientId"));
                if(id.equals("settings.copy_from"))applyCopy(snapshot);else{copyPreview=snapshot;copyError="";((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);showCopyPreview();}
            }else if(id.equals("settings.copy.cancel")){
                if(!pageId.equals("profile-copy")||copyPreview==null)throw new IllegalStateException("没有配置预览");copyPreview=null;copyError="";pageId="settings";showProfile(selected,draft);
            }else if(id.equals("settings.copy.apply")){
                if(!pageId.equals("profile-copy")||copyPreview==null)throw new IllegalStateException("请先预览要复制的配置");applyCopy(copyPreview);
            }else throw new IllegalArgumentException("unknown copy action");
            return RhActions.obj("page",pageId);
        }catch(Exception failure){copyError=failure.getMessage()==null?"复制配置失败":failure.getMessage();if(copyFailure!=null){copyFailure.setText(copyError);copyFailure.setVisibility(View.VISIBLE);}throw failure;}
    }
    private void applyCopy(org.json.JSONObject snapshot)throws Exception{
        if(!selected.uuid.equals(snapshot.getString("targetUuid")))throw new IllegalStateException("复制目标已改变，请重新选择配置");
        RhProfile incoming=RhProfile.fromJson(selected.uuid,snapshot.getJSONObject("profile"));incoming.swapButtons=draft.swapButtons;draft=incoming;draftRelay=draft.relayUrl;numberInputs.clear();formError="";formErrorField="";profileSourcesExpanded=false;copyError="";copyPreview=null;copyNotice=profileDirty()?"已从 "+snapshot.getString("sourceName")+" 复制，保存后生效":"与当前配置相同";pageId="settings";showProfile(selected,draft);
    }
    private void formPadding(int width){int inset=Math.max(dp(16),(width-dp(720))/2);content.setPadding(inset,dp(18),inset,dp(24));formHeader.setPadding(inset,dp(16),inset,dp(10));formFooter.setPadding(inset,dp(10),inset,dp(12));}
    private void buildProfileChrome(ComputerDetails device){
        formHeader.removeAllViews();formFooter.removeAllViews();formHeader.setVisibility(View.VISIBLE);formFooter.setVisibility(View.VISIBLE);
        LinearLayout heading=row(),titles=column();titles.addView(text("连接设置",22,ink,true));TextView caption=text(RhShared.displayName(activity,device.uuid,device.name)+" · "+RhShared.clientInfo().optString("name"),13,muted,false);caption.setSingleLine(true);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);titles.addView(caption);heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));heading.addView(button("返回设备",false,()->invoke("navigation.back",RhActions.obj())));formHeader.addView(heading);
        RhActionRow tabs=actionRow();String[] ids={"video","audio","input","network","advanced"},labels={"画面","音频","输入","网络","高级"};for(int i=0;i<ids.length;i++){String id=ids[i];tabs.addView(tab(labels[i],profileTab.equals(id),()->invoke("settings.tab",RhActions.obj("tab",id))));}formHeader.addView(tabs);
        formErrorView=text("",13,c("#b04452"),false);formErrorView.setVisibility(View.GONE);formFooter.addView(formErrorView);
        LinearLayout end=row();formNotice=text("",12,muted,false);formNotice.setSingleLine(true);formNotice.setEllipsize(android.text.TextUtils.TruncateAt.END);end.addView(formNotice,new LinearLayout.LayoutParams(0,-2,1));Button cancel=button("取消",false,()->invoke("settings.cancel",RhActions.obj()));end.addView(cancel);formSave=button("保存",true,()->invoke("settings.save",RhActions.obj()));LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(-2,dp(48));saveParams.setMarginStart(dp(8));end.addView(formSave,saveParams);formFooter.addView(end);refreshProfileFooter();
    }
    private void watch(EditText edit,String action,String key){edit.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(android.text.Editable value){if(!bindingProfile)invoke(action,action.equals("device.add.input")?RhActions.obj("address",value.toString()):action.equals("device.info.input")?RhActions.obj("displayName",value.toString()):RhActions.obj("key",key,"value",value.toString()));}});}
    private String draftSnapshot(){
        if(draft==null)return "";try{
            org.json.JSONObject raw=new org.json.JSONObject(),values=draft.json();for(Map.Entry<String,String> input:numberInputs.entrySet())if(!input.getValue().trim().equals(String.valueOf(values.opt(input.getKey()))))raw.put(input.getKey(),input.getValue());
            return RhShared.canonical(RhActions.obj("profile",values,"name",draftDisplayName.trim(),"address",draftAddress.trim(),"addresses",splitAddresses(draftAddresses),"relay",draftRelay.trim(),"raw",raw));
        }catch(Exception failure){throw new IllegalStateException("设置草稿读取失败",failure);}
    }
    private boolean profileDirty(){return draft!=null&&!draftSnapshot().equals(draftBaseline);}
    private void discardDraft(){copyPreview=null;copyNotice="";copyError="";copyFailure=null;draft=null;draftDisplayName="";draftAddress="";draftAddresses="";draftRelay="";draftBaseline="";numberInputs.clear();numberEditors.clear();profileControls.clear();selectKeys.clear();presets.clear();formError="";formErrorField="";displayNameEditor=null;primaryEditor=null;backupEditor=null;relayEditor=null;}
    private void refreshProfileFooter(){if(draft==null||formSave==null||!pageId.equals("settings"))return;boolean dirty=profileDirty();formNotice.setText(copyNotice.isEmpty()?(dirty?"有未保存的更改":"设置已保存"):copyNotice);submitState(formSave,dirty);formErrorView.setText(formError);formErrorView.setVisibility(formError.isEmpty()?View.GONE:View.VISIBLE);}
    private void setText(EditText edit,String text){if(edit!=null&&!edit.getText().toString().equals(text)){edit.setText(text);edit.setSelection(edit.length());}}
    private void refreshProfileControls(){
        if(draft==null)return;bindingProfile=true;
        try{
            org.json.JSONObject values=draft.json();for(Map.Entry<String,View> entry:profileControls.entrySet()){
                String key=entry.getKey();View view=entry.getValue();
                if(view instanceof EditText){setText((EditText)view,numberInputs.containsKey(key)?numberInputs.get(key):String.valueOf(values.getInt(key)));((EditText)view).setError(key.equals(formErrorField)&&!formError.isEmpty()?formError:null);}
                else if(view instanceof CheckBox)((CheckBox)view).setChecked(values.getBoolean(key));
                else if(view instanceof Spinner){String[] keys=selectKeys.get(key);int index=Arrays.asList(keys).indexOf(values.getString(key));if(index>=0&&((Spinner)view).getSelectedItemPosition()!=index)((Spinner)view).setSelection(index);}
            }
            setText(displayNameEditor,draftDisplayName);setText(primaryEditor,draftAddress);setText(backupEditor,draftAddresses);setText(relayEditor,draftRelay);
            int width=-1,height=-1;try{width=Integer.parseInt(numberInputs.containsKey("width")?numberInputs.get("width"):String.valueOf(draft.width));height=Integer.parseInt(numberInputs.containsKey("height")?numberInputs.get("height"):String.valueOf(draft.height));}catch(NumberFormatException ignored){}
            String preset=width==1280&&height==720?"720P":width==1920&&height==1080?"1080P":width==2560&&height==1440?"1440P":width==2520&&height==1680?"平板":width==3840&&height==2160?"4K":"";
            for(Map.Entry<String,Button> entry:presets.entrySet()){boolean selected=entry.getKey().equals(preset);Button b=entry.getValue();b.setSelected(selected);b.setTextColor(selected?blue:muted);b.setBackground(bg(selected?blueSoft:surface,0,8));}
        }catch(Exception invalid){throw new IllegalStateException("设置控件刷新失败",invalid);}finally{bindingProfile=false;}
        refreshProfileFooter();
    }
    boolean syncRemovalOpen(){return syncClearPending;}
    private void showSyncClearPrompt(){
        String message="仅移除此设备保存的 WebDAV 地址和登录信息，并关闭自动同步。本机远程设备和服务器上的配置文件保留。";
        if(!syncError.isEmpty())message+="\n\n"+syncError;
        if(syncClearDialog!=null&&syncClearDialog.isShowing()){syncClearDialog.setMessage(message);return;}
        syncClearDialog=new AlertDialog.Builder(activity,RhUiStyle.dialogTheme(activity)).setTitle("移除同步设置？").setMessage(message)
            .setNegativeButton("取消",(dialog,which)->invoke("sync.clear.resolve",RhActions.obj("choice","cancel")))
            .setPositiveButton("移除",null).create();
        syncClearDialog.setCanceledOnTouchOutside(false);
        syncClearDialog.setOnCancelListener(dialog->invoke("sync.clear.resolve",RhActions.obj("choice","cancel")));
        syncClearDialog.setOnShowListener(dialog->{syncClearDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->invoke("sync.clear.resolve",RhActions.obj("choice","remove")));syncClearDialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();});
        syncClearDialog.show();
    }
    private void showNavigationPrompt(){
        if(pendingNavigation==null||activity.isDestroyed()||navigationDialog!=null&&navigationDialog.isShowing())return;
        navigationDialog=new AlertDialog.Builder(activity,RhUiStyle.dialogTheme(activity)).setTitle("保存更改？").setMessage(pendingNavigation.optBoolean("info")?"设备名称有未保存的更改。":pendingNavigation.optBoolean("sync")?"WebDAV 设置有未保存的更改。":"连接设置有未保存的更改。")
            .setPositiveButton("保存并继续",(dialog,which)->invoke("navigation.resolve",RhActions.obj("choice","save")))
            .setNegativeButton("放弃更改",(dialog,which)->invoke("navigation.resolve",RhActions.obj("choice","discard")))
            .setNeutralButton("继续编辑",(dialog,which)->invoke("navigation.resolve",RhActions.obj("choice","stay"))).create();
        navigationDialog.setCanceledOnTouchOutside(false);navigationDialog.setOnCancelListener(dialog->invoke("navigation.resolve",RhActions.obj("choice","stay")));navigationDialog.show();
    }
    private org.json.JSONObject resolveNavigation(org.json.JSONObject args)throws Exception {
        String choice=args.getString("choice");if(!Arrays.asList("save","discard","stay").contains(choice))throw new IllegalArgumentException("unknown navigation choice");if(pendingNavigation==null)throw new IllegalStateException("没有等待处理的更改");
        org.json.JSONObject next=pendingNavigation;pendingNavigation=null;if(navigationDialog!=null){navigationDialog.dismiss();navigationDialog=null;}
        if(choice.equals("stay"))return RhActions.obj("continuedEditing",true);
        if(choice.equals("save")&&pageId.equals("profile-copy")){copyPreview=null;copyError="";pageId="settings";showProfile(selected,draft);}
        if(choice.equals("save"))perform(next.optBoolean("info")?"device.info.save":next.optBoolean("sync")?"sync.save":"settings.save",RhActions.obj());else if(next.optBoolean("info")){clearInfo();returnToDevice();}else if(next.optBoolean("sync")){syncDraft=null;syncError="";}else returnToDevice();
        return perform(next.getString("id"),next.getJSONObject("args"));
    }
    private boolean infoDirty(){return !infoUuid.isEmpty()&&!infoName.trim().equals(infoOriginal);}
    private void clearInfo(){infoUuid="";infoName="";infoOriginal="";infoError="";infoEditor=null;infoFailure=null;infoNotice=null;infoReset=null;}
    private void refreshInfo(){if(!pageId.equals("device-info"))return;bindingProfile=true;try{setText(infoEditor,infoName);if(infoFailure!=null){infoFailure.setText(infoError);infoFailure.setVisibility(infoError.isEmpty()?View.GONE:View.VISIBLE);}if(infoNotice!=null)infoNotice.setText(infoDirty()?"有未保存的更改":"");if(infoReset!=null){infoReset.setEnabled(!infoName.isEmpty());infoReset.setAlpha(infoName.isEmpty()?0.45f:1f);}}finally{bindingProfile=false;}}
    private void showInfo(){
        pageId="device-info";profileVisible=true;showContent();content.removeAllViews();formHeader.removeAllViews();formHeader.setVisibility(View.VISIBLE);formFooter.removeAllViews();formFooter.setVisibility(View.VISIBLE);
        LinearLayout heading=row();heading.addView(text("设备资料",24,ink,true),new LinearLayout.LayoutParams(0,dp(48),1));heading.addView(button("返回设备",false,()->invoke("navigation.back",RhActions.obj())));formHeader.addView(heading);
        ComputerDetails device=computers.get(infoUuid);infoEditor=field("设备名称",infoName,false);infoEditor.setHint(device==null?"":device.name);watch(infoEditor,"device.info.input","");infoEditor.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);infoEditor.setOnEditorActionListener((v,id,event)->{if(id==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){invoke("device.info.save",RhActions.obj());return true;}return false;});
        infoFailure=text(infoError,13,c("#b04452"),false);content.addView(infoFailure);label("留空使用主机名。显示名称随设备配置同步。");infoReset=button("恢复原名",false,()->invoke("device.info.reset",RhActions.obj()));content.addView(infoReset);
        label("主机名");content.addView(text(device==null?"":device.name,15,ink,false));label("设备标识");content.addView(text(infoUuid,13,ink,false));
        LinearLayout commands=row();infoNotice=text("",12,muted,false);commands.addView(infoNotice,new LinearLayout.LayoutParams(0,dp(48),1));commands.addView(button("取消",false,()->invoke("device.info.cancel",RhActions.obj())));LinearLayout.LayoutParams doneLayout=new LinearLayout.LayoutParams(-2,dp(48));doneLayout.setMarginStart(dp(8));commands.addView(button("完成",true,()->invoke("device.info.save",RhActions.obj())),doneLayout);formFooter.addView(commands);formPadding(scroll.getWidth());refreshInfo();
    }
    private boolean addingBusy(){return addOperation!=null;}
    private void cancelAdding(){RhDeviceAdd retired=addOperation;addOperation=null;addAddress="";addError="";if(retired!=null)retired.cancel();}
    private void showAdd(){
        pageId="add";profileVisible=true;showContent();content.removeAllViews();formHeader.setVisibility(View.VISIBLE);formHeader.removeAllViews();
        LinearLayout heading=row();heading.addView(text("添加电脑",24,ink,true),new LinearLayout.LayoutParams(0,dp(48),1));heading.addView(button("返回设备",false,()->invoke("device.add.cancel",RhActions.obj())));formHeader.addView(heading);
        label("IP 或主机名，支持局域网、Tailscale 和公网地址");addEditor=field("设备地址",addAddress,false);addEditor.setHint("例如 192.168.0.104 或 100.x.x.x");watch(addEditor,"device.add.input","address");addEditor.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);addEditor.setOnEditorActionListener((view,action,event)->{invoke("device.add.submit",RhActions.obj());return true;});
        addFailure=text(addError,13,Color.rgb(176,68,82),false);content.addView(addFailure);
        addProgress=row();ProgressBar spinner=new ProgressBar(activity);spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(blue));addProgress.addView(spinner,new LinearLayout.LayoutParams(dp(28),dp(28)));addProgressText=text("",14,muted,false);LinearLayout.LayoutParams progressLabel=new LinearLayout.LayoutParams(-2,dp(48));progressLabel.setMarginStart(dp(12));addProgress.addView(addProgressText,progressLabel);content.addView(addProgress);
        label("找到设备后会保存到远程设备列表，配对在设备详情中完成。");
        formFooter.setVisibility(View.VISIBLE);formFooter.removeAllViews();LinearLayout footer=row();footer.addView(new View(activity),new LinearLayout.LayoutParams(0,dp(48),1));addCancel=button("取消",false,()->invoke("device.add.cancel",RhActions.obj()));addSubmit=button("添加",true,()->invoke("device.add.submit",RhActions.obj()));footer.addView(addCancel);LinearLayout.LayoutParams submitLayout=new LinearLayout.LayoutParams(-2,dp(48));submitLayout.setMarginStart(dp(8));footer.addView(addSubmit,submitLayout);formFooter.addView(footer);formPadding(scroll.getWidth());refreshAdd();
    }
    private void refreshAdd(){
        if(!pageId.equals("add")||addEditor==null)return;
        boolean busy=addingBusy();
        addEditor.setEnabled(!busy);if(!addEditor.getText().toString().equals(addAddress))addEditor.setText(addAddress);
        addSubmit.setText(busy?"正在查找…":"添加");submitState(addSubmit,!busy&&!syncBusy&&!addAddress.trim().isEmpty());
        addCancel.setEnabled(true);addCancel.setText("取消");
        addFailure.setText(addError);addFailure.setVisibility(addError.isEmpty()?View.GONE:View.VISIBLE);
        addProgress.setVisibility(busy||syncBusy?View.VISIBLE:View.GONE);addProgressText.setText(syncBusy&&!busy?"配置正在同步，完成后可添加设备。":"正在查找设备…");
    }
    private void beginAdd(org.json.JSONObject args)throws Exception{
        if(!pageId.equals("add"))throw new IllegalStateException("请先打开添加电脑页面");
        if(args.has("address"))addAddress=args.getString("address");
        String address=addAddress.trim();try{RhNetwork.validate(address);if(RhNetwork.kind(address).equals("transport"))throw new java.io.IOException("这里填写 IP 或主机名；内置中继地址请在连接设置里配置");}catch(Exception invalid){addError=invalid.getMessage();refreshAdd();throw invalid;}
        addError="";((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(addEditor.getWindowToken(),0);
        addOperation=new RhDeviceAdd(activity,NETWORK,new RhDeviceAdd.Listener(){
            public void changed(RhDeviceAdd operation){if(addOperation==operation)refreshAdd();}
            public void finished(RhDeviceAdd operation,ComputerDetails found,String error){
                if(addOperation!=operation)return;addOperation=null;if(activity.isDestroyed()||activity.isFinishing())return;
                if(operation.cancelled()){addAddress="";addError="";showDevices();return;}
                if(!error.isEmpty()){addError=error;refreshAdd();return;}
                try{
                    for(ComputerDetails known:computers.values())if(known.uuid.equalsIgnoreCase(found.uuid)){found.uuid=known.uuid;break;}
                    boolean existed=computers.containsKey(found.uuid);ComputerDetails saved=RhShared.addDevice(activity,found,actions::commitAdd);update(saved);selected=null;browserUuid=saved.uuid;deviceQuery="";addAddress="";addError="";showDevices();browserUuid=saved.uuid;browser.showDetail(true);renderDevices(false);message(existed?"设备地址已保存":"设备已添加");
                }catch(Exception failed){addError=failed.getMessage()==null?"保存设备失败":failed.getMessage();refreshAdd();}
            }
        });addOperation.start(operation->actions.inspectAdd(address,operation));
    }
    private static org.json.JSONArray splitAddresses(String text){org.json.JSONArray result=new org.json.JSONArray();for(String address:text.split("[,;，\n]"))if(!address.trim().isEmpty())result.put(address.trim());return result;}
    private void invoke(String id,org.json.JSONObject args){RhActions.click(id,args);}
    private void set(String key,Object value){invoke("settings.set",RhActions.obj("key",key,"value",value));}
    public org.json.JSONObject state()throws Exception{
        org.json.JSONArray devices=new org.json.JSONArray();org.json.JSONObject profiles=new org.json.JSONObject();for(ComputerDetails d:RhShared.ordered(activity,computers.values())){devices.put(RhActions.obj("uuid",d.uuid,"name",d.name,"displayName",RhShared.alias(activity,d.uuid),"online",d.state==ComputerDetails.State.ONLINE,"paired",d.pairState==PairingManager.PairState.PAIRED,"canConnect",RhNetwork.connectInsteadOfPair(d),"pairingStatus",RhNetwork.pairingStatus(d),"isSelf",false));profiles.put(d.uuid,RhProfile.load(activity,d.uuid).json());}
        org.json.JSONObject shared=RhShared.read(activity);
        return RhActions.obj("profileCopy",copyPreview==null?org.json.JSONObject.NULL:new org.json.JSONObject(copyPreview.toString()),"profileCopyNotice",copyNotice,"profileCopyError",copyError,"connection",connection.state(),"infoDraft",infoUuid.isEmpty()?org.json.JSONObject.NULL:RhActions.obj("uuid",infoUuid,"displayName",infoName,"error",infoError),"infoDirty",infoDirty(),"deviceQuery",deviceQuery,"homeLayout",RhActions.obj("browser",browser.layoutState(),"width",root.getWidth(),"height",root.getHeight(),"insets",RhActions.obj("left",root.getPaddingLeft(),"top",root.getPaddingTop(),"right",root.getPaddingRight(),"bottom",root.getPaddingBottom())),"add",RhActions.obj("address",addAddress,"error",addError,"phase",addingBusy()?"probing":"editing"),"admission",admission==null?org.json.JSONObject.NULL:admission.state(),"network",RhNetwork.state(),"networkBusy",networkBusy,"platform","android","page",pageId,"settingsTab",settingsTab,"profileTab",profileTab,"profileSourcesExpanded",profileSourcesExpanded,"settingsCatalog",RhSettings.catalog(activity),"devices",devices,"saved",RhActions.obj("profiles",profiles,"dark",RhShared.dark(activity),"clientId",RhShared.clientId(activity),"clients",shared.getJSONObject("clients"),"clientProfiles",shared.getJSONObject("profiles"),"devices",shared.getJSONArray("devices"),"profileSchemaVersion",2),"draft",draft==null?org.json.JSONObject.NULL:draft.json(),"selected",selected==null?browserUuid:selected.uuid,"settingsDirty",profileDirty(),"settingsInputs",new org.json.JSONObject(numberInputs),"settingsError",formError,"settingsErrorField",formErrorField,"navigationPrompt",pendingNavigation==null?org.json.JSONObject.NULL:RhActions.obj("kind",pendingNavigation.optBoolean("info")?"unsaved-info":pendingNavigation.optBoolean("sync")?"unsaved-sync":"unsaved-device","title","保存更改？"),"syncClearPrompt",syncClearPending?RhActions.obj("title","移除同步设置？"):org.json.JSONObject.NULL,"syncDraft",publicSyncDraft(),"syncDirty",syncDirty(),"syncError",syncError,"draftDisplayName",draftDisplayName,"draftAddress",draftAddress,"draftAddresses",draftAddresses,"draftRelay",draftRelay,"status",status.getText().toString(),"sync",sync.state(),"syncBusy",syncBusy,"updates",updates.state(),"updatesBusy",updatesBusy,"updateDetails",updateDetails,"tracing",RhStatistics.state(),"pairPin",pairPin,"pairing",pairing);
    }
    public org.json.JSONObject perform(String id,org.json.JSONObject args)throws Exception{
        if(id.equals("sync.run")&&args.optBoolean("automatic")&&(!sync.state().optBoolean("automatic")||syncBusy||!infoUuid.isEmpty()||draft!=null||pageId.equals("add")||pairing||admission!=null||RhActions.hasStream()||pendingNavigation!=null||syncClearPending||syncDirty()))return RhActions.obj("skipped",true);
        if(id.equals("navigation.back")&&syncClearPending)return perform("sync.clear.resolve",RhActions.obj("choice","cancel"));
        if(syncClearPending&&!Arrays.asList("sync.clear.resolve","sync.clear","settings.catalog","updates.state","sync.state","statistics.state","network.state").contains(id))throw new IllegalStateException("请先确认是否移除同步设置");
        if(id.equals("navigation.resolve"))return resolveNavigation(args);
        if(id.equals("navigation.back")){if(pageId.equals("connection")){if(connectionBusy())return RhActions.dispatch("stream.disconnect",RhActions.obj());return perform("connection.dismiss",RhActions.obj());}if(pageId.equals("profile-copy"))return perform("settings.copy.cancel",RhActions.obj());if(pageId.equals("add"))return perform("device.add.cancel",RhActions.obj());if(pendingNavigation!=null)return resolveNavigation(RhActions.obj("choice","stay"));if(draft!=null)return perform("device.select",RhActions.obj("uuid",selected.uuid));if(pageId.equals("devices")&&browser.isDetailOpen()){browser.showDetail(false);return RhActions.obj("accepted",true);}return perform("devices.open",RhActions.obj());}
        if(id.equals("connection.retry")||id.equals("connection.dismiss")){
            if(!pageId.equals("connection"))throw new IllegalStateException("请先打开连接页");
            if(connection.uuid.isEmpty())throw new IllegalStateException("没有可操作的连接");
            if(connectionBusy())throw new IllegalStateException("上一连接尚未结束");
            if(id.equals("connection.retry"))return perform(connection.operation.equals("pair")?"device.pair":"device.connect",RhActions.obj("uuid",connection.uuid));
            returnToDevice();message("");return RhActions.obj("accepted",true);
        }
        if((pageId.equals("connection")||pageId.equals("pair"))&&connectionBusy()&&Arrays.asList("devices.open","device.select","device.add.open","device.settings.open","device.info.open","app.settings.open","app.settings.tab","appearance.toggle").contains(id))throw new IllegalStateException(connection.operation.equals("pair")?"请先完成或取消当前配对":"请先取消或断开当前连接");
        if(pendingNavigation!=null&&!Arrays.asList("settings.catalog","updates.state","sync.state","statistics.state","network.state").contains(id))throw new IllegalStateException("请先处理未保存的更改");
        boolean leavingInfo=Arrays.asList("devices.open","device.select","device.settings.open","device.add.open","device.info.open","app.settings.open","app.settings.tab","device.connect","device.pair","appearance.toggle","sync.run","sync.resolve","updates.install").contains(id)||id.equals("updates.primary")&&updates.primaryAction().equals("updates.install");
        if(!infoUuid.isEmpty()&&leavingInfo){
            if(id.equals("device.info.open")&&infoUuid.equals(args.optString("uuid")))return RhActions.obj("reused",true);
            if(infoDirty()){pendingNavigation=RhActions.obj("id",id,"args",new org.json.JSONObject(args.toString()),"info",true);showNavigationPrompt();return RhActions.obj("pendingNavigation",true);}
            clearInfo();returnToDevice();
        }
        boolean leavingSync=Arrays.asList("devices.open","device.settings.open","device.info.open","device.add.open","device.select","device.connect","device.pair","appearance.toggle","sync.run","sync.resolve","updates.install").contains(id)||id.equals("app.settings.tab")&&!args.optString("tab").equals("sync")||id.equals("updates.primary")&&updates.primaryAction().equals("updates.install");
        if(syncDraft!=null&&leavingSync){
            if(syncDirty()){pendingNavigation=RhActions.obj("id",id,"args",new org.json.JSONObject(args.toString()),"sync",true);showNavigationPrompt();return RhActions.obj("pendingNavigation",true);}
            syncDraft=null;syncError="";
        }
        if(draft!=null&&(Arrays.asList("devices.open","app.settings.open","app.settings.tab","device.settings.open","device.info.open","device.add.open","device.select","device.connect","device.pair","appearance.toggle","updates.install").contains(id)||id.equals("updates.primary")&&updates.primaryAction().equals("updates.install"))){
            if(id.equals("device.settings.open")&&selected.uuid.equals(args.optString("uuid")))return RhActions.obj("reused",true);
            if(profileDirty()){pendingNavigation=RhActions.obj("id",id,"args",new org.json.JSONObject(args.toString()));showNavigationPrompt();return RhActions.obj("pendingNavigation",true);}
            returnToDevice();
        }

        if(addingBusy()&&!Arrays.asList("device.add.cancel","settings.catalog","updates.state","sync.state","statistics.state","network.state").contains(id)){
            if(id.equals("device.add.open"))return RhActions.obj("reused",true);
            if(Arrays.asList("devices.open","device.select","app.settings.open","app.settings.tab","device.settings.open","device.info.open","appearance.toggle").contains(id)){
                cancelAdding();return perform(id,args);
            }
            throw new IllegalStateException("正在查找设备，请先取消当前请求");
        }
        if(syncBusy&&((id.startsWith("sync.")&&!id.equals("sync.state"))||id.equals("device.move")||id.equals("device.settings.open")||id.startsWith("settings.")||id.equals("appearance.toggle")||id.equals("device.add.submit")))throw new IllegalStateException("同步进行中，请稍后保存");
        if(pairing&&(id.equals("device.connect")||id.equals("device.pair")||id.equals("appearance.toggle")))throw new IllegalStateException("请先取消当前配对");
        if(admission!=null&&id.equals("appearance.toggle"))throw new IllegalStateException("请先取消当前连接");
        if(updatesBusy&&id.equals("appearance.toggle"))throw new IllegalStateException("更新操作正在进行，请稍后切换外观");
        if(pageId.equals("profile-copy")&&Arrays.asList("settings.input","settings.set","settings.step","settings.network.edit","settings.route.move","settings.route.toggle","settings.sources.toggle","settings.tab","settings.save","settings.cancel","settings.copy_from").contains(id))throw new IllegalStateException("请先完成或取消配置预览");
        if(id.startsWith("settings.copy.")||id.equals("settings.copy_from"))return performCopy(id,args);
        if(Arrays.asList("settings.input","settings.set","settings.step","settings.network.edit","settings.route.move","settings.route.toggle").contains(id))copyNotice="";
        switch(id){
            case "device.info.open":{
                if(syncBusy||pairing||admission!=null||RhActions.hasStream())throw new IllegalStateException("请先完成当前操作");
                String uuid=args.getString("uuid");ComputerDetails device=computers.get(uuid);if(device==null)throw new IllegalArgumentException("unknown device");
                selected=device;browserUuid=uuid;infoUuid=uuid;infoOriginal=RhShared.alias(activity,uuid);infoName=infoOriginal;infoError="";showInfo();break;
            }
            case "device.info.input":if(infoUuid.isEmpty())throw new IllegalStateException("请先打开设备资料");infoName=args.getString("displayName");infoError="";refreshInfo();break;
            case "device.info.reset":if(infoUuid.isEmpty())throw new IllegalStateException("请先打开设备资料");infoName="";infoError="";refreshInfo();break;
            case "device.info.cancel":if(infoUuid.isEmpty())throw new IllegalStateException("请先打开设备资料");clearInfo();returnToDevice();message("");break;
            case "device.info.save":{
                if(infoUuid.isEmpty())throw new IllegalStateException("请先打开设备资料");if(syncBusy)throw new IllegalStateException("同步进行中，请稍后保存");
                try{RhShared.validateDisplayName(infoName);boolean changed=infoDirty();ComputerDetails device=computers.get(infoUuid);if(device==null)throw new IllegalStateException("设备已不存在");if(changed)RhShared.saveDisplayName(activity,device,infoName);((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);clearInfo();returnToDevice();if(changed)message("设备名称已保存");}
                catch(Exception failure){infoError=failure.getMessage()==null?"设备名称保存失败":failure.getMessage();refreshInfo();throw failure;}break;
            }
            case "device.select":if(!pageId.equals("devices")||admission!=null||RhActions.hasStream())throw new IllegalStateException("请先返回空闲设备列表");String uuid=args.getString("uuid");if(!computers.containsKey(uuid))throw new IllegalArgumentException("unknown device");browserUuid=uuid;browser.showDetail(true);renderDevices(false);break;
            case "devices.filter":String query=args.getString("query");if(query.codePointCount(0,query.length())>128)throw new IllegalArgumentException("搜索内容不能超过 128 字符");deviceQuery=query;if(pageId.equals("devices"))renderDevices(false);break;
            case "statistics.state":return RhStatistics.state();
            case "statistics.complete":return RhStatistics.complete(args.getString("runId"),args.getString("sha256"));
            case "statistics.configure":if(!(args.get("enabled") instanceof Boolean))throw new IllegalArgumentException("enabled must be boolean");org.json.JSONObject tracing=RhStatistics.configure(args.getBoolean("enabled"));if(pageId.equals("global-settings"))showGlobal();return tracing;
            case "devices.open":showDevices();break;
            case "settings.cancel":returnToDevice();break;
            case "devices.refresh":actions.refresh();break;
            case "device.move":if(!pageId.equals("devices"))throw new IllegalStateException("请先返回设备列表");RhShared.move(activity,computers.values(),args.getString("uuid"),args.getString("direction"));renderDevices(false);message("设备顺序已保存");break;
            case "device.add.open":if(pairing||admission!=null)throw new IllegalStateException("请先完成或取消当前操作");if(!pageId.equals("add")){addAddress="";addError="";}showAdd();break;
            case "device.add.cancel":cancelAdding();showDevices();break;
            case "device.add.input":if(!pageId.equals("add"))throw new IllegalStateException("请先打开添加电脑页面");String addValue=args.getString("address");addAddress=addValue;addError="";message("");refreshAdd();break;
            case "device.add.submit":beginAdd(args);break;
            case "device.settings.open":ComputerDetails d=computers.get(args.getString("uuid"));if(d==null)throw new IllegalArgumentException("unknown device");showProfile(d);break;
            case "settings.catalog":return RhActions.obj("fields",RhSettings.catalog(activity));
            case "settings.sources.toggle":if(draft==null)throw new IllegalStateException("open device settings first");profileSourcesExpanded=!profileSourcesExpanded;showProfile(selected,draft);break;
            case "settings.tab":if(draft==null)throw new IllegalStateException("open device settings first");String profileSection=args.getString("tab");if(!Arrays.asList("video","audio","input","network","advanced").contains(profileSection))throw new IllegalArgumentException("unknown tab");profileTab=profileSection;showProfile(selected,draft);break;
            case "settings.route.move":case "settings.route.toggle":
                if(draft==null)throw new IllegalStateException("open device settings first");
                String route=args.getString("route");if(!java.util.Arrays.asList(RhProfile.ROUTES).contains(route))throw new IllegalArgumentException("unknown route");
                java.util.ArrayList<String> order=new java.util.ArrayList<>(draft.routeOrder);
                if(id.equals("settings.route.toggle")){if(order.contains(route))order.remove(route);else order.add(route);}else{int old=order.indexOf(route);if(old<0)throw new IllegalArgumentException("route is disabled");String direction=args.getString("direction");if(!direction.equals("up")&&!direction.equals("down"))throw new IllegalArgumentException("invalid direction");int target=direction.equals("up")?Math.max(0,old-1):Math.min(order.size()-1,old+1);java.util.Collections.swap(order,old,target);}
                org.json.JSONObject routeDraft=draft.json();routeDraft.put("routeOrder",new org.json.JSONArray(order));draft=RhProfile.fromJson(selected.uuid,routeDraft);showProfile(selected,draft);break;
            case "settings.input":{
                if(draft==null)throw new IllegalStateException("请先打开连接设置");String key=args.getString("key"),text=args.getString("value");if(text.length()>4096)throw new IllegalArgumentException("输入内容过长");
                if(key.equals("displayName"))draftDisplayName=text;else{boolean number=false;org.json.JSONArray catalog=RhSettings.catalog(activity);for(int i=0;i<catalog.length();i++){org.json.JSONObject field=catalog.getJSONObject(i);if(field.getString("key").equals(key)&&field.getString("type").equals("number"))number=true;}if(!number)throw new IllegalArgumentException("unknown editable field");numberInputs.put(key,text);}
                formError="";formErrorField="";refreshProfileControls();break;
            }
            case "settings.step":{
                if(draft==null)throw new IllegalStateException("请先打开连接设置");String key=args.getString("key"),direction=args.getString("direction");org.json.JSONArray catalog=RhSettings.catalog(activity);org.json.JSONObject meta=null;for(int i=0;i<catalog.length();i++){org.json.JSONObject field=catalog.getJSONObject(i);if(field.getString("key").equals(key)&&field.getString("type").equals("number"))meta=field;}if(meta==null||!Arrays.asList("up","down").contains(direction))throw new IllegalArgumentException("unknown numeric field / direction");int n=draft.json().getInt(key);try{n=Integer.parseInt(numberInputs.get(key));}catch(Exception ignored){}n=Math.max(meta.getInt("min"),Math.min(meta.getInt("max"),n+(direction.equals("up")?1:-1)*meta.getInt("step")));numberInputs.put(key,String.valueOf(n));formError="";refreshProfileControls();break;
            }
            case "settings.network.edit":if(draft==null)throw new IllegalStateException("open device settings first");String networkKey=args.getString("key"),networkValue=args.getString("value");if(networkValue.length()>4096)throw new IllegalArgumentException("network field too long");if(networkKey.equals("address"))draftAddress=networkValue;else if(networkKey.equals("addresses"))draftAddresses=networkValue;else if(networkKey.equals("relayUrl"))draftRelay=networkValue;else throw new IllegalArgumentException("unknown network field");formError="";formErrorField="";refreshProfileControls();break;
            case "settings.set":if(draft==null)throw new IllegalStateException("open device settings first");org.json.JSONObject next=draft.json();String key=args.getString("key");if(key.equals("displayName")){draftDisplayName=RhShared.validateDisplayName(args.getString("value"));refreshProfileControls();break;}if(key.equals("resolution")){org.json.JSONArray size=args.getJSONArray("value");next.put("width",size.getInt(0));next.put("height",size.getInt(1));numberInputs.remove("width");numberInputs.remove("height");}else if(next.has(key)){next.put(key,args.get("value"));numberInputs.remove(key);}else throw new IllegalArgumentException("unknown option");draft=RhProfile.fromJson(selected.uuid,next);if(key.equals("relayUrl"))draftRelay=draft.relayUrl;formError="";formErrorField="";refreshProfileControls();break;

            case "settings.save":{
                if(draft==null)throw new IllegalStateException("请先打开连接设置");
                try{
                    RhProfile validated=RhSettingsDraft.validate(activity,selected.uuid,draft,numberInputs,draftRelay);
                    String name=args.optString("displayName",draftDisplayName);
                    try{RhShared.validateDisplayName(name);}catch(Exception invalid){throw new RhSettingsDraft.Invalid("displayName","advanced",invalid.getMessage());}
                    try{RhNetwork.addresses(RhActions.obj("address",draftAddress.trim(),"addresses",splitAddresses(draftAddresses)));}catch(Exception invalid){throw new RhSettingsDraft.Invalid("address","network",invalid.getMessage());}
                    RhShared.saveProfile(activity,selected,validated,name,draftAddress.trim(),splitAddresses(draftAddresses));returnToDevice();message("连接设置已保存");
                }catch(Exception invalid){formError=invalid.getMessage()==null?"保存失败":invalid.getMessage();if(invalid instanceof RhSettingsDraft.Invalid){RhSettingsDraft.Invalid field=(RhSettingsDraft.Invalid)invalid;profileTab=field.tab;formErrorField=field.key;}showProfile(selected,draft);throw invalid;}break;
            }
            case "network.state":return RhNetwork.state();
            case "device.network.configure":ComputerDetails networkTarget=computers.get(args.getString("uuid"));if(networkTarget==null)throw new IllegalArgumentException("unknown device");RhShared.capture(activity,Collections.singletonList(networkTarget));RhShared.saveAddresses(activity,args.getString("uuid"),args.getString("address"),args.getJSONArray("addresses"));message("连接地址已保存");if(draft!=null)showProfile(selected,draft);break;
            case "device.network.diagnose":{
                if(networkBusy)throw new IllegalStateException("连接检查正在进行");ComputerDetails device=computers.get(args.getString("uuid"));if(device==null)throw new IllegalArgumentException("unknown device");
                if(draft!=null&&selected!=null&&selected.uuid.equals(device.uuid)){args.put("address",draftAddress.trim());args.put("addresses",splitAddresses(draftAddresses));}
                RhProfile probeProfile=draft!=null&&selected!=null&&selected.uuid.equals(device.uuid)?RhProfile.fromJson(device.uuid,draft.json().put("relayUrl",draftRelay.trim())):RhProfile.load(activity,device.uuid);
                networkBusy=true;message("正在诊断连接…");NETWORK.execute(()->{try{RhNetwork.diagnose(activity,device,probeProfile,args);activity.runOnUiThread(()->message("连接诊断完成"));}catch(Exception e){activity.runOnUiThread(()->message("诊断："+e.getMessage()));}finally{activity.runOnUiThread(()->{networkBusy=false;if(draft!=null&&pageId.equals("settings")&&profileTab.equals("network"))showProfile(selected,draft);});}});break;
            }
            case "device.connect":case "device.pair":{
                if(networkBusy)throw new IllegalStateException("正在检查连接地址");ComputerDetails target=computers.get(args.getString("uuid"));if(target==null)throw new IllegalArgumentException("unknown device");
                boolean connect=id.equals("device.connect");String pin=args.optString("pin",String.format(Locale.ROOT,"%04d",new java.security.SecureRandom().nextInt(10000)));if(!pin.matches("[0-9]{4}"))throw new IllegalArgumentException("PIN must contain four digits");
                beginAdmission(target,connect,pin);break;
            }
            case "pair.cancel":if(admission!=null){admission.cancel();break;}if(!pairing)throw new IllegalStateException("no active pairing");actions.cancelPair();message("正在取消配对…");break;
            case "appearance.toggle":RhShared.theme(activity,!RhShared.dark(activity));activity.recreate();break;
            case "app.settings.open":pageId="global-settings";showGlobal();break;
            case "app.settings.tab":String tab=args.getString("tab");if(!Arrays.asList("appearance","sync","updates","about").contains(tab))throw new IllegalArgumentException("unknown tab");settingsTab=tab;pageId="global-settings";showGlobal();break;
            case "sync.state":return sync.state();
            case "sync.edit":{
                ensureSyncDraft();String syncKey=args.getString("key");Object value=args.get("value");
                if(Arrays.asList("url","username","password").contains(syncKey)){if(!(value instanceof String)||((String)value).length()>4096)throw new IllegalArgumentException("输入过长或类型无效");}
                else if(syncKey.equals("automatic")){if(!(value instanceof Boolean))throw new IllegalArgumentException("automatic must be boolean");}
                else if(syncKey.equals("intervalMinutes"))RhSync.interval(args,RhSync.interval(syncDraft,5));else throw new IllegalArgumentException("unknown sync setting");
                syncDraft.put(syncKey,value);syncError="";message("");refreshSyncFooter();break;
            }
            case "sync.cancel":syncDraft=null;syncError="";showGlobal();break;
            case "sync.save":case "sync.configure":{
                ensureSyncDraft();try{sync.configure(id.equals("sync.save")?syncDraft:args);syncDraft=null;syncError="";message("同步设置已保存");showGlobal();}
                catch(Exception invalid){syncError=invalid.getMessage()==null?"同步设置保存失败":invalid.getMessage();refreshSyncFooter();throw invalid;}break;
            }
            case "sync.clear":
                if(!pageId.equals("global-settings")||!settingsTab.equals("sync"))throw new IllegalStateException("请先打开配置同步页");
                if(!sync.state().optBoolean("hasPassword")&&sync.state().optString("url").isEmpty())throw new IllegalStateException("没有已保存的同步设置");
                syncClearPending=true;syncError="";message("");refreshSyncFooter();showSyncClearPrompt();return RhActions.obj("confirmationRequired",true);
            case "sync.clear.resolve":{
                if(!syncClearPending)throw new IllegalStateException("没有等待确认的移除操作");
                String choice=args.getString("choice");if(!Arrays.asList("cancel","remove").contains(choice))throw new IllegalArgumentException("unknown removal choice");
                if(choice.equals("remove")){try{sync.clear();}catch(Exception failure){syncError=failure.getMessage()==null?"同步设置移除失败":failure.getMessage();showSyncClearPrompt();throw failure;}}
                syncClearPending=false;if(syncClearDialog!=null){syncClearDialog.dismiss();syncClearDialog=null;}
                if(choice.equals("remove")){syncDraft=null;syncError="";showGlobal();message("同步设置已移除");}else{syncError="";refreshSyncFooter();}
                return RhActions.obj(choice.equals("remove")?"removed":"cancelled",true);
            }
            case "sync.run":case "sync.resolve":if(draft!=null)throw new IllegalStateException("请先保存或取消设备设置");startSync(id.equals("sync.run")?"review":args.getString("resolution"));break;
            case "updates.state":return updates.state();
            case "updates.details.toggle":if(!pageId.equals("global-settings")||!settingsTab.equals("updates"))throw new IllegalStateException("请先打开更新页");updateDetails=!updateDetails;refreshUpdateControls();break;
            case "updates.primary":if(updatesBusy)throw new IllegalStateException("更新操作正在进行");String primaryAction=updates.primaryAction();if(primaryAction.equals("updates.install")&&RhActions.hasStream())throw new IllegalStateException("请先断开当前连接再安装更新");startUpdate(primaryAction);break;
            case "updates.check":case "updates.download":case "updates.install":if(updatesBusy)throw new IllegalStateException("更新操作正在进行");startUpdate(id);break;
            default:throw new IllegalArgumentException("unsupported Android action: "+id);
        }return RhActions.obj("accepted",true);
    }
    private void settingsHeading(){LinearLayout heading=row();heading.addView(text("设置",26,ink,true),new LinearLayout.LayoutParams(0,dp(56),1));heading.addView(button("返回设备",false,()->invoke("devices.open",RhActions.obj())));content.addView(heading);scroll.post(()->scroll.scrollTo(0,0));}
    private void showGlobal(){profileVisible=true;showContent();content.removeAllViews();globalChrome();if(settingsTab.equals("sync")){showSync();return;}if(settingsTab.equals("updates")){showUpdates();return;}label(settingsTab.equals("about")?"RHRemote-Moonlight · "+com.limelight.BuildConfig.VERSION_NAME:"主题");if(settingsTab.equals("about")){content.addView(button(RhStatistics.enabled()?"测试统计：开启":"测试统计：关闭",RhStatistics.enabled(),()->invoke("statistics.configure",RhActions.obj("enabled",!RhStatistics.enabled()))));label("测试模式每次连接记录阶段和逐帧时间。分析后自动清理原始记录。");label(RhStatistics.state().optString("latest"));}if(settingsTab.equals("appearance"))content.addView(button(RhShared.dark(activity)?"切换为浅色":"切换为深色",false,()->invoke("appearance.toggle",RhActions.obj())));}
    private void showUpdates(){
        org.json.JSONObject state=updates.state();label("RHRemote-Moonlight "+state.optString("currentVersion"));
        updateVersion=text("",15,ink,true);content.addView(updateVersion);
        updateProvider=text("",13,muted,false);content.addView(updateProvider);
        updateStatus=text("",15,ink,false);updateStatus.setPadding(0,dp(10),0,dp(10));content.addView(updateStatus);
        updateDetailsToggle=button("查看详情",false,()->invoke("updates.details.toggle",RhActions.obj()));updateDetailsToggle.setTag("updates.details.toggle");content.addView(updateDetailsToggle);
        updateFailureDetails=text("",13,muted,false);updateFailureDetails.setContentDescription("更新错误详情");content.addView(updateFailureDetails);
        updateBytes=text("",13,muted,false);content.addView(updateBytes);
        updateProgress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);content.addView(updateProgress,new LinearLayout.LayoutParams(-1,dp(12)));
        formFooter.setVisibility(View.VISIBLE);formFooter.removeAllViews();LinearLayout commands=row();commands.setGravity(Gravity.END);updatePrimary=button("检查更新",true,()->invoke("updates.primary",RhActions.obj()));updatePrimary.setTag("updates.primary");commands.addView(updatePrimary);formFooter.addView(commands);formPadding(scroll.getWidth());
        refreshUpdateControls();
        label("安装时在系统界面手动确认；需要安装权限时会先打开对应设置。");
    }
    private void refreshUpdateControls(){
        if(updateStatus==null)return;org.json.JSONObject s=updates.state();String phase=s.optString("phase"),provider=s.optString("provider");
        updateVersion.setText(s.isNull("availableVersion")?"":"可用版本 "+s.optString("availableVersion"));updateVersion.setVisibility(s.isNull("availableVersion")?View.GONE:View.VISIBLE);
        boolean failed=phase.equals("error");updateStatus.setText(failed?"更新失败，请重试。":s.optString("status"));updateStatus.setTextColor(failed?c("#b04452"):ink);
        updateDetailsToggle.setVisibility(failed?View.VISIBLE:View.GONE);updateDetailsToggle.setText(updateDetails?"收起详情":"查看详情");updateFailureDetails.setText(s.optString("status"));updateFailureDetails.setVisibility(failed&&updateDetails?View.VISIBLE:View.GONE);
        updateProvider.setText(provider.isEmpty()?"更新源：Gitee / GitHub":"更新源："+provider);
        long total=s.optLong("totalBytes"),bytes=s.optLong("downloadedBytes");boolean downloading=phase.equals("downloading")&&total>0;
        updateBytes.setVisibility(total>0&&(bytes>0||phase.equals("downloading"))?View.VISIBLE:View.GONE);updateBytes.setText(String.format(Locale.ROOT,"%.1f / %.1f MB",bytes/1048576.0,total/1048576.0));
        updateProgress.setVisibility(updatesBusy?View.VISIBLE:View.GONE);updateProgress.setIndeterminate(!downloading);if(downloading)updateProgress.setProgress((int)(bytes*100/total));
        updatePrimary.setText(updates.primaryLabel(updatesBusy));updatePrimary.setEnabled(!updatesBusy);updatePrimary.setAlpha(updatesBusy?0.65f:1f);
    }
    private void startUpdate(String id){startUpdate(id,false);}
    private void startUpdate(String id,boolean automatic){
        updatesBusy=true;updateDetails=false;updates.pending(id);if(pageId.equals("global-settings")&&settingsTab.equals("updates"))message("");updateUi.removeCallbacks(updateTick);refreshUpdateUi();
        NETWORK.execute(()->{
            String error=null;boolean installReady=false;
            try{if(id.equals("updates.check")){updates.check();if(automatic&&!updates.state().isNull("availableVersion"))updates.download();}else if(id.equals("updates.install")){installReady=updates.prepareInstall();}else updates.download();}
            catch(Exception e){error=e.getMessage()==null?"更新操作失败，请重试":e.getMessage();updates.failure(error);}
            final String failure=error;final boolean launch=installReady;
            activity.runOnUiThread(()->{
                updatesBusy=false;updateUi.removeCallbacks(updateTick);if(activity.isDestroyed())return;
                String problem=failure;
                if(problem==null&&launch)try{updates.install(activity);}catch(Exception e){problem=e.getMessage()==null?"系统安装界面未能打开":e.getMessage();updates.failure(problem);}
                // Failure remains owned by the update page; another page must not receive a stale banner or Toast.
                if(pageId.equals("global-settings")&&settingsTab.equals("updates"))refreshUpdateControls();
                if(resumeInstall){resumeInstall=false;startUpdate("updates.install");}
            });
        });
    }
    private EditText field(String title,String value,boolean password){label(title);EditText edit=new EditText(activity);edit.setContentDescription(title);edit.setTextColor(ink);edit.setHintTextColor(muted);edit.setTextSize(15);edit.setSingleLine(true);edit.setPadding(dp(12),0,dp(12),0);edit.setBackground(bg(surface,line,8));edit.setText(value);if(password)edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);content.addView(edit,new LinearLayout.LayoutParams(-1,dp(52)));return edit;}
    private void globalChrome(){
        formHeader.setVisibility(View.VISIBLE);formHeader.removeAllViews();formHeader.setPadding(dp(20),dp(12),dp(20),dp(8));
        LinearLayout heading=row();heading.addView(text("设置",24,ink,true),new LinearLayout.LayoutParams(0,dp(48),1));heading.addView(button("返回设备",false,()->invoke("navigation.back",RhActions.obj())));formHeader.addView(heading);
        RhActionRow tabs=actionRow();for(String tab:new String[]{"updates","sync","appearance","about"}){String title=tab.equals("updates")?"更新":tab.equals("sync")?"配置同步":tab.equals("appearance")?"外观":"关于";tabs.addView(tab(title,settingsTab.equals(tab),()->invoke("app.settings.tab",RhActions.obj("tab",tab))));}formHeader.addView(tabs);formPadding(scroll.getWidth());
    }
    private void ensureSyncDraft(){if(syncDraft==null){org.json.JSONObject s=sync.state();syncDraft=RhActions.obj("url",s.optString("url"),"username",s.optString("username"),"password","","automatic",s.optBoolean("automatic"),"intervalMinutes",s.optInt("intervalMinutes",5));}}
    private org.json.JSONObject publicSyncDraft(){if(syncDraft==null)return null;try{org.json.JSONObject s=new org.json.JSONObject(syncDraft.toString());s.remove("password");return s;}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private boolean syncDirty(){if(syncDraft==null)return false;org.json.JSONObject s=sync.state();return !syncDraft.optString("url").trim().equals(s.optString("url"))||!syncDraft.optString("username").trim().equals(s.optString("username"))||!syncDraft.optString("password").isEmpty()||syncDraft.optBoolean("automatic")!=s.optBoolean("automatic")||syncDraft.optInt("intervalMinutes",5)!=s.optInt("intervalMinutes",5);}
    private void submitState(Button button,boolean enabled){button.setEnabled(enabled);button.setAlpha(1f);}
    private void refreshSyncFooter(){if(syncSave==null||!pageId.equals("global-settings")||!settingsTab.equals("sync"))return;boolean dirty=syncDirty();submitState(syncSave,dirty&&!syncBusy);syncCancel.setEnabled(dirty&&!syncBusy);syncCancel.setAlpha(dirty&&!syncBusy?1f:0.45f);syncNotice.setText(dirty?"有未保存的更改":sync.state().optBoolean("hasPassword")?"设置已保存":"未配置");syncErrorView.setText(syncError);syncErrorView.setVisibility(syncError.isEmpty()?View.GONE:View.VISIBLE);}
    private void showSync(){
        ensureSyncDraft();org.json.JSONObject s=sync.state();
        EditText url=field("WebDAV 目录地址（HTTP / HTTPS）",syncDraft.optString("url"),false),user=field("用户名",syncDraft.optString("username"),false),password=field(s.optBoolean("hasPassword")?"密码（留空保留）":"密码",syncDraft.optString("password"),true);
        url.setEnabled(!syncBusy);user.setEnabled(!syncBusy);password.setEnabled(!syncBusy);watch(url,"sync.edit","url");watch(user,"sync.edit","username");watch(password,"sync.edit","password");
        check("自动同步",syncDraft.optBoolean("automatic"),v->invoke("sync.edit",RhActions.obj("key","automatic","value",v))).setEnabled(!syncBusy);label("同步间隔");
        Spinner interval=new Spinner(activity);interval.setEnabled(!syncBusy);interval.setAdapter(options(new String[]{"5 分钟","15 分钟","30 分钟","60 分钟"}));interval.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surface));interval.setSelection(Math.max(0,Arrays.asList(5,15,30,60).indexOf(syncDraft.optInt("intervalMinutes",5))));content.addView(interval,new LinearLayout.LayoutParams(-1,dp(48)));
        interval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long row){int minutes=new int[]{5,15,30,60}[position];if(syncDraft!=null&&syncDraft.optInt("intervalMinutes",5)!=minutes)invoke("sync.edit",RhActions.obj("key","intervalMinutes","value",minutes));}});
        label("同步设备列表、连接配置和主题。画面参数按客户端分别保存；配对密钥及 WebDAV 凭据不参与同步。");
        label(!s.optBoolean("hasPassword")?"请先保存 WebDAV 设置。":s.optLong("lastSyncMs")>0?"上次同步："+android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",s.optLong("lastSyncMs")):"尚未同步");
        RhActionRow commands=actionRow();Button run=button(syncBusy?"同步中…":"立即同步",false,()->invoke("sync.run",RhActions.obj())),clear=button("移除同步设置",false,()->invoke("sync.clear",RhActions.obj()));run.setEnabled(!syncBusy&&s.optBoolean("hasPassword"));clear.setEnabled(!syncBusy&&s.optBoolean("hasPassword"));commands.addView(run);commands.addView(clear);content.addView(commands);
        org.json.JSONArray conflicts=s.optJSONArray("conflicts");if(conflicts!=null&&conflicts.length()>0){label("配置冲突："+conflicts.toString());RhActionRow choices=actionRow();choices.addView(button("冲突项保留本机",false,()->invoke("sync.resolve",RhActions.obj("resolution","local"))));choices.addView(button("冲突项保留远端",false,()->invoke("sync.resolve",RhActions.obj("resolution","remote"))));content.addView(choices);}
        formFooter.setVisibility(View.VISIBLE);formFooter.removeAllViews();formFooter.setPadding(dp(20),dp(8),dp(20),dp(12));syncErrorView=text(syncError,13,android.graphics.Color.rgb(176,68,82),false);formFooter.addView(syncErrorView);
        LinearLayout footer=row();syncNotice=text("",12,muted,false);footer.addView(syncNotice,new LinearLayout.LayoutParams(0,dp(48),1));syncCancel=button("取消",false,()->invoke("sync.cancel",RhActions.obj()));syncSave=button("保存",true,()->invoke("sync.save",RhActions.obj()));footer.addView(syncCancel);LinearLayout.LayoutParams saveLayout=new LinearLayout.LayoutParams(-2,dp(48));saveLayout.setMarginStart(dp(8));footer.addView(syncSave,saveLayout);formFooter.addView(footer);formPadding(scroll.getWidth());refreshSyncFooter();
    }
    private void startSync(String resolution)throws Exception{syncError="";org.json.JSONObject captured=RhShared.capture(activity,new ArrayList<>(computers.values()));syncBusy=true;message("同步中…");if(pageId.equals("global-settings")&&settingsTab.equals("sync"))showGlobal();else if(pageId.equals("add"))refreshAdd();NETWORK.execute(()->{try{org.json.JSONObject result=sync.run(captured,resolution);activity.runOnUiThread(()->{syncBusy=false;try{if(result.has("shared")){boolean before=RhShared.dark(activity);RhShared.apply(activity,result.getJSONObject("shared"));restoreSharedDevices();if(pageId.equals("devices")||pageId.equals("global-settings")&&settingsTab.equals("sync"))message("同步完成");if(before!=RhShared.dark(activity)){activity.recreate();return;}}else if(pageId.equals("global-settings")&&settingsTab.equals("sync"))message("配置冲突，请选择保留哪一端的冲突项");if(pageId.equals("global-settings"))showGlobal();else if(pageId.equals("devices"))renderDevices(false);else if(pageId.equals("add"))refreshAdd();}catch(Exception e){message("应用同步配置失败");}});}catch(Exception e){activity.runOnUiThread(()->{syncBusy=false;syncError=e instanceof java.io.IOException?e.getMessage():"同步失败，请检查 WebDAV 设置";if(pageId.equals("global-settings")&&settingsTab.equals("sync"))message(syncError);if(pageId.equals("global-settings"))showGlobal();else if(pageId.equals("add"))refreshAdd();});}});}
    private void restoreSharedDevices(){try{org.json.JSONArray list=RhShared.read(activity).getJSONArray("devices");for(int i=0;i<list.length();i++){org.json.JSONObject d=list.getJSONObject(i);String uuid=d.getString("uuid");if(!computers.containsKey(uuid)){ComputerDetails added=new ComputerDetails();added.uuid=uuid;added.name=d.getString("name");added.manualAddress=new ComputerDetails.AddressTuple(d.getString("address"),47989);computers.put(uuid,added);}}}catch(Exception e){message("已同步的设备列表读取失败");}}
    private void showPair(){
        pageId="pair";profileVisible=true;showContent();content.removeAllViews();formHeader.removeAllViews();formFooter.removeAllViews();formHeader.setVisibility(View.VISIBLE);formFooter.setVisibility(View.VISIBLE);
        formHeader.addView(text("配对",23,ink,true));TextView name=text(connection.name,13,muted,false);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);formHeader.addView(name);
        label("请在这台电脑的 RHRemote-Sunshine 管理界面输入配对码：");TextView pin=text(pairPin,40,blue,true);pin.setContentDescription("配对码");content.addView(pin);label("等待主机确认配对");
        RhActionRow footer=actionRow();footer.addView(button("取消配对",false,()->invoke("pair.cancel",RhActions.obj())));LinearLayout aligned=row();aligned.setGravity(Gravity.END);aligned.addView(footer,new LinearLayout.LayoutParams(-2,-2));formFooter.addView(aligned);formPadding(scroll.getWidth());refreshNavigation();
    }
    public void pairFinished(boolean success,String result){if(admission!=null){admission.pairFinished(success,result);return;}pairing=false;pairPin="";showDevices();message(result);}
}
