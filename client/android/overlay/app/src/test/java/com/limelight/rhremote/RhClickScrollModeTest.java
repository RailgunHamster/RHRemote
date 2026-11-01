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

/** Packet and pointer transitions only; no connection or OS input injection. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="mdpi")
public class RhClickScrollModeTest {
    final List<String> buttons=new ArrayList<>();final List<Short> vertical=new ArrayList<>(),horizontal=new ArrayList<>();
    final List<String> positions=new ArrayList<>();View surface;RhTouchController touch;
    void create(boolean swapped,boolean reverse)throws Exception{
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);surface=new View(a);root.addView(surface,new FrameLayout.LayoutParams(800,600));a.setContentView(root);root.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));root.layout(0,0,800,600);
        RhProfile p=RhProfile.fromJson("fixture",RhActions.obj("width",800,"height",600,"fps",30,"bitrateMbps",6,"codec","H.264","statistics",false,"swapButtons",swapped,"reverseScroll",reverse,"trackpad",false));
        touch=new RhTouchController(new RhTouchController.TouchSink(){public void button(byte b,boolean down){buttons.add(b+":"+down);}public void position(short x,short y,short w,short h){positions.add(x+","+y);}public void relative(short x,short y){fail("Relative pointer movement");}public void scroll(short n){vertical.add(n);}public void horizontalScroll(short n){horizontal.add(n);}},surface,p);touch.setClickScrollMode(true);
    }
    void event(int action,float... xy){
        int n=xy.length/2;MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[n];MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[n];
        for(int i=0;i<n;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coordinates[i]=new MotionEvent.PointerCoords();coordinates[i].x=xy[i*2];coordinates[i].y=xy[i*2+1];coordinates[i].pressure=1;coordinates[i].size=1;}
        MotionEvent e=MotionEvent.obtain(0,android.os.SystemClock.uptimeMillis(),action,n,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);try{assertTrue(touch.handle(surface,e));}finally{e.recycle();}
    }
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));}
    void startDrag(){event(MotionEvent.ACTION_DOWN,200,200);event(MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),200,200,260,200);event(MotionEvent.ACTION_MOVE,240,240,300,240);}
    @After public void cleanup(){if(touch!=null)touch.cancel();}
    @Test public void tapUsesTheLogicalPrimaryButtonIncludingSwappedHosts()throws Exception{
        for(boolean swapped:new boolean[]{false,true}){create(swapped,false);buttons.clear();event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_UP,100,100);idle();byte b=swapped?MouseButtonPacket.BUTTON_RIGHT:MouseButtonPacket.BUTTON_LEFT;assertEquals(Arrays.asList(b+":true",b+":false"),buttons);assertTrue(vertical.isEmpty());assertTrue(horizontal.isEmpty());}
    }
    @Test public void singleFingerSwipesScrollBothAxesWithoutHoldingAButton()throws Exception{
        create(false,false);event(MotionEvent.ACTION_DOWN,200,200);event(MotionEvent.ACTION_MOVE,260,280);event(MotionEvent.ACTION_UP,260,280);idle();assertTrue(buttons.isEmpty());assertTrue(horizontal.get(0)<0);assertTrue(vertical.get(0)>0);
    }
    @Test public void directionCanStillBeReversed()throws Exception{
        create(false,true);event(MotionEvent.ACTION_DOWN,200,200);event(MotionEvent.ACTION_MOVE,260,280);event(MotionEvent.ACTION_UP,260,280);assertTrue(horizontal.get(0)>0);assertTrue(vertical.get(0)<0);assertTrue(buttons.isEmpty());
    }
    @Test public void anUpWithoutMoveStillScrollsRatherThanClicking()throws Exception{
        create(false,false);event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_UP,180,100);idle();assertTrue(buttons.isEmpty());assertTrue(horizontal.get(0)<0);
    }
    @Test public void twoFingersDragFromTheFirstFingerInsteadOfTheirMidpoint()throws Exception{
        create(false,false);startDrag();assertEquals(Collections.singletonList("1:true"),buttons);assertEquals("200,200",positions.get(positions.size()-2));assertEquals("240,240",positions.get(positions.size()-1));assertTrue(vertical.isEmpty());assertTrue(horizontal.isEmpty());event(MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),240,240,300,240);event(MotionEvent.ACTION_MOVE,280,280);event(MotionEvent.ACTION_UP,280,280);idle();assertEquals(Arrays.asList("1:true","1:false"),buttons);
    }
    @Test public void aSwappedHostUsesItsPrimaryButtonForDragging()throws Exception{
        create(true,false);startDrag();event(MotionEvent.ACTION_CANCEL,240,240);idle();assertEquals(Arrays.asList("3:true","3:false"),buttons);
    }
    @Test public void aThirdFingerCancelsAndNeverStartsAnotherDragUntilAllLift()throws Exception{
        create(false,false);startDrag();event(MotionEvent.ACTION_POINTER_DOWN|(2<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),240,240,300,240,350,240);event(MotionEvent.ACTION_MOVE,260,260,320,260);event(MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),260,260,320,260);event(MotionEvent.ACTION_UP,260,260);idle();assertEquals(Arrays.asList("1:true","1:false"),buttons);
    }
    @Test public void pinchZoomRemainsAvailableWithoutMouseButtons()throws Exception{
        create(false,false);event(MotionEvent.ACTION_DOWN,200,200);event(MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),200,200,260,200);event(MotionEvent.ACTION_MOVE,170,200,290,200);event(MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),170,200,290,200);event(MotionEvent.ACTION_UP,170,200);idle();assertTrue(touch.scale()>1);assertTrue(buttons.isEmpty());assertTrue(vertical.isEmpty());
    }
    @Test public void changingModesReleasesTheDragExactlyOnce()throws Exception{
        create(false,false);startDrag();touch.setScrollMode(true);touch.cancel();idle();assertFalse(touch.isClickScrollMode());assertTrue(touch.isScrollMode());assertEquals(Arrays.asList("1:true","1:false"),buttons);
        touch.setClickScrollMode(true);touch.setTrackpad(true);assertFalse(touch.isClickScrollMode());touch.setClickScrollMode(true);touch.togglePan();assertFalse(touch.isClickScrollMode());
    }
    @Test public void apiSwipesUseTheSameWheelAndDragMeanings()throws Exception{
        create(false,false);touch.inject(RhActions.obj("gesture","swipe","fingers",1,"x",100,"y",100,"toX",160,"toY",180));assertTrue(buttons.isEmpty());assertTrue(horizontal.get(0)<0);assertTrue(vertical.get(0)>0);horizontal.clear();vertical.clear();touch.inject(RhActions.obj("gesture","swipe","fingers",2,"x",100,"y",100,"toX",160,"toY",180));idle();assertEquals(Arrays.asList("1:true","1:false"),buttons);assertEquals("160,180",positions.get(positions.size()-1));assertTrue(horizontal.isEmpty());assertTrue(vertical.isEmpty());
    }
    @Test public void unsupportedFingerCountsDoNotPressAnyMouseButton()throws Exception{
        create(false,false);for(int fingers:new int[]{0,3}){try{touch.inject(RhActions.obj("gesture","swipe","fingers",fingers));fail();}catch(IllegalArgumentException expected){}}assertTrue(buttons.isEmpty());
    }
}
