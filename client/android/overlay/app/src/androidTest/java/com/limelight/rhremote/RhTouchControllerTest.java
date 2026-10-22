package com.limelight.rhremote;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

/** Uses real Android MotionEvents and the main looper, but never sends input over the network. */
@RunWith(AndroidJUnit4.class)
public class RhTouchControllerTest {
    private View surface;private RhTouchController touch;private Spy output;
    private static class Spy implements RhTouchController.TouchSink {
        final List<String> buttons=new ArrayList<>();int scroll,relative,positions;
        public void button(byte b,boolean down){buttons.add(b+(down?":D":":U"));}
        public void position(short x,short y,short w,short h){positions++;}
        public void relative(short x,short y){relative++;}
        public void scroll(short d){scroll+=d;}
    }
    private void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private void create(boolean swap){main(()->{surface=new View(InstrumentationRegistry.getInstrumentation().getTargetContext());surface.layout(0,0,1000,700);RhProfile p=RhProfile.load(surface.getContext(),"rhremote-input-tests");p.swapButtons=swap;p.trackpad=false;output=new Spy();touch=new RhTouchController(output,surface,p);});}
    @After public void cleanup(){if(touch!=null)main(()->touch.cancel());}
    private void event(int action,float... points){main(()->{int count=points.length/2;MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];for(int i=0;i<count;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].x=points[i*2];coords[i].y=points[i*2+1];coords[i].pressure=1;coords[i].size=1;}long time=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(time,time,action,count,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);try{touch.handle(surface,e);}finally{e.recycle();}});}
    @Test public void tapWaitsForReleaseAndHasBalancedButtons(){create(false);event(MotionEvent.ACTION_DOWN,100,100);assertTrue(output.buttons.isEmpty());event(MotionEvent.ACTION_UP,100,100);SystemClock.sleep(130);main(()->{});assertEquals(Arrays.asList("1:D","1:U"),output.buttons);}
    @Test public void swappedHostUsesSecondaryPhysicalButtonForTap(){create(true);event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_UP,100,100);SystemClock.sleep(130);main(()->{});assertEquals(Arrays.asList("3:D","3:U"),output.buttons);}
    @Test public void longPressNeverSendsPrimaryClick(){create(false);event(MotionEvent.ACTION_DOWN,100,100);SystemClock.sleep(730);main(()->{});assertEquals(Collections.singletonList("3:D"),output.buttons);event(MotionEvent.ACTION_UP,100,100);assertEquals(Arrays.asList("3:D","3:U"),output.buttons);}
    @Test public void cancellingDragReleasesTheHeldButton(){create(false);event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_MOVE,300,100);main(()->touch.cancel());assertEquals(Arrays.asList("1:D","1:U"),output.buttons);SystemClock.sleep(700);main(()->{});assertEquals(2,output.buttons.size());}
    @Test public void twoFingerScrollCancelsSingleFingerClick(){create(false);event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_POINTER_DOWN|(1<<8),100,100,300,100);event(MotionEvent.ACTION_MOVE,100,180,300,180);event(MotionEvent.ACTION_POINTER_UP|(1<<8),100,180,300,180);event(MotionEvent.ACTION_UP,100,180);assertTrue(output.buttons.isEmpty());assertTrue(output.scroll>0);}
    @Test public void pinchChangesOnlyTheLocalViewTransform(){create(false);event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_POINTER_DOWN|(1<<8),100,100,300,100);event(MotionEvent.ACTION_MOVE,50,100,350,100);assertTrue(surface.getScaleX()>1.45f);assertTrue(output.buttons.isEmpty());assertEquals(0,output.scroll);main(()->touch.resetZoom());assertEquals(1f,surface.getScaleX(),.001f);assertEquals(0f,surface.getTranslationX(),.001f);}
    @Test public void apiDragSendsPositionsOverTimeThenReleases(){create(false);main(()->{try{touch.inject(RhActions.obj("gesture","drag","x",100,"y",100,"toX",500,"toY",100));}catch(Exception e){throw new RuntimeException(e);}});assertEquals(1,output.positions);SystemClock.sleep(310);main(()->{});assertEquals(13,output.positions);assertEquals(Arrays.asList("1:D","1:U"),output.buttons);}
}
