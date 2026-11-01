package com.limelight.rhremote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.ArrayList;
import com.limelight.R;

/** Native enabled/focus states shared by the browser, forms and session panel. */
final class RhUiStyle {
    private RhUiStyle(){}
    static int failure(boolean dark){return android.graphics.Color.parseColor(dark?"#f28b9c":"#b04452");}
    static void buttonText(Button button,int normalText,int disabledText){button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{disabledText,normalText}));}
    static void button(Button button,int normalText,int disabledText,Drawable normal,Drawable disabled,Drawable focused){
        buttonText(button,normalText,disabledText);
        focusBackground(button,normal,disabled,focused);
    }
    static void focusBackground(View view,Drawable normal,Drawable disabled,Drawable focused){
        view.setDefaultFocusHighlightEnabled(false);
        StateListDrawable backgrounds=new StateListDrawable();
        backgrounds.addState(new int[]{-android.R.attr.state_enabled},disabled);
        backgrounds.addState(new int[]{android.R.attr.state_pressed},focused);
        backgrounds.addState(new int[]{android.R.attr.state_focused},focused);
        backgrounds.addState(new int[]{},normal);
        view.setBackground(backgrounds);
    }
    static int dialogTheme(Context context){
        return RhShared.dark(context)?R.style.RhDialogDark:R.style.RhDialogLight;
    }
    private static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    private static GradientDrawable dialogShape(Context c,int color,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(stroke!=0)d.setStroke(dp(c,1),stroke);return d;}
    /** Styles the existing native buttons; their listeners, roles and adaptive stacking stay native. */
    static void dialog(AlertDialog dialog,boolean destructive){
        Window window=dialog.getWindow();if(window==null)return;View decor=window.getDecorView();if(decor.getTag(R.id.rh_dialog_style)!=null)return;decor.setTag(R.id.rh_dialog_style,Boolean.TRUE);
        Context c=dialog.getContext();boolean dark=RhShared.dark(c);int surface=Color.parseColor(dark?"#1b2232":"#ffffff"),ink=Color.parseColor(dark?"#e1e8f6":"#34415b"),muted=Color.parseColor(dark?"#a4b1c8":"#69778e"),soft=Color.parseColor(dark?"#253452":"#edf2ff"),line=Color.parseColor(dark?"#303c53":"#e5eaf4"),accent=Color.parseColor(dark?"#6f8af1":"#4868db"),danger=failure(dark);
        window.setBackgroundDrawable(new InsetDrawable(dialogShape(c,surface,0,14),dp(c,16)));decor.setClipToOutline(true);decor.setDefaultFocusHighlightEnabled(false);
        ArrayList<Button> visible=new ArrayList<>();for(int type:new int[]{AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE}){Button b=dialog.getButton(type);if(b!=null&&b.getVisibility()==View.VISIBLE)visible.add(b);}
        Button positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE);LinearLayout bar=null;
        for(Button b:visible){boolean primary=b==positive||visible.size()==1;int fill=primary?(destructive?danger:accent):surface,foreground=primary?(dark?Color.parseColor("#151b29"):Color.WHITE):ink,focused=primary?androidx.core.graphics.ColorUtils.blendARGB(fill,dark?Color.WHITE:Color.BLACK,.1f):soft;
            b.setAllCaps(false);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setMinWidth(dp(c,72));b.setMinimumWidth(dp(c,72));b.setPadding(dp(c,16),dp(c,8),dp(c,16),dp(c,8));b.setSingleLine(true);b.setStateListAnimator(null);b.setElevation(0);
            button(b,foreground,muted,dialogShape(c,fill,primary?0:line,8),dialogShape(c,soft,line,8),dialogShape(c,focused,accent,8));
            // The native action ScrollView can inherit child focus and paint a grey overlay.
            for(View p=b.getParent() instanceof View?(View)b.getParent():null;p!=null&&p!=decor;p=p.getParent() instanceof View?(View)p.getParent():null)p.setDefaultFocusHighlightEnabled(false);
            if(b.getParent() instanceof LinearLayout)bar=(LinearLayout)b.getParent();
        }
        if(bar!=null){LinearLayout actions=bar;actions.setBackgroundColor(Color.TRANSPARENT);View.OnLayoutChangeListener gaps=(v,l,t,r,b,ol,ot,or,ob)->dialogGaps(actions,c);actions.addOnLayoutChangeListener(gaps);actions.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){actions.removeOnLayoutChangeListener(gaps);v.removeOnAttachStateChangeListener(this);}});dialogGaps(actions,c);}
    }
    private static void dialogGaps(LinearLayout bar,Context c){
        boolean stacked=bar.getOrientation()==LinearLayout.VERTICAL;int index=0;for(int i=0;i<bar.getChildCount();i++){View child=bar.getChildAt(i);if(!(child instanceof Button)||child.getVisibility()!=View.VISIBLE)continue;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)child.getLayoutParams();int start=!stacked&&index>0?dp(c,8):0,top=stacked&&index>0?dp(c,8):0;if(p.getMarginStart()!=start||p.topMargin!=top){p.setMarginStart(start);p.topMargin=top;child.setLayoutParams(p);}index++;}
    }
}
