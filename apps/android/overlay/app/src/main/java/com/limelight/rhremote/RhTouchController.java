package com.limelight.rhremote;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

/** Touch-only logical-button compensation. Physical mouse events never enter this handler. */
public final class RhTouchController {
    interface TouchSink {void button(byte button,boolean down);void position(short x,short y,short width,short height);void relative(short x,short y);void scroll(short delta);}
    private final TouchSink sink;private final View surface;private final boolean swapped;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final float slop;private boolean active,multi,drag,secondary,primary,trackpad,panMode;
    private int twoFingerMode;private float downX,downY,lastX,lastY,initialDistance,initialScale,centerX,centerY,initialPanX,initialPanY;
    private float scale=1,panX,panY;private long sequence;
    private final Runnable longPress=()->{if(active&&!multi&&!drag&&!panMode){secondary=true;button(false,true);}};
    public RhTouchController(NvConnection c,View view,RhProfile profile){this(new TouchSink(){public void button(byte b,boolean down){if(down)c.sendMouseButtonDown(b);else c.sendMouseButtonUp(b);}public void position(short x,short y,short w,short h){c.sendMousePosition(x,y,w,h);}public void relative(short x,short y){c.sendMouseMove(x,y);}public void scroll(short delta){c.sendMouseHighResScroll(delta);}},view,profile);}
    RhTouchController(TouchSink output,View view,RhProfile profile){sink=output;surface=view;swapped=profile.swapButtons;trackpad=profile.trackpad;slop=12*view.getResources().getDisplayMetrics().density;surface.setPivotX(0);surface.setPivotY(0);}
    private void button(boolean main,boolean down){byte b=(main^swapped)?MouseButtonPacket.BUTTON_LEFT:MouseButtonPacket.BUTTON_RIGHT;sink.button(b,down);}
    private float sx(View source,MotionEvent e,int i){return source==surface?e.getX(i)*scale+surface.getLeft()+panX:e.getX(i);}
    private float sy(View source,MotionEvent e,int i){return source==surface?e.getY(i)*scale+surface.getTop()+panY:e.getY(i);}
    private void position(float x,float y){float px=(x-surface.getLeft()-panX)/scale,py=(y-surface.getTop()-panY)/scale;int w=surface.getWidth(),h=surface.getHeight();if(w>0&&h>0)sink.position((short)Math.max(0,Math.min(w,px)),(short)Math.max(0,Math.min(h,py)),(short)w,(short)h);}
    private void apiPosition(float x,float y){position(x*scale+surface.getLeft()+panX,y*scale+surface.getTop()+panY);}
    private void transform(){panX=Math.max(surface.getWidth()*(1-scale),Math.min(0,panX));panY=Math.max(surface.getHeight()*(1-scale),Math.min(0,panY));surface.setScaleX(scale);surface.setScaleY(scale);surface.setTranslationX(panX);surface.setTranslationY(panY);}
    public void resetZoom(){scale=1;panX=panY=0;transform();}
    public void setTrackpad(boolean value){cancel();trackpad=value;}
    public boolean isTrackpad(){return trackpad;}
    public boolean togglePan(){cancel();return panMode=!panMode;}
    public boolean isPanMode(){return panMode;}
    public org.json.JSONObject surfaceSize(){return RhActions.obj("width",surface.getWidth(),"height",surface.getHeight());}
    public void cancel(){handler.removeCallbacksAndMessages(null);sequence++;if(primary){button(true,false);primary=false;}if(secondary){button(false,false);secondary=false;}active=drag=multi=false;}
    public void inject(org.json.JSONObject args)throws org.json.JSONException{
        String gesture=args.getString("gesture");if(!java.util.Arrays.asList("tap","secondary","drag","scroll","pinch").contains(gesture))throw new IllegalArgumentException("unsupported gesture");
        float x=(float)args.optDouble("x",surface.getWidth()/2f),y=(float)args.optDouble("y",surface.getHeight()/2f);if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||x>surface.getWidth()||y>surface.getHeight())throw new IllegalArgumentException("invalid coordinates");
        cancel();
        // API and touch input share the same logical-button and coordinate mapping.
        if(gesture.equals("scroll")){int delta=args.optInt("delta",120);if(Math.abs((long)delta)>32767)throw new IllegalArgumentException("invalid scroll delta");sink.scroll((short)delta);return;}
        if(gesture.equals("pinch")){float factor=(float)args.optDouble("factor",1.25);if(!Float.isFinite(factor)||factor<0.5||factor>2)throw new IllegalArgumentException("invalid zoom factor");scale=Math.max(1,Math.min(3,scale*factor));transform();return;}
        float endX=(float)args.optDouble("toX",x+100),endY=(float)args.optDouble("toY",y+100);
        if(gesture.equals("drag")&&(!Float.isFinite(endX)||!Float.isFinite(endY)||endX<0||endY<0||endX>surface.getWidth()||endY>surface.getHeight()))throw new IllegalArgumentException("invalid drag destination");
        apiPosition(x,y);boolean main=!gesture.equals("secondary");button(main,true);primary=main;secondary=!main;
        long token=++sequence;
        if(gesture.equals("drag")){for(int i=1;i<=12;i++){final int step=i;handler.postDelayed(()->{if(sequence==token&&primary)apiPosition(x+(endX-x)*step/12,y+(endY-y)*step/12);},i*16L);}}
        handler.postDelayed(()->{if(sequence==token)cancel();},gesture.equals("drag")?240:80);
    }
    public boolean handle(View source,MotionEvent e){
        int action=e.getActionMasked();float x=sx(source,e,0),y=sy(source,e,0);
        if(action==MotionEvent.ACTION_DOWN){cancel();active=true;downX=lastX=x;downY=lastY=y;if(!trackpad&&!panMode)position(x,y);if(!panMode)handler.postDelayed(longPress,650);return true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN){handler.removeCallbacksAndMessages(null);if(primary){button(true,false);primary=false;}if(secondary){button(false,false);secondary=false;}multi=true;twoFingerMode=0;if(e.getPointerCount()==2){float x1=sx(source,e,1),y1=sy(source,e,1);initialDistance=(float)Math.hypot(x1-x,y1-y);initialScale=scale;centerX=(x+x1)/2;centerY=(y+y1)/2;lastY=centerY;initialPanX=panX;initialPanY=panY;}return true;}
        if(action==MotionEvent.ACTION_MOVE&&active){
            if(multi){if(e.getPointerCount()!=2)return true;float x1=sx(source,e,1),y1=sy(source,e,1),cx=(x+x1)/2,cy=(y+y1)/2;float ratio=(float)Math.hypot(x1-x,y1-y)/Math.max(1,initialDistance);
                if(twoFingerMode==0){if(Math.abs(ratio-1)>.08f)twoFingerMode=2;else if(Math.abs(cy-centerY)>slop)twoFingerMode=1;}
                if(twoFingerMode==2){scale=Math.max(1,Math.min(3,initialScale*ratio));panX=cx-surface.getLeft()-(centerX-surface.getLeft()-initialPanX)*scale/initialScale;panY=cy-surface.getTop()-(centerY-surface.getTop()-initialPanY)*scale/initialScale;transform();}
                else if(twoFingerMode==1){sink.scroll((short)Math.max(-32767,Math.min(32767,(cy-lastY)*3)));}lastY=cy;return true;
            }
            if(panMode){panX+=x-lastX;panY+=y-lastY;transform();}
            else if(trackpad){if(Math.hypot(x-downX,y-downY)>slop){handler.removeCallbacks(longPress);drag=true;}if(drag)sink.relative((short)(x-lastX),(short)(y-lastY));}
            else if(!secondary){if(!drag&&Math.hypot(x-downX,y-downY)>slop){handler.removeCallbacks(longPress);position(downX,downY);button(true,true);primary=true;drag=true;}if(drag)position(x,y);}
            lastX=x;lastY=y;return true;
        }
        if(action==MotionEvent.ACTION_POINTER_UP){handler.removeCallbacks(longPress);return true;}
        if(action==MotionEvent.ACTION_UP){handler.removeCallbacks(longPress);if(active&&!multi&&!drag&&!secondary&&!panMode){if(!trackpad)position(x,y);button(true,true);primary=true;long token=++sequence;handler.postDelayed(()->{if(sequence==token&&primary){button(true,false);primary=false;}},80);}else{if(primary){button(true,false);primary=false;}if(secondary){button(false,false);secondary=false;}}active=false;return true;}
        if(action==MotionEvent.ACTION_CANCEL){cancel();return true;}
        return true;
    }
}
