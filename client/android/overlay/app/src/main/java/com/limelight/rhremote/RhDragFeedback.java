package com.limelight.rhremote;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import org.json.JSONObject;

/** Temporary, input-transparent drag feedback. Never samples or copies the remote frame. */
final class RhDragFeedback extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF origin=new RectF(),target=new RectF(),extent=new RectF();
    private final RectF drop=new RectF();private final Rect dirty=new Rect();
    private static final int[] COLORS={0xf5101724,0xfaffffff,0xff5274e8};
    private final float[] points=new float[64];
    private final float density;
    private int count,opacity=255;
    private boolean active;
    RhDragFeedback(float density){this.density=density;paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    void begin(Rect rect){origin.set(rect);target.set(rect);count=0;active=true;append(rect.exactCenterX(),rect.exactCenterY());rebuild();}
    void move(Rect rect){target.set(rect);append(rect.exactCenterX(),rect.exactCenterY());rebuild();}
    private void append(float x,float y){
        if(count>1&&Math.hypot(x-points[(count-1)*2],y-points[(count-1)*2+1])<2*density){points[(count-1)*2]=x;points[(count-1)*2+1]=y;return;}
        if(count==points.length/2){System.arraycopy(points,4,points,2,points.length-4);count--;}
        points[count*2]=x;points[count*2+1]=y;count++;
    }
    private void rebuild(){
        path.rewind();path.moveTo(points[0],points[1]);extent.set(origin);extent.union(target);
        for(int i=1;i<count;i++){path.lineTo(points[i*2],points[i*2+1]);extent.union(points[i*2],points[i*2+1]);}
        extent.inset(-8*density,-8*density);extent.roundOut(dirty);setBounds(dirty);invalidateSelf();
    }
    void clear(){active=false;count=0;path.rewind();origin.setEmpty();target.setEmpty();invalidateSelf();setBounds(0,0,0,0);}
    JSONObject state(){return RhActions.obj("visible",active,"pointCount",count,"origin",rect(origin),"target",rect(target));}
    private JSONObject rect(RectF rect){return RhActions.obj("x",rect.left,"y",rect.top,"width",rect.width(),"height",rect.height());}
    private void stroke(Canvas canvas,Path line,RectF rect){
        paint.setStyle(Paint.Style.STROKE);
        for(int i=0;i<3;i++){
            int color=COLORS[i];paint.setColor(color);paint.setAlpha(Color.alpha(color)*opacity/255);paint.setStrokeWidth((i==0?6:i==1?4:2)*density);
            if(line!=null)canvas.drawPath(line,paint);else canvas.drawRoundRect(rect,8*density,8*density,paint);
        }
    }
    @Override public void draw(Canvas canvas){
        if(!active)return;
        int save=canvas.save();canvas.clipRect(origin,Region.Op.DIFFERENCE);canvas.clipRect(target,Region.Op.DIFFERENCE);stroke(canvas,path,null);canvas.restoreToCount(save);
        paint.setPathEffect(new DashPathEffect(new float[]{6*density,4*density},0));stroke(canvas,null,origin);paint.setPathEffect(null);
        drop.set(target);drop.inset(-4*density,-4*density);stroke(canvas,null,drop);
    }
    @Override public void setAlpha(int alpha){opacity=alpha;invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
