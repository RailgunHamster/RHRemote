package com.limelight.rhremote;

import android.app.Activity;
import android.graphics.Color;
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
    private final int surface,page,ink,muted,line,accent,soft,green;
    private final LinearLayout listPane,list,detailBody,detailHeader;
    private final ScrollView detail;
    private final EditText search;
    private final TextView count,title,subtitle,availability;
    private final Button back,settings,connect,up,down;
    private final Map<String,LinearLayout> rows=new LinkedHashMap<>();
    private final TextView[] values=new TextView[8];
    private List<ComputerDetails> devices=Collections.emptyList();
    private String selected="",query="";
    private boolean wide,detailOpen,updatingSearch;
    private int widthBucket=-1;

    public RhDeviceBrowser(Activity activity,Actions actions) {
        super(activity);this.activity=activity;this.actions=actions;
        boolean dark=RhShared.dark(activity);
        surface=Color.parseColor(dark?"#1b2232":"#ffffff");page=Color.parseColor(dark?"#151b29":"#f6f7fa");
        ink=Color.parseColor(dark?"#e1e8f6":"#34415b");muted=Color.parseColor(dark?"#a4b1c8":"#69778e");
        line=Color.parseColor(dark?"#303c53":"#e5eaf4");accent=Color.parseColor(dark?"#6f8af1":"#5274e8");
        soft=Color.parseColor(dark?"#253452":"#edf2ff");green=Color.parseColor(dark?"#8bc7b1":"#369c81");
        setOrientation(HORIZONTAL);setBackgroundColor(surface);

        listPane=column();listPane.setPadding(dp(16),dp(16),dp(16),dp(16));
        LinearLayout listHeading=row();TextView heading=text("远程设备",20,ink,true);
        listHeading.addView(heading,new LayoutParams(0,dp(48),1));
        listHeading.addView(button("刷新",false,()->send("devices.refresh")));
        listPane.addView(listHeading);space(listPane,12);
        search=new EditText(activity);search.setSingleLine(true);search.setTextSize(14);search.setTextColor(ink);search.setHintTextColor(muted);
        search.setHint("搜索名称或地址");search.setContentDescription("搜索名称或地址");search.setPadding(dp(12),0,dp(12),0);
        search.setBackground(background(page,line,8));search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(!updatingSearch)send("devices.filter","query",s.toString());}public void afterTextChanged(Editable value){}});
        listPane.addView(search,new LayoutParams(-1,dp(48)));space(listPane,12);
        ScrollView listScroll=new ScrollView(activity);listScroll.setFillViewport(false);
        list=column();listScroll.addView(list);listPane.addView(listScroll,new LayoutParams(-1,0,1));
        divider(listPane);count=text("",12,muted,false);count.setPadding(0,dp(14),0,0);listPane.addView(count);
        addView(listPane,new LayoutParams(dp(272),-1));
        View separator=new View(activity);separator.setBackgroundColor(line);addView(separator,new LayoutParams(dp(1),-1));separator.setTag("browser-divider");

        detail=new ScrollView(activity);detail.setFillViewport(true);
        LinearLayout detailContent=column();detail.addView(detailContent);
        detailHeader=column();detailHeader.setPadding(dp(24),dp(20),dp(24),dp(20));detailHeader.setBackgroundColor(page);
        LinearLayout titleRow=row();title=text("远程设备",25,ink,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title,new LayoutParams(0,dp(48),1));availability=text("",12,green,true);availability.setGravity(Gravity.CENTER);
        availability.setBackground(background(surface,0,14));titleRow.addView(availability,new LayoutParams(dp(64),dp(28)));
        detailHeader.addView(titleRow);subtitle=text("",13,muted,false);subtitle.setSingleLine(true);subtitle.setEllipsize(TextUtils.TruncateAt.END);detailHeader.addView(subtitle);
        detailContent.addView(detailHeader);
        RhActionRow tabs=new RhActionRow(activity);tabs.setPadding(dp(24),dp(12),dp(24),dp(12));
        back=button("返回设备",false,()->send("devices.open"));tabs.addView(back);
        TextView overview=text("概览",14,accent,true);overview.setGravity(Gravity.CENTER);tabs.addView(overview,new LayoutParams(dp(56),dp(48)));
        settings=button("连接设置",false,()->send("device.settings.open","uuid",selected));tabs.addView(settings);detailContent.addView(tabs);divider(detailContent);

        detailBody=column();detailBody.setPadding(dp(24),dp(24),dp(24),dp(24));detailContent.addView(detailBody);
        LinearLayout hero=row();hero.setPadding(dp(18),dp(18),dp(18),dp(18));hero.setBackground(background(surface,line,10));
        ImageView monitor=new ImageView(activity);monitor.setImageResource(R.drawable.rh_monitor);monitor.setColorFilter(accent);monitor.setPadding(dp(12),dp(12),dp(12),dp(12));monitor.setBackground(background(page,0,8));
        LayoutParams imageParams=new LayoutParams(dp(72),dp(72));imageParams.setMarginEnd(dp(20));hero.addView(monitor,imageParams);
        LinearLayout heroActions=column();heroActions.addView(text("桌面连接",17,ink,true));space(heroActions,12);
        connect=button("连接",true,()->{ComputerDetails d=chosen();if(d!=null)send(RhNetwork.connectInsteadOfPair(d)?"device.connect":"device.pair","uuid",d.uuid);});
        connect.setMinWidth(dp(112));connect.setMinimumWidth(dp(112));heroActions.addView(connect,new LayoutParams(-2,dp(48)));hero.addView(heroActions,new LayoutParams(0,-2,1));
        detailBody.addView(hero);space(detailBody,16);

        LinearLayout quality=row();metric(quality,"分辨率",0,1.5f);metric(quality,"帧率",1,1);metric(quality,"码率",2,1);detailBody.addView(quality);
        space(detailBody,16);divider(detailBody);section("设备信息");
        LinearLayout host=row();metric(host,"连接地址",3,1.5f);metric(host,"配对状态",4,1);detailBody.addView(host);
        section("当前客户端配置");LinearLayout profile=row();metric(profile,"视频编码",5,1);metric(profile,"声音",6,1);metric(profile,"屏幕",7,1);detailBody.addView(profile);
        space(detailBody,16);divider(detailBody);space(detailBody,16);detailBody.addView(text("设备顺序",12,muted,false));space(detailBody,12);
        RhActionRow ordering=new RhActionRow(activity);up=button("上移",false,()->send("device.move","uuid",selected,"direction","up"));down=button("下移",false,()->send("device.move","uuid",selected,"direction","down"));ordering.addView(up);ordering.addView(down);detailBody.addView(ordering);
        addView(detail,new LayoutParams(0,-1,1));
        applyMode();
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",0));return v;}
    private GradientDrawable background(int fill,int border,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    private Button button(String label,boolean primary,Runnable action){Button b=new Button(activity);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setStateListAnimator(null);b.setElevation(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setTextColor(primary?Color.WHITE:ink);b.setBackground(background(primary?accent:surface,primary?0:line,8));b.setOnClickListener(v->action.run());b.setLayoutParams(new LayoutParams(-2,dp(48)));return b;}
    private void send(String id,Object... args){actions.send(id,RhActions.obj(args));}
    private void space(LinearLayout parent,int height){parent.addView(new View(activity),new LayoutParams(-1,dp(height)));}
    private void divider(LinearLayout parent){View v=new View(activity);v.setBackgroundColor(line);parent.addView(v,new LayoutParams(-1,dp(1)));}
    private void section(String label){space(detailBody,16);detailBody.addView(text(label,14,ink,true));space(detailBody,14);}
    private void metric(LinearLayout parent,String label,int index,float weight){LinearLayout cell=column();cell.setPadding(0,0,dp(8),0);cell.addView(text(label,12,muted,false));space(cell,8);TextView value=text("—",14,ink,false);value.setSingleLine(true);value.setEllipsize(TextUtils.TruncateAt.END);values[index]=value;cell.addView(value);parent.addView(cell,new LayoutParams(0,-2,weight));}
    private ComputerDetails chosen(){for(ComputerDetails d:devices)if(d.uuid.equals(selected))return d;return null;}

    public void showDetail(boolean value){detailOpen=value;applyMode();}
    public boolean isDetailOpen(){return !wide&&detailOpen;}
    private void applyMode(){listPane.setVisibility(wide||!detailOpen?VISIBLE:GONE);detail.setVisibility(wide||detailOpen?VISIBLE:GONE);findViewWithTag("browser-divider").setVisibility(wide?VISIBLE:GONE);back.setVisibility(wide?GONE:VISIBLE);LayoutParams params=(LayoutParams)listPane.getLayoutParams();params.width=wide?dp(272):0;params.weight=wide?0:1;listPane.setLayoutParams(params);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){int bucket=MeasureSpec.getSize(widthSpec)>=dp(700)?1:0;if(bucket!=widthBucket){widthBucket=bucket;wide=bucket==1;applyMode();}super.onMeasure(widthSpec,heightSpec);}

    public void update(List<ComputerDetails> devices,String selected,String query){
        this.devices=new ArrayList<>(devices);this.selected=selected;this.query=query;
        if(!search.getText().toString().equals(query)){updatingSearch=true;search.setText(query);search.setSelection(search.length());updatingSearch=false;}
        renderList();ComputerDetails d=chosen();boolean available=d!=null;settings.setEnabled(available);connect.setEnabled(available);
        detailBody.setVisibility(available?VISIBLE:GONE);settings.setVisibility(available?VISIBLE:GONE);
        if(d==null){title.setText("远程设备");subtitle.setText("尚未添加设备");availability.setVisibility(GONE);up.setEnabled(false);down.setEnabled(false);for(TextView value:values)value.setText("—");return;}
        availability.setVisibility(VISIBLE);boolean online=d.state==ComputerDetails.State.ONLINE,paired=RhNetwork.connectInsteadOfPair(d);
        String name=RhShared.displayName(activity,d.uuid,d.name);title.setText(name);subtitle.setText(name.equals(d.name)?address(d):d.name);
        availability.setText(d.state==ComputerDetails.State.UNKNOWN?"检查中":online?"在线":"离线");availability.setTextColor(online?green:muted);
        connect.setText(paired?"连接":"配对");RhProfile p=RhProfile.load(activity,d.uuid);
        String[] data={p.width+" × "+p.height,p.fps+" FPS",p.bitrateMbps+" Mbps",address(d),paired?"已配对":"未配对",p.codec,p.audioEnabled?"开启":"关闭",p.displayMode.equals("virtual")?"虚拟屏":"自动"};
        for(int i=0;i<values.length;i++)values[i].setText(data[i]);int index=devices.indexOf(d);up.setEnabled(index>0);down.setEnabled(index>=0&&index<devices.size()-1);
    }
    private String address(ComputerDetails d){try{return RhNetwork.metadata(activity,d).optString("address");}catch(Exception ignored){return d.activeAddress!=null?d.activeAddress.address:d.manualAddress!=null?d.manualAddress.address:d.localAddress!=null?d.localAddress.address:"";}}
    private void renderList(){
        String needle=query.trim().toLowerCase(Locale.ROOT);List<String> visible=new ArrayList<>();int online=0;
        for(ComputerDetails d:devices){if(d.state==ComputerDetails.State.ONLINE)online++;String name=RhShared.displayName(activity,d.uuid,d.name),address=address(d);
            if(!needle.isEmpty()&&!(name+"\n"+d.name+"\n"+address).toLowerCase(Locale.ROOT).contains(needle))continue;
            visible.add(d.uuid);LinearLayout cell=rows.get(d.uuid);
            if(cell==null){cell=row();cell.setPadding(dp(12),dp(12),dp(12),dp(12));cell.setMinimumHeight(dp(92));ImageView icon=new ImageView(activity);icon.setImageResource(R.drawable.rh_monitor);icon.setColorFilter(accent);icon.setPadding(dp(7),dp(7),dp(7),dp(7));icon.setBackground(background(page,0,8));LayoutParams iconParams=new LayoutParams(dp(36),dp(36));iconParams.setMarginEnd(dp(12));cell.addView(icon,iconParams);LinearLayout names=column();for(int i=0;i<3;i++){TextView text=text("",i==0?14:12,i==0?ink:muted,i==0);text.setSingleLine(true);text.setEllipsize(TextUtils.TruncateAt.END);text.setPadding(0,dp(2),0,dp(2));names.addView(text);}cell.addView(names,new LayoutParams(0,-2,1));final String uuid=d.uuid;cell.setOnClickListener(v->send("device.select","uuid",uuid));rows.put(d.uuid,cell);}
            cell.setBackground(background(d.uuid.equals(selected)?soft:surface,0,8));cell.setContentDescription("选择设备 "+name);LinearLayout names=(LinearLayout)cell.getChildAt(1);
            ((TextView)names.getChildAt(0)).setText(name);((TextView)names.getChildAt(1)).setText(address);TextView status=(TextView)names.getChildAt(2);
            status.setText(d.state==ComputerDetails.State.UNKNOWN?"正在检查":d.state!=ComputerDetails.State.ONLINE?"离线":RhNetwork.connectInsteadOfPair(d)?"● 已配对":"● 待配对");status.setTextColor(d.state==ComputerDetails.State.ONLINE?green:muted);
        }
        boolean same=!visible.isEmpty()&&list.getChildCount()==visible.size();for(int i=0;same&&i<visible.size();i++)same=list.getChildAt(i)==rows.get(visible.get(i));
        if(!same){list.removeAllViews();for(String uuid:visible){LayoutParams p=new LayoutParams(-1,-2);p.bottomMargin=dp(6);list.addView(rows.get(uuid),p);}if(visible.isEmpty()){TextView empty=text(query.isEmpty()?"尚未添加设备":"没有匹配的设备",13,muted,false);empty.setGravity(Gravity.CENTER);list.addView(empty,new LayoutParams(-1,dp(96)));}}
        rows.keySet().retainAll(new HashSet<>(Arrays.asList(devices.stream().map(d->d.uuid).toArray(String[]::new))));
        count.setText(devices.size()+" 台设备 · "+online+" 台在线");
    }

    public JSONObject layoutState(){return RhActions.obj("mode",wide?"split":detailOpen?"detail":"list","selected",selected,"query",query,"width",getWidth(),"height",getHeight());}
}
