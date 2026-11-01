package com.limelight.rhremote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.FocusFinder;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.limelight.R;
import org.json.*;
import java.util.*;

/** An adaptive native View panel. All interactive controls emit catalog actions. */
public final class RhStreamPanel {
    public interface Actions { void send(String action, JSONObject arguments); }
    private final Context context;
    private final FrameLayout root;
    private final Actions actions;
    private final Button handle;
    private LinearLayout panel, header, headerActions, input, screens, sound;
    private ScrollView scroll;
    private final Map<String, Button> tabs=new LinkedHashMap<>();
    private final Map<Button,Boolean> monitorAvailability=new IdentityHashMap<>();
    private final Map<Button,String> monitorIds=new IdentityHashMap<>();
    private final Map<Long,View> localKeys=new HashMap<>();
    private TextView displayStatus, volumeLabel, sessionName, feedback, zoomLabel;
    private Button feedbackDetails, stats, disconnect;
    private final String surfaceColor,lineColor,inkColor,mutedColor,selectedColor,selectedLine;
    private final boolean dark;
    private final int failureColor;
    private String monitorFingerprint="";private boolean trackingVolume;
    private SeekBar volume;
    private LinearLayout monitorList;
    private Button mode, pan, audio, refresh;
    private Switch scrollModeSwitch,floatingKeyboardSwitch,clickScrollModeSwitch;
    private boolean windowCapture;
    private boolean bindingScrollMode,bindingFloatingKeyboard;
    private GridLayout inputGrid, screenGrid, viewGrid;
    private final View.OnLayoutChangeListener layoutListener;
    private String tab="input";
    private int columns=2, maxBodyHeight;
    private boolean disposed;
    private View returnFocus;
    private String pendingMonitorFocus="";
    private int zoomPercent=100,shownZoomPercent=100;
    private Insets currentInsets=Insets.NONE;
    private String networkWarning="",networkWarningSummary="";
    private boolean warningDetails;
    private Runnable pendingDisplay,pendingControls;
    private String deviceName="远程设备",pendingFeedbackGroup="",pendingFeedbackMessage="",pendingFeedbackFailure="";
    private boolean scrollMode,floatingKeyboard,pendingFeedback,creatingContents,clickScrollMode,bindingClickScrollMode,shownTrackpad;

    private int dp(int value) { return Math.round(context.getResources().getDisplayMetrics().density*value); }
    private GradientDrawable surface(String fill, String border, int radius) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(Color.parseColor(fill));shape.setCornerRadius(dp(radius));
        if(border!=null)shape.setStroke(dp(1),Color.parseColor(border));
        return shape;
    }
    public RhStreamPanel(Context context, FrameLayout root, Actions actions) {
        this.context=context;this.root=root;this.actions=actions;returnFocus=root.findFocus();
        dark=RhShared.dark(context);surfaceColor=dark?"#1b2232":"#ffffff";lineColor=dark?"#303c53":"#e5eaf4";inkColor=dark?"#e1e8f6":"#34415b";mutedColor=dark?"#a4b1c8":"#69778e";selectedColor=dark?"#253452":"#edf2ff";selectedLine=dark?"#6f8af1":"#c7d4f6";
        failureColor=RhUiStyle.failure(dark);
        handle=button("控制", "stream.controls.toggle", RhActions.obj());
        handle.setTextColor(Color.WHITE);handle.setBackground(surface("#5274e8",null,18));
        handle.setElevation(dp(4));
        icon(handle,R.drawable.rh_chevron_down);
        handle.getCompoundDrawablesRelative()[0].setTint(Color.WHITE);
        handle.setMaxLines(1);handle.setHorizontallyScrolling(false);
        FrameLayout.LayoutParams handleParams=new FrameLayout.LayoutParams(dp(88),dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        handleParams.topMargin=dp(12);root.addView(handle,handleParams);

        layoutListener=(v,l,t,r,b,ol,ot,or,ob)->updateBounds();root.addOnLayoutChangeListener(layoutListener);
        // Prepare controls during session initialization, so the first explicit
        // expansion reuses the same ready tree instead of inflating it on input.
        createContents();
        ViewCompat.setOnApplyWindowInsetsListener(handle,(view,insets)->{updateBounds(safeInsets(insets));return insets;});
        ViewCompat.setWindowInsetsAnimationCallback(handle,new androidx.core.view.WindowInsetsAnimationCompat.Callback(androidx.core.view.WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){
            @Override public WindowInsetsCompat onProgress(WindowInsetsCompat insets,java.util.List<androidx.core.view.WindowInsetsAnimationCompat> running){updateBounds(safeInsets(insets));return insets;}
        });
        ViewCompat.requestApplyInsets(handle);warningIndicator();root.post(this::updateBounds);
    }

    /** Build once during session initialization, without emitting model-binding actions. */
    private void createContents(){
        if(disposed||panel!=null)return;
        creatingContents=true;
        try(RhStatistics.Scope trace=new RhStatistics.Scope("session_controls_body_create")){
            buildContents();
            try(RhStatistics.Scope binding=new RhStatistics.Scope("session_controls_bind_initial")){
            setDeviceName(deviceName);setTab(tab);
            if(pendingDisplay!=null)pendingDisplay.run();
            if(pendingControls!=null)pendingControls.run();
            updateScrollMode(scrollMode);updateClickScrollMode(clickScrollMode);updateFloatingKeyboard(floatingKeyboard);
            if(pendingFeedback)updateFeedback(pendingFeedbackGroup,pendingFeedbackMessage,pendingFeedbackFailure);
            pendingDisplay=null;pendingControls=null;pendingFeedback=false;
            }
        }catch(RuntimeException|Error failure){
            // Discard a partial tree if initialization fails.
            if(panel!=null)root.removeView(panel);panel=null;tabs.clear();monitorAvailability.clear();monitorIds.clear();monitorFingerprint="";shownZoomPercent=100;trackingVolume=false;
            throw failure;
        }finally{creatingContents=false;}
    }

    private void buildContents(){
        try(RhStatistics.Scope trace=new RhStatistics.Scope("session_controls_chrome_create")){
        panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setClickable(true);panel.setFocusable(false);
        panel.setPadding(dp(14),dp(12),dp(14),dp(12));
        panel.setBackground(surface(surfaceColor,lineColor,16));panel.setElevation(dp(10));
        header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(0,0,0,dp(8));sessionName=text("远程设备",14);sessionName.setGravity(Gravity.CENTER_VERTICAL);sessionName.setSingleLine(true);sessionName.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(sessionName,new LinearLayout.LayoutParams(0,dp(48),1));headerActions=new LinearLayout(context);headerActions.setGravity(Gravity.CENTER_VERTICAL);header.addView(headerActions,new LinearLayout.LayoutParams(-2,-2));disconnect=button("断开","stream.disconnect",RhActions.obj());RhUiStyle.buttonText(disconnect,failureColor,Color.parseColor(mutedColor));headerActions.addView(disconnect,new LinearLayout.LayoutParams(-2,dp(48)));panel.addView(header);
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
        scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);scroll.setPadding(0,0,dp(6),0);
        LinearLayout pages=new LinearLayout(context);pages.setOrientation(LinearLayout.VERTICAL);
        input=page();screens=page();sound=page();pages.addView(input);pages.addView(screens);pages.addView(sound);
        scroll.addView(pages,new ScrollView.LayoutParams(-1,-2));panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        }
        try(RhStatistics.Scope trace=new RhStatistics.Scope("session_controls_input_create")){
        inputGrid=new GridLayout(context);inputGrid.setUseDefaultMargins(false);input.addView(inputGrid);
        mode=gridButton(inputGrid,"直接点击","stream.mode.toggle",RhActions.obj());icon(mode,R.drawable.rh_mouse);
        icon(gridButton(inputGrid,"键盘","stream.keyboard.toggle",RhActions.obj()),R.drawable.rh_keyboard);
        icon(gridButton(inputGrid,"快捷键","stream.shortcuts.open",RhActions.obj()),R.drawable.rh_shortcuts);
        floatingKeyboardSwitch=new Switch(context);floatingKeyboardSwitch.setText("悬浮键盘");floatingKeyboardSwitch.setContentDescription("悬浮键盘");floatingKeyboardSwitch.setTextSize(14);floatingKeyboardSwitch.setTextColor(Color.parseColor(inkColor));floatingKeyboardSwitch.setPadding(dp(4),0,dp(4),0);floatingKeyboardSwitch.setShowText(false);floatingKeyboardSwitch.setMinimumHeight(dp(48));floatingKeyboardSwitch.setTag("stream.keyboard.floating.set");
        styleSwitch(floatingKeyboardSwitch);
        floatingKeyboardSwitch.setOnCheckedChangeListener((view,checked)->{if(!bindingFloatingKeyboard&&ownsVisible(view))send("stream.keyboard.floating.set",RhActions.obj("enabled",checked));});LinearLayout.LayoutParams floatingParams=new LinearLayout.LayoutParams(-2,dp(48));floatingParams.leftMargin=dp(8);floatingParams.rightMargin=dp(8);headerActions.addView(floatingKeyboardSwitch,0,floatingParams);
        icon(gridButton(inputGrid,"Ctrl+Alt+Delete","stream.shortcut",RhActions.obj("shortcut","ctrl-alt-delete")),R.drawable.rh_lock);
        scrollModeSwitch=new Switch(context);scrollModeSwitch.setText("滚动模式");scrollModeSwitch.setTextColor(Color.parseColor(inkColor));scrollModeSwitch.setTextSize(14);scrollModeSwitch.setContentDescription("滚动模式");scrollModeSwitch.setShowText(false);scrollModeSwitch.setSwitchMinWidth(dp(44));scrollModeSwitch.setMinHeight(dp(48));scrollModeSwitch.setPadding(dp(4),0,dp(4),0);scrollModeSwitch.setDefaultFocusHighlightEnabled(false);scrollModeSwitch.setTag("stream.scroll.set");
        android.graphics.drawable.StateListDrawable scrollFocus=new android.graphics.drawable.StateListDrawable();scrollFocus.addState(new int[]{-android.R.attr.state_enabled},surface(surfaceColor,null,10));scrollFocus.addState(new int[]{android.R.attr.state_pressed},surface(selectedColor,"#5274e8",10));scrollFocus.addState(new int[]{android.R.attr.state_focused},surface(selectedColor,"#5274e8",10));scrollFocus.addState(new int[]{},surface(surfaceColor,null,10));scrollModeSwitch.setBackground(scrollFocus);
        styleSwitch(scrollModeSwitch);
        scrollModeSwitch.setOnCheckedChangeListener((view,checked)->{if(!bindingScrollMode&&ownsVisible(view))send("stream.scroll.set",RhActions.obj("enabled",checked));});LinearLayout.LayoutParams scrollModeLayout=new LinearLayout.LayoutParams(-1,dp(48));scrollModeLayout.topMargin=dp(8);input.addView(scrollModeSwitch,scrollModeLayout);TextView scrollHelp=text("单击中键 · 四向滚动",12);scrollHelp.setTextColor(Color.parseColor(mutedColor));scrollHelp.setPadding(dp(4),0,dp(4),0);input.addView(scrollHelp);
        clickScrollModeSwitch=new Switch(context);clickScrollModeSwitch.setText("点击与滚动");clickScrollModeSwitch.setContentDescription("点击与滚动");clickScrollModeSwitch.setTextColor(Color.parseColor(inkColor));clickScrollModeSwitch.setTextSize(14);clickScrollModeSwitch.setShowText(false);clickScrollModeSwitch.setSwitchMinWidth(dp(44));clickScrollModeSwitch.setMinHeight(dp(48));clickScrollModeSwitch.setPadding(dp(4),0,dp(4),0);clickScrollModeSwitch.setTag("stream.click-scroll.set");
        android.graphics.drawable.StateListDrawable clickFocus=new android.graphics.drawable.StateListDrawable();clickFocus.addState(new int[]{-android.R.attr.state_enabled},surface(surfaceColor,null,10));clickFocus.addState(new int[]{android.R.attr.state_pressed},surface(selectedColor,"#5274e8",10));clickFocus.addState(new int[]{android.R.attr.state_focused},surface(selectedColor,"#5274e8",10));clickFocus.addState(new int[]{},surface(surfaceColor,null,10));clickScrollModeSwitch.setBackground(clickFocus);styleSwitch(clickScrollModeSwitch);
        clickScrollModeSwitch.setOnCheckedChangeListener((view,checked)->{if(!bindingClickScrollMode&&ownsVisible(view))send("stream.click-scroll.set",RhActions.obj("enabled",checked));});LinearLayout.LayoutParams clickScrollLayout=new LinearLayout.LayoutParams(-1,dp(48));clickScrollLayout.topMargin=dp(8);input.addView(clickScrollModeSwitch,clickScrollLayout);TextView clickScrollHelp=text("轻点点击 · 单指滚动 · 双指拖动",12);clickScrollHelp.setTextColor(Color.parseColor(mutedColor));clickScrollHelp.setPadding(dp(4),0,dp(4),0);input.addView(clickScrollHelp);
        LinearLayout viewOptions=new LinearLayout(context);viewOptions.setGravity(Gravity.CENTER_VERTICAL);viewOptions.setPadding(dp(4),dp(12),0,0);
        viewOptions.addView(new View(context),new LinearLayout.LayoutParams(0,dp(44),1));
        stats=button("统计：关","stream.stats.toggle",RhActions.obj());icon(stats,R.drawable.rh_chart);viewOptions.addView(stats,new LinearLayout.LayoutParams(-2,dp(44)));input.addView(viewOptions);
        }
        try(RhStatistics.Scope trace=new RhStatistics.Scope("session_controls_displays_create")){
        displayStatus=text("正在读取屏幕…",13);displayStatus.setPadding(dp(4),dp(2),dp(4),dp(12));screens.addView(displayStatus);
        screenGrid=new GridLayout(context);screens.addView(screenGrid);
        icon(gridButton(screenGrid,"自动选屏","stream.display.select",RhActions.obj("displayId","auto")),R.drawable.rh_monitor);
        icon(gridButton(screenGrid,"虚拟屏","stream.display.select",RhActions.obj("displayId","virtual")),R.drawable.rh_monitor);
        icon(gridButton(screenGrid,"窗口移到左屏","stream.window.move",RhActions.obj("direction","left")),R.drawable.rh_move_left);
        icon(gridButton(screenGrid,"窗口移到右屏","stream.window.move",RhActions.obj("direction","right")),R.drawable.rh_move_right);
        TextView label=text("显示器",12);label.setTextColor(Color.parseColor(mutedColor));label.setPadding(dp(4),dp(14),0,dp(6));screens.addView(label);
        monitorList=new LinearLayout(context);monitorList.setOrientation(LinearLayout.VERTICAL);screens.addView(monitorList);
        refresh=button("刷新屏幕","stream.displays.refresh",RhActions.obj());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(48));rp.topMargin=dp(8);screens.addView(refresh,rp);

        zoomLabel=text("缩放 100%",13);zoomLabel.setGravity(Gravity.CENTER_VERTICAL);zoomLabel.setPadding(dp(4),dp(16),0,dp(8));screens.addView(zoomLabel);
        viewGrid=new GridLayout(context);viewGrid.setUseDefaultMargins(false);screens.addView(viewGrid);
        pan=gridButton(viewGrid,"移动画面","stream.pan.toggle",RhActions.obj());icon(pan,R.drawable.rh_pan);
        icon(gridButton(viewGrid,"还原缩放","stream.zoom.reset",RhActions.obj()),R.drawable.rh_zoom_reset);
        }
        try(RhStatistics.Scope trace=new RhStatistics.Scope("session_controls_audio_create")){
        audio=button("声音：关","stream.audio.toggle",RhActions.obj());sound.addView(audio,new LinearLayout.LayoutParams(-1,dp(48)));
        volumeLabel=text("音量 100%",14);volumeLabel.setPadding(dp(4),dp(16),dp(4),dp(4));sound.addView(volumeLabel);
        volume=new SeekBar(context);volume.setMax(100);volume.setContentDescription("串流音量");volume.setTag("stream.audio.volume");sound.addView(volume,new LinearLayout.LayoutParams(-1,dp(52)));
        android.content.res.ColorStateList accent=android.content.res.ColorStateList.valueOf(Color.parseColor(dark?"#6f8af1":"#5274e8"));volume.setProgressTintList(accent);volume.setThumbTintList(accent);volume.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(lineColor)));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(!disposed&&fromUser&&ownsVisible(bar)){volumeLabel.setText("音量 "+value+"%");if(!trackingVolume)send("stream.audio.volume",RhActions.obj("volume",value));}}
            public void onStartTrackingTouch(SeekBar bar){if(!disposed&&ownsVisible(bar))trackingVolume=true;}
            public void onStopTrackingTouch(SeekBar bar){if(disposed)return;trackingVolume=false;if(ownsVisible(bar))send("stream.audio.volume",RhActions.obj("volume",bar.getProgress()));}
        });
        }
        LinearLayout result=new LinearLayout(context);result.setGravity(Gravity.CENTER_VERTICAL);result.setPadding(0,dp(8),0,0);feedback=text("",12);feedback.setSingleLine(true);feedback.setEllipsize(android.text.TextUtils.TruncateAt.END);result.addView(feedback,new LinearLayout.LayoutParams(0,-2,1));feedbackDetails=button("详情","stream.menu.open",RhActions.obj("menu","feedback"));feedbackDetails.setVisibility(View.GONE);result.addView(feedbackDetails,new LinearLayout.LayoutParams(-2,dp(40)));panel.addView(result);
        FrameLayout.LayoutParams panelParams=new FrameLayout.LayoutParams(dp(560),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        panelParams.topMargin=dp(68);root.addView(panel,panelParams);panel.setVisibility(View.GONE);
        // An unmeasured GridLayout reports an inferred column count. Establish
        // explicit child spans before any query or first tab becomes visible.
        gridColumns(inputGrid,2);gridColumns(screenGrid,2);gridColumns(viewGrid,2);
        feedbackDetails.setOnClickListener(view->{if(ownsVisible(view))send("stream.menu.open",RhActions.obj("menu",warningDetails?"warning":"feedback"));});
    }
    private TextView text(String label,int size){TextView t=new TextView(context);t.setText(label);t.setTextSize(size);t.setTextColor(Color.parseColor(inkColor));return t;}
    private void send(String id,JSONObject args){if(!disposed)actions.send(id,args);}
    private LinearLayout page(){LinearLayout p=new LinearLayout(context);p.setOrientation(LinearLayout.VERTICAL);return p;}
    private Button button(String title,String action,JSONObject arguments){
        Button b=new Button(context);b.setText(title);b.setStateListAnimator(null);b.setElevation(0);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));b.setAllCaps(false);b.setTextSize(13);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setMaxLines(2);RhUiStyle.button(b,Color.parseColor(inkColor),Color.parseColor(mutedColor),surface(dark?"#242e41":"#f3f5fa",null,10),surface(surfaceColor,lineColor,10),surface(selectedColor,"#5274e8",10));b.setPadding(dp(12),dp(2),dp(12),dp(2));
        b.setTag(action);b.setOnClickListener(v->{if(ownsVisible(v))send(action,arguments);});return b;
    }
    private void icon(Button button,int resource){android.graphics.drawable.Drawable drawable=context.getDrawable(resource).mutate();drawable.setBounds(0,0,dp(18),dp(18));drawable.setTintList(new android.content.res.ColorStateList(new int[][]{{-android.R.attr.state_enabled},{android.R.attr.state_selected},{}},new int[]{Color.parseColor(mutedColor),Color.parseColor(dark?"#6f8af1":"#5274e8"),Color.parseColor(inkColor)}));button.setCompoundDrawablesRelative(drawable,null,null,null);button.setCompoundDrawablePadding(dp(7));}
    private void styleSwitch(Switch toggle){
        toggle.setSwitchMinWidth(dp(44));toggle.setSwitchPadding(dp(8));toggle.setSplitTrack(false);
        GradientDrawable track=surface("#ffffff",null,12);track.setSize(dp(44),dp(24));toggle.setTrackDrawable(track);
        toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        toggle.setTrackTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{Color.parseColor(dark?"#6f8af1":"#5274e8"),Color.parseColor(dark?"#64748b":"#7c8da8")}));
    }
    private Button gridButton(GridLayout grid,String title,String action,JSONObject args){Button b=button(title,action,args);b.setSingleLine(true);b.setHorizontallyScrolling(false);grid.addView(b);return b;}
    private int labelWidth(TextView label){return labelWidth(label,label.getText().toString());}
    private int labelWidth(TextView label,String text){return (int)Math.ceil(label.getPaint().measureText(text))+label.getCompoundPaddingLeft()+label.getCompoundPaddingRight()+dp(2);}
    private int labelHeight(TextView label,int minimum){return Math.max(dp(minimum),(int)Math.ceil(label.getPaint().getFontSpacing())+label.getCompoundPaddingTop()+label.getCompoundPaddingBottom());}
    private int gridCount(GridLayout grid,int width,int maximum){int needed=0;for(int i=0;i<grid.getChildCount();i++)needed=Math.max(needed,labelWidth((TextView)grid.getChildAt(i)));return Math.max(1,Math.min(maximum,(width+dp(8))/(needed+dp(8))));}
    private int updateHeader(int width){
        int switchWidth=Math.max(labelWidth(floatingKeyboardSwitch),(int)Math.ceil(floatingKeyboardSwitch.getPaint().measureText(floatingKeyboardSwitch.getText().toString()))+floatingKeyboardSwitch.getPaddingLeft()+floatingKeyboardSwitch.getPaddingRight()+floatingKeyboardSwitch.getSwitchMinWidth()+floatingKeyboardSwitch.getSwitchPadding());
        int required=switchWidth+labelWidth(disconnect)+dp(16);
        boolean stacked=required+Math.min(dp(120),labelWidth(sessionName))+dp(8)>width;
        int orientation=stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL;
        if(header.getOrientation()!=orientation){header.setOrientation(orientation);sessionName.setLayoutParams(new LinearLayout.LayoutParams(stacked?-1:0,labelHeight(sessionName,48),stacked?0:1));headerActions.setLayoutParams(new LinearLayout.LayoutParams(stacked?-1:-2,-2));}
        headerActions.setGravity(Gravity.CENTER_VERTICAL|(stacked?Gravity.END:Gravity.START));
        LinearLayout.LayoutParams switchParams=(LinearLayout.LayoutParams)floatingKeyboardSwitch.getLayoutParams();int margin=stacked?0:dp(8);
        if(switchParams.leftMargin!=margin){switchParams.leftMargin=margin;floatingKeyboardSwitch.setLayoutParams(switchParams);}
        int actionsHeight=Math.max(labelHeight(floatingKeyboardSwitch,48),labelHeight(disconnect,48));
        return (stacked?labelHeight(sessionName,48)+actionsHeight:Math.max(labelHeight(sessionName,48),actionsHeight))+header.getPaddingTop()+header.getPaddingBottom();
    }
    private void gridColumns(GridLayout grid,int count){
        // Remove old explicit specs before changing column count (Android validates them).
        List<View> children=new ArrayList<>();for(int i=0;i<grid.getChildCount();i++)children.add(grid.getChildAt(i));grid.removeAllViews();grid.setColumnCount(count);
        for(int i=0;i<children.size();i++){GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec(i/count),GridLayout.spec(i%count,1f));p.width=0;p.height=dp(52);p.setMargins(i%count==0?0:dp(8),i<count?0:dp(8),0,0);grid.addView(children.get(i),p);}
    }
    private static Insets safeInsets(WindowInsetsCompat window){return window.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());}
    private void updateBounds(){
        WindowInsetsCompat window=ViewCompat.getRootWindowInsets(root);updateBounds(window==null?currentInsets:safeInsets(window));
    }
    private void updateBounds(Insets safe){
        currentInsets=safe;
        if(disposed||creatingContents||root.getWidth()==0||root.getHeight()==0)return;
        int top=safe.top,bottom=safe.bottom,left=safe.left,right=safe.right;
        int side=Math.max(left,right);
        int width=Math.max(dp(160),Math.min(dp(560),root.getWidth()-2*side-dp(24)));
        FrameLayout.LayoutParams hp=(FrameLayout.LayoutParams)handle.getLayoutParams();
        // The toggle remains one fixed touch target when its caption changes.
        int handleWidth=Math.max(dp(88),Math.max(labelWidth(handle,"控制"),labelWidth(handle,"收起"))),handleHeight=labelHeight(handle,48);
        int topMargin=Math.max(top,Math.min(top+dp(8),root.getHeight()-bottom-handleHeight));
        if(hp.width!=handleWidth||hp.height!=handleHeight){hp.width=handleWidth;hp.height=handleHeight;handle.setLayoutParams(hp);}
        if(hp.topMargin!=topMargin){hp.topMargin=topMargin;handle.setLayoutParams(hp);}
        if(panel==null)return;
        FrameLayout.LayoutParams pp=(FrameLayout.LayoutParams)panel.getLayoutParams();int panelTop=topMargin+handleHeight+dp(8);
        if(pp.width!=width||pp.topMargin!=panelTop||pp.leftMargin!=side||pp.rightMargin!=side){pp.width=width;pp.topMargin=panelTop;pp.leftMargin=side;pp.rightMargin=side;panel.setLayoutParams(pp);}
        int headerHeight=updateHeader(width-panel.getPaddingLeft()-panel.getPaddingRight());
        int chrome=panel.getPaddingTop()+panel.getPaddingBottom()+headerHeight+dp(54)+Math.max(labelHeight(feedback,0),feedbackDetails.getVisibility()==View.VISIBLE?dp(40):0)+dp(8);
        int nextHeight=Math.max(dp(48),Math.min(dp(440),root.getHeight()-panelTop-bottom-dp(16)-chrome));
        if(maxBodyHeight!=nextHeight){maxBodyHeight=nextHeight;scroll.requestLayout();}
        int bodyWidth=width-panel.getPaddingLeft()-panel.getPaddingRight()-scroll.getPaddingLeft()-scroll.getPaddingRight();
        int nextColumns=gridCount(inputGrid,bodyWidth,2);
        if(inputGrid.getColumnCount()!=nextColumns){columns=nextColumns;gridColumns(inputGrid,columns);}
        int screenColumns=gridCount(screenGrid,bodyWidth,2);if(screenGrid.getColumnCount()!=screenColumns)gridColumns(screenGrid,screenColumns);
        int viewColumns=gridCount(viewGrid,bodyWidth,2);if(viewGrid.getColumnCount()!=viewColumns)gridColumns(viewGrid,viewColumns);
    }
    public boolean isExpanded(){return panel!=null&&panel.getVisibility()==View.VISIBLE;}
    private boolean ownsVisible(View view){
        if(!RhViewOrigin.visibleWithin(root,view))return false;
        for(View current=view;current!=null;){
            if(current.getVisibility()!=View.VISIBLE||!current.isEnabled())return false;
            if(current==handle)return true;
            if(current==panel)return isExpanded();
            ViewParent parent=current.getParent();current=parent instanceof View?(View)parent:null;
        }
        return false;
    }
    private ArrayList<View> focusables(){
        ArrayList<View> result=new ArrayList<>();handle.addFocusables(result,View.FOCUS_FORWARD);
        if(isExpanded())panel.addFocusables(result,View.FOCUS_FORWARD);
        result.removeIf(view->!ownsVisible(view)||!(view.getTag() instanceof String));return result;
    }
    private void retainLocalFocus(boolean ownedBefore){if(ownedBefore&&isExpanded()&&!ownsVisible(root.findFocus()))handle.requestFocusFromTouch();}
    /** Own a complete key pair only when its down event starts on a local control. */
    boolean dispatchKeyEvent(KeyEvent event,Runnable escape){
        if(disposed||!event.isFromSource(InputDevice.SOURCE_KEYBOARD)||event.isSystem()&&event.getKeyCode()!=KeyEvent.KEYCODE_ESCAPE)return false;
        int action=event.getAction(),key=event.getKeyCode();if(action!=KeyEvent.ACTION_DOWN&&action!=KeyEvent.ACTION_UP)return false;
        long identity=((long)event.getDeviceId()<<32)|(key&0xffffffffL);View origin=localKeys.get(identity),focused=root.findFocus();
        if(action==KeyEvent.ACTION_DOWN){
            if(origin==null){if(!ownsVisible(focused))return false;origin=focused;localKeys.put(identity,origin);}
        }else{
            if(origin==null)return false;localKeys.remove(identity);
        }
        if(key==KeyEvent.KEYCODE_ESCAPE){if(action==KeyEvent.ACTION_UP&&!event.isCanceled()&&ownsVisible(origin))escape.run();return true;}
        // The panel may close or change pages before release. Never send that release remotely
        // or activate a different newly focused button.
        if(!ownsVisible(origin)||!origin.hasFocus()){origin.setPressed(false);if(action==KeyEvent.ACTION_UP)return true;}
        else if(origin.dispatchKeyEvent(event))return true;
        if(action==KeyEvent.ACTION_DOWN&&ownsVisible(focused)&&!event.isCtrlPressed()&&!event.isAltPressed()&&!event.isMetaPressed()){
            if(key==KeyEvent.KEYCODE_TAB){
                ArrayList<View> available=focusables();int index=available.indexOf(focused);
                if(!available.isEmpty()){int next=Math.floorMod(index+(event.isShiftPressed()?-1:1),available.size());available.get(next).requestFocusFromTouch();}
            }else{
                int direction=key==KeyEvent.KEYCODE_DPAD_LEFT?View.FOCUS_LEFT:key==KeyEvent.KEYCODE_DPAD_RIGHT?View.FOCUS_RIGHT:key==KeyEvent.KEYCODE_DPAD_UP?View.FOCUS_UP:key==KeyEvent.KEYCODE_DPAD_DOWN?View.FOCUS_DOWN:0;
                if(direction!=0){View next=FocusFinder.getInstance().findNextFocus(root,focused,direction);if(ownsVisible(next))next.requestFocusFromTouch();}
            }
        }
        return true;
    }
    public String tab(){return tab;}
    public void setExpanded(boolean expanded){
        if(disposed)return;if(expanded)createContents();
        boolean changed=expanded!=isExpanded();View focused=root.findFocus();boolean local=ownsVisible(focused);
        if(changed&&expanded&&focused!=null&&!local)returnFocus=focused;
        if(panel!=null)panel.setVisibility(expanded?View.VISIBLE:View.GONE);handle.setText(expanded?"收起":"控制");
        icon(handle,expanded?R.drawable.rh_chevron_up:R.drawable.rh_chevron_down);handle.getCompoundDrawablesRelative()[0].setTint(Color.WHITE);
        warningIndicator();updateBounds();
        if(!expanded)pendingMonitorFocus="";
        renderZoom();
        if(changed&&expanded)handle.requestFocusFromTouch();
        else if(changed&&local&&returnFocus!=null&&returnFocus.getVisibility()==View.VISIBLE&&returnFocus.isEnabled())returnFocus.requestFocusFromTouch();
    }
    public void setTab(String value){
        if(!Arrays.asList("input","displays","audio").contains(value))throw new IllegalArgumentException("tab must be input, displays or audio");if(disposed)return;boolean local=ownsVisible(root.findFocus());if(!tab.equals(value))pendingMonitorFocus="";tab=value;if(panel==null)return;
        input.setVisibility(value.equals("input")?View.VISIBLE:View.GONE);screens.setVisibility(value.equals("displays")?View.VISIBLE:View.GONE);sound.setVisibility(value.equals("audio")?View.VISIBLE:View.GONE);
        for(Map.Entry<String,Button> entry:tabs.entrySet()){boolean selected=entry.getKey().equals(value);Button button=entry.getValue();button.setSelected(selected);RhUiStyle.button(button,Color.parseColor(selected?"#5274e8":mutedColor),Color.parseColor(mutedColor),surface(selected?selectedColor:surfaceColor,selected?selectedLine:null,8),surface(surfaceColor,lineColor,8),surface(selectedColor,"#5274e8",8));}
        scroll.scrollTo(0,0);
        renderZoom();
        updateFeedback(pendingFeedbackGroup,pendingFeedbackMessage,pendingFeedbackFailure);
        if(local&&!ownsVisible(root.findFocus()))tabs.get(value).requestFocusFromTouch();
    }
    public void update(boolean trackpad,boolean panning,boolean audioEnabled,int level,JSONObject display,boolean busy,String failure){
        update(trackpad,panning,audioEnabled,level,display,busy,failure,"");
    }
    public void update(boolean trackpad,boolean panning,boolean audioEnabled,int level,JSONObject display,boolean busy,String failure,String operation){
        if(disposed)return;
        if(panel==null){
            final JSONObject snapshot;try{snapshot=new JSONObject(display.toString());}catch(JSONException error){throw new IllegalArgumentException("Invalid display snapshot",error);}
            pendingDisplay=()->update(trackpad,panning,audioEnabled,level,snapshot,busy,failure,operation);return;
        }
        View focused=root.findFocus();boolean local=ownsVisible(focused);String focusId=monitorIds.get(focused);
        if(focusId==null&&focused==handle&&tab.equals("displays")&&!pendingMonitorFocus.isEmpty())focusId=pendingMonitorFocus;
        if(focusId==null)pendingMonitorFocus="";
        shownTrackpad=trackpad;mode.setText(clickScrollMode?"点击与滚动":trackpad?"触控板":"直接点击");pan.setText(panning?"移动画面：开":"移动画面");audio.setText(audioEnabled?"声音：开":"声音：关");
        boolean selecting=operation.equals("stream.display.select");
        if(!trackingVolume){volume.setProgress(level);volumeLabel.setText("音量 "+level+"%");}refresh.setEnabled(!busy);refresh.setText(busy&&!selecting?"正在读取…":"刷新屏幕");
        for(int i=0;i<screenGrid.getChildCount();i++)screenGrid.getChildAt(i).setEnabled(!busy);
        JSONObject selected=display.optJSONObject("selected");String error=failure.isEmpty()?display.optString("error"):failure;
        windowCapture=selected!=null&&selected.optString("kind").equals("window");refresh.setEnabled(!busy&&!windowCapture);if(windowCapture)refresh.setText("窗口串流");
        displayStatus.setText(busy?(selecting?"正在切换屏幕…":"正在读取屏幕…"):selected==null?(!error.isEmpty()?"屏幕列表未加载":"选择要查看的屏幕"):monitorName(selected)+" · "+selected.optInt("width")+"×"+selected.optInt("height"));
        if(windowCapture)displayStatus.setText(selected.optString("title","窗口")+" · "+selected.optInt("width")+"×"+selected.optInt("height"));
        displayStatus.setTextColor(Color.parseColor(mutedColor));
        JSONArray monitors=display.optJSONArray("displays");String fingerprint=String.valueOf(monitors)+":"+(selected==null?"":selected.optString("id"))+":"+busy;
        if(fingerprint.equals(monitorFingerprint)){retainLocalFocus(local);return;}monitorFingerprint=fingerprint;monitorList.removeAllViews();monitorAvailability.clear();monitorIds.clear();int count=monitors==null?0:monitors.length();Button focusRow=null;
        for(int i=0;i<count;i++){JSONObject monitor=monitors.optJSONObject(i);if(monitor==null)continue;
            String id=monitor.optString("id");boolean available=monitor.optBoolean("available"),chosen=selected!=null&&id.equals(selected.optString("id"));
            String title=monitorName(monitor)+" · "+monitor.optInt("width")+"×"+monitor.optInt("height")+(available?chosen?"  ✓":"":"  暂不可用");
            Button b=button(title,"stream.display.select",RhActions.obj("displayId",id));b.setEnabled(available&&!busy);monitorAvailability.put(b,available);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
            monitorIds.put(b,id);if(id.equals(focusId)&&available)focusRow=b;
            b.setSelected(chosen);b.setMinimumHeight(dp(56));b.setMinHeight(dp(56));RhUiStyle.button(b,Color.parseColor(inkColor),Color.parseColor(mutedColor),surface(chosen?selectedColor:surfaceColor,chosen?selectedLine:lineColor,8),surface(surfaceColor,lineColor,8),surface(selectedColor,"#5274e8",8));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=i==0?0:dp(8);monitorList.addView(b,lp);
        }
        if(count==0){TextView empty=text(windowCapture?"窗口串流（预览）：先点击窗口激活输入。更换窗口或桌面需断开后重新选择。":error.isEmpty()?"暂无屏幕信息，请刷新":"屏幕列表未加载",13);empty.setPadding(dp(4),dp(8),dp(4),dp(8));monitorList.addView(empty);}
        if(local&&isExpanded()&&tab.equals("displays")&&focusId!=null){
            if(!busy&&focusRow!=null){pendingMonitorFocus="";focusRow.requestFocusFromTouch();}
            else{pendingMonitorFocus=busy?focusId:"";handle.requestFocusFromTouch();}
        }
        retainLocalFocus(local);
    }
    public void setDeviceName(String name){if(disposed)return;deviceName=name==null||name.isEmpty()?"远程设备":name;if(panel==null)return;sessionName.setText(deviceName);updateBounds();}
    private void enabled(View view,boolean value){view.setEnabled(value);view.setAlpha(value?1f:0.45f);}
    public void updateControls(boolean connected,boolean statistics,float scale,boolean busy,String group,String message,String failure){
        if(disposed)return;
        if(panel==null){updateZoom(scale);pendingControls=()->updateControls(connected,statistics,zoomPercent/100f,busy,group,message,failure);updateFeedback(group,message,failure);return;}
        boolean local=ownsVisible(root.findFocus());stats.setText(statistics?"统计：开":"统计：关");stats.setSelected(statistics);RhUiStyle.button(stats,Color.parseColor(inkColor),Color.parseColor(mutedColor),surface(statistics?selectedColor:surfaceColor,statistics?selectedLine:lineColor,8),surface(surfaceColor,lineColor,8),surface(selectedColor,"#5274e8",8));mode.setSelected(mode.getText().toString().equals("触控板"));pan.setSelected(pan.getText().toString().equals("移动画面：开"));updateZoom(scale);
        for(int i=0;i<inputGrid.getChildCount();i++)enabled(inputGrid.getChildAt(i),connected&&!busy);
        for(int i=0;i<screenGrid.getChildCount();i++){View control=screenGrid.getChildAt(i);enabled(control,connected&&!busy&&(!windowCapture||"stream.window.move".equals(control.getTag())));}
        for(int i=0;i<viewGrid.getChildCount();i++)enabled(viewGrid.getChildAt(i),connected&&!busy);
        for(Map.Entry<Button,Boolean> entry:monitorAvailability.entrySet())enabled(entry.getKey(),connected&&!busy&&entry.getValue());
        enabled(audio,connected&&!busy);enabled(volume,connected&&!busy);enabled(refresh,connected&&!busy&&!windowCapture);
        enabled(stats,connected&&!busy);updateFeedback(group,message,failure);
        enabled(scrollModeSwitch,connected&&!busy);
        enabled(clickScrollModeSwitch,connected&&!busy);
        enabled(floatingKeyboardSwitch,connected&&!busy);
        updateBounds();
        retainLocalFocus(local);
    }
    public void updateFeedback(String group,String message,String failure){
        if(disposed)return;pendingFeedbackGroup=group;pendingFeedbackMessage=message;pendingFeedbackFailure=failure;
        if(panel==null){pendingFeedback=true;return;}
        boolean visible=tab.equals(group),repeatedVolume=group.equals("audio")&&failure.isEmpty()&&message.contentEquals(volumeLabel.getText());
        String text=visible&&!repeatedVolume?(failure.isEmpty()||!message.isEmpty()?message:"操作失败"):"";
        warningDetails=text.isEmpty()&&!networkWarning.isEmpty();if(warningDetails)text=networkWarningSummary;
        if(!text.contentEquals(feedback.getText()))feedback.setText(text);feedback.setContentDescription(text);
        feedback.setTextColor(visible&&!failure.isEmpty()?failureColor:Color.parseColor(mutedColor));feedbackDetails.setVisibility(warningDetails||visible&&!failure.isEmpty()?View.VISIBLE:View.GONE);
    }
    View controlHandle(){return handle;}
    public void updateNetworkWarning(String message,String summary){if(networkWarning.equals(message)&&networkWarningSummary.equals(summary))return;networkWarning=message;networkWarningSummary=summary;warningIndicator();updateBounds();}
    private void warningIndicator(){
        // Keep the warning slot even when it clears while the panel is open.
        android.graphics.drawable.Drawable marker=networkWarning.isEmpty()?new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT):context.getDrawable(R.drawable.rh_status_warning).mutate();
        marker.setBounds(0,0,dp(14),dp(14));
        handle.setCompoundDrawablesRelative(handle.getCompoundDrawablesRelative()[0],null,marker,null);handle.setContentDescription((isExpanded()?"收起控制":"控制")+(networkWarning.isEmpty()?"":"，"+networkWarning));
    }
    public void updateScrollMode(boolean enabled){if(disposed)return;scrollMode=enabled;if(panel==null)return;bindingScrollMode=true;try{scrollModeSwitch.setChecked(enabled);}finally{bindingScrollMode=false;}}
    public void updateClickScrollMode(boolean enabled){if(disposed)return;clickScrollMode=enabled;if(panel==null)return;bindingClickScrollMode=true;try{clickScrollModeSwitch.setChecked(enabled);mode.setText(enabled?"点击与滚动":shownTrackpad?"触控板":"直接点击");}finally{bindingClickScrollMode=false;}}
    public void updateFloatingKeyboard(boolean enabled){if(disposed)return;floatingKeyboard=enabled;if(panel==null)return;bindingFloatingKeyboard=true;try{floatingKeyboardSwitch.setChecked(enabled);}finally{bindingFloatingKeyboard=false;}}
    public void updateZoom(float scale){zoomPercent=Math.round(scale*100);renderZoom();}
    private void renderZoom(){if(isExpanded()&&tab.equals("displays")&&zoomPercent!=shownZoomPercent){shownZoomPercent=zoomPercent;zoomLabel.setText("缩放 "+zoomPercent+"%");}}
    private String monitorName(JSONObject monitor){String name=monitor.optString("name");return monitor.optBoolean("virtual")?"虚拟屏":name.isEmpty()?"物理屏幕":name;}
    public JSONObject layoutState(){Rect h=new Rect(),p=new Rect();handle.getHitRect(h);if(panel!=null)panel.getHitRect(p);return RhActions.obj("created",panel!=null,"expanded",isExpanded(),"tab",tab,"columns",columns,"handle",RhActions.obj("x",h.left,"y",h.top,"width",h.width(),"height",h.height()),"panel",RhActions.obj("x",p.left,"y",p.top,"width",p.width(),"height",p.height()),"scrollable",panel!=null&&(scroll.canScrollVertically(1)||scroll.canScrollVertically(-1)));}
    public void dispose(){if(disposed)return;disposed=true;trackingVolume=false;pendingDisplay=null;pendingControls=null;pendingFeedback=false;for(View view:localKeys.values())view.setPressed(false);localKeys.clear();ViewCompat.setOnApplyWindowInsetsListener(handle,null);ViewCompat.setWindowInsetsAnimationCallback(handle,null);root.removeOnLayoutChangeListener(layoutListener);if(panel!=null)root.removeView(panel);panel=null;root.removeView(handle);}
}
