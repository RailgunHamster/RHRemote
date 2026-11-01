package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Own Activity/View events and a packet recorder only; no system or remote input injection. */
@RunWith(AndroidJUnit4.class)
public class RhClickScrollDeviceTest {
    Instrumentation instrument;Activity activity;View surface;FrameLayout root;RhTouchController touch;RhStreamPanel panel;
    final List<String> buttons=new ArrayList<>();final List<Short> vertical=new ArrayList<>(),horizontal=new ArrayList<>();
    void event(int action,float... xy){
        int n=xy.length/2;MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[n];MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[n];
        for(int i=0;i<n;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coordinates[i]=new MotionEvent.PointerCoords();coordinates[i].x=xy[i*2];coordinates[i].y=xy[i*2+1];coordinates[i].pressure=1;coordinates[i].size=1;}
        MotionEvent event=MotionEvent.obtain(0,android.os.SystemClock.uptimeMillis(),action,n,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{assertTrue(surface.dispatchTouchEvent(event));}finally{event.recycle();}
    }
    void idle()throws Exception{Thread.sleep(120);instrument.waitForIdleSync();}
    void frame()throws Exception{
        java.util.concurrent.CountDownLatch drawn=new java.util.concurrent.CountDownLatch(1);
        instrument.runOnMainSync(()->{root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(root.getWidth()>0&&root.getHeight()>0){root.getViewTreeObserver().removeOnPreDrawListener(this);drawn.countDown();}return true;}});root.invalidate();});
        assertTrue("Owned fixture did not complete a native layout",drawn.await(3,java.util.concurrent.TimeUnit.SECONDS));instrument.waitForIdleSync();
    }
    @Test public void nativeSwitchAndPointerEventsProduceWheelAndLogicalPrimaryDrag()throws Exception{
        instrument=InstrumentationRegistry.getInstrumentation();Context context=instrument.getTargetContext();
        assertEquals("com.rhremote.client.uicheck172",context.getPackageName());assertEquals("selected-window-click-scroll-196",InstrumentationRegistry.getArguments().getString("owner"));assertFalse(com.limelight.BuildConfig.DEBUG);
        activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.rhremote.RhUiFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrument.waitForIdleSync();
        try{
            instrument.runOnMainSync(()->{try{
                int theme=context.getResources().getIdentifier("RhTheme","style",context.getPackageName());assertTrue(theme!=0);activity.setTheme(theme);root=new FrameLayout(activity);surface=new View(activity);surface.setBackgroundColor(Color.rgb(30,40,60));root.addView(surface,new FrameLayout.LayoutParams(-1,-1));activity.setContentView(root);
                RhProfile profile=RhProfile.fromJson("fixture",RhActions.obj("width",800,"height",600,"fps",30,"bitrateMbps",6,"codec","H.264","statistics",false,"swapButtons",false,"trackpad",false));
                touch=new RhTouchController(new RhTouchController.TouchSink(){public void button(byte b,boolean down){buttons.add(b+":"+down);}public void position(short x,short y,short w,short h){}public void relative(short x,short y){throw new AssertionError("Unexpected relative movement");}public void scroll(short value){vertical.add(value);}public void horizontalScroll(short value){horizontal.add(value);}},surface,profile);surface.setOnTouchListener(touch::handle);
                panel=new RhStreamPanel(activity,root,(id,args)->{assertEquals("stream.click-scroll.set",id);touch.setClickScrollMode(args.optBoolean("enabled"));panel.updateClickScrollMode(touch.isClickScrollMode());});panel.setExpanded(true);panel.updateControls(true,false,1,false,"input","","");
            }catch(Exception failure){throw new RuntimeException(failure);}});frame();
            instrument.runOnMainSync(()->{Switch control=root.findViewWithTag("stream.click-scroll.set");assertNotNull(control);assertTrue("New mode switch is disabled",control.isEnabled());control.requestRectangleOnScreen(new Rect(0,0,control.getWidth(),control.getHeight()),true);});frame();
            instrument.runOnMainSync(()->{Switch control=root.findViewWithTag("stream.click-scroll.set");Rect visible=new Rect();assertTrue("New mode switch is not visible",control.getGlobalVisibleRect(visible));assertEquals("Switch width clipped",control.getWidth(),visible.width());assertEquals("Switch height clipped",control.getHeight(),visible.height());control.performClick();assertTrue("Native switch did not toggle",control.isChecked());assertTrue("Toggle did not reach the touch controller",touch.isClickScrollMode());panel.setExpanded(false);
                event(MotionEvent.ACTION_DOWN,100,100);event(MotionEvent.ACTION_UP,100,100);});idle();assertEquals(Arrays.asList("1:true","1:false"),buttons);buttons.clear();
            instrument.runOnMainSync(()->{event(MotionEvent.ACTION_DOWN,200,200);event(MotionEvent.ACTION_MOVE,260,280);event(MotionEvent.ACTION_UP,260,280);});idle();assertTrue(buttons.isEmpty());assertTrue(vertical.get(0)>0);assertTrue(horizontal.get(0)<0);vertical.clear();horizontal.clear();
            instrument.runOnMainSync(()->{event(MotionEvent.ACTION_DOWN,200,200);event(MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),200,200,260,200);event(MotionEvent.ACTION_MOVE,240,240,300,240);event(MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),240,240,300,240);event(MotionEvent.ACTION_UP,240,240);});idle();assertEquals(Arrays.asList("1:true","1:false"),buttons);assertTrue(vertical.isEmpty());assertTrue(horizontal.isEmpty());buttons.clear();
            instrument.runOnMainSync(()->{event(MotionEvent.ACTION_DOWN,200,200);event(MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),200,200,260,200);event(MotionEvent.ACTION_MOVE,170,200,290,200);event(MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),170,200,290,200);event(MotionEvent.ACTION_UP,170,200);});idle();assertTrue(touch.scale()>1);assertTrue(buttons.isEmpty());
            File folder=context.getExternalFilesDir(null);assertNotNull(folder);instrument.runOnMainSync(()->{panel.setExpanded(true);panel.updateClickScrollMode(true);});instrument.waitForIdleSync();Bitmap[] image={null};instrument.runOnMainSync(()->{image[0]=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image[0]));});try(OutputStream output=new FileOutputStream(new File(folder,"feature196.png"))){assertTrue(image[0].compress(Bitmap.CompressFormat.PNG,100,output));}finally{image[0].recycle();}
            RhShared.write(new File(folder,"feature196.json"),RhActions.obj("tap",true,"singleFingerWheelBothAxes",true,"twoFingerPrimaryDragReleased",true,"pinchZoom",true,"switchFullyVisible",true,"applicationViewEventsOnly",true,"remoteInputSent",false));
        }finally{instrument.runOnMainSync(()->{if(touch!=null)touch.cancel();if(panel!=null)panel.dispose();activity.finish();});instrument.waitForIdleSync();}
    }
}
