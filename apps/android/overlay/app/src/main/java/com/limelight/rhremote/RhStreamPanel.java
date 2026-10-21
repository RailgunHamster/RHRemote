package com.limelight.rhremote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import org.json.*;
import java.util.*;

/** An adaptive native View panel. All interactive controls emit catalog actions. */
public final class RhStreamPanel {
    public interface Actions { void send(String action, JSONObject arguments); }
    private final Context context;
    private final FrameLayout root;
    private final Actions actions;
    private final Button handle;
    private final LinearLayout panel, input, screens, sound;
    private final ScrollView scroll;
    private final Map<String, Button> tabs=new LinkedHashMap<>();
    private final TextView displayStatus, volumeLabel;
    private final SeekBar volume;
    private final LinearLayout monitorList;
    private final Button mode, pan, audio, refresh;
    private final GridLayout inputGrid, screenGrid;
    private final View.OnLayoutChangeListener layoutListener;
    private String tab="input";
    private int columns=2, maxBodyHeight;
    private boolean disposed;

    private int dp(int value) { return Math.round(context.getResources().getDisplayMetrics().density*value); }
    private GradientDrawable surface(String fill, String border, int radius) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(Color.parseColor(fill));shape.setCornerRadius(dp(radius));
        if(border!=null)shape.setStroke(dp(1),Color.parseColor(border));
        return shape;
    }
    public RhStreamPanel(Context context, FrameLayout root, Actions actions) {
        this.context=context;this.root=root;this.actions=actions;
        handle=button("控制", "stream.controls.toggle", RhActions.obj());
        handle.setTextColor(Color.WHITE);handle.setBackground(surface("#5274e8",null,18));
        handle.setElevation(dp(4));
        FrameLayout.LayoutParams handleParams=new FrameLayout.LayoutParams(dp(88),dp(40),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        handleParams.topMargin=dp(12);root.addView(handle,handleParams);

        panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setClickable(true);
        panel.setPadding(dp(14),dp(12),dp(14),dp(12));
        panel.setBackground(surface("#f8faff","#e5eaf4",20));panel.setElevation(dp(10));
        LinearLayout navigation=new LinearLayout(context);navigation.setPadding(0,0,0,dp(10));
        String[] ids={"input","displays","audio"}, labels={"操作","屏幕","声音"};
        for(int i=0;i<ids.length;i++){
            Button b=button(labels[i],"stream.controls.tab",RhActions.obj("tab",ids[i]));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1);if(i>0)lp.leftMargin=dp(8);
            navigation.addView(b,lp);tabs.put(ids[i],b);
        }
        panel.addView(navigation);
        scroll=new ScrollView(context) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(Math.max(dp(48),maxBodyHeight),MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setPadding(0,0,dp(2),0);
        LinearLayout pages=new LinearLayout(context);pages.setOrientation(LinearLayout.VERTICAL);
        input=page();screens=page();sound=page();pages.addView(input);pages.addView(screens);pages.addView(sound);
        scroll.addView(pages,new ScrollView.LayoutParams(-1,-2));panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));

        inputGrid=new GridLayout(context);inputGrid.setUseDefaultMargins(false);input.addView(inputGrid);
        mode=gridButton(inputGrid,"直接点击","stream.mode.toggle",RhActions.obj());
        gridButton(inputGrid,"键盘","stream.keyboard.toggle",RhActions.obj());
        gridButton(inputGrid,"快捷键","stream.shortcuts.open",RhActions.obj());
        gridButton(inputGrid,"Ctrl+Alt+Delete","stream.shortcut",RhActions.obj("shortcut","ctrl-alt-delete"));
        pan=gridButton(inputGrid,"移动画面","stream.pan.toggle",RhActions.obj());
        gridButton(inputGrid,"还原缩放","stream.zoom.reset",RhActions.obj());
        LinearLayout bottom=new LinearLayout(context);bottom.setPadding(0,dp(8),0,0);
        Button stats=button("统计","stream.stats.toggle",RhActions.obj());
        Button disconnect=button("断开","stream.disconnect",RhActions.obj());disconnect.setTextColor(Color.parseColor("#b14350"));
        bottom.addView(stats,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams end=new LinearLayout.LayoutParams(0,dp(48),1);end.leftMargin=dp(8);bottom.addView(disconnect,end);input.addView(bottom);

        displayStatus=text("正在读取屏幕…",13);displayStatus.setPadding(dp(4),dp(2),dp(4),dp(12));screens.addView(displayStatus);
        screenGrid=new GridLayout(context);screens.addView(screenGrid);
        gridButton(screenGrid,"自动选屏","stream.display.select",RhActions.obj("displayId","auto"));
        gridButton(screenGrid,"虚拟屏","stream.display.select",RhActions.obj("displayId","virtual"));
        gridButton(screenGrid,"窗口移到左屏","stream.window.move",RhActions.obj("direction","left"));
        gridButton(screenGrid,"窗口移到右屏","stream.window.move",RhActions.obj("direction","right"));
        TextView label=text("显示器",12);label.setTextColor(Color.parseColor("#69778e"));label.setPadding(dp(4),dp(14),0,dp(6));screens.addView(label);
        monitorList=new LinearLayout(context);monitorList.setOrientation(LinearLayout.VERTICAL);screens.addView(monitorList);
        refresh=button("刷新屏幕","stream.displays.refresh",RhActions.obj());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(48));rp.topMargin=dp(8);screens.addView(refresh,rp);

        audio=button("声音：关","stream.audio.toggle",RhActions.obj());sound.addView(audio,new LinearLayout.LayoutParams(-1,dp(48)));
        volumeLabel=text("音量 100%",14);volumeLabel.setPadding(dp(4),dp(16),dp(4),dp(4));sound.addView(volumeLabel);
        volume=new SeekBar(context);volume.setMax(100);volume.setContentDescription("串流音量");sound.addView(volume,new LinearLayout.LayoutParams(-1,dp(52)));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(fromUser)volumeLabel.setText("音量 "+value+"%");}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){actions.send("stream.audio.volume",RhActions.obj("volume",bar.getProgress()));}
        });
        FrameLayout.LayoutParams panelParams=new FrameLayout.LayoutParams(dp(560),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        panelParams.topMargin=dp(60);root.addView(panel,panelParams);panel.setVisibility(View.GONE);
        layoutListener=(v,l,t,r,b,ol,ot,or,ob)->updateBounds();root.addOnLayoutChangeListener(layoutListener);
        setTab("input");root.post(this::updateBounds);
    }
    private TextView text(String label,int size){TextView t=new TextView(context);t.setText(label);t.setTextSize(size);t.setTextColor(Color.parseColor("#34415b"));return t;}
    private LinearLayout page(){LinearLayout p=new LinearLayout(context);p.setOrientation(LinearLayout.VERTICAL);return p;}
    private Button button(String title,String action,JSONObject arguments){
        Button b=new Button(context);b.setText(title);b.setAllCaps(false);b.setTextSize(13);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setMaxLines(2);b.setTextColor(Color.parseColor("#34415b"));b.setPadding(dp(8),dp(2),dp(8),dp(2));b.setBackground(surface("#ffffff","#e5eaf4",12));
        b.setTag(action);b.setOnClickListener(v->actions.send(action,arguments));return b;
    }
    private Button gridButton(GridLayout grid,String title,String action,JSONObject args){Button b=button(title,action,args);grid.addView(b);return b;}
    private void gridColumns(GridLayout grid,int count){
        // Remove old explicit specs before changing column count (Android validates them).
        List<View> children=new ArrayList<>();for(int i=0;i<grid.getChildCount();i++)children.add(grid.getChildAt(i));grid.removeAllViews();grid.setColumnCount(count);
        for(int i=0;i<children.size();i++){GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec(i/count),GridLayout.spec(i%count,1f));p.width=0;p.height=dp(52);p.setMargins(i%count==0?0:dp(8),i<count?0:dp(8),0,0);grid.addView(children.get(i),p);}
    }
    private void updateBounds(){
        if(disposed||root.getWidth()==0||root.getHeight()==0)return;
        int top=0,bottom=0,left=0,right=0;WindowInsets insets=root.getRootWindowInsets();
        if(insets!=null){top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();left=insets.getSystemWindowInsetLeft();right=insets.getSystemWindowInsetRight();
            if(android.os.Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){top=Math.max(top,insets.getDisplayCutout().getSafeInsetTop());left=Math.max(left,insets.getDisplayCutout().getSafeInsetLeft());right=Math.max(right,insets.getDisplayCutout().getSafeInsetRight());}}
        int topMargin=top+dp(8), width=Math.max(dp(160),Math.min(dp(560),root.getWidth()-left-right-dp(24)));
        FrameLayout.LayoutParams hp=(FrameLayout.LayoutParams)handle.getLayoutParams();
        if(hp.topMargin!=topMargin){hp.topMargin=topMargin;handle.setLayoutParams(hp);}
        FrameLayout.LayoutParams pp=(FrameLayout.LayoutParams)panel.getLayoutParams();int panelTop=topMargin+dp(48);
        if(pp.width!=width||pp.topMargin!=panelTop||pp.leftMargin!=left||pp.rightMargin!=right){pp.width=width;pp.topMargin=panelTop;pp.leftMargin=left;pp.rightMargin=right;panel.setLayoutParams(pp);}
        int nextHeight=Math.max(dp(48),Math.min(dp(440),root.getHeight()-panelTop-bottom-dp(92)));
        if(maxBodyHeight!=nextHeight){maxBodyHeight=nextHeight;scroll.requestLayout();}
        int nextColumns=width>=dp(480)?3:2;
        if(inputGrid.getColumnCount()!=nextColumns){columns=nextColumns;gridColumns(inputGrid,columns);}
        if(screenGrid.getColumnCount()!=2)gridColumns(screenGrid,2);
    }
    public boolean isExpanded(){return panel.getVisibility()==View.VISIBLE;}
    public String tab(){return tab;}
    public void setExpanded(boolean expanded){panel.setVisibility(expanded?View.VISIBLE:View.GONE);handle.setText(expanded?"收起":"控制");updateBounds();}
    public void setTab(String value){
        if(!tabs.containsKey(value))throw new IllegalArgumentException("tab must be input, displays or audio");tab=value;
        input.setVisibility(value.equals("input")?View.VISIBLE:View.GONE);screens.setVisibility(value.equals("displays")?View.VISIBLE:View.GONE);sound.setVisibility(value.equals("audio")?View.VISIBLE:View.GONE);
        for(Map.Entry<String,Button> entry:tabs.entrySet()){boolean selected=entry.getKey().equals(value);entry.getValue().setSelected(selected);entry.getValue().setTextColor(Color.parseColor(selected?"#4666d6":"#69778e"));entry.getValue().setBackground(surface(selected?"#edf2ff":"#f8faff",selected?"#c7d4f6":null,12));}
        scroll.scrollTo(0,0);
    }
    public void update(boolean trackpad,boolean panning,boolean audioEnabled,int level,JSONObject display,boolean busy,String failure){
        mode.setText(trackpad?"触控板":"直接点击");pan.setText(panning?"移动中":"移动画面");audio.setText(audioEnabled?"声音：开":"声音：关");
        volume.setProgress(level);volumeLabel.setText("音量 "+level+"%");refresh.setEnabled(!busy);refresh.setText(busy?"正在读取…":"刷新屏幕");
        for(int i=0;i<screenGrid.getChildCount();i++)screenGrid.getChildAt(i).setEnabled(!busy);
        JSONObject selected=display.optJSONObject("selected");String error=failure.isEmpty()?display.optString("error"):failure;
        displayStatus.setText(busy?"正在读取屏幕…":!error.isEmpty()?error:selected==null?"选择要查看的屏幕":monitorName(selected)+" · "+selected.optInt("width")+"×"+selected.optInt("height"));
        displayStatus.setTextColor(Color.parseColor(error.isEmpty()?"#69778e":"#b14350"));monitorList.removeAllViews();
        JSONArray monitors=display.optJSONArray("displays");int count=monitors==null?0:monitors.length();
        for(int i=0;i<count;i++){JSONObject monitor=monitors.optJSONObject(i);if(monitor==null)continue;
            String id=monitor.optString("id");boolean available=monitor.optBoolean("available"),chosen=selected!=null&&id.equals(selected.optString("id"));
            String title=monitorName(monitor)+" · "+monitor.optInt("width")+"×"+monitor.optInt("height")+(available?chosen?"  ✓":"":"  暂不可用");
            Button b=button(title,"stream.display.select",RhActions.obj("displayId",id));b.setEnabled(available&&!busy);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
            if(chosen)b.setBackground(surface("#edf2ff","#c7d4f6",12));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.topMargin=i==0?0:dp(8);monitorList.addView(b,lp);
        }
        if(count==0){TextView empty=text(error.isEmpty()?"暂无屏幕信息，请刷新":"屏幕列表未加载",13);empty.setPadding(dp(4),dp(8),dp(4),dp(8));monitorList.addView(empty);}
    }
    private String monitorName(JSONObject monitor){String name=monitor.optString("name");return monitor.optBoolean("virtual")?"虚拟屏":name.isEmpty()?"物理屏幕":name;}
    public JSONObject layoutState(){Rect h=new Rect(),p=new Rect();handle.getHitRect(h);panel.getHitRect(p);return RhActions.obj("expanded",isExpanded(),"tab",tab,"columns",columns,"handle",RhActions.obj("x",h.left,"y",h.top,"width",h.width(),"height",h.height()),"panel",RhActions.obj("x",p.left,"y",p.top,"width",p.width(),"height",p.height()),"scrollable",scroll.canScrollVertically(1)||scroll.canScrollVertically(-1));}
    public void dispose(){disposed=true;root.removeOnLayoutChangeListener(layoutListener);root.removeView(panel);root.removeView(handle);}
}
