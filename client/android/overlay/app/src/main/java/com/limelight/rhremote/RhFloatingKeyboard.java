package com.limelight.rhremote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import java.util.*;
import org.json.*;

/** Touch-only function keys. The container never consumes a touch outside a key. */
final class RhFloatingKeyboard {
    interface Actions {void send(String id,JSONObject args);}
    private final FrameLayout layer;private final ViewGroup parent;private final Map<String,Button> keys=new LinkedHashMap<>();
    private final View.OnLayoutChangeListener layoutListener;
    private boolean enabled,ready,obscured,disposed;
    RhFloatingKeyboard(Context context,ViewGroup parent,Actions actions){
        this.parent=parent;layer=new FrameLayout(context);layer.setTag("floating-keyboard");layer.setClickable(false);layer.setFocusable(false);layer.setClipChildren(true);
        for(String[] key:new String[][]{{"escape","Esc","Esc"},{"tab","Tab","Tab"},{"shift-tab","⇧ Tab","Shift+Tab"},{"backspace","⌫","Backspace"},{"delete","Del","Delete"},{"enter","Enter","Enter"},{"arrow-up","↑","向上"},{"arrow-left","←","向左"},{"arrow-down","↓","向下"},{"arrow-right","→","向右"}}){
            Button button=new Button(context);button.setText(key[1]);button.setContentDescription(key[2]);button.setTag("floating-key:"+key[0]);button.setAllCaps(false);button.setTextSize(16);button.setMaxLines(1);button.setHorizontallyScrolling(false);button.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));button.setGravity(Gravity.CENTER);button.setPadding(dp(4),0,dp(4),0);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setStateListAnimator(null);button.setElevation(0);button.setFocusable(false);button.setFocusableInTouchMode(false);button.setTextColor(Color.parseColor(RhShared.dark(context)?"#eef3ff":"#263653"));
            StateListDrawable background=new StateListDrawable();background.addState(new int[]{android.R.attr.state_pressed},background(RhShared.dark(context)?"#e630405e":"#e6dce6ff"));background.addState(new int[]{},background(RhShared.dark(context)?"#a6202a3e":"#a6f6f8ff"));button.setBackground(background);
            button.setOnClickListener(view->{if(!disposed&&enabled&&ready&&!obscured&&view.isEnabled()&&RhViewOrigin.visibleWithin(parent,view))actions.send("stream.shortcut",RhActions.obj("shortcut",key[0]));});keys.put(key[0],button);layer.addView(button,new FrameLayout.LayoutParams(dp(48),dp(48)));
        }
        parent.addView(layer,new ViewGroup.LayoutParams(-1,-1));layoutListener=(v,l,t,r,b,ol,ot,or,ob)->layout();parent.addOnLayoutChangeListener(layoutListener);layer.setVisibility(View.GONE);
        // Edge-to-edge windows keep their size when system bars become visible.
        // Observe only our own layer and preserve dispatch to the rest of Game.
        ViewCompat.setOnApplyWindowInsetsListener(layer,(view,insets)->{layout(insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()));return insets;});
        ViewCompat.requestApplyInsets(layer);
    }
    private int dp(float value){return Math.round(value*layer.getResources().getDisplayMetrics().density);}
    private GradientDrawable background(String color){GradientDrawable value=new GradientDrawable();value.setColor(Color.parseColor(color));value.setCornerRadius(dp(10));value.setStroke(dp(1),Color.parseColor(RhShared.dark(layer.getContext())?"#737a8eae":"#7397a8cd"));return value;}
    boolean enabled(){return enabled;}
    void setEnabled(boolean value){enabled=value;refresh();}
    void update(boolean ready,boolean obscured){this.ready=ready;this.obscured=obscured;refresh();}
    private void refresh(){if(disposed)return;layer.setVisibility(enabled&&!obscured?View.VISIBLE:View.GONE);for(Button key:keys.values()){key.setEnabled(ready);key.setAlpha(ready?1f:.4f);}layout();}
    private void place(String id,int x,int y,int width,int height,boolean show){Button button=keys.get(id);button.setVisibility(show?View.VISIBLE:View.GONE);FrameLayout.LayoutParams old=(FrameLayout.LayoutParams)button.getLayoutParams();if(old.leftMargin!=x||old.topMargin!=y||old.width!=width||old.height!=height){FrameLayout.LayoutParams next=new FrameLayout.LayoutParams(width,height);next.leftMargin=x;next.topMargin=y;button.setLayoutParams(next);}}
    private void layout(){
        WindowInsetsCompat window=ViewCompat.getRootWindowInsets(parent);layout(window==null?Insets.NONE:window.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()));
    }
    private void layout(Insets safe){
        if(disposed||parent.getWidth()==0||parent.getHeight()==0)return;
        int left=safe.left+dp(16),right=parent.getWidth()-safe.right-dp(16),top=safe.top+dp(16),bottom=parent.getHeight()-safe.bottom-dp(16),k=dp(48),gap=dp(8),wide=dp(88),w=right-left,h=bottom-top;
        for(Map.Entry<String,Button> entry:keys.entrySet()){Button key=entry.getValue();int textWidth=(int)Math.ceil(key.getPaint().measureText(key.getText().toString()))+key.getCompoundPaddingLeft()+key.getCompoundPaddingRight();k=Math.max(k,(int)Math.ceil(key.getPaint().getFontSpacing())+key.getCompoundPaddingTop()+key.getCompoundPaddingBottom());if(entry.getKey().equals("escape")||entry.getKey().startsWith("arrow-"))k=Math.max(k,textWidth);else wide=Math.max(wide,textWidth);}
        boolean fits=w>=Math.max(dp(288),Math.max(3*k+2*gap,2*wide+gap))&&h>=Math.max(dp(208),3*k+gap),full=h>=Math.max(dp(288),5*k+3*gap);int tab=top+Math.max(dp(64),(int)(h*.26));tab=Math.min(tab,bottom-2*k-gap);
        place("escape",left,top,k,k,fits);place("tab",left,tab,wide,k,fits);place("shift-tab",left,tab+k+gap,wide,k,fits);
        place("backspace",right-wide,top,wide,k,fits&&full);place("delete",right-wide,top+k+gap,wide,k,fits&&full);place("enter",right-wide,Math.max(top+2*(k+gap),top+(h-k)/2),wide,k,fits&&full);
        int pad=right-3*k-2*gap;place("arrow-up",pad+k+gap,bottom-2*k-gap,k,k,fits);place("arrow-left",pad,bottom-k,k,k,fits);place("arrow-down",pad+k+gap,bottom-k,k,k,fits);place("arrow-right",pad+2*(k+gap),bottom-k,k,k,fits);
    }
    JSONObject state(){JSONArray positions=new JSONArray();boolean anyVisible=false;for(Map.Entry<String,Button> entry:keys.entrySet()){Button key=entry.getValue();Rect r=new Rect();key.getHitRect(r);boolean visible=layer.getVisibility()==View.VISIBLE&&key.getVisibility()==View.VISIBLE;anyVisible|=visible;positions.put(RhActions.obj("id",entry.getKey(),"visible",visible,"x",r.left,"y",r.top,"width",r.width(),"height",r.height()));}return RhActions.obj("enabled",enabled,"visible",anyVisible,"ready",ready,"backgroundAlpha",166,"keys",positions);}
    void dispose(){if(disposed)return;disposed=true;ViewCompat.setOnApplyWindowInsetsListener(layer,null);parent.removeOnLayoutChangeListener(layoutListener);parent.removeView(layer);}
}
