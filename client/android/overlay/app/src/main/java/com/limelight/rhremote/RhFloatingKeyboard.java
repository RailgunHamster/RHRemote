package com.limelight.rhremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import java.util.*;
import org.json.*;

/** Complete physical keyboard, outer IME/floating toggles, and draggable custom shortcut chips. */
final class RhFloatingKeyboard {
    interface Actions {void send(String id,JSONObject args);}
    static final class Pos {
        final float x,y;final long order;
        Pos(float x,float y,long order){this.x=x;this.y=y;this.order=order;}
    }
    private final FrameLayout layer,table,simpleLayer;private final ViewGroup parent;
    private final ScrollView vertical;private final HorizontalScrollView horizontal;
    private final Button toggle,imeToggle;
    private final LinearLayout bankBar;private final Button simpleTab,fullModeButton;private final List<View> bankTargets=new ArrayList<>();
    private static final Set<String> SIMPLE=new HashSet<>(Arrays.asList("escape","tab","backspace","delete","enter","arrow-up","arrow-left","arrow-down","arrow-right","meta-left","ctrl-left","alt-left","shift-left"));
    private final Map<String,Button> keys=new LinkedHashMap<>();
    private final Map<String,Button> pinnedButtons=new LinkedHashMap<>();
    private final Map<Button,String> draggableIds=new IdentityHashMap<>();
    private final List<String> pinnedShortcuts=new ArrayList<>();
    private final Map<String,Pos> positions=new LinkedHashMap<>();
    private final JSONArray catalog,shortcutCatalog;
    private final View.OnLayoutChangeListener layoutListener;
    private final Actions actions;
    private String presentation="physical";
    private boolean enabled,ready,obscured,ime,disposed,buttonVisible,receivedInsets,simple;
    private Insets lastInsets=Insets.NONE;
    private int safeLeft,safeRight,safeTop,safeBottom;
    private Button activeTouchTarget;private String activePosKey;
    private float downX,downY;private int startLeft,startTop;private boolean dragging;

    RhFloatingKeyboard(Context context,ViewGroup parent,Actions actions){
        this.parent=parent;this.actions=actions;
        SharedPreferences prefs=context.getSharedPreferences("rhremote.global",0);
        buttonVisible=!prefs.getBoolean("hideKeyboardButton",false);
        layer=new FrameLayout(context){
            @Override public boolean dispatchTouchEvent(MotionEvent event){
                if(handleDraggableTouch(event))return true;
                return super.dispatchTouchEvent(event);
            }
        };
        layer.setTag("floating-keyboard");layer.setClickable(false);layer.setFocusable(false);
        simpleLayer=new FrameLayout(context);simpleLayer.setTag("simple-floating-keyboard");table=new FrameLayout(context);
        try{
            catalog=RhKeyboard.catalog(context);
            shortcutCatalog=RhShortcuts.catalog(context);
        }catch(Exception error){throw new IllegalStateException("Keyboard catalog unavailable",error);}
        for(int i=0;i<catalog.length();i++){
            JSONObject key=catalog.optJSONObject(i);String id=key.optString("id");Button button=button(context,key.optString("label"),key.optString("description"));button.setTag("floating-key:"+id);
            button.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.keyboard.press",simple&&id.equals("meta-left")?RhActions.obj("key",id,"tap",true):RhActions.obj("key",id));});keys.put(id,button);table.addView(button,new FrameLayout.LayoutParams(dp(40),dp(40)));
            if(SIMPLE.contains(id))draggableIds.put(button,id);
        }
        horizontal=new HorizontalScrollView(context);horizontal.setTag("floating-keyboard-horizontal");horizontal.setFillViewport(false);horizontal.setHorizontalScrollBarEnabled(true);horizontal.addView(table,new HorizontalScrollView.LayoutParams(-2,-2));
        vertical=new ScrollView(context);vertical.setTag("floating-keyboard-viewport");vertical.setFillViewport(false);vertical.setVerticalScrollBarEnabled(true);vertical.addView(horizontal,new ScrollView.LayoutParams(-1,-2));layer.addView(vertical);
        layer.addView(simpleLayer,new FrameLayout.LayoutParams(-1,-1));
        simpleTab=button(context,"⇧ Tab","Shift+Tab");simpleTab.setTag("floating-key:shift-tab");simpleTab.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.shortcut",RhActions.obj("shortcut","shift-tab"));});simpleLayer.addView(simpleTab);draggableIds.put(simpleTab,"shift-tab");
        fullModeButton=button(context,"完整键盘","切换为完整键盘");fullModeButton.setTag("floating-keyboard-layout:full");fullModeButton.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.keyboard.layout.set",RhActions.obj("layout","full"));});simpleLayer.addView(fullModeButton);draggableIds.put(fullModeButton,"keyboard-layout");
        bankBar=new LinearLayout(context);bankBar.setTag("floating-keyboard-banks");layer.addView(bankBar);
        Button simpleModeButton=button(context,"简化键盘","切换为简化键盘");simpleModeButton.setTag("floating-keyboard-layout:simple");bankBar.addView(simpleModeButton,new LinearLayout.LayoutParams(dp(104),-1));simpleModeButton.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.keyboard.layout.set",RhActions.obj("layout","simple"));});
        for(String[] bank:new String[][]{{"主键区","escape"},{"导航 / 数字","num-5"}}){Button b=button(context,bank[0],bank[0]);b.setTag("floating-keyboard-bank:"+bank[1]);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),0,dp(2),dp(4));bankBar.addView(b,p);bankTargets.add(b);b.setOnClickListener(view->{if(canUse(view)&&enabled&&!ime)actions.send("stream.keyboard.reveal",RhActions.obj("key",bank[1]));});}
        imeToggle=button(context,"手机键盘","打开手机键盘");imeToggle.setTag("floating-ime-toggle");imeToggle.setOnClickListener(view->{if(canUse(view)&&buttonVisible)actions.send("stream.keyboard.toggle",RhActions.obj());});layer.addView(imeToggle,new FrameLayout.LayoutParams(dp(72),dp(48)));draggableIds.put(imeToggle,"ime-toggle");
        toggle=button(context,"键盘","展开悬浮键盘");toggle.setTag("floating-keyboard-toggle");toggle.setOnClickListener(view->{if(canUse(view)&&buttonVisible)actions.send("stream.keyboard.floating.set",RhActions.obj("enabled",!enabled));});layer.addView(toggle,new FrameLayout.LayoutParams(dp(72),dp(48)));draggableIds.put(toggle,"keyboard-toggle");
        loadPinnedShortcuts(prefs);
        loadPositions(prefs);
        rebuildPinnedButtons();
        parent.addView(layer,new ViewGroup.LayoutParams(-1,-1));layoutListener=(v,l,t,r,b,ol,ot,or,ob)->layout();parent.addOnLayoutChangeListener(layoutListener);
        ViewCompat.setOnApplyWindowInsetsListener(layer,(view,insets)->{receivedInsets=true;layout(safe(insets));return insets;});
        ViewCompat.setWindowInsetsAnimationCallback(layer,new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){@Override public WindowInsetsCompat onProgress(WindowInsetsCompat insets,List<WindowInsetsAnimationCompat> running){receivedInsets=true;layout(safe(insets));return insets;}});
        ViewCompat.requestApplyInsets(layer);simple="simple".equals(prefs.getString("keyboardLayout","full"));configureLayout();refresh();
    }

    private void loadPinnedShortcuts(SharedPreferences prefs){
        pinnedShortcuts.clear();
        String raw=prefs.getString("pinnedShortcuts","[]");
        try{
            JSONArray arr=new JSONArray(raw);
            for(int i=0;i<arr.length()&&pinnedShortcuts.size()<34;i++){
                String id=arr.optString(i,"");
                if(findShortcut(id)!=null&&!pinnedShortcuts.contains(id))pinnedShortcuts.add(id);
            }
        }catch(JSONException ignored){}
    }
    private void savePinnedShortcuts(){
        layer.getContext().getSharedPreferences("rhremote.global",0).edit().putString("pinnedShortcuts",new JSONArray(pinnedShortcuts).toString()).apply();
    }
    private JSONObject findShortcut(String id){
        if(id==null||id.isEmpty())return null;
        for(int i=0;i<shortcutCatalog.length();i++){
            JSONObject item=shortcutCatalog.optJSONObject(i);
            if(item!=null&&id.equals(item.optString("id")))return item;
        }
        return null;
    }
    private static String shortLabel(String fullLabel){
        int idx=fullLabel.indexOf(" · ");
        return idx>0?fullLabel.substring(0,idx):fullLabel;
    }
    private void rebuildPinnedButtons(){
        Iterator<Map.Entry<String,Button>> it=pinnedButtons.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String,Button> entry=it.next();
            if(!pinnedShortcuts.contains(entry.getKey())){
                draggableIds.remove(entry.getValue());
                layer.removeView(entry.getValue());
                it.remove();
            }
        }
        for(String id:pinnedShortcuts){
            if(pinnedButtons.containsKey(id))continue;
            JSONObject item=findShortcut(id);if(item==null)continue;
            String full=item.optString("label",id);
            Button b=button(layer.getContext(),shortLabel(full),full);
            b.setTag("floating-shortcut:"+id);
            b.setOnClickListener(view->{if(canUse(view)&&buttonVisible)actions.send("stream.shortcut",RhActions.obj("shortcut",id));});
            layer.addView(b,new FrameLayout.LayoutParams(dp(76),dp(40)));
            pinnedButtons.put(id,b);
            draggableIds.put(b,"shortcut:"+id);
        }
    }
    List<String> pinnedShortcuts(){return new ArrayList<>(pinnedShortcuts);}
    boolean isShortcutPinned(String id){return pinnedShortcuts.contains(id);}
    boolean setShortcutPinned(String id,Boolean pinned){
        if(findShortcut(id)==null)throw new IllegalArgumentException("unknown remote shortcut");
        boolean current=pinnedShortcuts.contains(id);
        boolean next=pinned!=null?pinned:!current;
        if(next&&!current)pinnedShortcuts.add(id);
        else if(!next&&current)pinnedShortcuts.remove(id);
        savePinnedShortcuts();
        rebuildPinnedButtons();
        refresh();
        return next;
    }

    private void loadPositions(SharedPreferences prefs){
        positions.clear();
        String raw=prefs.getString("overlayButtonPositions","{}");
        try{
            JSONObject obj=new JSONObject(raw);
            Iterator<String> keysIt=obj.keys();
            while(keysIt.hasNext()&&positions.size()<128){
                String k=keysIt.next();
                JSONObject p=obj.optJSONObject(k);
                if(p==null)continue;
                double x=p.optDouble("x",-1),y=p.optDouble("y",-1);
                long order=p.optLong("order",0);
                if(Double.isFinite(x)&&Double.isFinite(y)&&x>=0&&x<=1&&y>=0&&y<=1){
                    positions.put(k,new Pos((float)x,(float)y,Math.max(1L,order)));
                }
            }
        }catch(JSONException ignored){}
    }
    private void savePositions(){
        layer.getContext().getSharedPreferences("rhremote.global",0).edit().putString("overlayButtonPositions",positionsJson().toString()).apply();
    }
    JSONObject positionsJson(){
        JSONObject obj=new JSONObject();
        try{
            for(Map.Entry<String,Pos> entry:positions.entrySet()){
                Pos p=entry.getValue();
                obj.put(entry.getKey(),RhActions.obj("x",p.x,"y",p.y,"order",p.order));
            }
        }catch(JSONException ignored){}
        return obj;
    }
    private long nextOrder(){
        long max=0;
        for(Pos p:positions.values())if(p.order>max)max=p.order;
        return max+1;
    }
    void moveButton(String id,float rx,float ry){
        if(id==null||id.isEmpty()||id.length()>64||!Float.isFinite(rx)||!Float.isFinite(ry)||rx<0f||rx>1f||ry<0f||ry>1f)
            throw new IllegalArgumentException("invalid overlay coordinates");
        long order=nextOrder();
        positions.put(id,new Pos(rx,ry,order));
        savePositions();
        refresh();
    }
    void resetPositions(){
        positions.clear();
        for(Button b:draggableIds.keySet())b.setTranslationZ(0f);
        savePositions();
        refresh();
    }

    private List<Button> activeDraggableButtons(){
        List<Button> list=new ArrayList<>();
        boolean fullKeyboard=enabled&&!ime&&!simple;
        if(simple&&enabled&&!ime){
            for(String id:SIMPLE){Button b=keys.get(id);if(b!=null&&b.getVisibility()==View.VISIBLE)list.add(b);}
            if(simpleTab.getVisibility()==View.VISIBLE)list.add(simpleTab);
            if(fullModeButton.getVisibility()==View.VISIBLE)list.add(fullModeButton);
        }
        if(buttonVisible){
            if(!fullKeyboard){
                for(Button b:pinnedButtons.values())if(b.getVisibility()==View.VISIBLE)list.add(b);
            }
            if(imeToggle.getVisibility()==View.VISIBLE)list.add(imeToggle);
            if(toggle.getVisibility()==View.VISIBLE)list.add(toggle);
        }
        list.sort((a,b)->{
            long oa=orderOf(draggableIds.get(a)),ob=orderOf(draggableIds.get(b));
            return Long.compare(oa,ob);
        });
        return list;
    }
    private long orderOf(String posKey){
        if(posKey==null)return 0;
        Pos p=positions.get(posKey);
        return p==null?0:p.order;
    }
    private boolean handleDraggableTouch(MotionEvent event){
        if(disposed||obscured)return false;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            activeTouchTarget=null;activePosKey=null;dragging=false;
            List<Button> candidates=activeDraggableButtons();
            int x=Math.round(event.getX()),y=Math.round(event.getY());
            for(int i=candidates.size()-1;i>=0;i--){
                Button b=candidates.get(i);
                if(!canUse(b))continue;
                Rect r=bounds(b);
                if(r.contains(x,y)){
                    activeTouchTarget=b;
                    activePosKey=draggableIds.get(b);
                    break;
                }
            }
            if(activeTouchTarget==null)return false;
            downX=event.getX();downY=event.getY();
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)activeTouchTarget.getLayoutParams();
            startLeft=lp.leftMargin;startTop=lp.topMargin;
            activeTouchTarget.setPressed(true);
            return true;
        }
        if(activeTouchTarget==null)return false;
        if(action==MotionEvent.ACTION_MOVE){
            float dx=event.getX()-downX,dy=event.getY()-downY;
            if(!dragging&&Math.hypot(dx,dy)>dp(6)){
                dragging=true;
                activeTouchTarget.setTranslationZ(nextOrder());
                activeTouchTarget.bringToFront();
            }
            if(dragging){
                int w=activeTouchTarget.getWidth(),h=activeTouchTarget.getHeight();
                int nx=Math.max(safeLeft,Math.min(Math.max(safeLeft,safeRight-w),Math.round(startLeft+dx)));
                int ny=Math.max(safeTop,Math.min(Math.max(safeTop,safeBottom-h),Math.round(startTop+dy)));
                place(activeTouchTarget,nx,ny,w,h);
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP){
            Button target=activeTouchTarget;String posKey=activePosKey;
            activeTouchTarget=null;activePosKey=null;
            target.setPressed(false);
            if(dragging){
                dragging=false;
                if(posKey!=null){
                    FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)target.getLayoutParams();
                    float spanX=Math.max(1f,(safeRight-safeLeft)-target.getWidth());
                    float spanY=Math.max(1f,(safeBottom-safeTop)-target.getHeight());
                    float rx=Math.max(0f,Math.min(1f,(lp.leftMargin-safeLeft)/spanX));
                    float ry=Math.max(0f,Math.min(1f,(lp.topMargin-safeTop)/spanY));
                    moveButton(posKey,rx,ry);
                }
            }else{
                Rect r=bounds(target);
                if(r.contains(Math.round(event.getX()),Math.round(event.getY())))target.performClick();
            }
            return true;
        }
        if(action==MotionEvent.ACTION_CANCEL){
            activeTouchTarget.setPressed(false);
            activeTouchTarget=null;activePosKey=null;dragging=false;
            return true;
        }
        return true;
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
    private void configureLayout(){for(Map.Entry<String,Button> entry:keys.entrySet()){Button key=entry.getValue();FrameLayout target=simple&&SIMPLE.contains(entry.getKey())?simpleLayer:table;if(key.getParent()!=target){((ViewGroup)key.getParent()).removeView(key);target.addView(key,new FrameLayout.LayoutParams(dp(40),dp(40)));}key.setVisibility(View.VISIBLE);key.setTranslationZ(simple?orderOf(entry.getKey()):0f);}}
    void setLayout(String name){if(!name.equals("full")&&!name.equals("simple"))throw new IllegalArgumentException("layout must be full or simple");simple=name.equals("simple");configureLayout();layer.getContext().getSharedPreferences("rhremote.global",0).edit().putString("keyboardLayout",name).apply();refresh();}
    void setEnabled(boolean value){enabled=value;refresh();}
    void setButtonVisible(boolean value){buttonVisible=value;layer.getContext().getSharedPreferences("rhremote.global",0).edit().putBoolean("hideKeyboardButton",!value).apply();refresh();}
    void modifiers(Collection<String> selected){for(int i=0;i<catalog.length();i++){JSONObject key=catalog.optJSONObject(i);keys.get(key.optString("id")).setSelected(!key.optString("modifier").isEmpty()&&selected.contains(key.optString("modifier")));}}
    void update(boolean ready,boolean obscured){update(ready,obscured,false);}
    void update(boolean ready,boolean obscured,boolean ime){this.ready=ready;this.obscured=obscured;this.ime=ime;refresh();}
    private void refresh(){
        if(disposed)return;
        boolean fullKeyboard=enabled&&!ime&&!simple;
        layer.setVisibility(!obscured&&(enabled||buttonVisible)?View.VISIBLE:View.GONE);
        vertical.setVisibility(fullKeyboard?View.VISIBLE:View.GONE);
        simpleLayer.setVisibility(enabled&&!ime&&simple?View.VISIBLE:View.GONE);
        fullModeButton.setEnabled(ready);simpleTab.setEnabled(ready);
        for(int i=0;i<bankBar.getChildCount();i++)bankBar.getChildAt(i).setEnabled(ready);
        toggle.setVisibility(buttonVisible?View.VISIBLE:View.GONE);
        toggle.setText(enabled?"收起":"键盘");toggle.setContentDescription(enabled?"收起悬浮键盘":"展开悬浮键盘");
        toggle.setSelected(enabled);toggle.setEnabled(ready);toggle.setAlpha(ready?1f:.4f);
        imeToggle.setVisibility(buttonVisible?View.VISIBLE:View.GONE);
        imeToggle.setText(ime?"收起键盘":"手机键盘");imeToggle.setContentDescription(ime?"关闭手机键盘":"打开手机键盘");
        imeToggle.setSelected(ime);imeToggle.setEnabled(ready);imeToggle.setAlpha(ready?1f:.4f);
        boolean showPinned=buttonVisible&&!fullKeyboard;
        for(Button b:pinnedButtons.values()){
            b.setVisibility(showPinned?View.VISIBLE:View.GONE);
            b.setEnabled(ready);b.setAlpha(ready?1f:.4f);
        }
        for(Button key:keys.values()){key.setEnabled(ready);key.setAlpha(ready?1f:.4f);}
        layout();
    }
    private void place(View view,int x,int y,int width,int height){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)view.getLayoutParams();if(p==null||p.leftMargin!=x||p.topMargin!=y||p.width!=width||p.height!=height){p=new FrameLayout.LayoutParams(width,height);p.leftMargin=x;p.topMargin=y;view.setLayoutParams(p);if(view.getWidth()==width&&view.getHeight()==height)view.layout(x,y,x+width,y+height);}}
    private void placeDraggable(View view,String posKey,int defX,int defY,int width,int height,boolean allowCustom){
        if(allowCustom&&posKey!=null&&positions.containsKey(posKey)){
            Pos pos=positions.get(posKey);
            int spanX=Math.max(1,(safeRight-safeLeft)-width);
            int spanY=Math.max(1,(safeBottom-safeTop)-height);
            int x=Math.max(safeLeft,Math.min(Math.max(safeLeft,safeRight-width),safeLeft+Math.round(pos.x*spanX)));
            int y=Math.max(safeTop,Math.min(Math.max(safeTop,safeBottom-height),safeTop+Math.round(pos.y*spanY)));
            place(view,x,y,width,height);
            view.setTranslationZ(pos.order);
        }else{
            place(view,defX,defY,width,height);
            view.setTranslationZ(0f);
        }
    }
    private static Insets safe(WindowInsetsCompat insets){return insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());}
    private void layout(){WindowInsetsCompat insets=receivedInsets?null:ViewCompat.getRootWindowInsets(parent);layout(insets==null?lastInsets:safe(insets));}
    private void layout(Insets safe){
        lastInsets=safe;if(disposed||parent.getWidth()==0||parent.getHeight()==0)return;
        int left=safe.left+dp(12),right=parent.getWidth()-safe.right-dp(12),top=safe.top+dp(12),bottom=parent.getHeight()-safe.bottom-dp(12),width=Math.max(1,right-left);
        safeLeft=left;safeRight=right;safeTop=Math.max(safe.top,Math.min(top,bottom-dp(48)));safeBottom=Math.max(safeTop+dp(48),bottom);
        boolean fullKeyboard=enabled&&!ime&&!simple;
        int toggleY=Math.max(safe.top,Math.min(top,bottom-dp(48)));
        int toggleW=Math.min(width,dp(72));
        placeDraggable(toggle,"keyboard-toggle",Math.max(left,right-dp(72)),toggleY,toggleW,dp(48),!fullKeyboard);
        int imeW=Math.min(width,dp(72));
        int imeDefX=Math.max(left,right-dp(72)-dp(6)-imeW);
        placeDraggable(imeToggle,"ime-toggle",imeDefX,toggleY,imeW,dp(48),!fullKeyboard);
        if(!fullKeyboard){
            int chipW=Math.min(width,dp(76)),chipH=dp(40),gap=dp(6);
            int colRight=right;
            int curY=toggleY+dp(48)+gap;
            for(Map.Entry<String,Button> entry:pinnedButtons.entrySet()){
                if(curY+chipH>bottom&&colRight-chipW-gap>=left+chipW){
                    colRight-=chipW+gap;
                    curY=toggleY;
                }
                int defX=Math.max(left,colRight-chipW);
                int defY=Math.max(safeTop,Math.min(curY,bottom-chipH));
                placeDraggable(entry.getValue(),"shortcut:"+entry.getKey(),defX,defY,chipW,chipH,true);
                curY+=chipH+gap;
            }
        }
        int available=Math.max(1,bottom-(top+dp(64)));float font=layer.getResources().getConfiguration().fontScale;
        int minUnit=dp(font>1.2f?48:36),padding=dp(16);boolean banks=23*minUnit+padding>width;
        int unit=Math.max(minUnit,Math.min(dp(48),(width-padding)/(banks?15:23)));
        RhKeyboardGeometry geometry=RhKeyboardGeometry.fit(catalog,Math.max(1,(width-padding)/(float)unit));presentation=geometry.presentation;
        int bankHeight=dp(44);bankBar.setVisibility(!simple&&enabled&&!ime?View.VISIBLE:View.GONE);for(View bank:bankTargets)bank.setVisibility(banks?View.VISIBLE:View.GONE);
        int row=Math.max(dp(font>1.2f?48:40),Math.min(dp(48),(int)((available-bankHeight-padding)/geometry.rows)));
        int tableWidth=Math.min(width,(int)Math.ceil(geometry.columns*unit)+padding),tableHeight=(int)Math.ceil(geometry.rows*row)+padding;
        if(!simple){
            for(int i=0;i<catalog.length();i++){JSONObject key=catalog.optJSONObject(i);RhKeyboardGeometry.Cell cell=geometry.keys.get(key.optString("id"));Button b=keys.get(key.optString("id"));b.setTranslationZ(0f);place(b,dp(8)+Math.round(cell.x*unit),dp(8)+Math.round(cell.y*row),Math.round(cell.width*unit)-dp(4),Math.round(cell.height*row)-dp(4));}
        }
        horizontal.scrollTo(0,0);
        ViewGroup.LayoutParams tp=table.getLayoutParams();if(tp.width!=tableWidth||tp.height!=tableHeight){tp.width=tableWidth;tp.height=tableHeight;table.setLayoutParams(tp);}
        int height=Math.max(1,Math.min(available-bankHeight,tableHeight));place(vertical,left,bottom-height,width,height);place(bankBar,left,bottom-height-bankHeight,width,bankHeight);if(simple)layoutSimple(left,right,top,bottom);
    }
    private void simplePlace(String id,int x,int y,int w,int h,boolean visible){Button key=keys.get(id);key.setVisibility(visible?View.VISIBLE:View.GONE);placeDraggable(key,id,x,y,w,h,true);}
    private void layoutSimple(int left,int right,int top,int bottom){
        int gap=dp(6),k=Math.min(dp(48),(right-left-4*gap)/6),wide=2*k+gap;boolean roomy=bottom-top>=dp(320);int tab=roomy?Math.min(top+Math.max(dp(128),(bottom-top)*26/100),bottom-3*k-3*gap):top+k+dp(8);
        simplePlace("escape",left,top,k,k,true);simplePlace("meta-left",left+k+gap,top,k,k,true);
        placeDraggable(fullModeButton,"keyboard-layout",left,roomy?top+k+dp(16):bottom-dp(44),Math.min(dp(104),wide),dp(44),true);
        simplePlace("tab",left,tab,wide,k,true);placeDraggable(simpleTab,"shift-tab",left,tab+k+gap,wide,k,true);
        simplePlace("backspace",right-wide,top+dp(64),wide,k,roomy);simplePlace("delete",right-wide,top+dp(64)+k+gap,wide,k,roomy);simplePlace("enter",right-wide,top+dp(64)+2*(k+gap),wide,k,roomy);
        for(int i=0;i<3;i++)simplePlace(new String[]{"ctrl-left","alt-left","shift-left"}[i],left+i*(k+gap),bottom-k,k,k,roomy);
        int pad=right-3*k-2*gap;simplePlace("arrow-up",pad+k+gap,bottom-2*k-gap,k,k,true);simplePlace("arrow-left",pad,bottom-k,k,k,true);simplePlace("arrow-down",pad+k+gap,bottom-k,k,k,true);simplePlace("arrow-right",pad+2*(k+gap),bottom-k,k,k,true);
    }
    void reveal(String id){if(simple){if(!SIMPLE.contains(id))throw new IllegalStateException("Switch to the full keyboard to reveal this key");return;}Button key=keys.get(id);if(key==null)throw new IllegalArgumentException("unknown keyboard key");horizontal.scrollTo(Math.max(0,key.getLeft()-(horizontal.getWidth()-key.getWidth())/2),0);vertical.scrollTo(0,Math.max(0,key.getTop()-(vertical.getHeight()-key.getHeight())/2));}
    private Rect bounds(View view){Rect r=new Rect();view.getDrawingRect(r);layer.offsetDescendantRectToMyCoords(view,r);return r;}
    JSONObject state(){JSONArray positionsArr=new JSONArray();boolean visible=layer.getVisibility()==View.VISIBLE&&(simple?simpleLayer:vertical).getVisibility()==View.VISIBLE;
        for(Map.Entry<String,Button> entry:keys.entrySet()){Button key=entry.getValue();Rect r=bounds(key),clip=bounds(vertical);boolean showing=visible&&key.getVisibility()==View.VISIBLE&&(simple?SIMPLE.contains(entry.getKey()):Rect.intersects(r,clip));positionsArr.put(RhActions.obj("id",entry.getKey(),"visible",showing,"selected",key.isSelected(),"x",r.left,"y",r.top,"width",r.width(),"height",r.height()));}
        Rect r=bounds(toggle),ir=bounds(imeToggle);return RhActions.obj("enabled",enabled,"layout",layoutName(),"visible",visible,"ready",ready,"buttonVisible",buttonVisible,"backgroundAlpha",80,"contrast","dual-outline","backgroundSampling",false,"presentation",presentation,"horizontalScrollable",table.getWidth()>horizontal.getWidth(),"scrollable",table.getHeight()>vertical.getHeight(),"toggle",RhActions.obj("visible",layer.getVisibility()==View.VISIBLE&&toggle.getVisibility()==View.VISIBLE,"x",r.left,"y",r.top,"width",r.width(),"height",r.height()),"imeToggle",RhActions.obj("visible",layer.getVisibility()==View.VISIBLE&&imeToggle.getVisibility()==View.VISIBLE,"x",ir.left,"y",ir.top,"width",ir.width(),"height",ir.height()),"pinnedShortcuts",new JSONArray(pinnedShortcuts),"buttonPositions",positionsJson(),"keys",positionsArr);
    }
    void dispose(){if(disposed)return;disposed=true;activeTouchTarget=null;activePosKey=null;ViewCompat.setOnApplyWindowInsetsListener(layer,null);ViewCompat.setWindowInsetsAnimationCallback(layer,null);parent.removeOnLayoutChangeListener(layoutListener);parent.removeView(layer);}
}
