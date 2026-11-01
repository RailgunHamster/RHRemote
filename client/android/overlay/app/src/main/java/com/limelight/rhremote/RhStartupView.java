package com.limelight.rhremote;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Loading feedback while the existing visible GL surface obtains renderer information. */
public final class RhStartupView extends FrameLayout {
    public RhStartupView(Context context,View graphicsProbe){
        super(context);
        // Keep the probe attached, visible and full-size so its original callback runs.
        addView(graphicsProbe,new FrameLayout.LayoutParams(-1,-1));
        boolean dark=RhShared.dark(context);
        TextView status=new TextView(context);
        status.setText("正在加载…");status.setTextSize(14);status.setGravity(Gravity.CENTER);
        status.setTextColor(dark?0xffa4b1c8:0xff69778e);
        status.setBackgroundColor(dark?0xff151b29:0xfff6f7fa);
        addView(status,new FrameLayout.LayoutParams(-1,-1));
        setTag("rhremote-startup");
    }
}
