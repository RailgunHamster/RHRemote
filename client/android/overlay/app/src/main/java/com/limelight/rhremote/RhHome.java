package com.limelight.rhremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.util.*;

/** Native RHRemote v2 shell. Only actual ComputerManager discoveries become device cards. */
public final class RhHome implements RhActions.Target {
    public interface Actions { void connect(ComputerDetails d); void add(String address); void advanced(); void pair(ComputerDetails d,String pin);void cancelPair();void refresh(); }
    private long lastSyncAttempt=0;private final Activity activity;private final Actions actions;
    private final LinearLayout root,content,bar;private final TextView status;
    private final RelativeLayout empty;
    private final ScrollView scroll;
    private final LinkedHashMap<String,ComputerDetails> computers=new LinkedHashMap<>();
    private final HashMap<String,String> signatures=new HashMap<>();
    private final int page,surface,soft,ink,muted,line,blue,blueSoft,purple,purpleSoft,green;
    private boolean profileVisible;
    private String pageId="devices",settingsTab="updates";
    private RhProfile draft;private ComputerDetails selected;private AlertDialog adding;
    private String draftDisplayName="";private EditText displayNameEditor;
    private boolean networkBusy=false;
    private String profileTab="video";private final Map<String,EditText> numberEditors=new HashMap<>();
    private boolean profileSourcesExpanded;
    private final RhSync sync;private boolean syncBusy;
    private final RhUpdates updates;private boolean updatesBusy;
    private boolean pairing;private String pairPin="";
    private static final java.util.concurrent.ExecutorService NETWORK=java.util.concurrent.Executors.newFixedThreadPool(2);
    public RhHome(Activity a,Actions callbacks) {
        activity=a;actions=callbacks;sync=new RhSync(a);updates=new RhUpdates(a);
        boolean dark=RhShared.dark(a);
        page=c(dark?"#151b29":"#f4f7fc");surface=c(dark?"#1b2232":"#ffffff");soft=c(dark?"#202a3e":"#f8faff");
        ink=c(dark?"#e1e8f6":"#34415b");muted=c(dark?"#a4b1c8":"#69778e");line=c(dark?"#303c53":"#e5eaf4");
        blue=c(dark?"#6f8af1":"#5274e8");blueSoft=c(dark?"#253452":"#edf2ff");purple=c(dark?"#ba9aef":"#8b5be2");purpleSoft=c(dark?"#332845":"#f4efff");green=c(dark?"#8bc7b1":"#2e795f");
        a.getWindow().setStatusBarColor(page);a.getWindow().setNavigationBarColor(surface);
        if(!dark)a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root=column();root.setBackgroundColor(page);root.setPadding(dp(26),dp(12),dp(26),0);
        LinearLayout top=row();TextView brand=text("RHRemote-Moonlight",18,ink,true);top.addView(brand,new LinearLayout.LayoutParams(0,dp(54),1));
        top.addView(button("设置",false,()->invoke("app.settings.open",RhActions.obj())));top.addView(button("＋ 添加电脑",false,()->invoke("device.add.open",RhActions.obj())));root.addView(top);
        scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        content=column();content.setPadding(0,dp(20),0,dp(20));scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=text("",12,muted,false);status.setMinHeight(dp(27));root.addView(status);
        bar=row();bar.setGravity(Gravity.CENTER);bar.setPadding(0,dp(10),0,dp(10));bar.setBackgroundColor(surface);
        bar.addView(button("设备",false,()->invoke("devices.open",RhActions.obj())));bar.addView(button("＋ 添加",false,()->invoke("device.add.open",RhActions.obj())));
        bar.addView(button("设置",false,()->invoke("app.settings.open",RhActions.obj())));root.addView(bar);
        empty=new RelativeLayout(a); // Compatibility with the upstream activity's discovery placeholder.
        a.setContentView(root);RhActions.initialize(a,this);restoreSharedDevices();showDevices();
        final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());handler.postDelayed(new Runnable(){public void run(){if(activity.isDestroyed())return;if(!RhActions.hasStream()){if(sync.state().optBoolean("automatic")&&!syncBusy&&draft==null&&sync.state().optJSONArray("conflicts").length()==0&&System.currentTimeMillis()-Math.max(lastSyncAttempt,sync.state().optLong("lastSyncMs"))>=sync.state().optInt("intervalMinutes",5)*60000L){lastSyncAttempt=System.currentTimeMillis();invoke("sync.run",RhActions.obj());}long last=activity.getSharedPreferences("rhremote.global",0).getLong("lastUpdateCheck",0);if(!updatesBusy&&System.currentTimeMillis()-last>21600000){activity.getSharedPreferences("rhremote.global",0).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).apply();startUpdate("updates.check",true);}}handler.postDelayed(this,30000);}},15000);
    }
    public RelativeLayout emptyView(){return empty;}
    private int c(String hex){return Color.parseColor(hex);}private int dp(float v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private GradientDrawable bg(int color,int border,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    private TextView text(String value,int size,int color,boolean medium){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);if(medium)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private Button button(String title,boolean primary,Runnable action){Button b=new Button(activity);b.setText(title);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setTextColor(primary?Color.WHITE:ink);b.setBackground(bg(primary?blue:surface,primary?0:line,24));b.setMinHeight(dp(46));b.setMinimumHeight(dp(46));b.setPadding(dp(18),dp(8),dp(18),dp(8));b.setOnClickListener(v->action.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(46));lp.setMargins(dp(4),dp(3),dp(4),dp(3));b.setLayoutParams(lp);return b;}
    private void heading(String title,String sub){TextView h=text(title,26,ink,true);h.setPadding(0,0,0,dp(7));content.addView(h);TextView s=text(sub,13,muted,false);s.setPadding(0,0,0,dp(26));content.addView(s);}
    public void update(ComputerDetails d){String signature=d.name+"|"+d.state+"|"+d.pairState+"|"+RhProfile.load(activity,d.uuid).summary();String old=signatures.put(d.uuid,signature);computers.put(d.uuid,d);if(!profileVisible&&!signature.equals(old))renderDevices(false);}
    public void message(String value){status.setText(value==null?"":value);}
    public boolean goBack(){if(pairing){invoke("pair.cancel",RhActions.obj());return true;}if(profileVisible){invoke("devices.open",RhActions.obj());return true;}return false;}
    public void showDevices(){pageId="devices";draft=null;selected=null;displayNameEditor=null;numberEditors.clear();draftDisplayName="";renderDevices(true);}
    private void renderDevices(boolean resetScroll){
        profileVisible=false;content.removeAllViews();heading("远程设备","");
        List<ComputerDetails> list=RhShared.ordered(activity,computers.values());
        if(list.isEmpty()){TextView hint=text("正在发现局域网电脑，也可以直接添加地址。",14,muted,false);hint.setPadding(dp(18),dp(36),dp(18),dp(36));content.addView(hint);}
        for(int i=0;i<list.size();i++)card(list.get(i),i>0,i<list.size()-1);
        if(resetScroll)scroll.post(()->scroll.scrollTo(0,0));
    }
    private void card(ComputerDetails d,boolean canMoveUp,boolean canMoveDown){
        boolean preferred=d.name!=null&&d.name.equalsIgnoreCase("winserver");boolean online=d.state==ComputerDetails.State.ONLINE;
        boolean paired=RhNetwork.connectInsteadOfPair(d);
        LinearLayout card=column();card.setPadding(dp(18),dp(15),dp(18),dp(15));card.setBackground(bg(preferred?blueSoft:surface,preferred?blue:line,18));
        LinearLayout.LayoutParams margin=new LinearLayout.LayoutParams(-1,-2);margin.setMargins(0,0,0,dp(12));content.addView(card,margin);
        LinearLayout info=row();TextView monitor=text("▣",25,blue,false);monitor.setGravity(Gravity.CENTER);monitor.setBackground(bg(blueSoft,0,12));info.addView(monitor,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout names=column();names.setPadding(dp(14),0,dp(8),0);TextView name=text(RhShared.displayName(activity,d.uuid,d.name),16,ink,true);names.addView(name);
        String state=d.state==ComputerDetails.State.UNKNOWN?"正在检查":(!online?"离线":paired?"● 已配对":"待配对");TextView st=text(state,12,paired&&online?green:muted,false);names.addView(st);
        names.addView(text(RhProfile.load(activity,d.uuid).summary(),12,muted,false));info.addView(names,new LinearLayout.LayoutParams(0,-2,1));card.addView(info);
        LinearLayout operations=row();operations.setGravity(Gravity.END);operations.setPadding(0,dp(8),0,0);
        Button up=button("↑",false,()->invoke("device.move",RhActions.obj("uuid",d.uuid,"direction","up")));up.setContentDescription("上移 "+RhShared.displayName(activity,d.uuid,d.name));up.setEnabled(canMoveUp);operations.addView(up);
        Button down=button("↓",false,()->invoke("device.move",RhActions.obj("uuid",d.uuid,"direction","down")));down.setContentDescription("下移 "+RhShared.displayName(activity,d.uuid,d.name));down.setEnabled(canMoveDown);operations.addView(down);
        operations.addView(button("连接设置",false,()->invoke("device.settings.open",RhActions.obj("uuid",d.uuid))));
        Button connect=button(!online?"检查状态":paired?"连接":"配对",preferred,()->invoke(paired?"device.connect":"device.pair",RhActions.obj("uuid",d.uuid)));
        if(!paired&&online){connect.setBackground(bg(purpleSoft,0,24));connect.setTextColor(purple);}operations.addView(connect);card.addView(operations);
    }
    private void label(String value){TextView t=text(value,13,muted,false);t.setPadding(0,dp(15),0,dp(7));content.addView(t);}
    private void choices(String[] values,String selected,java.util.function.Consumer<String> onSelect){LinearLayout r=row();r.setGravity(Gravity.START);for(String value:values){Button b=button(value,value.equals(selected),()->onSelect.accept(value));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(0,dp(3),dp(6),dp(3));b.setLayoutParams(p);b.setPadding(dp(6),0,dp(6),0);b.setTextSize(13);r.addView(b);}content.addView(r);}
    private CheckBox check(String label,boolean checked,java.util.function.Consumer<Boolean> change){CheckBox b=new CheckBox(activity);b.setText(label);b.setTextColor(ink);b.setTextSize(14);b.setChecked(checked);b.setMinHeight(dp(48));b.setButtonTintList(android.content.res.ColorStateList.valueOf(blue));b.setOnCheckedChangeListener((v,state)->change.accept(state));content.addView(b);return b;}
    public void showProfile(ComputerDetails d){pageId="settings";selected=d;draft=RhProfile.load(activity,d.uuid);draftDisplayName=RhShared.alias(activity,d.uuid);profileSourcesExpanded=false;showProfile(d,draft);scroll.post(()->scroll.scrollTo(0,0));}
    private void showProfile(ComputerDetails d,RhProfile p){
        numberEditors.clear();
        profileVisible=true;content.removeAllViews();org.json.JSONObject shared=RhShared.read(activity);String currentClient=RhShared.clientId(activity);
        heading(RhShared.displayName(activity,d.uuid,d.name),"主机名："+d.name+"\n当前客户端："+RhShared.clientInfo().optString("name")+" · "+currentClient.substring(currentClient.length()-4));
        displayNameEditor=field("设备别名（DisplayName）",draftDisplayName,false);displayNameEditor.setHint("留空使用原始主机名");
        label("画面与输入参数按客户端分别保存。主机的鼠标左右键设置共用。");
        org.json.JSONObject groups=shared.optJSONObject("profiles"),clients=shared.optJSONObject("clients");
        content.addView(button("从其他客户端复制配置",profileSourcesExpanded,()->invoke("settings.sources.toggle",RhActions.obj()))); if(profileSourcesExpanded) for(Iterator<String> it=groups.keys();it.hasNext();){String client=it.next();org.json.JSONObject group=groups.optJSONObject(client);if(client.equals(currentClient)||group==null||!group.has(d.uuid))continue;org.json.JSONObject source=group.optJSONObject(d.uuid),info=clients.optJSONObject(client);String name=info==null?client:info.optString("name");label(name+" · "+client.substring(Math.max(0,client.length()-4))+" · "+source.optInt("width")+" × "+source.optInt("height")+" · "+source.optInt("fps")+" 帧");content.addView(button("复制到当前配置",false,()->invoke("settings.copy_from",RhActions.obj("clientId",client))));}
        LinearLayout tabs=row();String[] tabIds={"video","audio","input","network","advanced"},titles={"画面","音频","输入","网络","高级"};for(int i=0;i<tabIds.length;i++){final String tab=tabIds[i];tabs.addView(button(titles[i],profileTab.equals(tab),()->invoke("settings.tab",RhActions.obj("tab",tab))));}android.widget.HorizontalScrollView tabScroll=new android.widget.HorizontalScrollView(activity);tabScroll.setHorizontalScrollBarEnabled(false);tabScroll.addView(tabs);content.addView(tabScroll);
        if(profileTab.equals("network"))try {
            org.json.JSONObject host=RhNetwork.metadata(activity,d);EditText primary=field("首选 IP 或主机名",host.getString("address"),false);
            org.json.JSONArray extra=host.optJSONArray("addresses");List<String> values=new ArrayList<>();if(extra!=null)for(int i=0;i<extra.length();i++)values.add(extra.getString(i));
            EditText backup=field("备用地址（逗号分隔，随 WebDAV 同步）",android.text.TextUtils.join(", ",values),false);
            content.addView(button("保存地址",false,()->{org.json.JSONArray addresses=new org.json.JSONArray();for(String a:backup.getText().toString().split("[,;，\n]"))if(!a.trim().isEmpty())addresses.put(a.trim());invoke("device.network.configure",RhActions.obj("uuid",d.uuid,"address",primary.getText().toString().trim(),"addresses",addresses));}));
            content.addView(button("诊断连接",false,()->invoke("device.network.diagnose",RhActions.obj("uuid",d.uuid))));
            label("连接顺序");java.util.ArrayList<String> routes=new java.util.ArrayList<>(p.routeOrder);for(String route:RhProfile.ROUTES)if(!routes.contains(route))routes.add(route);
            for(String route:routes){boolean enabled=p.routeOrder.contains(route);int index=java.util.Arrays.asList(RhProfile.ROUTES).indexOf(route);label(RhProfile.ROUTE_LABELS[index]);LinearLayout controls=row();controls.addView(button(enabled?"启用":"停用",enabled,()->invoke("settings.route.toggle",RhActions.obj("route",route))));if(enabled){controls.addView(button("↑",false,()->invoke("settings.route.move",RhActions.obj("route",route,"direction","up"))));controls.addView(button("↓",false,()->invoke("settings.route.move",RhActions.obj("route",route,"direction","down"))));}content.addView(controls);}
            content.addView(button("恢复默认顺序",false,()->set("routeOrder",new org.json.JSONArray(java.util.Arrays.asList(RhProfile.ROUTES)))));
            EditText relay=field("中继服务器",p.relayUrl,false);content.addView(button("应用服务器地址",false,()->set("relayUrl",relay.getText().toString().trim())));
            label("按已启用的顺序尝试，失败后自动回退。配置随 WebDAV 同步。");label(d.uuid.equals(RhNetwork.state().optString("uuid"))?RhNetwork.summary(RhNetwork.state()):"尚未诊断");
        }catch(Exception e){label("连接地址读取失败："+e.getMessage());}
        if(profileTab.equals("video"))choices(new String[]{"720P","1080P","1440P","平板","4K"},"",v->{int w=v.equals("720P")?1280:v.equals("1080P")?1920:v.equals("1440P")?2560:v.equals("平板")?2520:3840;int h=w==1280?720:w==1920?1080:w==2560?1440:w==2520?1680:2160;set("resolution",new org.json.JSONArray().put(w).put(h));});
        try{org.json.JSONArray fields=RhSettings.catalog(activity);org.json.JSONObject values=p.json();for(int i=0;i<fields.length();i++){
            org.json.JSONObject f=fields.getJSONObject(i);if(!profileTab.equals(f.getString("tab"))||!RhSettings.supports(f,"android"))continue;String key=f.getString("key"),type=f.getString("type"),title=f.getString("label");
            if(type.equals("bool")){check(title,values.getBoolean(key),v->set(key,v));}
            else if(type.equals("number")){EditText edit=field(title,String.valueOf(values.getInt(key)),false);edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);numberEditors.put(key,edit);}
            else if(type.equals("select")){label(title);org.json.JSONArray options=f.getJSONArray("options");String[] labels=new String[options.length()],keys=new String[options.length()];int index=0;for(int j=0;j<options.length();j++){keys[j]=options.getJSONArray(j).getString(0);labels[j]=options.getJSONArray(j).getString(1);if(keys[j].equals(values.getString(key)))index=j;}Spinner spinner=new Spinner(activity);ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels);spinner.setAdapter(adapter);spinner.setSelection(index,false);final String previous=values.getString(key);spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long row){if(!keys[position].equals(previous))set(key,keys[position]);}});content.addView(spinner,new LinearLayout.LayoutParams(-1,dp(48)));}
            if(!f.optString("help").isEmpty())label(f.optString("help"));
        }}catch(org.json.JSONException e){throw new IllegalStateException("invalid settings catalog",e);}
        if(profileTab.equals("audio"))label("主机 Sunshine 需启用音频捕获。串流中可随时切换声音和音量。");label("保存后用于下次连接。");
        LinearLayout end=row();end.setGravity(Gravity.END);end.addView(button("取消",false,()->invoke("settings.cancel",RhActions.obj())));end.addView(button("保存",true,()->invoke("settings.save",RhActions.obj())));content.addView(end);
    }
    private void addDialog(){EditText input=new EditText(activity);input.setSingleLine(true);input.setTextColor(ink);input.setTextSize(16);input.setHint("例如 192.168.0.104");input.setPadding(dp(20),dp(14),dp(20),dp(14));adding=new AlertDialog.Builder(activity).setTitle("添加电脑").setView(input).setNegativeButton("取消",(d,w)->invoke("device.add.cancel",RhActions.obj())).setPositiveButton("添加",(dialog,w)->invoke("device.add.submit",RhActions.obj("address",input.getText().toString().trim()))).create();adding.show();}
    private void invoke(String id,org.json.JSONObject args){RhActions.click(id,args);}
    private void set(String key,Object value){invoke("settings.set",RhActions.obj("key",key,"value",value));}
    public org.json.JSONObject state()throws Exception{
        org.json.JSONArray devices=new org.json.JSONArray();org.json.JSONObject profiles=new org.json.JSONObject();for(ComputerDetails d:RhShared.ordered(activity,computers.values())){devices.put(RhActions.obj("uuid",d.uuid,"name",d.name,"displayName",RhShared.alias(activity,d.uuid),"online",d.state==ComputerDetails.State.ONLINE,"paired",d.pairState==PairingManager.PairState.PAIRED,"isSelf",false));profiles.put(d.uuid,RhProfile.load(activity,d.uuid).json());}
        org.json.JSONObject shared=RhShared.read(activity);
        return RhActions.obj("network",RhNetwork.state(),"networkBusy",networkBusy,"platform","android","page",pageId,"settingsTab",settingsTab,"profileTab",profileTab,"profileSourcesExpanded",profileSourcesExpanded,"settingsCatalog",RhSettings.catalog(activity),"devices",devices,"saved",RhActions.obj("profiles",profiles,"dark",RhShared.dark(activity),"clientId",RhShared.clientId(activity),"clients",shared.getJSONObject("clients"),"clientProfiles",shared.getJSONObject("profiles"),"devices",shared.getJSONArray("devices"),"profileSchemaVersion",2),"draft",draft==null?org.json.JSONObject.NULL:draft.json(),"selected",selected==null?"":selected.uuid,"draftDisplayName",draftDisplayName,"status",status.getText().toString(),"sync",sync.state(),"syncBusy",syncBusy,"updates",updates.state(),"updatesBusy",updatesBusy,"tracing",RhStatistics.state(),"pairPin",pairPin,"pairing",pairing);
    }
    public org.json.JSONObject perform(String id,org.json.JSONObject args)throws Exception{
        if(syncBusy&&(id.startsWith("sync.")||id.equals("device.move")||id.equals("device.settings.open")||id.startsWith("settings.")||id.equals("appearance.toggle")||id.equals("device.add.submit")))throw new IllegalStateException("同步进行中，请稍后保存");
        if(pairing&&(id.equals("device.connect")||id.equals("device.pair")||id.equals("appearance.toggle")))throw new IllegalStateException("请先取消当前配对");
        if(draft!=null&&displayNameEditor!=null&&id.startsWith("settings."))draftDisplayName=displayNameEditor.getText().toString();
        if(draft!=null&&id.startsWith("settings.")&&!id.equals("settings.cancel")){org.json.JSONObject pending=draft.json();for(Map.Entry<String,EditText> entry:numberEditors.entrySet()){try{pending.put(entry.getKey(),Integer.parseInt(entry.getValue().getText().toString()));}catch(NumberFormatException e){throw new IllegalArgumentException("请输入有效的数字");}}draft=RhProfile.fromJson(selected.uuid,pending);}
        switch(id){
            case "statistics.state":return RhStatistics.state();
            case "statistics.configure":if(!(args.get("enabled") instanceof Boolean))throw new IllegalArgumentException("enabled must be boolean");org.json.JSONObject tracing=RhStatistics.configure(args.getBoolean("enabled"));if(pageId.equals("global-settings"))showGlobal();return tracing;
            case "devices.open":case "settings.cancel":showDevices();break;
            case "devices.refresh":actions.refresh();break;
            case "device.move":if(!pageId.equals("devices"))throw new IllegalStateException("请先返回设备列表");RhShared.move(activity,computers.values(),args.getString("uuid"),args.getString("direction"));renderDevices(false);message("设备顺序已保存");break;
            case "device.add.open":addDialog();break;
            case "device.add.cancel":if(adding!=null)adding.dismiss();break;
            case "device.add.submit":String address=args.getString("address");if(address.isEmpty())throw new IllegalArgumentException("address required");actions.add(address);if(adding!=null)adding.dismiss();break;
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
            case "settings.set":if(draft==null)throw new IllegalStateException("open device settings first");org.json.JSONObject next=draft.json();String key=args.getString("key");if(key.equals("displayName")){draftDisplayName=RhShared.validateDisplayName(args.getString("value"));showProfile(selected,draft);break;}if(key.equals("resolution")){org.json.JSONArray size=args.getJSONArray("value");next.put("width",size.getInt(0));next.put("height",size.getInt(1));}else if(next.has(key))next.put(key,args.get("value"));else throw new IllegalArgumentException("unknown option");draft=RhProfile.fromJson(selected.uuid,next);showProfile(selected,draft);break;
            case "settings.copy_from":if(draft==null)throw new IllegalStateException("open device settings first");String client=args.getString("clientId");if(client.equals(RhShared.clientId(activity)))throw new IllegalArgumentException("select another client");org.json.JSONObject group=RhShared.read(activity).getJSONObject("profiles").optJSONObject(client);if(group==null||!group.has(selected.uuid))throw new IllegalArgumentException("no profile for this client and device");boolean swap=draft.swapButtons;draft=RhProfile.fromClientJson(selected.uuid,group.getJSONObject(selected.uuid));draft.swapButtons=swap;showProfile(selected,draft);break;
            case "settings.save":if(draft==null)throw new IllegalStateException("no settings draft");RhShared.saveProfile(activity,selected,draft,args.optString("displayName",draftDisplayName));showDevices();message("连接偏好已保存");break;
            case "network.state":return RhNetwork.state();
            case "device.network.configure":ComputerDetails networkTarget=computers.get(args.getString("uuid"));if(networkTarget==null)throw new IllegalArgumentException("unknown device");RhShared.capture(activity,Collections.singletonList(networkTarget));RhShared.saveAddresses(activity,args.getString("uuid"),args.getString("address"),args.getJSONArray("addresses"));message("连接地址已保存");if(draft!=null)showProfile(selected,draft);break;
            case "device.network.diagnose":{
                if(networkBusy)throw new IllegalStateException("连接检查正在进行");ComputerDetails device=computers.get(args.getString("uuid"));if(device==null)throw new IllegalArgumentException("unknown device");
                networkBusy=true;message("正在诊断连接…");NETWORK.execute(()->{try{RhNetwork.diagnose(activity,device,draft!=null&&selected!=null&&selected.uuid.equals(device.uuid)?draft:RhProfile.load(activity,device.uuid),args);activity.runOnUiThread(()->message("连接诊断完成"));}catch(Exception e){activity.runOnUiThread(()->message("诊断："+e.getMessage()));}finally{activity.runOnUiThread(()->{networkBusy=false;if(draft!=null&&profileTab.equals("network"))showProfile(selected,draft);});}});break;
            }
            case "device.connect":case "device.pair":{
                if(networkBusy)throw new IllegalStateException("正在检查连接地址");ComputerDetails target=computers.get(args.getString("uuid"));if(target==null)throw new IllegalArgumentException("unknown device");
                boolean connect=id.equals("device.connect");String pin=args.optString("pin",String.format(Locale.ROOT,"%04d",new java.security.SecureRandom().nextInt(10000)));if(!pin.matches("[0-9]{4}"))throw new IllegalArgumentException("PIN must contain four digits");
                networkBusy=true;message("正在检查连接地址…");NETWORK.execute(()->{try{
                    if(connect)RhStatistics.begin(RhProfile.load(activity,target.uuid));
                    ComputerDetails resolved=RhNetwork.refreshConnectionDetails(activity,RhNetwork.resolve(activity,new ComputerDetails(target),RhProfile.load(activity,target.uuid)));
                    activity.runOnUiThread(()->{networkBusy=false;update(resolved);if(connect){actions.connect(resolved);}else{pairPin=pin;pairing=true;showPair();actions.pair(resolved,pairPin);}});
                }catch(Exception e){if(connect)RhStatistics.finish();activity.runOnUiThread(()->{networkBusy=false;message("连接失败："+e.getMessage());});}});break;
            }
            case "pair.cancel":if(!pairing)throw new IllegalStateException("no active pairing");actions.cancelPair();message("正在取消配对…");break;
            case "appearance.toggle":RhShared.theme(activity,!RhShared.dark(activity));activity.recreate();break;
            case "app.settings.open":pageId="global-settings";showGlobal();break;
            case "app.settings.tab":String tab=args.getString("tab");if(!Arrays.asList("appearance","sync","updates","about").contains(tab))throw new IllegalArgumentException("unknown tab");settingsTab=tab;pageId="global-settings";showGlobal();break;
            case "sync.state":return sync.state();
            case "sync.configure":sync.configure(args);message("同步设置已保存");showGlobal();break;
            case "sync.clear":sync.clear();showGlobal();break;
            case "sync.run":case "sync.resolve":if(draft!=null)throw new IllegalStateException("请先保存或取消设备设置");startSync(id.equals("sync.run")?"review":args.getString("resolution"));break;
            case "updates.state":return updates.state();
            case "updates.check":case "updates.download":if(updatesBusy)throw new IllegalStateException("更新操作正在进行");startUpdate(id);break;
            case "updates.install":updates.install(activity);break;
            default:throw new IllegalArgumentException("unsupported Android action: "+id);
        }return RhActions.obj("accepted",true);
    }
    private void showGlobal(){profileVisible=true;content.removeAllViews();heading("设置","");LinearLayout tabs=row();for(String tab:new String[]{"updates","sync","appearance","about"}){String title=tab.equals("appearance")?"外观":tab.equals("sync")?"配置同步":tab.equals("updates")?"更新":"关于";tabs.addView(button(title,settingsTab.equals(tab),()->invoke("app.settings.tab",RhActions.obj("tab",tab))));}android.widget.HorizontalScrollView tabScroll=new android.widget.HorizontalScrollView(activity);tabScroll.setHorizontalScrollBarEnabled(false);tabScroll.addView(tabs);content.addView(tabScroll);if(settingsTab.equals("sync")){showSync();return;}if(settingsTab.equals("updates")){showUpdates();return;}label(settingsTab.equals("about")?"RHRemote-Moonlight · Android 原生 View + Java":"主题");if(settingsTab.equals("about")){content.addView(button(RhStatistics.enabled()?"测试统计：开启":"测试统计：关闭",RhStatistics.enabled(),()->invoke("statistics.configure",RhActions.obj("enabled",!RhStatistics.enabled()))));label("测试模式每次连接记录阶段和逐帧时间。分析后自动清理原始记录。");label(RhStatistics.state().optString("latest"));}if(settingsTab.equals("appearance"))content.addView(button("切换深色 / 浅色",false,()->invoke("appearance.toggle",RhActions.obj())));}
    private void showUpdates(){org.json.JSONObject state=updates.state();label("RHRemote-Moonlight "+state.optString("currentVersion"));label("GitHub / Gitee");if(!state.isNull("availableVersion"))label("可用版本 "+state.optString("availableVersion"));label(state.optString("status"));LinearLayout commands=row();commands.addView(button(updatesBusy?"处理中…":"检查更新",false,()->invoke("updates.check",RhActions.obj())));commands.addView(button("下载 APK",false,()->invoke("updates.download",RhActions.obj())));commands.addView(button("安装更新",true,()->invoke("updates.install",RhActions.obj())));content.addView(commands);label("下载后在系统安装界面手动确认。");}
    private void startUpdate(String id){startUpdate(id,false);}
    private void startUpdate(String id,boolean automatic){updatesBusy=true;NETWORK.execute(()->{String error=null;try{if(id.equals("updates.check")){updates.check();if(automatic&&!updates.state().isNull("availableVersion"))updates.download();}else updates.download();}catch(Exception e){error=e instanceof java.io.IOException?e.getMessage():"更新操作失败";}final String failure=error;activity.runOnUiThread(()->{updatesBusy=false;if(failure!=null&&!automatic)message(failure);if(pageId.equals("global-settings")&&settingsTab.equals("updates"))showGlobal();if(failure==null&&!automatic&&id.equals("updates.download"))invoke("updates.install",RhActions.obj());});});}
    private EditText field(String title,String value,boolean password){label(title);EditText edit=new EditText(activity);edit.setTextColor(ink);edit.setTextSize(15);edit.setSingleLine(true);edit.setText(value);if(password)edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);content.addView(edit,new LinearLayout.LayoutParams(-1,dp(52)));return edit;}
    private void showSync(){org.json.JSONObject s=sync.state();EditText url=field("WebDAV 目录地址（HTTP / HTTPS）",s.optString("url"),false),user=field("用户名",s.optString("username"),false),password=field(s.optBoolean("hasPassword")?"密码（留空保留）":"密码","",true);CheckBox automatic=check("自动同步",s.optBoolean("automatic"),v->{});label("同步间隔");Spinner interval=new Spinner(activity);interval.setAdapter(new android.widget.ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"5 分钟","15 分钟","30 分钟","60 分钟"}));interval.setSelection(Math.max(0,Arrays.asList(5,15,30,60).indexOf(s.optInt("intervalMinutes",5))));content.addView(interval);label("同步设备列表、各客户端的连接配置和主题。各端只使用自己的配置，其他端的配置可手动复制。配对密钥及本机凭据不参与同步。");LinearLayout commands=row();commands.addView(button("保存设置",false,()->invoke("sync.configure",RhActions.obj("url",url.getText().toString(),"username",user.getText().toString(),"password",password.getText().toString(),"automatic",automatic.isChecked(),"intervalMinutes",new int[]{5,15,30,60}[interval.getSelectedItemPosition()]))));commands.addView(button(syncBusy?"同步中…":"立即同步",true,()->invoke("sync.run",RhActions.obj())));commands.addView(button("移除设置",false,()->invoke("sync.clear",RhActions.obj())));content.addView(commands);org.json.JSONArray conflicts=s.optJSONArray("conflicts");if(conflicts!=null&&conflicts.length()>0){label("配置冲突："+conflicts.toString());LinearLayout choices=row();choices.addView(button("冲突项保留本机",false,()->invoke("sync.resolve",RhActions.obj("resolution","local"))));choices.addView(button("冲突项保留远端",false,()->invoke("sync.resolve",RhActions.obj("resolution","remote"))));content.addView(choices);}}
    private void startSync(String resolution)throws Exception{org.json.JSONObject captured=RhShared.capture(activity,new ArrayList<>(computers.values()));syncBusy=true;message("同步中…");NETWORK.execute(()->{try{org.json.JSONObject result=sync.run(captured,resolution);activity.runOnUiThread(()->{syncBusy=false;try{if(result.has("shared")){boolean before=RhShared.dark(activity);RhShared.apply(activity,result.getJSONObject("shared"));restoreSharedDevices();message("同步完成");if(before!=RhShared.dark(activity)){activity.recreate();return;}}else message("配置冲突，请选择保留哪一端的冲突项");if(pageId.equals("global-settings"))showGlobal();else if(pageId.equals("devices"))renderDevices(false);}catch(Exception e){message("应用同步配置失败");}});}catch(Exception e){activity.runOnUiThread(()->{syncBusy=false;message(e instanceof java.io.IOException?e.getMessage():"同步失败，请检查 WebDAV 设置");if(pageId.equals("global-settings"))showGlobal();});}});}
    private void restoreSharedDevices(){try{org.json.JSONArray list=RhShared.read(activity).getJSONArray("devices");for(int i=0;i<list.length();i++){org.json.JSONObject d=list.getJSONObject(i);String uuid=d.getString("uuid");if(!computers.containsKey(uuid)){ComputerDetails added=new ComputerDetails();added.uuid=uuid;added.name=d.getString("name");added.manualAddress=new ComputerDetails.AddressTuple(d.getString("address"),47989);computers.put(uuid,added);}}}catch(Exception e){message("已同步的设备列表读取失败");}}
    private void showPair(){pageId="pair";profileVisible=true;content.removeAllViews();heading("配对","");label("在 Sunshine 输入配对码");TextView pin=text(pairPin,40,blue,true);content.addView(pin);content.addView(button("取消配对",false,()->invoke("pair.cancel",RhActions.obj())));}
    public void pairFinished(boolean success,String result){pairing=false;pairPin="";showDevices();message(result);}
}
