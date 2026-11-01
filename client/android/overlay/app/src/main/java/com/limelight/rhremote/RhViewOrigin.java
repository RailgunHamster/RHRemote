package com.limelight.rhremote;

import android.view.View;

/** Detached widgets can still receive queued IME or selection callbacks. */
final class RhViewOrigin {
    private RhViewOrigin(){}
    static boolean visibleWithin(View root,View source){
        for(View current=source;current!=null;current=current.getParent() instanceof View?(View)current.getParent():null){
            if(current.getVisibility()!=View.VISIBLE)return false;
            if(current==root)return true;
        }
        return false;
    }
}
