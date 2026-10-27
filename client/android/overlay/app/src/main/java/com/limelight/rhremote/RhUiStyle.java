package com.limelight.rhremote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;
import com.limelight.R;

/** Native enabled/focus states shared by the browser, forms and session panel. */
final class RhUiStyle {
    private RhUiStyle(){}
    static void button(Button button,int normalText,int disabledText,Drawable normal,Drawable disabled,Drawable focused){
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{disabledText,normalText}));
        StateListDrawable backgrounds=new StateListDrawable();
        backgrounds.addState(new int[]{-android.R.attr.state_enabled},disabled);
        backgrounds.addState(new int[]{android.R.attr.state_pressed},focused);
        backgrounds.addState(new int[]{android.R.attr.state_focused},focused);
        backgrounds.addState(new int[]{},normal);
        button.setBackground(backgrounds);
    }
    static int dialogTheme(Context context){
        return RhShared.dark(context)?R.style.RhDialogDark:R.style.RhDialogLight;
    }
}
