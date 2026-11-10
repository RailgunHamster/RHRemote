package com.limelight.rhremote;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real attached/hardware-rendered Views in an isolated package; no stream or system input. */
@RunWith(AndroidJUnit4.class)
public final class RhFloatingDragDeviceTest {
    final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    Activity activity;FrameLayout root;RhFloatingKeyboard keyboard;View layer,button;long downTime;
    final List<String> actions=new ArrayList<>();
    void main(Runnable action){instrument.runOnMainSync(action);}
    Rect bounds(View view){Rect rect=new Rect();view.getDrawingRect(rect);root.offsetDescendantRectToMyCoords(view,rect);return rect;}
    void frames()throws Exception{CountDownLatch done=new CountDownLatch(1);main(()->root.postOnAnimation(()->root.postOnAnimation(done::countDown)));assertTrue("Owned window did not draw",done.await(5,TimeUnit.SECONDS));instrument.waitForIdleSync();}
    void touch(int action,float x,float y){main(()->{long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)downTime=now;MotionEvent event=MotionEvent.obtain(downTime,now,action,x,y,0);try{assertTrue(layer.dispatchTouchEvent(event));}finally{event.recycle();}});}
    Bitmap capture(String name,File folder)throws Exception{
        frames();Rect[] area={new Rect()};main(()->{int[] offset=new int[2];root.getLocationInWindow(offset);area[0].set(offset[0],offset[1],offset[0]+root.getWidth(),offset[1]+root.getHeight());});
        Bitmap image=Bitmap.createBitmap(area[0].width(),area[0].height(),Bitmap.Config.ARGB_8888);CountDownLatch done=new CountDownLatch(1);int[] result={-1};
        main(()->PixelCopy.request(activity.getWindow(),area[0],image,code->{result[0]=code;done.countDown();},new Handler(Looper.getMainLooper())));
        assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals("Own fixture window PixelCopy",PixelCopy.SUCCESS,result[0]);
        try(OutputStream output=new FileOutputStream(new File(folder,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}return image;
    }
    @Test public void attachedDragTrailIsVisibleAndDoesNotSurviveReleaseOrCancel()throws Exception{
        Context context=instrument.getTargetContext();assertTrue("Never run against the normal user application",context.getPackageName().contains(".uicheck"));
        File folder=context.getExternalFilesDir("drag-feedback-evidence");assertNotNull(folder);assertTrue(folder.isDirectory()||folder.mkdirs());JSONArray cases=new JSONArray();
        activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.rhremote.RhUiFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{for(int color:new int[]{Color.WHITE,0xff111722}){
            main(()->{context.getSharedPreferences("rhremote.global",0).edit().remove("overlayButtonPositions").remove("pinnedShortcuts").putBoolean("hideKeyboardButton",false).commit();root=new FrameLayout(activity);root.setBackgroundColor(color);activity.setContentView(root);keyboard=new RhFloatingKeyboard(activity,root,(id,args)->actions.add(id));keyboard.setEnabled(false);keyboard.update(true,false);});frames();
            Rect[] start={new Rect()};int[] end=new int[2];main(()->{layer=root.findViewWithTag("floating-keyboard");button=root.findViewWithTag("floating-ime-toggle");start[0].set(bounds(button));end[0]=Math.round(root.getWidth()*.4f);end[1]=Math.round(root.getHeight()*.4f);});
            touch(MotionEvent.ACTION_DOWN,start[0].centerX(),start[0].centerY());touch(MotionEvent.ACTION_MOVE,end[0],end[1]);
            Rect[] moved={new Rect()};main(()->{moved[0].set(bounds(button));keyboard.update(true,false);assertEquals(moved[0],bounds(button));assertTrue(keyboard.state().optJSONObject("dragFeedback").optBoolean("visible"));});
            Bitmap image=capture(color==Color.WHITE?"drag-light":"drag-dark",folder);int mx=(start[0].centerX()+moved[0].centerX())/2,my=(start[0].centerY()+moved[0].centerY())/2;boolean visible=false;
            for(int y=my-8;y<=my+8;y++)for(int x=mx-8;x<=mx+8;x++){int pixel=image.getPixel(x,y);visible|=Color.blue(pixel)>180&&Color.red(pixel)<150&&Color.green(pixel)<170;}assertTrue("No rendered drag trail",visible);image.recycle();
            touch(MotionEvent.ACTION_UP,end[0],end[1]);main(()->{assertFalse(keyboard.state().optJSONObject("dragFeedback").optBoolean("visible"));assertEquals(moved[0],bounds(button));});
            image=capture(color==Color.WHITE?"released-light":"released-dark",folder);assertEquals("Drag pixels remained after release",color,image.getPixel(mx,my));image.recycle();
            touch(MotionEvent.ACTION_DOWN,moved[0].centerX(),moved[0].centerY());touch(MotionEvent.ACTION_MOVE,end[0]+30,end[1]+80);touch(MotionEvent.ACTION_CANCEL,end[0]+30,end[1]+80);main(()->{assertFalse(keyboard.state().optJSONObject("dragFeedback").optBoolean("visible"));assertEquals(moved[0],bounds(button));assertTrue(actions.isEmpty());keyboard.dispose();});
            cases.put(RhActions.obj("background",color==Color.WHITE?"light":"dark","trailVisible",true,"releaseClears",true,"cancelRestores",true,"noRemoteActions",true));
        }
        try(Writer output=new OutputStreamWriter(new FileOutputStream(new File(folder,"result.json")),java.nio.charset.StandardCharsets.UTF_8)){output.write(RhActions.obj("success",true,"capture","own attached window PixelCopy","systemInput",false,"streamStarted",false,"cases",cases).toString());}
        }finally{main(()->{if(keyboard!=null)keyboard.dispose();activity.finish();});instrument.waitForIdleSync();}
    }
}
