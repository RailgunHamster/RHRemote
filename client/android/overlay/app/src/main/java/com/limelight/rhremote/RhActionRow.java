package com.limelight.rhremote;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Button groups wrap as a unit instead of squeezing touch targets against the screen edge. */
public final class RhActionRow extends ViewGroup {
    private final int gap;

    public RhActionRow(Context context) {
        super(context);
        gap=Math.round(8*context.getResources().getDisplayMetrics().density);
    }

    @Override protected LayoutParams generateDefaultLayoutParams() { return new MarginLayoutParams(-2,-2); }
    @Override public LayoutParams generateLayoutParams(AttributeSet attributes) { return new MarginLayoutParams(getContext(),attributes); }
    @Override protected LayoutParams generateLayoutParams(LayoutParams source) { return new MarginLayoutParams(source); }
    @Override protected boolean checkLayoutParams(LayoutParams params) { return params instanceof MarginLayoutParams; }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int available=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
        int x=0,y=0,rowHeight=0,maxWidth=0;
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            LayoutParams params=child.getLayoutParams();
            child.measure(MeasureSpec.makeMeasureSpec(available,params.width==LayoutParams.MATCH_PARENT?MeasureSpec.EXACTLY:MeasureSpec.AT_MOST),
                    getChildMeasureSpec(heightSpec,getPaddingTop()+getPaddingBottom(),params.height));
            int width=child.getMeasuredWidth(),height=child.getMeasuredHeight();
            if(x>0&&x+gap+width>available) { maxWidth=Math.max(maxWidth,x);y+=rowHeight+gap;x=0;rowHeight=0; }
            x+=(x==0?0:gap)+width;rowHeight=Math.max(rowHeight,height);
        }
        maxWidth=Math.max(maxWidth,x);
        setMeasuredDimension(resolveSize(maxWidth+getPaddingLeft()+getPaddingRight(),widthSpec),
                resolveSize(y+rowHeight+getPaddingTop()+getPaddingBottom(),heightSpec));
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        int available=getWidth()-getPaddingLeft()-getPaddingRight(),x=0,y=getPaddingTop(),rowHeight=0;
        boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            int width=child.getMeasuredWidth(),height=child.getMeasuredHeight();
            if(x>0&&x+gap+width>available) { y+=rowHeight+gap;x=0;rowHeight=0; }
            if(x>0)x+=gap;
            int start=rtl?getWidth()-getPaddingRight()-x-width:getPaddingLeft()+x;
            child.layout(start,y,start+width,y+height);
            x+=width;rowHeight=Math.max(rowHeight,height);
        }
    }
}
