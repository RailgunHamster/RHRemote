package com.limelight.rhremote;

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.util.function.BooleanSupplier;

/** One completion for the current editor; Enter down/up are one action on every Android API. */
final class RhEditorSubmit {
    private RhEditorSubmit(){}
    static Runnable install(EditText editor,BooleanSupplier current,Runnable submit){
        long[] downTime={-1};int[] device={-1},code={-1};
        Runnable clear=()->{downTime[0]=-1;device[0]=-1;code[0]=-1;};
        View.OnFocusChangeListener previous=editor.getOnFocusChangeListener();
        editor.setOnFocusChangeListener((view,focused)->{if(!focused)clear.run();if(previous!=null)previous.onFocusChange(view,focused);});
        editor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){}public void onViewDetachedFromWindow(View view){clear.run();}});
        BooleanSupplier available=()->editor.isEnabled()&&editor.hasFocus()&&current.getAsBoolean();
        editor.setOnEditorActionListener((view,action,event)->{
            if(event==null){clear.run();if(action!=EditorInfo.IME_ACTION_DONE)return action!=EditorInfo.IME_ACTION_NEXT&&action!=EditorInfo.IME_ACTION_PREVIOUS;if(available.getAsBoolean())submit.run();return true;}
            int key=event.getKeyCode();
            if((key!=KeyEvent.KEYCODE_ENTER&&key!=KeyEvent.KEYCODE_NUMPAD_ENTER)||(action!=EditorInfo.IME_NULL&&action!=EditorInfo.IME_ACTION_DONE))return false;
            if(!available.getAsBoolean()||!event.hasNoModifiers()||event.isCanceled()){clear.run();return true;}
            if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()==0){downTime[0]=event.getDownTime();device[0]=event.getDeviceId();code[0]=key;}}
            else if(event.getAction()==KeyEvent.ACTION_UP){boolean accepted=downTime[0]==event.getDownTime()&&device[0]==event.getDeviceId()&&code[0]==key;clear.run();if(accepted)submit.run();}
            return true;
        });
        return clear;
    }
}
