package com.limelight.rhremote;

import android.app.AlertDialog;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;

/** Local Home confirmation keys. The owning controller still resolves every decision. */
final class RhDialogKeys {
    private RhDialogKeys(){}
    static void install(AlertDialog dialog,int safeChoice,Runnable cancel){
        View decor=dialog.getWindow().getDecorView();ViewTreeObserver observer=decor.getViewTreeObserver();
        Button safe=dialog.getButton(safeChoice);boolean[] pressed={false},initialFocus={false};
        Runnable focusSafe=()->{if(!initialFocus[0]&&dialog.isShowing()&&decor.hasWindowFocus()&&safe!=null&&safe.isEnabled()&&safe.getVisibility()==View.VISIBLE)initialFocus[0]=safe.requestFocusFromTouch();};
        // The native window can select its message ScrollView during first attachment.
        // Establish our initial choice once that focus handoff has completed.
        ViewTreeObserver.OnWindowFocusChangeListener focus=hasFocus->{if(!hasFocus)pressed[0]=false;else if(!initialFocus[0])decor.post(focusSafe);};observer.addOnWindowFocusChangeListener(focus);
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){}public void onViewDetachedFromWindow(View view){pressed[0]=false;if(observer.isAlive())observer.removeOnWindowFocusChangeListener(focus);view.removeOnAttachStateChangeListener(this);}});
        dialog.setOnKeyListener((owner,key,event)->{
            if(event.getAction()==KeyEvent.ACTION_DOWN)initialFocus[0]=true;
            if(key!=KeyEvent.KEYCODE_ESCAPE)return false;
            if(!dialog.isShowing()){pressed[0]=false;return true;}
            if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()==0)pressed[0]=!event.isCanceled();}
            else if(event.getAction()==KeyEvent.ACTION_UP){boolean accepted=pressed[0]&&!event.isCanceled();pressed[0]=false;if(accepted)cancel.run();}
            return true;
        });
        if(safe!=null&&safe.isEnabled()&&safe.getVisibility()==View.VISIBLE)safe.requestFocusFromTouch();decor.post(focusSafe);
    }
}
