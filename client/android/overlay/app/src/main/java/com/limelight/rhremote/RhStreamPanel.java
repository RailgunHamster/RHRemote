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
import android.view.WindowInsets;
import android.widget.*;
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
    private final LinearLayout panel, input, screens, sound;
    private final ScrollView scroll;
    private final Map<String, Button> tabs=new LinkedHashMap<>();
    private final Map<Button,Boolean> monitorAvailability=new IdentityHashMap<>();
    private final Map<Button,String> monitorIds=new IdentityHashMap<>();
    private final Map<Long,View> localKeys=new HashMap<>();
    private final TextView displayStatus, volumeLabel, sessionName, feedback, zoomLabel;
    private final Button feedbackDetails, stats, disconnect;
    private final String surfaceColor,lineColor,inkColor,mutedColor,selectedColor,selectedLine;
    private final int failureColor;
    private String monitorFingerprint="";private boolean trackingVolume;
    private final SeekBar volume;
    private final LinearLayout monitorList;
    private final Button mode, pan, audio, refresh;
    private final Switch scrollModeSwitch;
    private boolean bindingScrollMode;
    private final GridLayout inputGrid, screenGrid;
    private final View.OnLayoutChangeListener layoutListener;
    private String tab="input";
    private int columns=2, maxBodyHeight;
    private boolean disposed;
    private View returnFocus;
    private String pendingMonitorFocus="";
    private int zoomPercent=100,shownZoomPercent=100;

    private int dp(int value) { return Math.round(context.getResources().getDisplayMetrics().density*value); }
    private GradientDrawable surface(String fill, String border, int radius) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(Color.parseColor(fill));shape.setCornerRadius(dp(radius));
        if(border!=null)shape.setStroke(dp(1),Color.parseColor(border));
        return shape;
    }
    public RhStreamPanel(Context context, FrameLayout root, Actions actions) {
        this.context=context;this.root=root;this.actions=actions;returnFocus=root.findFocus();
        boolean dark=RhShared.dark(context);surfaceColor=dark?"#1b2232":"#ffffff";lineColor=dark?"#303c53":"#e5eaf4";inkColor=dark?"#e1e8f6":"#34415b";mutedColor=dark?"#a4b1c8":"#69778e";selectedColor=dark?"#253452":"#edf2ff";selectedLine=dark?"#6f8af1":"#c7d4f6";
        failureColor=RhUiStyle.failure(dark);
        handle=button("控制", "stream.controls.toggle", RhActions.obj());
        handle.setTextColor(Color.WHITE);handle.setBackground(surface("#5274e8",null,18));
        handle.setElevation(dp(4));
        icon(handle,R.drawable.rh_chevron_down);
        handle.getCompoundDrawablesRelative()[0].setTint(Color.WHITE);
        FrameLayout.LayoutParams handleParams=new FrameLayout.LayoutParams(dp(88),dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        handleParams.topMargin=dp(12);root.addView(handle,handleParams);

        panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setClickable(true);panel.setFocusable(false);
        panel.setPadding(dp(14),dp(12),dp(14),dp(12));
        panel.setBackground(surface(surfaceColor,lineColor,16));panel.setElevation(dp(10));
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(0,0,0,dp(8));sessionName=text("远程设备",14);sessionName.setSingleLine(true);sessionName.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(sessionName,new LinearLayout.LayoutParams(0,dp(48),1));disconnect=button("断开","stream.disconnect",RhActions.obj());RhUiStyle.buttonText(disconnect,failureColor,Color.parseColor(mutedColor));header.addView(disconnect,new LinearLayout.LayoutParams(-2,dp(48)));panel.addView(header);
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

        inputGrid=new GridLayout(context);inputGrid.setUseDefaultMargins(false);input.addView(inputGrid);
        mode=gridButton(inputGrid,"直接点击","stream.mode.toggle",RhActions.obj());icon(mode,R.drawable.rh_mouse);
        icon(gridButton(inputGrid,"键盘","stream.keyboard.toggle",RhActions.obj()),R.drawable.rh_keyboard);
        icon(gridButton(inputGrid,"快捷键","stream.shortcuts.open",RhActions.obj()),R.drawable.rh_shortcuts);
        icon(gridButton(inputGrid,"Ctrl+Alt+Delete","stream.shortcut",RhActions.obj("shortcut","ctrl-alt-delete")),R.drawable.rh_lock);
        pan=gridButton(inputGrid,"移动画面","stream.pan.toggle",RhActions.obj());icon(pan,R.drawable.rh_pan);
        icon(gridButton(inputGrid,"还原缩放","stream.zoom.reset",RhActions.obj()),R.drawable.rh_zoom_reset);
        scrollModeSwitch=new Switch(context);scrollModeSwitch.setText("滚动模式");scrollModeSwitch.setTextColor(Color.parseColor(inkColor));scrollModeSwitch.setTextSize(14);scrollModeSwitch.setContentDescription("滚动模式");scrollModeSwitch.setShowText(false);scrollModeSwitch.setSwitchMinWidth(dp(44));scrollModeSwitch.setMinHeight(dp(48));scrollModeSwitch.setPadding(dp(4),0,dp(4),0);scrollModeSwitch.setDefaultFocusHighlightEnabled(false);scrollModeSwitch.setTag("stream.scroll.set");
        android.graphics.drawable.StateListDrawable scrollFocus=new android.graphics.drawable.StateListDrawable();scrollFocus.addState(new int[]{-android.R.attr.state_enabled},surface(surfaceColor,null,10));scrollFocus.addState(new int[]{android.R.attr.state_pressed},surface(selectedColor,"#5274e8",10));scrollFocus.addState(new int[]{android.R.attr.state_focused},surface(selectedColor,"#5274e8",10));scrollFocus.addState(new int[]{},surface(surfaceColor,null,10));scrollModeSwitch.setBackground(scrollFocus);
        scrollModeSwitch.setThumbTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{Color.parseColor(RhShared.dark(context)?"#6f8af1":"#5274e8"),Color.parseColor(mutedColor)}));scrollModeSwitch.setTrackTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{Color.parseColor(selectedLine),Color.parseColor(lineColor)}));
        scrollModeSwitch.setOnCheckedChangeListener((view,checked)->{if(!bindingScrollMode)actions.send("stream.scroll.set",RhActions.obj("enabled",checked));});LinearLayout.LayoutParams scrollModeLayout=new LinearLayout.LayoutParams(-1,dp(48));scrollModeLayout.topMargin=dp(8);input.addView(scrollModeSwitch,scrollModeLayout);TextView scrollHelp=text("单击中键 · 四向滚动",12);scrollHelp.setTextColor(Color.parseColor(mutedColor));scrollHelp.setPadding(dp(4),0,dp(4),0);input.addView(scrollHelp);
        LinearLayout viewOptions=new LinearLayout(context);viewOptions.setGravity(Gravity.CENTER_VERTICAL);viewOptions.setPadding(dp(4),dp(12),0,0);
        zoomLabel=text("缩放 100%",13);viewOptions.addView(zoomLabel,new LinearLayout.LayoutParams(0,dp(44),1));
        stats=button("统计：关","stream.stats.toggle",RhActions.obj());icon(stats,R.drawable.rh_chart);viewOptions.addView(stats,new LinearLayout.LayoutParams(-2,dp(44)));input.addView(viewOptions);

        displayStatus=text("正在读取屏幕…",13);displayStatus.setPadding(dp(4),dp(2),dp(4),dp(12));screens.addView(displayStatus);
        screenGrid=new GridLayout(context);screens.addView(screenGrid);
        icon(gridButton(screenGrid,"自动选屏","stream.display.select",RhActions.obj("displayId","auto")),R.drawable.rh_monitor);
        icon(gridButton(screenGrid,"虚拟屏","stream.display.select",RhActions.obj("displayId","virtual")),R.drawable.rh_monitor);
        icon(gridButton(screenGrid,"窗口移到左屏","stream.window.move",RhActions.obj("direction","left")),R.drawable.rh_move_left);
        icon(gridButton(screenGrid,"窗口移到右屏","stream.window.move",RhActions.obj("direction","right")),R.drawable.rh_move_right);
        TextView label=text("显示器",12);label.setTextColor(Color.parseColor(mutedColor));label.setPadding(dp(4),dp(14),0,dp(6));screens.addView(label);
        monitorList=new LinearLayout(context);monitorList.setOrientation(LinearLayout.VERTICAL);screens.addView(monitorList);
        refresh=button("刷新屏幕","stream.displays.refresh",RhActions.obj());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(48));rp.topMargin=dp(8);screens.addView(refresh,rp);

        audio=button("声音：关","stream.audio.toggle",RhActions.obj());sound.addView(audio,new LinearLayout.LayoutParams(-1,dp(48)));
        volumeLabel=text("音量 100%",14);volumeLabel.setPadding(dp(4),dp(16),dp(4),dp(4));sound.addView(volumeLabel);
        volume=new SeekBar(context);volume.setMax(100);volume.setContentDescription("串流音量");volume.setTag("stream.audio.volume");sound.addView(volume,new LinearLayout.LayoutParams(-1,dp(52)));
        android.content.res.ColorStateList accent=android.content.res.ColorStateList.valueOf(Color.parseColor(RhShared.dark(context)?"#6f8af1":"#5274e8"));volume.setProgressTintList(accent);volume.setThumbTintList(accent);volume.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(lineColor)));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(fromUser){volumeLabel.setText("音量 "+value+"%");if(!trackingVolume)actions.send("stream.audio.volume",RhActions.obj("volume",value));}}
            public void onStartTrackingTouch(SeekBar bar){trackingVolume=true;}
            public void onStopTrackingTouch(SeekBar bar){trackingVolume=false;actions.send("stream.audio.volume",RhActions.obj("volume",bar.getProgress()));}
        });
        LinearLayout result=new LinearLayout(context);result.setGravity(Gravity.CENTER_VERTICAL);result.setPadding(0,dp(8),0,0);feedback=text("",12);feedback.setSingleLine(true);feedback.setEllipsize(android.text.TextUtils.TruncateAt.END);result.addView(feedback,new LinearLayout.LayoutParams(0,-2,1));feedbackDetails=button("详情","stream.menu.open",RhActions.obj("menu","feedback"));feedbackDetails.setVisibility(View.GONE);result.addView(feedbackDetails,new LinearLayout.LayoutParams(-2,dp(40)));panel.addView(result);
        FrameLayout.LayoutParams panelParams=new FrameLayout.LayoutParams(dp(560),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        panelParams.topMargin=dp(68);root.addView(panel,panelParams);panel.setVisibility(View.GONE);
        layoutListener=(v,l,t,r,b,ol,ot,or,ob)->updateBounds();root.addOnLayoutChangeListener(layoutListener);
        setTab("input");root.post(this::updateBounds);
    }
    private TextView text(String label,int size){TextView t=new TextView(context);t.setText(label);t.setTextSize(size);t.setTextColor(Color.parseColor(inkColor));return t;}
    private LinearLayout page(){LinearLayout p=new LinearLayout(context);p.setOrientation(LinearLayout.VERTICAL);return p;}
    private Button button(String title,String action,JSONObject arguments){
        Button b=new Button(context);b.setText(title);b.setStateListAnimator(null);b.setElevation(0);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));b.setAllCaps(false);b.setTextSize(13);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setMaxLines(2);RhUiStyle.button(b,Color.parseColor(inkColor),Color.parseColor(mutedColor),surface(RhShared.dark(context)?"#242e41":"#f3f5fa",null,10),surface(surfaceColor,lineColor,10),surface(selectedColor,"#5274e8",10));b.setPadding(dp(12),dp(2),dp(12),dp(2));
        b.setTag(action);b.setOnClickListener(v->actions.send(action,arguments));return b;
    }
    private void icon(Button button,int resource){android.graphics.drawable.Drawable drawable=context.getDrawable(resource).mutate();drawable.setBounds(0,0,dp(18),dp(18));drawable.setTintList(new android.content.res.ColorStateList(new int[][]{{-android.R.attr.state_enabled},{android.R.attr.state_selected},{}},new int[]{Color.parseColor(mutedColor),Color.parseColor(RhShared.dark(context)?"#6f8af1":"#5274e8"),Color.parseColor(inkColor)}));button.setCompoundDrawablesRelative(drawable,null,null,null);button.setCompoundDrawablePadding(dp(7));}
    private Button gridButton(GridLayout grid,String title,String action,JSONObject args){Button b=button(title,action,args);b.setMaxLines(1);b.setHorizontallyScrolling(false);b.setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);grid.addView(b);return b;}
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
        FrameLayout.LayoutParams pp=(FrameLayout.LayoutParams)panel.getLayoutParams();int panelTop=topMargin+dp(56);
        if(pp.width!=width||pp.topMargin!=panelTop||pp.leftMargin!=left||pp.rightMargin!=right){pp.width=width;pp.topMargin=panelTop;pp.leftMargin=left;pp.rightMargin=right;panel.setLayoutParams(pp);}
        int nextHeight=Math.max(dp(48),Math.min(dp(440),root.getHeight()-panelTop-bottom-dp(192)));
        if(maxBodyHeight!=nextHeight){maxBodyHeight=nextHeight;scroll.requestLayout();}
        int nextColumns=width>=dp(480)?3:2;
        if(inputGrid.getColumnCount()!=nextColumns){columns=nextColumns;gridColumns(inputGrid,columns);}
        if(screenGrid.getColumnCount()!=2)gridColumns(screenGrid,2);
    }
    public boolean isExpanded(){return panel.getVisibility()==View.VISIBLE;}
    private boolean ownsVisible(View view){
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
        boolean changed=expanded!=isExpanded();View focused=root.findFocus();boolean local=ownsVisible(focused);
        if(changed&&expanded&&focused!=null&&!local)returnFocus=focused;
        panel.setVisibility(expanded?View.VISIBLE:View.GONE);handle.setText(expanded?"收起":"控制");updateBounds();
        icon(handle,expanded?R.drawable.rh_chevron_up:R.drawable.rh_chevron_down);handle.getCompoundDrawablesRelative()[0].setTint(Color.WHITE);
        if(!expanded)pendingMonitorFocus="";
        renderZoom();
        if(changed&&expanded)handle.requestFocusFromTouch();
        else if(changed&&local&&returnFocus!=null&&returnFocus.getVisibility()==View.VISIBLE&&returnFocus.isEnabled())returnFocus.requestFocusFromTouch();
    }
    public void setTab(String value){
        if(!tabs.containsKey(value))throw new IllegalArgumentException("tab must be input, displays or audio");boolean local=ownsVisible(root.findFocus());if(!tab.equals(value))pendingMonitorFocus="";tab=value;
        input.setVisibility(value.equals("input")?View.VISIBLE:View.GONE);screens.setVisibility(value.equals("displays")?View.VISIBLE:View.GONE);sound.setVisibility(value.equals("audio")?View.VISIBLE:View.GONE);
        for(Map.Entry<String,Button> entry:tabs.entrySet()){boolean selected=entry.getKey().equals(value);Button button=entry.getValue();button.setSelected(selected);RhUiStyle.button(button,Color.parseColor(selected?"#5274e8":mutedColor),Color.parseColor(mutedColor),surface(selected?selectedColor:surfaceColor,selected?selectedLine:null,8),surface(surfaceColor,lineColor,8),surface(selectedColor,"#5274e8",8));}
        scroll.scrollTo(0,0);
        renderZoom();
        if(local&&!ownsVisible(root.findFocus()))tabs.get(value).requestFocusFromTouch();
    }
    public void update(boolean trackpad,boolean panning,boolean audioEnabled,int level,JSONObject display,boolean busy,String failure){
        View focused=root.findFocus();boolean local=ownsVisible(focused);String focusId=monitorIds.get(focused);
        if(focusId==null&&focused==handle&&tab.equals("displays")&&!pendingMonitorFocus.isEmpty())focusId=pendingMonitorFocus;
        if(focusId==null)pendingMonitorFocus="";
        mode.setText(trackpad?"触控板":"直接点击");pan.setText(panning?"移动画面：开":"移动画面");audio.setText(audioEnabled?"声音：开":"声音：关");
        if(!trackingVolume){volume.setProgress(level);volumeLabel.setText("音量 "+level+"%");}refresh.setEnabled(!busy);refresh.setText(busy?"正在读取…":"刷新屏幕");
        for(int i=0;i<screenGrid.getChildCount();i++)screenGrid.getChildAt(i).setEnabled(!busy);
        JSONObject selected=display.optJSONObject("selected");String error=failure.isEmpty()?display.optString("error"):failure;
        displayStatus.setText(busy?"正在读取屏幕…":selected==null?(!error.isEmpty()?"屏幕列表未加载":"选择要查看的屏幕"):monitorName(selected)+" · "+selected.optInt("width")+"×"+selected.optInt("height"));
        displayStatus.setTextColor(Color.parseColor(mutedColor));
        JSONArray monitors=display.optJSONArray("displays");String fingerprint=String.valueOf(monitors)+":"+(selected==null?"":selected.optString("id"))+":"+busy;
        if(fingerprint.equals(monitorFingerprint)){retainLocalFocus(local);return;}monitorFingerprint=fingerprint;monitorList.removeAllViews();monitorAvailability.clear();monitorIds.clear();int count=monitors==null?0:monitors.length();Button focusRow=null;
        for(int i=0;i<count;i++){JSONObject monitor=monitors.optJSONObject(i);if(monitor==null)continue;
            String id=monitor.optString("id");boolean available=monitor.optBoolean("available"),chosen=selected!=null&&id.equals(selected.optString("id"));
            String title=monitorName(monitor)+" · "+monitor.optInt("width")+"×"+monitor.optInt("height")+(available?chosen?"  ✓":"":"  暂不可用");
            Button b=button(title,"stream.display.select",RhActions.obj("displayId",id));b.setEnabled(available&&!busy);monitorAvailability.put(b,available);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
            monitorIds.put(b,id);if(id.equals(focusId)&&available)focusRow=b;
            b.setSelected(chosen);RhUiStyle.button(b,Color.parseColor(inkColor),Color.parseColor(mutedColor),surface(chosen?selectedColor:surfaceColor,chosen?selectedLine:lineColor,8),surface(surfaceColor,lineColor,8),surface(selectedColor,"#5274e8",8));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.topMargin=i==0?0:dp(8);monitorList.addView(b,lp);
        }
        if(count==0){TextView empty=text(error.isEmpty()?"暂无屏幕信息，请刷新":"屏幕列表未加载",13);empty.setPadding(dp(4),dp(8),dp(4),dp(8));monitorList.addView(empty);}
        if(local&&isExpanded()&&tab.equals("displays")&&focusId!=null){
            if(!busy&&focusRow!=null){pendingMonitorFocus="";focusRow.requestFocusFromTouch();}
            else{pendingMonitorFocus=busy?focusId:"";handle.requestFocusFromTouch();}
        }
        retainLocalFocus(local);
    }
    public void setDeviceName(String name){sessionName.setText(name==null||name.isEmpty()?"远程设备":name);}
    private void enabled(View view,boolean value){view.setEnabled(value);view.setAlpha(value?1f:0.45f);}
    public void updateControls(boolean connected,boolean statistics,float scale,boolean busy,String group,String message,String failure){
        boolean local=ownsVisible(root.findFocus());stats.setText(statistics?"统计：开":"统计：关");stats.setSelected(statistics);RhUiStyle.button(stats,Color.parseColor(inkColor),Color.parseColor(mutedColor),surface(statistics?selectedColor:surfaceColor,statistics?selectedLine:lineColor,8),surface(surfaceColor,lineColor,8),surface(selectedColor,"#5274e8",8));mode.setSelected(mode.getText().toString().equals("触控板"));pan.setSelected(pan.getText().toString().equals("移动画面：开"));updateZoom(scale);
        for(int i=0;i<inputGrid.getChildCount();i++)enabled(inputGrid.getChildAt(i),connected&&!busy);
        for(int i=0;i<screenGrid.getChildCount();i++)enabled(screenGrid.getChildAt(i),connected&&!busy);
        for(Map.Entry<Button,Boolean> entry:monitorAvailability.entrySet())enabled(entry.getKey(),connected&&!busy&&entry.getValue());
        enabled(audio,connected&&!busy);enabled(volume,connected&&!busy);enabled(refresh,connected&&!busy);
        enabled(stats,connected&&!busy);updateFeedback(group,message,failure);
        enabled(scrollModeSwitch,connected&&!busy);
        retainLocalFocus(local);
    }
    public void updateFeedback(String group,String message,String failure){boolean visible=tab.equals(group);boolean repeatedVolume=group.equals("audio")&&failure.isEmpty()&&message.contentEquals(volumeLabel.getText());feedback.setText(visible&&!repeatedVolume?(failure.isEmpty()||!message.isEmpty()?message:"操作失败"):"");feedback.setContentDescription(visible?feedback.getText():"");feedback.setTextColor(failure.isEmpty()?Color.parseColor(mutedColor):failureColor);feedbackDetails.setVisibility(visible&&!failure.isEmpty()?View.VISIBLE:View.GONE);}
    public void updateScrollMode(boolean enabled){bindingScrollMode=true;try{scrollModeSwitch.setChecked(enabled);}finally{bindingScrollMode=false;}}
    public void updateZoom(float scale){zoomPercent=Math.round(scale*100);renderZoom();}
    private void renderZoom(){if(isExpanded()&&tab.equals("input")&&zoomPercent!=shownZoomPercent){shownZoomPercent=zoomPercent;zoomLabel.setText("缩放 "+zoomPercent+"%");}}
    private String monitorName(JSONObject monitor){String name=monitor.optString("name");return monitor.optBoolean("virtual")?"虚拟屏":name.isEmpty()?"物理屏幕":name;}
    public JSONObject layoutState(){Rect h=new Rect(),p=new Rect();handle.getHitRect(h);panel.getHitRect(p);return RhActions.obj("expanded",isExpanded(),"tab",tab,"columns",columns,"handle",RhActions.obj("x",h.left,"y",h.top,"width",h.width(),"height",h.height()),"panel",RhActions.obj("x",p.left,"y",p.top,"width",p.width(),"height",p.height()),"scrollable",scroll.canScrollVertically(1)||scroll.canScrollVertically(-1));}
    public void dispose(){disposed=true;for(View view:localKeys.values())view.setPressed(false);localKeys.clear();root.removeOnLayoutChangeListener(layoutListener);root.removeView(panel);root.removeView(handle);}
}
