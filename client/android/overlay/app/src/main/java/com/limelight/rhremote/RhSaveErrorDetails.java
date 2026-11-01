package com.limelight.rhremote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Complete error text in a bounded native viewport, independent of the fixed actions. */
final class RhSaveErrorDetails extends ScrollView {
    private final View budget;
    private final TextView message;
    private String previous="";
    private int measuredLimit=-1;
    private final OnLayoutChangeListener budgetChanged=(v,l,t,r,b,ol,ot,or,ob)->{
        if(isShown()&&limit(b-t)!=measuredLimit)requestLayout();
    };

    RhSaveErrorDetails(Context context,View budget,TextView message,int accent){
        super(context);this.budget=budget;this.message=message;
        setFillViewport(false);setDefaultFocusHighlightEnabled(false);setScrollBarStyle(SCROLLBARS_INSIDE_INSET);
        message.setFocusable(false);addView(message,new ViewGroup.LayoutParams(-1,-2));
        GradientDrawable normal=new GradientDrawable(),disabled=new GradientDrawable(),focused=new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);disabled.setColor(Color.TRANSPARENT);focused.setColor(Color.TRANSPARENT);focused.setStroke(dp(1),accent);focused.setCornerRadius(dp(4));
        RhUiStyle.focusBackground(this,normal,disabled,focused);
        setContentDescription("保存错误详情");
        message.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(Editable value){refresh();}});
        refresh();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private int limit(int height){return dp(height>0&&height<dp(480)?36:80);}
    private void refresh(){
        String value=message.getText().toString();int visible=value.isEmpty()?GONE:VISIBLE;
        message.setVisibility(visible);setVisibility(visible);
        if(!value.equals(previous)){previous=value;scrollTo(0,0);}
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        measuredLimit=limit(budget.getHeight());int mode=MeasureSpec.getMode(heightSpec);
        int height=mode==MeasureSpec.UNSPECIFIED?measuredLimit:Math.min(measuredLimit,MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(height,MeasureSpec.AT_MOST));
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();budget.addOnLayoutChangeListener(budgetChanged);}
    @Override protected void onDetachedFromWindow(){budget.removeOnLayoutChangeListener(budgetChanged);super.onDetachedFromWindow();}
    @Override public boolean executeKeyEvent(KeyEvent event){
        if(event.getAction()==KeyEvent.ACTION_DOWN&&!event.isCtrlPressed()&&!event.isAltPressed()&&!event.isMetaPressed()&&(canScrollVertically(-1)||canScrollVertically(1))){
            switch(event.getKeyCode()){
                case KeyEvent.KEYCODE_DPAD_UP:arrowScroll(FOCUS_UP);return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:arrowScroll(FOCUS_DOWN);return true;
                case KeyEvent.KEYCODE_PAGE_UP:pageScroll(FOCUS_UP);return true;
                case KeyEvent.KEYCODE_PAGE_DOWN:pageScroll(FOCUS_DOWN);return true;
                case KeyEvent.KEYCODE_MOVE_HOME:fullScroll(FOCUS_UP);return true;
                case KeyEvent.KEYCODE_MOVE_END:fullScroll(FOCUS_DOWN);return true;
            }
        }
        return super.executeKeyEvent(event);
    }
}
