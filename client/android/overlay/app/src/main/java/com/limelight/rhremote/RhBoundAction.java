package com.limelight.rhremote;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONException;
import org.json.JSONObject;

/** A gesture belongs to the action and arguments visible when it began. */
public final class RhBoundAction {
    public interface Listener {void send(View source,String action,JSONObject args);}
    private interface Touch {boolean send(MotionEvent event);}
    private RhBoundAction(){}
    private static final class Press {
        final long revision;final String action,args;
        Press(long revision,String action,String args){this.revision=revision;this.action=action;this.args=args;}
    }
    private static final class Binding {
        final View owner;final Listener listener;final Handler main=new Handler(Looper.getMainLooper());
        String action="",args="{}";long revision;Press press;
        Binding(View owner,Listener listener){this.owner=owner;this.listener=listener;}
        void bind(String next,JSONObject parameters){
            String data=parameters.toString();if(action.equals(next)&&args.equals(data))return;
            action=next;args=data;invalidate();
        }
        void invalidate(){revision++;owner.cancelPendingInputEvents();owner.cancelLongPress();owner.setPressed(false);}
        void reset(){invalidate();press=null;}
        void begin(){owner.cancelPendingInputEvents();press=new Press(revision,action,args);}
        boolean current(Press value){return value!=null&&value.revision==revision&&!value.action.isEmpty()&&owner.isEnabled()&&owner.getVisibility()==View.VISIBLE;}
        boolean canClick(){return press==null?!action.isEmpty()&&owner.isEnabled()&&owner.getVisibility()==View.VISIBLE:current(press);}
        void click(){
            Press intent=press==null?new Press(revision,action,args):press;
            if(!current(intent))return;press=null;
            try{listener.send(owner,intent.action,new JSONObject(intent.args));}catch(JSONException impossible){throw new IllegalStateException(impossible);}
        }
        void released(){
            Press completed=press;
            // View may post performClick(). Its callback is queued by super before this cleanup.
            if(completed!=null)main.post(()->{if(press==completed)press=null;});
        }
        boolean touch(MotionEvent event,Touch normal){
            int type=event.getActionMasked();if(type==MotionEvent.ACTION_DOWN)begin();
            if(type==MotionEvent.ACTION_CANCEL)invalidate();
            if(type==MotionEvent.ACTION_UP&&!current(press)){
                MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);
                try{normal.send(cancel);}finally{cancel.recycle();released();}return true;
            }
            boolean handled=normal.send(event);
            if(type==MotionEvent.ACTION_UP||type==MotionEvent.ACTION_CANCEL)released();
            return handled;
        }
        static boolean confirm(int key){return key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_SPACE||key==KeyEvent.KEYCODE_BUTTON_A;}
        void keyDown(int key,KeyEvent event){if(confirm(key)&&event.getRepeatCount()==0)begin();}
        boolean rejectKeyUp(int key){
            if(!confirm(key)||current(press))return false;
            owner.setPressed(false);owner.cancelLongPress();owner.cancelPendingInputEvents();
            KeyEvent.DispatcherState state=owner.getKeyDispatcherState();if(state!=null)state.reset(owner);
            released();return true;
        }
        void keyUp(int key){if(confirm(key))released();}
    }
    public static final class Button extends android.widget.Button {
        private final Binding binding;
        public Button(Context context,Listener listener){super(context);binding=new Binding(this,listener);super.setOnClickListener(v->binding.click());}
        public void bind(String action,JSONObject args){binding.bind(action,args);}
        @Override public boolean performClick(){return binding!=null&&binding.canClick()&&super.performClick();}
        @Override public boolean onTouchEvent(MotionEvent event){return binding==null?super.onTouchEvent(event):binding.touch(event,e->super.onTouchEvent(e));}
        @Override public boolean onKeyDown(int key,KeyEvent event){if(binding!=null)binding.keyDown(key,event);return super.onKeyDown(key,event);}
        @Override public boolean onKeyUp(int key,KeyEvent event){if(binding!=null&&binding.rejectKeyUp(key))return true;boolean result=super.onKeyUp(key,event);if(binding!=null)binding.keyUp(key);return result;}
        @Override public void setEnabled(boolean value){boolean changed=value!=isEnabled();super.setEnabled(value);if(changed&&binding!=null)binding.invalidate();}
        @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);if(visibility!=VISIBLE&&binding!=null)binding.invalidate();}
        @Override protected void onFocusChanged(boolean gain,int direction,Rect previous){super.onFocusChanged(gain,direction,previous);if(!gain&&binding!=null)binding.invalidate();}
        @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus&&binding!=null)binding.invalidate();}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(binding!=null)binding.reset();}
        @Override protected void onDetachedFromWindow(){if(binding!=null)binding.reset();super.onDetachedFromWindow();}
    }
    public static final class Row extends android.widget.LinearLayout {
        private final Binding binding;
        public Row(Context context,Listener listener){super(context);binding=new Binding(this,listener);super.setOnClickListener(v->binding.click());}
        public void bind(String action,JSONObject args){binding.bind(action,args);}
        @Override public boolean performClick(){return binding!=null&&binding.canClick()&&super.performClick();}
        @Override public boolean onTouchEvent(MotionEvent event){return binding==null?super.onTouchEvent(event):binding.touch(event,e->super.onTouchEvent(e));}
        @Override public boolean onKeyDown(int key,KeyEvent event){if(binding!=null)binding.keyDown(key,event);return super.onKeyDown(key,event);}
        @Override public boolean onKeyUp(int key,KeyEvent event){if(binding!=null&&binding.rejectKeyUp(key))return true;boolean result=super.onKeyUp(key,event);if(binding!=null)binding.keyUp(key);return result;}
        @Override public void setEnabled(boolean value){boolean changed=value!=isEnabled();super.setEnabled(value);if(changed&&binding!=null)binding.invalidate();}
        @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);if(visibility!=VISIBLE&&binding!=null)binding.invalidate();}
        @Override protected void onFocusChanged(boolean gain,int direction,Rect previous){super.onFocusChanged(gain,direction,previous);if(!gain&&binding!=null)binding.invalidate();}
        @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus&&binding!=null)binding.invalidate();}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(binding!=null)binding.reset();}
        @Override protected void onDetachedFromWindow(){if(binding!=null)binding.reset();super.onDetachedFromWindow();}
    }
}
