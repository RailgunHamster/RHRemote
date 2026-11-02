package com.limelight.rhremote;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.*;
import org.json.JSONObject;

/** Native device workspace: a split view on tablets and list/detail navigation on narrow screens. */
public final class RhDeviceBrowser extends LinearLayout {
    public interface Actions { void send(String id,JSONObject args); }
    private final Activity activity;
    private final Actions actions;
    private final int surface,page,ink,muted,line,accent,soft,green,failure;
    private final LinearLayout listPane,list,detailPane,detailBody,detailHeader,detailChrome,detailNavigation,listEmpty,detailEmpty;
    private final RhActionRow tabs;
    private final ScrollView detail;
    private final EditText search;
    private final TextView count,title,subtitle,availability,endpoints,lastRoute,checkNotice,listEmptyText,detailEmptyText;
    private final Button back,globalSettings;
    private final ImageButton addDevice,refreshDevices;
    private boolean navigationBlocked;
    private final RhBoundAction.Button settings,connect,windows,pair,info,inspect,listEmptyAction,detailEmptyAction;
    private final Map<String,RhBoundAction.Row> rows=new LinkedHashMap<>();
    private final TextView[] values=new TextView[8];
    private final Map<LinearLayout,Float> metricWeights=new LinkedHashMap<>();
    private final List<LinearLayout> metricGroups=new ArrayList<>();
    private List<ComputerDetails> devices=Collections.emptyList();
    private String selected="",query="",selectionAction="device.select";
    private final LinearLayout brand;
    private boolean wide,detailOpen,updatingSearch,listOnly,connectionBlocked,windowEligible;
    private int widthBucket=-1;
    private int detailHeaderMode=-1;
    private View rowToReveal;
    private boolean resetDetailAfterLayout;

    public RhDeviceBrowser(Activity activity,Actions actions) {
        super(activity);this.activity=activity;this.actions=actions;
        boolean dark=RhShared.dark(activity);
        surface=Color.parseColor(dark?"#1b2232":"#ffffff");page=Color.parseColor(dark?"#151b29":"#f6f7fa");
        ink=Color.parseColor(dark?"#e1e8f6":"#34415b");muted=Color.parseColor(dark?"#a4b1c8":"#69778e");
        line=Color.parseColor(dark?"#303c53":"#e5eaf4");accent=Color.parseColor(dark?"#6f8af1":"#5274e8");
        soft=Color.parseColor(dark?"#253452":"#edf2ff");green=Color.parseColor(dark?"#8bc7b1":"#369c81");
        failure=RhUiStyle.failure(dark);
        setOrientation(HORIZONTAL);setBackgroundColor(surface);

        listPane=column();listPane.setPadding(dp(16),dp(16),dp(16),dp(16));
        brand=row();brand.setPadding(0,0,0,dp(12));ImageView logo=new ImageView(activity);logo.setImageResource(R.drawable.rh_icon);brand.addView(logo,new LayoutParams(dp(30),dp(30)));TextView brandName=text("RHRemote",18,ink,true);LayoutParams brandParams=new LayoutParams(0,dp(48),1);brandParams.setMarginStart(dp(10));brand.addView(brandName,brandParams);listPane.addView(brand);
        LinearLayout listHeading=row();TextView heading=text("远程设备",20,ink,true);
        heading.setMinHeight(dp(48));listHeading.addView(heading,new LayoutParams(0,-2,1));
        refreshDevices=iconButton("刷新设备",R.drawable.rh_refresh,()->send("devices.refresh"));listHeading.addView(refreshDevices);
        addDevice=iconButton("添加电脑",R.drawable.rh_plus,()->send("device.add.open"));listHeading.addView(addDevice);
        listPane.addView(listHeading);space(listPane,12);
        search=new EditText(activity);RhTextInput.filter(search);search.setTextSize(14);search.setTextColor(ink);search.setHintTextColor(muted);
        search.setHint("搜索名称或地址");search.setContentDescription("搜索名称或地址");search.setPadding(dp(12),0,dp(12),0);
        search.setBackground(background(page,line,8));search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(!updatingSearch){String value=s.toString();fromView(search,()->send("devices.filter","query",value));}}public void afterTextChanged(Editable value){}});
        listPane.addView(search,new LayoutParams(-1,dp(48)));space(listPane,12);
        ScrollView listScroll=new ScrollView(activity);listScroll.setFillViewport(false);
        list=column();listScroll.addView(list);listPane.addView(listScroll,new LayoutParams(-1,0,1));
        listEmpty=column();listEmpty.setGravity(Gravity.CENTER);listEmpty.setPadding(0,dp(24),0,dp(24));listEmptyText=text("尚未添加设备",13,muted,false);listEmptyText.setGravity(Gravity.CENTER);listEmpty.addView(listEmptyText,new LayoutParams(-1,-2));space(listEmpty,16);listEmptyAction=boundButton("添加电脑",true);listEmpty.addView(listEmptyAction,new LayoutParams(-2,-2));
        listScroll.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->{
            View target=rowToReveal;rowToReveal=null;
            if(target!=null&&target.hasFocus()&&target.getRevealOnFocusHint()&&RhViewOrigin.visibleWithin(this,target)){
                target.requestRectangleOnScreen(new Rect(0,0,target.getWidth(),target.getHeight()),true);
                // A resize may have animated toward the row's old coordinates before this layout.
                listScroll.smoothScrollTo(listScroll.getScrollX(),listScroll.getScrollY());
            }
        });
        divider(listPane);count=text("",12,muted,false);count.setPadding(0,dp(14),0,dp(12));listPane.addView(count);
        globalSettings=button("设置",false,()->send("app.settings.open"));globalSettings.setTag("nav:app.settings.open");listPane.addView(globalSettings,new LayoutParams(-1,-2));
        addView(listPane,new LayoutParams(dp(272),-1));
        View separator=new View(activity);separator.setBackgroundColor(line);addView(separator,new LayoutParams(dp(1),-1));separator.setTag("browser-divider");

        detailPane=column();
        detail=new ScrollView(activity);detail.setFillViewport(true);
        detail.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->{
            if(resetDetailAfterLayout){resetDetailAfterLayout=false;detail.scrollTo(0,0);}
        });
        LinearLayout detailContent=column();detail.addView(detailContent);
        detailHeader=column();detailHeader.setPadding(dp(24),dp(20),dp(24),dp(20));detailHeader.setBackgroundColor(page);
        LinearLayout titleRow=row();title=text("远程设备",25,ink,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMinHeight(dp(48));titleRow.addView(title,new LayoutParams(0,-2,1));availability=text("",12,green,true);availability.setGravity(Gravity.CENTER);
        // A centered badge needs its real content width, not singleLine's scrolling layout.
        availability.setMinHeight(dp(28));availability.setMinWidth(dp(64));availability.setMaxLines(1);availability.setPadding(dp(8),dp(4),dp(8),dp(4));availability.setBackground(background(surface,0,14));LayoutParams availabilityParams=new LayoutParams(-2,-2);availabilityParams.setMarginStart(dp(12));titleRow.addView(availability,availabilityParams);
        detailHeader.addView(titleRow);subtitle=text("",13,muted,false);subtitle.setSingleLine(true);subtitle.setEllipsize(TextUtils.TruncateAt.END);detailHeader.addView(subtitle);
        // Navigation belongs to the pane, not to its scrolling information body.
        detailChrome=column();detailPane.addView(detailChrome);detailNavigation=column();detailNavigation.setGravity(Gravity.CENTER_VERTICAL);detailChrome.addView(detailNavigation);detailNavigation.addView(detailHeader);
        tabs=new RhActionRow(activity);tabs.setPadding(dp(24),dp(12),dp(24),dp(12));
        back=button("返回设备",false,()->send("devices.open"));tabs.addView(back);
        TextView overview=text("概览",14,accent,true);overview.setGravity(Gravity.CENTER);overview.setMinHeight(dp(48));tabs.addView(overview,new LayoutParams(-2,-2));
        settings=boundButton("连接设置",false);tabs.addView(settings);detailNavigation.addView(tabs);divider(detailChrome);

        detailBody=column();detailBody.setPadding(dp(24),dp(24),dp(24),dp(24));detailContent.addView(detailBody);
        LinearLayout hero=row();hero.setPadding(dp(18),dp(18),dp(18),dp(18));hero.setBackground(background(surface,line,10));
        ImageView monitor=new ImageView(activity);monitor.setImageResource(R.drawable.rh_monitor);monitor.setColorFilter(accent);monitor.setPadding(dp(12),dp(12),dp(12),dp(12));monitor.setBackground(background(page,0,8));
        LayoutParams imageParams=new LayoutParams(dp(72),dp(72));imageParams.setMarginEnd(dp(20));hero.addView(monitor,imageParams);
        LinearLayout heroActions=column();heroActions.addView(text("桌面连接",17,ink,true));checkNotice=text("",13,muted,false);checkNotice.setPadding(0,dp(8),0,0);heroActions.addView(checkNotice);space(heroActions,12);
        connect=boundButton("连接",true);
        connect.setMinWidth(dp(112));connect.setMinimumWidth(dp(112));heroActions.addView(connect,new LayoutParams(-2,-2));
        windows=boundButton("选择窗口",false);LayoutParams windowsLayout=new LayoutParams(-2,-2);windowsLayout.topMargin=dp(8);heroActions.addView(windows,windowsLayout);
        pair=boundButton("配对",false);LayoutParams pairLayout=new LayoutParams(-2,-2);pairLayout.topMargin=dp(8);heroActions.addView(pair,pairLayout);pair.setVisibility(GONE);
        hero.addView(heroActions,new LayoutParams(0,-2,1));
        detailBody.addView(hero);space(detailBody,16);

        LinearLayout quality=row();metric(quality,"分辨率",0,1.5f);metric(quality,"帧率",1,1);metric(quality,"码率",2,1);detailBody.addView(quality);
        space(detailBody,16);divider(detailBody);space(detailBody,16);LinearLayout infoHeading=row();TextView infoTitle=text("设备信息",14,ink,true);infoTitle.setMinHeight(dp(48));infoHeading.addView(infoTitle,new LayoutParams(0,-2,1));info=boundButton("编辑资料",false);infoHeading.addView(info);detailBody.addView(infoHeading);space(detailBody,14);
        LinearLayout host=row();metric(host,"首选地址",3,1.5f);metric(host,"配对状态",4,1);detailBody.addView(host);space(detailBody,12);endpoints=text("",13,muted,false);detailBody.addView(endpoints);space(detailBody,8);lastRoute=text("",13,muted,false);detailBody.addView(lastRoute);inspect=boundButton("检查连接",false);inspect.setTag("device.check");LayoutParams inspectLayout=new LayoutParams(-2,-2);inspectLayout.topMargin=dp(12);detailBody.addView(inspect,inspectLayout);
        section("当前客户端配置");LinearLayout profile=row();metric(profile,"视频编码",5,1);metric(profile,"声音",6,1);metric(profile,"屏幕",7,1);detailBody.addView(profile);
        detailEmpty=column();detailEmpty.setGravity(Gravity.CENTER);detailEmpty.setPadding(dp(24),dp(32),dp(24),dp(32));detailEmptyText=text("尚未添加设备",18,muted,false);detailEmptyText.setGravity(Gravity.CENTER);detailEmpty.addView(detailEmptyText,new LayoutParams(-1,-2));space(detailEmpty,20);detailEmptyAction=boundButton("添加电脑",true);detailEmptyAction.bind("device.add.open",RhActions.obj());detailEmpty.addView(detailEmptyAction,new LayoutParams(-2,-2));detailContent.addView(detailEmpty,new LayoutParams(-1,0,1));
        detailPane.addView(detail,new LayoutParams(-1,0,1));
        addView(detailPane,new LayoutParams(0,-1,1));
        applyMode();
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void fromView(View source,Runnable action){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){activity.runOnUiThread(()->fromView(source,action));return;}
        if(!activity.isDestroyed()&&!activity.isFinishing()&&RhViewOrigin.visibleWithin(this,source))action.run();
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",0));return v;}
    private GradientDrawable background(int fill,int border,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    private void styleButton(Button b,String label,boolean primary){b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setStateListAnimator(null);b.setElevation(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(16),dp(8),dp(16),dp(8));RhUiStyle.button(b,primary?Color.WHITE:ink,muted,background(primary?accent:surface,primary?0:line,8),background(page,line,8),background(primary?accent:surface,primary?ink:accent,8));b.setLayoutParams(new LayoutParams(-2,-2));}
    private ImageButton iconButton(String label,int icon,Runnable action){ImageButton b=new ImageButton(activity);b.setImageResource(icon);b.setColorFilter(ink);b.setContentDescription(label);RhUiStyle.focusBackground(b,background(surface,0,8),background(soft,0,8),background(surface,accent,8));b.setPadding(dp(12),dp(12),dp(12),dp(12));b.setOnClickListener(v->fromView(v,action));b.setLayoutParams(new LayoutParams(dp(48),dp(48)));return b;}
    private Button button(String label,boolean primary,Runnable action){Button b=new Button(activity);styleButton(b,label,primary);b.setOnClickListener(v->fromView(v,action));return b;}
    private RhBoundAction.Button boundButton(String label,boolean primary){RhBoundAction.Button b=new RhBoundAction.Button(activity,(source,id,args)->fromView(source,()->actions.send(id,args)));styleButton(b,label,primary);return b;}
    private void send(String id,Object... args){actions.send(id,RhActions.obj(args));}
    private void space(LinearLayout parent,int height){parent.addView(new View(activity),new LayoutParams(-1,dp(height)));}
    private void divider(LinearLayout parent){View v=new View(activity);v.setBackgroundColor(line);parent.addView(v,new LayoutParams(-1,dp(1)));}
    private void section(String label){space(detailBody,16);detailBody.addView(text(label,14,ink,true));space(detailBody,14);}
    private void metric(LinearLayout parent,String label,int index,float weight){LinearLayout cell=column();cell.setPadding(0,0,dp(8),0);cell.addView(text(label,12,muted,false));space(cell,8);TextView value=text("—",14,ink,false);value.setSingleLine(true);value.setEllipsize(TextUtils.TruncateAt.END);values[index]=value;cell.addView(value);parent.addView(cell,new LayoutParams(0,-2,weight));metricWeights.put(cell,weight);if(!metricGroups.contains(parent))metricGroups.add(parent);}
    private void fitMetrics(int available){
        for(LinearLayout group:metricGroups){
            float weights=0;for(int i=0;i<group.getChildCount();i++)weights+=metricWeights.get((LinearLayout)group.getChildAt(i));
            boolean horizontal=true;
            for(int i=0;i<group.getChildCount();i++){
                LinearLayout cell=(LinearLayout)group.getChildAt(i);int required=0;
                for(int j=0;j<cell.getChildCount();j++)if(cell.getChildAt(j) instanceof TextView){TextView text=(TextView)cell.getChildAt(j);required=Math.max(required,(int)Math.ceil(text.getPaint().measureText(text.getText().toString()))+text.getCompoundPaddingLeft()+text.getCompoundPaddingRight());}
                required+=cell.getPaddingLeft()+cell.getPaddingRight();
                if(available*metricWeights.get(cell)/weights<required)horizontal=false;
            }
            int orientation=horizontal?HORIZONTAL:VERTICAL;if(group.getOrientation()==orientation)continue;group.setOrientation(orientation);
            for(int i=0;i<group.getChildCount();i++){LinearLayout cell=(LinearLayout)group.getChildAt(i);LayoutParams params=new LayoutParams(horizontal?0:-1,-2,horizontal?metricWeights.get(cell):0);params.topMargin=horizontal||i==0?0:dp(12);cell.setLayoutParams(params);}
        }
    }
    private ComputerDetails chosen(){for(ComputerDetails d:devices)if(d.uuid.equals(selected))return d;return null;}

    public void showDetail(boolean value){detailOpen=value;applyMode();}
    public void setSelectionAction(String action){if(selectionAction.equals(action))return;selectionAction=action;for(Map.Entry<String,RhBoundAction.Row> item:rows.entrySet())item.getValue().bind(action,RhActions.obj("uuid",item.getKey()));}
    public void setListOnly(boolean value){if(listOnly==value)return;listOnly=value;for(Map.Entry<String,RhBoundAction.Row> item:rows.entrySet())item.getValue().bind(selectionAction,RhActions.obj("uuid",item.getKey()));widthBucket=-1;applyMode();}
    public boolean isDetailOpen(){return !listOnly&&!wide&&detailOpen;}
    public void setNavigationBlocked(boolean blocked){navigationBlocked=blocked;for(View v:new View[]{globalSettings,addDevice,refreshDevices}){v.setEnabled(!blocked);v.setFocusable(!blocked);v.setAlpha(blocked?.45f:1f);}for(RhBoundAction.Row row:rows.values()){row.setEnabled(!blocked);row.setFocusable(!blocked);row.getChildAt(0).setEnabled(!blocked);}}
    public void setConnectionBlocked(boolean blocked){if(connectionBlocked==blocked)return;connectionBlocked=blocked;connectionEnabled();}
    private void connectionEnabled(){ComputerDetails d=chosen();connect.setEnabled(d!=null&&!connectionBlocked);windows.setEnabled(d!=null&&!connectionBlocked&&windowEligible);pair.setEnabled(d!=null&&!connectionBlocked);inspect.setEnabled(d!=null&&!connectionBlocked);}
    private void applyMode(){listEmptyAction.setVisibility(devices.isEmpty()&&wide&&!listOnly?GONE:VISIBLE);if(listOnly){listPane.setVisibility(VISIBLE);detailPane.setVisibility(GONE);back.setVisibility(GONE);findViewWithTag("browser-divider").setVisibility(VISIBLE);LayoutParams params=(LayoutParams)listPane.getLayoutParams();params.width=0;params.weight=1;listPane.setLayoutParams(params);return;}listPane.setVisibility(wide||!detailOpen?VISIBLE:GONE);detailPane.setVisibility(wide||detailOpen?VISIBLE:GONE);findViewWithTag("browser-divider").setVisibility(wide?VISIBLE:GONE);back.setVisibility(wide?GONE:VISIBLE);LayoutParams params=(LayoutParams)listPane.getLayoutParams();params.width=wide?dp(272):0;params.weight=wide?0:1;listPane.setLayoutParams(params);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getSize(widthSpec),bucket=width>=dp(700)?1:0;if(bucket!=widthBucket){widthBucket=bucket;wide=bucket==1;applyMode();}
        // Short landscape windows keep the title and actions, with room left to read the body.
        boolean compact=MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED&&MeasureSpec.getSize(heightSpec)<dp(400);
        listPane.setPadding(dp(16),dp(compact?8:16),dp(16),dp(compact?8:16));count.setVisibility(compact?GONE:VISIBLE);if(compact)brand.setVisibility(GONE);else brand.setVisibility(wide||listOnly?VISIBLE:GONE);
        int paneWidth=width-(wide?dp(273):0),headerMode=compact?(paneWidth>=dp(600)?2:1):0;
        if(headerMode!=detailHeaderMode){detailHeaderMode=headerMode;boolean beside=headerMode==2;detailNavigation.setOrientation(beside?HORIZONTAL:VERTICAL);
            detailHeader.setLayoutParams(new LayoutParams(beside?0:-1,-2,beside?1:0));tabs.setLayoutParams(new LayoutParams(beside?-2:-1,-2));
            detailHeader.setPadding(dp(beside?16:24),dp(compact?8:20),dp(beside?0:24),dp(compact?8:20));subtitle.setVisibility(compact?GONE:VISIBLE);tabs.setPadding(dp(beside?8:24),dp(compact?0:12),dp(beside?16:24),dp(compact?0:12));}
        fitMetrics(Math.max(0,width-(wide?dp(273):0)-detailBody.getPaddingLeft()-detailBody.getPaddingRight()));super.onMeasure(widthSpec,heightSpec);}

    public void update(List<ComputerDetails> devices,String selected,String query){
        update(RhShared.read(activity),devices,selected,query);
    }
    /** All row labels, endpoint facts and the selected profile belong to this one read. */
    void update(JSONObject shared,List<ComputerDetails> devices,String selected,String query){
        boolean selectionChanged=!Objects.equals(this.selected,selected);
        if(selectionChanged){View previousFocus=detailPane.findFocus();if(previousFocus!=null)previousFocus.clearFocus();}
        this.devices=new ArrayList<>(devices);this.selected=selected;this.query=query;
        if(!search.getText().toString().equals(query)){updatingSearch=true;search.setText(query);search.setSelection(search.length());updatingSearch=false;}
        renderList(shared);ComputerDetails d=chosen();boolean available=d!=null;settings.setEnabled(available);connectionEnabled();
        JSONObject target=RhActions.obj("uuid",available?d.uuid:"");JSONObject check=available?RhDeviceCheck.snapshot(activity,shared,d):new JSONObject();windowEligible=available&&RhDeviceCheck.canConnect(d,check);windows.setEnabled(windowEligible&&!connectionBlocked);String primary=available?RhDeviceCheck.primaryAction(d,check):"";JSONObject checkArgs=RhActions.obj("uuid",available?d.uuid:"");
        if(available)try{checkArgs.put("expectedIntent",RhDeviceCheck.intent(activity,shared,d));}catch(Exception invalid){/* The dispatcher validates malformed saved settings. */}
        windows.bind(available?"device.windows.open":"",target);settings.bind(available?"device.settings.open":"",target);info.bind(available?"device.info.open":"",target);connect.bind(primary,primary.equals("device.check")?checkArgs:target);pair.bind(available?"device.pair":"",target);inspect.bind(available?"device.check":"",checkArgs);inspect.setVisibility(available&&!primary.equals("device.check")?VISIBLE:GONE);
        detailBody.setVisibility(available?VISIBLE:GONE);detailChrome.setVisibility(available?VISIBLE:GONE);detailEmpty.setVisibility(available?GONE:VISIBLE);detailEmptyText.setText(devices.isEmpty()?"尚未添加设备":"选择设备");detailEmptyAction.setVisibility(devices.isEmpty()?VISIBLE:GONE);settings.setVisibility(available?VISIBLE:GONE);pair.setVisibility(available&&!RhDeviceCheck.blocks(check)&&primary.equals("device.check")&&d.serverCert==null?VISIBLE:GONE);
        if(d==null){detailOpen=false;applyMode();title.setText("远程设备");subtitle.setText("尚未添加设备");availability.setVisibility(GONE);for(TextView value:values){value.setText("—");value.setTextColor(ink);}return;}
        availability.setVisibility(VISIBLE);boolean online=RhDeviceCheck.online(d,check);
        String name=RhShared.displayName(shared,d.uuid,d.name);title.setText(name);subtitle.setText(name.equals(d.name)?address(shared,d):d.name);
        availability.setText(online?"在线":RhDeviceCheck.reachability(d,check).equals("unknown")?"待检查":"离线");availability.setTextColor(online?green:muted);
        endpoints.setText(RhNetwork.savedSummary(shared,d));lastRoute.setText(RhNetwork.lastRoute(d.uuid));
        connect.setText(RhDeviceCheck.primaryLabel(d,check));checkNotice.setText(RhDeviceCheck.hint(d,check));checkNotice.setTextColor(RhDeviceCheck.failed(check)?failure:muted);checkNotice.setVisibility(checkNotice.length()==0?GONE:VISIBLE);RhProfile p=RhProfile.load(activity,shared,d.uuid);
        String[] data={p.width+" × "+p.height,p.fps+" FPS",p.bitrateAuto?"自动（连接时）":p.bitrateMbps+" Mbps",address(shared,d),RhDeviceCheck.label(d,check),p.codec,p.audioEnabled?"开启":"关闭",p.displayMode.equals("virtual")?"虚拟屏":"自动"};
        for(int i=0;i<values.length;i++)values[i].setText(data[i]);values[4].setTextColor(pairingColor(d,check));
        if(selectionChanged){
            // ScrollView may reveal the previous focused child during its next layout.
            // Complete this selection reset there, once, rather than on later refreshes.
            resetDetailAfterLayout=true;detail.scrollTo(0,0);detail.requestLayout();
        }
    }
    private int pairingColor(ComputerDetails target,JSONObject check){return RhDeviceCheck.pairingFailed(check)?failure:RhDeviceCheck.paired(target,check)&&RhDeviceCheck.online(target,check)?green:muted;}
    private String address(JSONObject shared,ComputerDetails d){try{return RhNetwork.metadata(shared,d).optString("address");}catch(Exception ignored){return d.activeAddress!=null?d.activeAddress.address:d.manualAddress!=null?d.manualAddress.address:d.localAddress!=null?d.localAddress.address:"";}}
    private void renderList(JSONObject shared){
        String needle=query.trim().toLowerCase(Locale.ROOT);List<String> visible=new ArrayList<>();int online=0;
        for(ComputerDetails d:devices){JSONObject check=RhDeviceCheck.snapshot(activity,shared,d);boolean reachable=RhDeviceCheck.online(d,check);if(reachable)online++;String name=RhShared.displayName(shared,d.uuid,d.name),address=address(shared,d);
            if(!needle.isEmpty()&&!(name+"\n"+d.name+"\n"+address+"\n"+RhNetwork.savedSummary(shared,d)).toLowerCase(Locale.ROOT).contains(needle))continue;
            visible.add(d.uuid);RhBoundAction.Row cell=rows.get(d.uuid);
            if(cell==null){cell=new RhBoundAction.Row(activity,(source,id,args)->fromView(source,()->actions.send(id,args)));cell.setGravity(Gravity.CENTER_VERTICAL);cell.setPadding(dp(12),dp(12),dp(12),dp(12));cell.setMinimumHeight(dp(92));ImageView grip=new ImageView(activity);grip.setImageResource(R.drawable.rh_grip);grip.setColorFilter(muted);grip.setContentDescription("拖动设备 "+name+" 排序");grip.setPadding(dp(8),dp(12),dp(8),dp(12));cell.addView(grip,new LayoutParams(dp(36),dp(48)));final View handle=grip;RhReorder.bind(grip,d.uuid,()->{List<RhReorder.Item> items=new ArrayList<>();for(int i=0;i<list.getChildCount();i++){View row=list.getChildAt(i);for(Map.Entry<String,RhBoundAction.Row> known:rows.entrySet())if(known.getValue()==row)items.add(new RhReorder.Item(known.getKey(),row));}return items;},accent,(target,after)->fromView(listPane,()->send("device.move","uuid",d.uuid,"targetUuid",target,"placement",after?"after":"before")),direction->fromView(handle,()->send("device.move","uuid",d.uuid,"direction",direction)));ImageView icon=new ImageView(activity);icon.setImageResource(R.drawable.rh_monitor);icon.setColorFilter(accent);icon.setPadding(dp(7),dp(7),dp(7),dp(7));icon.setBackground(background(page,0,8));LayoutParams iconParams=new LayoutParams(dp(36),dp(36));iconParams.setMarginEnd(dp(12));cell.addView(icon,iconParams);LinearLayout names=column();names.setTag("device-labels");for(int i=0;i<3;i++){TextView text=text("",i==0?14:12,i==0?ink:muted,i==0);text.setSingleLine(true);text.setEllipsize(TextUtils.TruncateAt.END);text.setPadding(0,dp(2),0,dp(2));names.addView(text);}cell.addView(names,new LayoutParams(0,-2,1));rows.put(d.uuid,cell);}
            cell.bind(selectionAction,RhActions.obj("uuid",d.uuid));
            boolean chosen=d.uuid.equals(selected),selectionChanged=cell.isSelected()!=chosen;cell.setSelected(chosen);cell.setFocusable(!navigationBlocked);cell.setEnabled(!navigationBlocked);cell.getChildAt(0).setEnabled(!navigationBlocked);if(cell.getBackground()==null||selectionChanged)RhUiStyle.focusBackground(cell,background(chosen?soft:surface,0,8),background(chosen?soft:surface,0,8),background(chosen?soft:surface,accent,8));cell.setContentDescription("选择设备 "+name);LinearLayout names=(LinearLayout)cell.getChildAt(2);cell.getChildAt(0).setContentDescription("拖动设备 "+name+" 排序");
            ((TextView)names.getChildAt(0)).setText(name);((TextView)names.getChildAt(1)).setText(address);TextView status=(TextView)names.getChildAt(2);
            status.setText(check.has("outcome")||check.optBoolean("stale")?RhDeviceCheck.label(d,check):d.state==ComputerDetails.State.UNKNOWN?"待检查":!reachable?"离线":RhNetwork.pairingStatus(d).equals("unknown")?"● 配对未确认":"● "+RhNetwork.pairingLabel(d));status.setTextColor(pairingColor(d,check));
        }
        List<View> ordered=new ArrayList<>();for(String uuid:visible)ordered.add(rows.get(uuid));
        if(ordered.isEmpty()){boolean firstUse=devices.isEmpty();listEmptyText.setText(firstUse?"尚未添加设备":"没有匹配的设备");String actionLabel=firstUse?"添加电脑":"清除搜索";if(!actionLabel.contentEquals(listEmptyAction.getText()))styleButton(listEmptyAction,actionLabel,firstUse);listEmptyAction.bind(firstUse?"device.add.open":"devices.filter.clear",firstUse?RhActions.obj():RhActions.obj("expectedQuery",query));listEmptyAction.setVisibility(firstUse&&wide&&!listOnly?GONE:VISIBLE);ordered.add(listEmpty);}
        // Status changes and newly discovered devices must not detach every existing row.
        // Only reconcile actual list changes, and retain a still-visible keyboard target.
        boolean same=list.getChildCount()==ordered.size();for(int i=0;same&&i<ordered.size();i++)same=list.getChildAt(i)==ordered.get(i);
        if(!same){View focused=list.findFocus(),focusedRow=focused;Set<View> retained=new HashSet<>(ordered);
            while(focusedRow!=null&&focusedRow.getParent()!=list)focusedRow=focusedRow.getParent() instanceof View?(View)focusedRow.getParent():null;
            int oldFocusIndex=list.indexOfChild(focusedRow);
            for(int i=list.getChildCount()-1;i>=0;i--)if(!retained.contains(list.getChildAt(i)))list.removeViewAt(i);
            for(int i=0;i<ordered.size();i++){
                View child=ordered.get(i);if(list.getChildAt(i)==child)continue;
                if(child==focusedRow){
                    // Move its siblings around it; detaching the focused row queues a stale reveal.
                    while(list.getChildAt(i)!=child){View displaced=list.getChildAt(i);list.removeViewAt(i);list.addView(displaced);}
                }else{
                    if(child.getParent()==list)list.removeView(child);LayoutParams p=new LayoutParams(-1,child instanceof TextView?dp(96):-2);if(!(child instanceof TextView))p.bottomMargin=dp(6);list.addView(child,i,p);
                }
            }
            if(focused!=null&&!focused.hasFocus()&&RhViewOrigin.visibleWithin(this,focused))focused.requestFocusFromTouch();
            if(focused!=null&&focused.hasFocus()&&RhViewOrigin.visibleWithin(this,focused)&&oldFocusIndex!=list.indexOfChild(focusedRow))rowToReveal=focused;
        }
        rows.keySet().retainAll(new HashSet<>(Arrays.asList(devices.stream().map(d->d.uuid).toArray(String[]::new))));
        count.setText(devices.size()+" 台设备 · "+online+" 台在线");
    }

    public JSONObject layoutState(){return RhActions.obj("mode",listOnly?"settings-split":wide?"split":detailOpen?"detail":"list","selected",selected,"query",query,"width",getWidth(),"height",getHeight());}
}
