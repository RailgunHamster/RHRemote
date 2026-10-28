package com.limelight.rhremote;

import android.app.Activity;
import android.os.Looper;
import android.view.*;
import android.widget.FrameLayout;
import com.limelight.nvstream.input.MouseButtonPacket;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** New scroll mode packet mapping only. Never opens a native connection or injects OS input. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi")
public class RhScrollModeTest {
    final List<String> buttons=new ArrayList<>();final List<Short> vertical=new ArrayList<>(),horizontal=new ArrayList<>();
    int positions,relative;View surface;RhTouchController touch;
    void create(boolean swapped,boolean reversed,boolean trackpad)throws Exception{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(activity);surface=new View(activity);root.addView(surface,new FrameLayout.LayoutParams(800,600));activity.setContentView(root);root.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));root.layout(0,0,800,600);
        RhProfile profile=RhProfile.fromJson("fixture",RhActions.obj("width",800,"height",600,"fps",30,"bitrateMbps",6,"codec","H.264","statistics",false,"swapButtons",swapped,"reverseScroll",reversed,"trackpad",trackpad));
        touch=new RhTouchController(new RhTouchController.TouchSink(){public void button(byte value,boolean down){buttons.add(value+":"+down);}public void position(short x,short y,short w,short h){positions++;}public void relative(short x,short y){relative++;}public void scroll(short value){vertical.add(value);}public void horizontalScroll(short value){horizontal.add(value);}},surface,profile);touch.setScrollMode(true);
    }
    void event(int action,float x,float y){MotionEvent e=MotionEvent.obtain(0,android.os.SystemClock.uptimeMillis(),action,x,y,0);try{assertTrue(touch.handle(surface,e));}finally{e.recycle();}}
    void tap(){event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_UP,100,100);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90));}
    void swipe(float dx,float dy){event(MotionEvent.ACTION_DOWN,300,300);event(MotionEvent.ACTION_MOVE,300+dx,300+dy);event(MotionEvent.ACTION_UP,300+dx,300+dy);}
    @After public void cleanup(){if(touch!=null)touch.cancel();}
    @Test public void tapUsesMiddleEvenWhenPrimaryAndSecondaryAreSwapped()throws Exception{for(boolean swap:new boolean[]{false,true}){create(swap,false,false);buttons.clear();tap();assertEquals(Arrays.asList(MouseButtonPacket.BUTTON_MIDDLE+":true",MouseButtonPacket.BUTTON_MIDDLE+":false"),buttons);assertTrue(vertical.isEmpty());assertTrue(horizontal.isEmpty());touch.cancel();}}
    @Test public void fourDirectionsSendWheelPacketsWithoutDragging()throws Exception{
        create(false,false,false);swipe(80,0);assertTrue(horizontal.get(0)<0);horizontal.clear();swipe(-80,0);assertTrue(horizontal.get(0)>0);swipe(0,80);assertTrue(vertical.get(0)>0);vertical.clear();swipe(0,-80);assertTrue(vertical.get(0)<0);assertTrue("A swipe sent a mouse button",buttons.isEmpty());assertEquals(0,relative);
    }
    @Test public void diagonalSwipeScrollsBothAxesAndRetainsFineMovement()throws Exception{
        create(false,false,false);event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_MOVE,120,120);int first=vertical.stream().mapToInt(Short::intValue).sum();for(int i=1;i<=8;i++)event(MotionEvent.ACTION_MOVE,120+i*.1f,120+i*.1f);event(MotionEvent.ACTION_UP,120.8f,120.8f);assertTrue(vertical.stream().mapToInt(Short::intValue).sum()>first);assertTrue(horizontal.stream().mapToInt(Short::intValue).sum()<0);assertTrue(buttons.isEmpty());
    }
    @Test public void jitterIsATapAndHoldingDoesNotBecomeASecondaryClick()throws Exception{
        create(false,false,false);event(MotionEvent.ACTION_DOWN,100,100);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));assertTrue(buttons.isEmpty());event(MotionEvent.ACTION_MOVE,104,103);event(MotionEvent.ACTION_UP,104,103);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90));assertEquals(Arrays.asList("2:true","2:false"),buttons);assertTrue(vertical.isEmpty());assertTrue(horizontal.isEmpty());
    }
    @Test public void releaseAfterAnUnreportedMoveScrollsInsteadOfMiddleClicking()throws Exception{create(false,false,false);event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_UP,180,100);assertTrue(horizontal.get(0)<0);assertTrue(buttons.isEmpty());}
    @Test public void turningOffOrCancellingReleasesPendingMiddleExactlyOnce()throws Exception{create(false,false,false);event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_UP,100,100);touch.setScrollMode(false);touch.cancel();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertEquals(Arrays.asList("2:true","2:false"),buttons);}
    @Test public void switchingOffRestoresOrdinaryDragging()throws Exception{create(false,false,false);touch.setScrollMode(false);swipe(80,0);assertEquals(Arrays.asList("1:true","1:false"),buttons);assertTrue(horizontal.isEmpty());assertTrue(vertical.isEmpty());}
    @Test public void reversingScrollFlipsBothAxes()throws Exception{create(false,true,false);swipe(80,80);assertTrue(horizontal.get(0)>0);assertTrue(vertical.get(0)<0);assertTrue(buttons.isEmpty());}
    @Test public void trackpadStillUsesMiddleAndScrollingInScrollMode()throws Exception{create(true,false,true);tap();swipe(80,0);assertEquals(Arrays.asList("2:true","2:false"),buttons);assertEquals(0,positions);assertEquals(0,relative);assertTrue(horizontal.get(0)<0);}
    @Test public void apiTapAndSwipeShareTheModeAndHorizontalWheelIsExplicit()throws Exception{create(true,false,false);touch.inject(RhActions.obj("gesture","tap","x",100,"y",100));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90));assertEquals(Arrays.asList("2:true","2:false"),buttons);buttons.clear();touch.inject(RhActions.obj("gesture","drag","x",100,"y",100,"toX",180,"toY",80));assertTrue(horizontal.get(0)<0);assertTrue(vertical.get(0)<0);assertTrue(buttons.isEmpty());touch.inject(RhActions.obj("gesture","scroll","axis","horizontal","delta",120));assertEquals(120,(short)horizontal.get(horizontal.size()-1));}
    @Test public void panAndPointerModesExplicitlyLeaveScrollMode()throws Exception{create(false,false,false);assertTrue(touch.togglePan());assertFalse(touch.isScrollMode());touch.setScrollMode(true);assertFalse(touch.isPanMode());touch.setTrackpad(true);assertFalse(touch.isScrollMode());assertTrue(touch.isTrackpad());}
    @Test public void apiScrollModeRespectsTrackpadCursorPosition()throws Exception{create(false,false,true);touch.inject(RhActions.obj("gesture","tap","x",100,"y",100));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90));touch.inject(RhActions.obj("gesture","drag","x",100,"y",100,"toX",180,"toY",180));assertEquals(0,positions);assertEquals(0,relative);assertEquals(Arrays.asList("2:true","2:false"),buttons);assertTrue(horizontal.get(0)<0);assertTrue(vertical.get(0)>0);}
}
