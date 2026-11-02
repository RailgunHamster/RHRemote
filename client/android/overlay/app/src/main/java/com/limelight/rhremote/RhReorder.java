package com.limelight.rhremote;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;
import java.util.*;
import java.util.function.Supplier;

/** A grip owns the gesture; rows keep their normal click and scroll behaviour. */
final class RhReorder {
    static final class Item {
        final String id; final View view;
        Item(String id,View view){this.id=id;this.view=view;}
    }
    interface Drop {void move(String target,boolean after);}
    interface Step {void move(String direction);}
    static void bind(View grip,String id,Supplier<List<Item>> items,int color,Drop drop,Step step){
        final float density=grip.getResources().getDisplayMetrics().density;
        final class Gesture implements View.OnTouchListener,Runnable {
            boolean active,moved,after;float startY,x,y;String order="";Item target;Drawable previous;
            String fingerprint(){StringBuilder value=new StringBuilder();for(Item i:items.get())value.append(i.id).append('\n');return value.toString();}
            void clearTarget(){if(target!=null)target.view.setForeground(previous);target=null;previous=null;}
            void stop(){active=false;grip.removeCallbacks(this);clearTarget();if(grip.getParent()!=null)grip.getParent().requestDisallowInterceptTouchEvent(false);grip.setPressed(false);}
            void hit(){clearTarget();if(!moved)return;for(Item item:items.get()){Rect r=new Rect();if(!item.view.getGlobalVisibleRect(r)||!r.contains((int)x,(int)y))continue;if(item.id.equals(id))return;target=item;after=y>r.centerY();previous=item.view.getForeground();item.view.setForeground(new Drawable(){final Paint paint=new Paint();public void draw(Canvas canvas){paint.setColor(color);Rect b=getBounds();float h=Math.max(2,3*density);canvas.drawRect(b.left,after?b.bottom-h:b.top,b.right,after?b.bottom:b.top+h,paint);}public void setAlpha(int alpha){paint.setAlpha(alpha);}public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}});return;}}
            public void run(){if(!active)return;if(!grip.isAttachedToWindow()||!grip.isEnabled()||!order.equals(fingerprint())){stop();return;}if(moved){View parent=grip;while(parent.getParent() instanceof View){parent=(View)parent.getParent();if(parent instanceof ScrollView){Rect r=new Rect();if(parent.getGlobalVisibleRect(r)){int edge=(int)(40*density),delta=y<r.top+edge?-(int)(8*density):y>r.bottom-edge?(int)(8*density):0;if(delta!=0)((ScrollView)parent).scrollBy(0,delta);}break;}}hit();}grip.postOnAnimation(this);}
            public boolean onTouch(View view,MotionEvent e){
                if(!view.isEnabled())return false;
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:stop();active=true;moved=false;startY=y=e.getRawY();x=e.getRawX();order=fingerprint();view.requestFocusFromTouch();view.setPressed(true);view.getParent().requestDisallowInterceptTouchEvent(true);view.postOnAnimation(this);return true;
                    case MotionEvent.ACTION_MOVE:if(!active)return false;x=e.getRawX();y=e.getRawY();moved|=Math.abs(y-startY)>6*density;hit();return true;
                    case MotionEvent.ACTION_UP:if(!active)return false;x=e.getRawX();y=e.getRawY();hit();String targetId=target==null?"":target.id;boolean insertAfter=after,valid=order.equals(fingerprint())&&view.isAttachedToWindow()&&view.isEnabled();stop();if(valid&&moved&&!targetId.isEmpty())drop.move(targetId,insertAfter);return true;
                    case MotionEvent.ACTION_CANCEL:case MotionEvent.ACTION_POINTER_DOWN:stop();return true;
                    default:return active;
                }
            }
        }
        Gesture gesture=new Gesture();grip.setOnTouchListener(gesture);grip.setFocusable(true);
        grip.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){gesture.stop();}});
        grip.setOnKeyListener((v,key,event)->{if(!v.isEnabled()||!event.isAltPressed()||(key!=KeyEvent.KEYCODE_DPAD_UP&&key!=KeyEvent.KEYCODE_DPAD_DOWN))return false;if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)step.move(key==KeyEvent.KEYCODE_DPAD_UP?"up":"down");return true;});
        grip.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);}
            public boolean performAccessibilityAction(View host,int action,Bundle args){if(host.isEnabled()&&(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)){step.move(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD?"up":"down");return true;}return super.performAccessibilityAction(host,action,args);}
        });
    }
}
