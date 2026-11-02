package com.limelight.rhremote;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import java.util.*;
import org.json.*;

/** Complete physical keyboard. Only the bottom keyboard and top-right toggle take input. */
final class RhFloatingKeyboard {
    interface Actions {void send(String id,JSONObject args);}
    private final FrameLayout layer,table,simpleLayer;private final ViewGroup parent;
    private final ScrollView vertical;private final HorizontalScrollView horizontal;private final Button toggle;
    private final LinearLayout bankBar;private final Button simpleTab,fullModeButton;private final List<View> bankTargets=new ArrayList<>();
    private static final Set<String> SIMPLE=new HashSet<>(Arrays.asList("escape","tab","backspace","delete","enter","arrow-up","arrow-left","arrow-down","arrow-right","meta-left","ctrl-left","alt-left","shift-left"));
    private final Map<String,Button> keys=new LinkedHashMap<>();private final JSONArray catalog;
    private final View.OnLayoutChangeListener layoutListener;
    private boolean enabled,ready,obscured,ime,disposed,buttonVisible,receivedInsets,simple;private Insets lastInsets=Insets.NONE;
    RhFloatingKeyboard(Context context,ViewGroup parent,Actions actions){
        this.parent=parent;buttonVisible=!context.getSharedPreferences("rhremote.global",0).getBoolean("hideKeyboardButton",false);
        layer=new FrameLayout(context);layer.setTag("floating-keyboard");layer.setClickable(false);layer.setFocusable(false);
        simpleLayer=new FrameLayout(context);simpleLayer.setTag("simple-floating-keyboard");table=new FrameLayout(context);
        try{catalog=RhKeyboard.catalog(context);}catch(Exception error){throw new IllegalStateException("Keyboard catalog unavailable",error);}
        for(int i=0;i<catalog.length();i++){
            JSONObject key=catalog.optJSONObject(i);String id=key.optString("id");Button button=button(context,key.optString("label"),key.optString("description"));button.setTag("floating-key:"+id);
            button.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.keyboard.press",simple&&id.equals("meta-left")?RhActions.obj("key",id,"tap",true):RhActions.obj("key",id));});keys.put(id,button);table.addView(button,new FrameLayout.LayoutParams(dp(40),dp(40)));
        }
        horizontal=new HorizontalScrollView(context);horizontal.setTag("floating-keyboard-horizontal");horizontal.setFillViewport(false);horizontal.setHorizontalScrollBarEnabled(true);horizontal.addView(table,new HorizontalScrollView.LayoutParams(-2,-2));
        vertical=new ScrollView(context);vertical.setTag("floating-keyboard-viewport");vertical.setFillViewport(false);vertical.setVerticalScrollBarEnabled(true);vertical.addView(horizontal,new ScrollView.LayoutParams(-1,-2));layer.addView(vertical);
        layer.addView(simpleLayer,new FrameLayout.LayoutParams(-1,-1));
        simpleTab=button(context,"⇧ Tab","Shift+Tab");simpleTab.setTag("floating-key:shift-tab");simpleTab.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.shortcut",RhActions.obj("shortcut","shift-tab"));});simpleLayer.addView(simpleTab);
        fullModeButton=button(context,"完整键盘","切换为完整键盘");fullModeButton.setTag("floating-keyboard-layout:full");fullModeButton.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.keyboard.layout.set",RhActions.obj("layout","full"));});simpleLayer.addView(fullModeButton);
        bankBar=new LinearLayout(context);bankBar.setTag("floating-keyboard-banks");layer.addView(bankBar);
        Button simpleModeButton=button(context,"简化键盘","切换为简化键盘");simpleModeButton.setTag("floating-keyboard-layout:simple");bankBar.addView(simpleModeButton,new LinearLayout.LayoutParams(dp(104),-1));simpleModeButton.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.keyboard.layout.set",RhActions.obj("layout","simple"));});
        for(String[] bank:new String[][]{{"主键区","escape"},{"导航 / 数字","num-5"}}){Button b=button(context,bank[0],bank[0]);b.setTag("floating-keyboard-bank:"+bank[1]);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),0,dp(2),dp(4));bankBar.addView(b,p);bankTargets.add(b);b.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.keyboard.reveal",RhActions.obj("key",bank[1]));});}
        toggle=button(context,"键盘","展开悬浮键盘");toggle.setTag("floating-keyboard-toggle");toggle.setOnClickListener(view->{if(canUse(view)&&buttonVisible)actions.send("stream.keyboard.floating.set",RhActions.obj("enabled",!enabled));});layer.addView(toggle,new FrameLayout.LayoutParams(dp(72),dp(48)));
        parent.addView(layer,new ViewGroup.LayoutParams(-1,-1));layoutListener=(v,l,t,r,b,ol,ot,or,ob)->layout();parent.addOnLayoutChangeListener(layoutListener);
        ViewCompat.setOnApplyWindowInsetsListener(layer,(view,insets)->{receivedInsets=true;layout(safe(insets));return insets;});
        ViewCompat.setWindowInsetsAnimationCallback(layer,new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){@Override public WindowInsetsCompat onProgress(WindowInsetsCompat insets,List<WindowInsetsAnimationCompat> running){receivedInsets=true;layout(safe(insets));return insets;}});
        ViewCompat.requestApplyInsets(layer);simple="simple".equals(context.getSharedPreferences("rhremote.global",0).getString("keyboardLayout","full"));configureLayout();refresh();
    }
    private boolean canUse(View view){return !disposed&&ready&&!obscured&&view.isEnabled()&&RhViewOrigin.visibleWithin(parent,view)&&view.getLocalVisibleRect(new Rect());}
    private int dp(float value){return Math.round(value*layer.getResources().getDisplayMetrics().density);}
    // No sheet behind the keys: gaps expose the unmodified remote image. Each
    // translucent key has opposing edges so one remains visible on either tone.
    private Drawable background(String color){return new Drawable(){
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private int opacity=255;
        private void color(int value){paint.setColor(value);paint.setAlpha(Color.alpha(value)*opacity/255);}
        @Override public void draw(Canvas canvas){RectF rect=new RectF(getBounds());float pixel=layer.getResources().getDisplayMetrics().density;rect.inset(pixel*.5f,pixel*.5f);paint.setStyle(Paint.Style.FILL);color(Color.parseColor(color));canvas.drawRoundRect(rect,dp(6),dp(6),paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(pixel);color(0xe6101724);canvas.drawRoundRect(rect,dp(6),dp(6),paint);rect.inset(pixel,pixel);color(0xb8ffffff);canvas.drawRoundRect(rect,dp(5),dp(5),paint);}
        @Override public void setAlpha(int value){opacity=value;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter value){paint.setColorFilter(value);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    };}
    private final class ContrastButton extends Button{
        ContrastButton(Context context){super(context);}
        @Override protected void onDraw(Canvas canvas){
            android.text.Layout text=getLayout();if(text!=null){
                android.text.TextPaint paint=getPaint();int color=paint.getColor();Paint.Style style=paint.getStyle();float stroke=paint.getStrokeWidth();
                int save=canvas.save();canvas.translate(getCompoundPaddingLeft()-getScrollX(),getExtendedPaddingTop()+(getHeight()-getExtendedPaddingTop()-getExtendedPaddingBottom()-text.getHeight())/2f-getScrollY());
                paint.setColor(0xff101724);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeJoin(Paint.Join.ROUND);text.draw(canvas);canvas.restoreToCount(save);
                paint.setColor(color);paint.setStyle(style);paint.setStrokeWidth(stroke);
            }super.onDraw(canvas);
        }
    }
    private Button button(Context context,String label,String description){
        Button button=new ContrastButton(context);button.setText(label);button.setContentDescription(description);button.setAllCaps(false);button.setMaxLines(1);button.setHorizontallyScrolling(false);button.setGravity(Gravity.CENTER);button.setPadding(dp(2),0,dp(2),0);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setStateListAnimator(null);button.setElevation(0);button.setFocusable(false);button.setFocusableInTouchMode(false);button.setAutoSizeTextTypeUniformWithConfiguration(8,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);button.setTypeface(button.getTypeface(),Typeface.BOLD);button.setTextColor(Color.WHITE);
        StateListDrawable background=new StateListDrawable();background.addState(new int[]{android.R.attr.state_selected},background("#b0294c87"));background.addState(new int[]{android.R.attr.state_pressed},background("#c0294c87"));background.addState(new int[]{},background("#5018202e"));button.setBackground(background);return button;
    }
    boolean enabled(){return enabled;}boolean buttonVisible(){return buttonVisible;}String layoutName(){return simple?"simple":"full";}
    private void configureLayout(){for(Map.Entry<String,Button> entry:keys.entrySet()){Button key=entry.getValue();FrameLayout target=simple&&SIMPLE.contains(entry.getKey())?simpleLayer:table;if(key.getParent()!=target){((ViewGroup)key.getParent()).removeView(key);target.addView(key,new FrameLayout.LayoutParams(dp(40),dp(40)));}key.setVisibility(View.VISIBLE);}}
    void setLayout(String name){if(!name.equals("full")&&!name.equals("simple"))throw new IllegalArgumentException("layout must be full or simple");simple=name.equals("simple");configureLayout();layer.getContext().getSharedPreferences("rhremote.global",0).edit().putString("keyboardLayout",name).apply();refresh();}
    void setEnabled(boolean value){enabled=value;refresh();}
    void setButtonVisible(boolean value){buttonVisible=value;layer.getContext().getSharedPreferences("rhremote.global",0).edit().putBoolean("hideKeyboardButton",!value).apply();refresh();}
    void modifiers(Collection<String> selected){for(int i=0;i<catalog.length();i++){JSONObject key=catalog.optJSONObject(i);keys.get(key.optString("id")).setSelected(!key.optString("modifier").isEmpty()&&selected.contains(key.optString("modifier")));}}
    void update(boolean ready,boolean obscured){update(ready,obscured,false);}
    void update(boolean ready,boolean obscured,boolean ime){this.ready=ready;this.obscured=obscured;this.ime=ime;refresh();}
    private void refresh(){if(disposed)return;layer.setVisibility(!obscured&&(enabled||buttonVisible)?View.VISIBLE:View.GONE);vertical.setVisibility(enabled&&!ime&&!simple?View.VISIBLE:View.GONE);simpleLayer.setVisibility(enabled&&!ime&&simple?View.VISIBLE:View.GONE);fullModeButton.setEnabled(ready);simpleTab.setEnabled(ready);for(int i=0;i<bankBar.getChildCount();i++)bankBar.getChildAt(i).setEnabled(ready);toggle.setVisibility(buttonVisible?View.VISIBLE:View.GONE);toggle.setText(enabled?"收起":"键盘");toggle.setContentDescription(enabled?"收起悬浮键盘":"展开悬浮键盘");toggle.setSelected(enabled);toggle.setEnabled(ready);for(Button key:keys.values()){key.setEnabled(ready);key.setAlpha(ready?1f:.4f);}layout();}
    private void place(View view,int x,int y,int width,int height){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)view.getLayoutParams();if(p==null||p.leftMargin!=x||p.topMargin!=y||p.width!=width||p.height!=height){p=new FrameLayout.LayoutParams(width,height);p.leftMargin=x;p.topMargin=y;view.setLayoutParams(p);}}
    private static Insets safe(WindowInsetsCompat insets){return insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());}
    private void layout(){WindowInsetsCompat insets=receivedInsets?null:ViewCompat.getRootWindowInsets(parent);layout(insets==null?lastInsets:safe(insets));}
    private void layout(Insets safe){
        lastInsets=safe;if(disposed||parent.getWidth()==0||parent.getHeight()==0)return;
        int left=safe.left+dp(12),right=parent.getWidth()-safe.right-dp(12),top=safe.top+dp(12),bottom=parent.getHeight()-safe.bottom-dp(12),width=Math.max(1,right-left);
        place(toggle,Math.max(left,right-dp(72)),Math.max(safe.top,Math.min(top,bottom-dp(48))),Math.min(width,dp(72)),dp(48));
        int available=Math.max(1,bottom-(top+dp(64)));float font=layer.getResources().getConfiguration().fontScale;
        int unit=Math.max(dp(font>1.2f?48:36),Math.min(dp(48),(width-dp(16))/23));
        boolean banks=unit*23+dp(16)>width;int bankHeight=dp(44);
        bankBar.setVisibility(!simple&&enabled&&!ime?View.VISIBLE:View.GONE);for(View bank:bankTargets)bank.setVisibility(banks?View.VISIBLE:View.GONE);
        int row=Math.max(dp(font>1.2f?48:40),Math.min(dp(48),(available-bankHeight-dp(16))/6));int tableWidth=unit*23+dp(16),tableHeight=row*6+dp(16);
        for(int i=0;i<catalog.length();i++){JSONObject key=catalog.optJSONObject(i);place(keys.get(key.optString("id")),dp(8)+Math.round((float)key.optDouble("x")*unit),dp(8)+Math.round((float)key.optDouble("row")*row),Math.round((float)key.optDouble("width")*unit)-dp(4),Math.round((float)key.optDouble("height")*row)-dp(4));}
        ViewGroup.LayoutParams tp=table.getLayoutParams();if(tp.width!=tableWidth||tp.height!=tableHeight){tp.width=tableWidth;tp.height=tableHeight;table.setLayoutParams(tp);}
        int height=Math.max(1,Math.min(available-bankHeight,tableHeight));place(vertical,left,bottom-height,width,height);place(bankBar,left,bottom-height-bankHeight,width,bankHeight);if(simple)layoutSimple(left,right,top,bottom);
    }
    private void simplePlace(String id,int x,int y,int w,int h,boolean visible){Button key=keys.get(id);key.setVisibility(visible?View.VISIBLE:View.GONE);place(key,x,y,w,h);}
    private void layoutSimple(int left,int right,int top,int bottom){
        int gap=dp(6),k=Math.min(dp(48),(right-left-4*gap)/6),wide=2*k+gap;boolean roomy=bottom-top>=dp(320);int tab=roomy?Math.min(top+Math.max(dp(128),(bottom-top)*26/100),bottom-3*k-3*gap):top+k+dp(8);
        simplePlace("escape",left,top,k,k,true);simplePlace("meta-left",left+k+gap,top,k,k,true);
        place(fullModeButton,left,roomy?top+k+dp(16):bottom-dp(44),Math.min(dp(104),wide),dp(44));
        simplePlace("tab",left,tab,wide,k,true);place(simpleTab,left,tab+k+gap,wide,k);
        simplePlace("backspace",right-wide,top+dp(64),wide,k,roomy);simplePlace("delete",right-wide,top+dp(64)+k+gap,wide,k,roomy);simplePlace("enter",right-wide,top+dp(64)+2*(k+gap),wide,k,roomy);
        for(int i=0;i<3;i++)simplePlace(new String[]{"ctrl-left","alt-left","shift-left"}[i],left+i*(k+gap),bottom-k,k,k,roomy);
        int pad=right-3*k-2*gap;simplePlace("arrow-up",pad+k+gap,bottom-2*k-gap,k,k,true);simplePlace("arrow-left",pad,bottom-k,k,k,true);simplePlace("arrow-down",pad+k+gap,bottom-k,k,k,true);simplePlace("arrow-right",pad+2*(k+gap),bottom-k,k,k,true);
    }
    void reveal(String id){if(simple){if(!SIMPLE.contains(id))throw new IllegalStateException("Switch to the full keyboard to reveal this key");return;}Button key=keys.get(id);if(key==null)throw new IllegalArgumentException("unknown keyboard key");horizontal.scrollTo(Math.max(0,key.getLeft()-(horizontal.getWidth()-key.getWidth())/2),0);vertical.scrollTo(0,Math.max(0,key.getTop()-(vertical.getHeight()-key.getHeight())/2));}
    private Rect bounds(View view){Rect r=new Rect();view.getDrawingRect(r);layer.offsetDescendantRectToMyCoords(view,r);return r;}
    JSONObject state(){JSONArray positions=new JSONArray();boolean visible=layer.getVisibility()==View.VISIBLE&&(simple?simpleLayer:vertical).getVisibility()==View.VISIBLE;
        for(Map.Entry<String,Button> entry:keys.entrySet()){Button key=entry.getValue();Rect r=bounds(key),clip=bounds(vertical);boolean showing=visible&&key.getVisibility()==View.VISIBLE&&(simple?SIMPLE.contains(entry.getKey()):Rect.intersects(r,clip));positions.put(RhActions.obj("id",entry.getKey(),"visible",showing,"selected",key.isSelected(),"x",r.left,"y",r.top,"width",r.width(),"height",r.height()));}
        Rect r=bounds(toggle);return RhActions.obj("enabled",enabled,"layout",layoutName(),"visible",visible,"ready",ready,"buttonVisible",buttonVisible,"backgroundAlpha",80,"contrast","dual-outline","backgroundSampling",false,"scrollable",table.getWidth()>horizontal.getWidth()||table.getHeight()>vertical.getHeight(),"toggle",RhActions.obj("visible",layer.getVisibility()==View.VISIBLE&&toggle.getVisibility()==View.VISIBLE,"x",r.left,"y",r.top,"width",r.width(),"height",r.height()),"keys",positions);
    }
    void dispose(){if(disposed)return;disposed=true;ViewCompat.setOnApplyWindowInsetsListener(layer,null);ViewCompat.setWindowInsetsAnimationCallback(layer,null);parent.removeOnLayoutChangeListener(layoutListener);parent.removeView(layer);}
}
