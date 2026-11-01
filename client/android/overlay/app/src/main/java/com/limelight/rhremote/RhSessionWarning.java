package com.limelight.rhremote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.limelight.R;
import org.json.JSONObject;

/** Presents the upstream connection status; never changes its thresholds or remote input. */
public final class RhSessionWarning extends TextView {
    private boolean initialized,disposed;
    private int requestedVisibility;
    private String covered="",suppressed="inactive",lastMessage="";
    private boolean lastActive;
    private Runnable changed;
    private View anchor;
    private ViewGroup root;
    private Insets safe=Insets.NONE;
    private final View.OnLayoutChangeListener layoutListener=(v,l,t,r,b,ol,ot,or,ob)->refresh();
    public RhSessionWarning(Context context){this(context,null);}
    public RhSessionWarning(Context context,AttributeSet attributes){
        super(context,attributes);requestedVisibility=super.getVisibility();
        setTextSize(13);setGravity(Gravity.CENTER);setPadding(dp(12),dp(8),dp(12),dp(8));setMaxLines(Integer.MAX_VALUE);setEllipsize(null);setHorizontallyScrolling(false);
        setClickable(false);setLongClickable(false);setFocusable(false);setFocusableInTouchMode(false);setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        boolean dark=RhShared.dark(context);setTextColor(Color.parseColor(dark?"#e1e8f6":"#34415b"));GradientDrawable shape=new GradientDrawable();shape.setColor(Color.parseColor(dark?"#242e41":"#f3f5fa"));shape.setCornerRadius(dp(10));shape.setStroke(dp(1),Color.parseColor(dark?"#3e4d6b":"#d7e1fc"));setBackground(shape);
        initialized=true;ViewCompat.setOnApplyWindowInsetsListener(this,(view,insets)->{safe=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());refresh();return insets;});refresh();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    void bind(View control){
        if(disposed)return;anchor=control;ViewGroup next=control.getParent() instanceof ViewGroup?(ViewGroup)control.getParent():null;
        if(root!=next){if(root!=null)root.removeOnLayoutChangeListener(layoutListener);root=next;if(root!=null)root.addOnLayoutChangeListener(layoutListener);}refresh();ViewCompat.requestApplyInsets(this);
    }
    void setChanged(Runnable callback){changed=callback;}
    boolean active(){return !disposed&&requestedVisibility==View.VISIBLE&&!getText().toString().isEmpty();}
    String message(){return active()?getText().toString():"";}
    String summary(){return message().equals(getResources().getString(R.string.rh_connection_slow))?getResources().getString(R.string.rh_connection_slow_summary):message();}
    void cover(String reason){if(!covered.equals(reason)){covered=reason;refresh();}}
    @Override public void setVisibility(int visibility){
        if(!initialized){super.setVisibility(visibility);return;}
        requestedVisibility=visibility;refresh();notifyChanged();
    }
    @Override protected void onTextChanged(CharSequence text,int start,int before,int count){super.onTextChanged(text,start,before,count);if(initialized){refresh();notifyChanged();}}
    private void notifyChanged(){boolean active=active();String message=message();if(active==lastActive&&message.equals(lastMessage))return;lastActive=active;lastMessage=message;if(changed!=null)changed.run();}
    private void refresh(){
        if(!initialized)return;String reason=disposed?"disposed":!active()?"inactive":covered;
        if(root!=null){
            WindowInsetsCompat current=ViewCompat.getRootWindowInsets(root);if(current!=null)safe=current.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            int top=safe.top+dp(64);if(anchor!=null){Rect area=new Rect();anchor.getHitRect(area);top=Math.max(top,area.bottom+dp(8));}
            // FrameLayout applies the full margin difference when centering a child.
            // Symmetric safe margins keep the banner centered and inside either cutout.
            int side=Math.max(safe.left,safe.right)+dp(16);
            if(getLayoutParams() instanceof FrameLayout.LayoutParams){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)getLayoutParams();if(p.gravity!=(Gravity.TOP|Gravity.CENTER_HORIZONTAL)||p.topMargin!=top||p.leftMargin!=side||p.rightMargin!=side){p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.topMargin=top;p.leftMargin=side;p.rightMargin=side;setLayoutParams(p);}}
            int width=Math.min(dp(560),root.getWidth()-2*side)-getCompoundPaddingLeft()-getCompoundPaddingRight();
            if(reason.isEmpty()&&root.getHeight()>0&&width>0){Layout text=StaticLayout.Builder.obtain(getText(),0,getText().length(),getPaint(),width).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(getIncludeFontPadding()).build();int height=text.getHeight()+getCompoundPaddingTop()+getCompoundPaddingBottom();if(top+height>root.getHeight()-safe.bottom-dp(16))reason="viewport-small";}
        }
        suppressed=reason;super.setVisibility(reason.isEmpty()?View.VISIBLE:View.GONE);
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getMode(widthSpec)==MeasureSpec.UNSPECIFIED?dp(560):Math.min(dp(560),MeasureSpec.getSize(widthSpec));super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST),heightSpec);}
    JSONObject state(){Rect area=new Rect();getHitRect(area);return RhActions.obj("active",active(),"visible",getVisibility()==View.VISIBLE,"shown",isShown(),"attached",isAttachedToWindow(),"message",message(),"summary",summary(),"covered",covered,"suppressed",suppressed,"x",area.left,"y",area.top,"width",area.width(),"height",area.height());}
    void dispose(){if(disposed)return;disposed=true;changed=null;if(root!=null)root.removeOnLayoutChangeListener(layoutListener);ViewCompat.setOnApplyWindowInsetsListener(this,null);super.setVisibility(View.GONE);}
}
