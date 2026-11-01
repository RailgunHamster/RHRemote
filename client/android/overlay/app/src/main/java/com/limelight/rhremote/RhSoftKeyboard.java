package com.limelight.rhremote;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import com.limelight.Game;
import com.limelight.R;

/** One session's explicit IME request, confirmed by the window's observed insets. */
final class RhSoftKeyboard {
    interface Backend { Boolean visible(); void request(boolean show); default boolean settled(){return true;} default void dispose(){} }
    interface Completion { void done(boolean visible,String error); }
    private final Backend backend;
    private final Handler handler;
    private Runnable check;
    private Completion completion;
    private long generation,started;
    private boolean disposed,pending,target,lastVisible,ownsShow;
    private String error="";
    private Boolean lastObserved;

    RhSoftKeyboard(Game game){this(new WindowBackend(game.getWindow(),game.findViewById(R.id.surfaceView)),new Handler(Looper.getMainLooper()));}
    /** Own the posted show: AndroidX 1.16 otherwise posts a second, uncancellable IME show. */
    static final class WindowBackend implements Backend {
        private final Window window;
        private final View surface;
        private final int originalAdjustMode;
        private final int ownedAdjustMode=android.os.Build.VERSION.SDK_INT>=30?WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING:WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        private Runnable showRequest;
        private long requestGeneration;
        private final java.util.Set<WindowInsetsAnimationCompat> animations=new java.util.HashSet<>();
        WindowBackend(Window window,View surface){this.window=window;this.surface=surface;originalAdjustMode=window.getAttributes().softInputMode&WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;if(surface!=null)ViewCompat.setWindowInsetsAnimationCallback(surface,new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){
            @Override public void onPrepare(WindowInsetsAnimationCompat animation){if((animation.getTypeMask()&WindowInsetsCompat.Type.ime())!=0)animations.add(animation);}
            @Override public WindowInsetsCompat onProgress(WindowInsetsCompat insets,java.util.List<WindowInsetsAnimationCompat> running){return insets;}
            @Override public void onEnd(WindowInsetsAnimationCompat animation){animations.remove(animation);}
        });}
        public Boolean visible(){WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(window.getDecorView());return insets==null?null:insets.isVisible(WindowInsetsCompat.Type.ime());}
        public boolean settled(){return showRequest==null&&animations.isEmpty();}
        public void dispose(){requestGeneration++;if(surface!=null){if(showRequest!=null){surface.removeCallbacks(showRequest);showRequest=null;}ViewCompat.setWindowInsetsAnimationCallback(surface,null);}animations.clear();int mode=window.getAttributes().softInputMode;if((mode&WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)==ownedAdjustMode)window.setSoftInputMode((mode&~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)|originalAdjustMode);}
        public void request(boolean show){
            if(surface==null)throw new IllegalStateException("stream view unavailable");
            long ticket=++requestGeneration;
            if(showRequest!=null){surface.removeCallbacks(showRequest);showRequest=null;}
            InputMethodManager imm=(InputMethodManager)surface.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if(!show){imm.hideSoftInputFromWindow(surface.getWindowToken(),0);return;}
            // Keep the stream and its top controls stationary. Android's default
            // adjustPan can pan the entire window above the screen to keep the
            // focused full-screen stream view clear of the IME. Controls handle
            // IME insets separately without resizing the decoded picture.
            int mode=window.getAttributes().softInputMode;
            // Pre-Android 11 AndroidX requires adjustResize to observe IME insets.
            window.setSoftInputMode((mode&~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)|ownedAdjustMode);
            if(!surface.requestFocusFromTouch())throw new IllegalStateException("stream focus unavailable");
            showRequest=()->{if(ticket!=requestGeneration)return;showRequest=null;if(!surface.hasWindowFocus()||!surface.isAttachedToWindow())return;try{imm.showSoftInput(surface,0);}catch(RuntimeException unavailable){/* The observed-state deadline reports failure without crashing this window. */}};
            surface.post(showRequest);
        }
    }
    RhSoftKeyboard(Backend backend,Handler handler){this.backend=backend;this.handler=handler;}
    private Boolean observed(){try{return backend.visible();}catch(RuntimeException unavailable){return null;}}
    boolean visible(){if(disposed)return false;Boolean value=observed();if(value!=null)lastVisible=value;return lastVisible;}
    boolean pending(){return pending;}
    boolean target(){return target;}
    String error(){return error;}
    void request(boolean show,Completion callback){
        if(disposed)throw new IllegalStateException("keyboard controller disposed");
        if(pending&&target==show)return;
        cancelCheck();long ticket=++generation;target=show;pending=true;error="";completion=callback;started=SystemClock.uptimeMillis();if(show)ownsShow=true;lastObserved=null;
        RhStatistics.event("ime_request",-1,RhActions.obj("request",ticket,"show",show,"visible",visible()));
        try{backend.request(show);}catch(RuntimeException unavailable){cancelUnconfirmedShow();finish(visible(),show?"键盘未打开":"键盘未关闭");return;}
        check=()->poll(ticket);handler.post(check);
    }
    private void poll(long ticket){
        if(disposed||!pending||ticket!=generation)return;
        Boolean value=observed();boolean deadline=SystemClock.uptimeMillis()-started>=2000;
        // Prefer settled animation during normal operation. At the deadline the
        // observed target state is success, not a reason to undo a visible IME.
        if(value!=null){if(!value.equals(lastObserved)){lastObserved=value;RhStatistics.event("ime_observed",-1,RhActions.obj("request",ticket,"visible",value));}lastVisible=value;if(value==target&&(backend.settled()||deadline)){if(!value)ownsShow=false;finish(value,"");return;}}
        if(deadline){cancelUnconfirmedShow();finish(lastVisible,target?"键盘未打开":"键盘未关闭");return;}
        handler.postDelayed(check,50);
    }
    private void finish(boolean visible,String failure){RhStatistics.event("ime_request_end",-1,RhActions.obj("request",generation,"visible",visible,"error",failure,"elapsedMs",SystemClock.uptimeMillis()-started));pending=false;error=failure;cancelCheck();Completion done=completion;completion=null;if(done!=null)done.done(visible,failure);}
    private void cancelCheck(){if(check!=null){handler.removeCallbacks(check);check=null;}}
    private void cancelUnconfirmedShow(){if(target)try{backend.request(false);}catch(RuntimeException unavailable){/* Best-effort cancellation belongs to the same window. */}}
    void dispose(){
        if(disposed)return;boolean hide=ownsShow;disposed=true;pending=false;generation++;completion=null;cancelCheck();
        if(hide)try{backend.request(false);}catch(RuntimeException unavailable){/* The owner window may already be detached. */}
        backend.dispose();
    }
}
