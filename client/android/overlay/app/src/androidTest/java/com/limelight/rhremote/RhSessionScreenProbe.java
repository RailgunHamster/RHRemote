package com.limelight.rhremote;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import java.io.File;
import org.json.*;
import static org.junit.Assert.*;

/** Opt-in application dispatch probes. Select existing monitor IDs without changing modes. */
final class RhSessionScreenProbe {
    private RhSessionScreenProbe() {}
    private static void emulator(){assertTrue("Probe is restricted to the dedicated emulator",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));}
    private static String label(JSONObject monitor,boolean selected) {
        String name=monitor.optBoolean("virtual")?"虚拟屏":monitor.optString("name","物理屏幕");
        return name+" · "+monitor.optInt("width")+"×"+monitor.optInt("height")+(selected?"  ✓":"");
    }
    private static void reveal(RhSessionFlowDeviceTest test,View view) {
        test.instrument.runOnMainSync(()->{assertTrue(view.requestFocusFromTouch());view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);});
        test.instrument.waitForIdleSync();
    }
    static void screens(RhSessionFlowDeviceTest test)throws Exception {
        emulator();
        JSONObject before=new JSONObject(test.stream().getJSONObject("display").toString());
        JSONObject original=before.getJSONObject("selected"),other=null;JSONArray inventory=before.getJSONArray("displays");
        for(int i=0;i<inventory.length();i++){JSONObject monitor=inventory.getJSONObject(i);if(monitor.optBoolean("available")&&!monitor.optString("id").equals(original.optString("id"))){other=monitor;break;}}
        assertNotNull("Another existing available monitor is required",other);
        assertNotEquals("Use a physical and an existing virtual output",original.getBoolean("virtual"),other.getBoolean("virtual"));
        JSONArray steps=new JSONArray();
        try {
            for(JSONObject target:new JSONObject[]{other,original}) {
                JSONObject selected=test.stream().getJSONObject("display").getJSONObject("selected");
                View row=test.namedControl(label(target,target.getString("id").equals(selected.getString("id"))));reveal(test,row);
                long started=System.currentTimeMillis();RhStatistics.event("ui_display_test_requested",-1,RhActions.obj("gdi",target.getString("gdi"),"captureIndex",target.getInt("captureIndex")));
                boolean[] duplicateRejected={false};
                test.instrument.runOnMainSync(()->{row.performClick();assertTrue(RhActions.activeState().optBoolean("displayBusy"));assertTrue("Busy selection lost local focus",test.namedControl("收起").hasFocus());duplicateRejected[0]=!RhActions.dispatch("stream.display.select",RhActions.obj("displayId",target.optString("id"))).optBoolean("ok");});
                assertTrue("Selection accepted a second request while busy",duplicateRejected[0]);
                test.waitFor(()->!test.stream().optBoolean("displayBusy"),25,"Monitor request completion");
                JSONObject after=test.stream();assertEquals("",after.optString("displayError"));assertEquals(target.getString("id"),after.getJSONObject("display").getJSONObject("selected").getString("id"));
                assertEquals("Monitor topology changed",RhShared.canonical(RhActions.obj("displays",inventory)),RhShared.canonical(RhActions.obj("displays",after.getJSONObject("display").getJSONArray("displays"))));
                test.instrument.runOnMainSync(()->assertTrue("Selection lost the focused monitor",test.namedControl(label(target,true)).hasFocus()));
                // Sending the shortcut is not a host acknowledgement. Keep each source observable;
                // the controller verifies the actual capture sizes and generations in host logs.
                Thread.sleep(3000);
                String name="session-switch-"+target.getInt("captureIndex");JSONObject pixels=test.videoPixels(name+"-video");assertEquals(0,pixels.getInt("pixelCopyResult"));test.capture(name+"-controls");
                RhStatistics.event("ui_display_test_observed",-1,RhActions.obj("gdi",target.getString("gdi"),"captureIndex",target.getInt("captureIndex")));
                steps.put(RhActions.obj("target",target,"requestedAtMs",started,"observedAtMs",System.currentTimeMillis(),"duplicateRejected",true,"rowFocusRestored",true,"videoPixels",pixels,"hostCaptureAcknowledged",false));
            }
            RhShared.write(new File(test.folder,"session-screen-switches.json"),RhActions.obj("before",before,"steps",steps,"after",test.stream().getJSONObject("display"),"topologyUnchanged",true,"existingMonitorIdsOnly",true,"virtualModeRequested",false,"serverLogVerificationRequired",true));
        } finally {
            JSONObject current=test.stream().optJSONObject("display");
            if(current!=null&&current.optJSONObject("selected")!=null&&!original.getString("id").equals(current.getJSONObject("selected").optString("id"))){test.waitFor(()->!test.stream().optBoolean("displayBusy"),25,"Restore original capture selection");test.action("stream.display.select",RhActions.obj("displayId",original.getString("id")));test.waitFor(()->!test.stream().optBoolean("displayBusy"),25,"Original capture selection restored");}
        }
    }
    private static View text(View view,String value) {
        if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;
    }
    private static void touch(View view,long down,int action,float[] xs,float y) {
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[xs.length];MotionEvent.PointerCoords[] points=new MotionEvent.PointerCoords[xs.length];
        for(int i=0;i<xs.length;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;points[i]=new MotionEvent.PointerCoords();points[i].x=xs[i];points[i].y=y;points[i].pressure=1;points[i].size=.1f;}
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,xs.length,properties,points,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{boolean handled=view.dispatchTouchEvent(event);if(action!=MotionEvent.ACTION_CANCEL)assertTrue("Background touch View rejected action "+action,handled);}finally{event.recycle();}
    }
    static void pinch(RhSessionFlowDeviceTest test)throws Exception {
        emulator();
        test.click("操作");assertFalse(test.stream().getBoolean("pan"));SurfaceView[] surface={null};
        test.instrument.runOnMainSync(()->{surface[0]=test.game.findViewById(R.id.surfaceView);View input=test.game.findViewById(R.id.backgroundTouchView);assertNotNull(input);float width=surface[0].getWidth(),height=surface[0].getHeight(),left=surface[0].getLeft(),y=surface[0].getTop()+height*.5f;assertTrue(width>0&&height>0);long down=SystemClock.uptimeMillis();
            try{touch(input,down,MotionEvent.ACTION_DOWN,new float[]{left+width*.35f},y);touch(input,down,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{left+width*.35f,left+width*.65f},y);touch(input,down,MotionEvent.ACTION_MOVE,new float[]{left+width*.275f,left+width*.725f},y);
                assertEquals(1.5f,surface[0].getScaleX(),.01f);assertEquals(1.5f,surface[0].getScaleY(),.01f);
                // The input background is not transformed with the video. A stationary
                // following move therefore uses exactly the same background coordinates.
                touch(input,down,MotionEvent.ACTION_MOVE,new float[]{left+width*.275f,left+width*.725f},y);assertEquals("Stationary fingers changed scale",1.5f,surface[0].getScaleX(),.01f);
            }finally{touch(input,down,MotionEvent.ACTION_CANCEL,new float[]{left+width*.35f},y);}
        });test.instrument.waitForIdleSync();
        JSONObject zoom=test.stream().getJSONObject("zoom");assertEquals(1.5,zoom.getDouble("scale"),.01);View[] label={null};test.instrument.runOnMainSync(()->{label[0]=text(test.game.findViewById(android.R.id.content),"缩放 150%");assertNotNull("Live pinch did not update the panel",label[0]);label[0].requestRectangleOnScreen(new Rect(0,0,label[0].getWidth(),label[0].getHeight()),true);});test.instrument.waitForIdleSync();test.capture("session-touch-zoom-controls");
        reveal(test,test.namedControl("还原缩放"));test.click("还原缩放");assertEquals(1,test.stream().getJSONObject("zoom").getDouble("scale"),.001);
        test.instrument.runOnMainSync(()->{assertEquals(1,surface[0].getScaleX(),.001);assertEquals(1,surface[0].getScaleY(),.001);assertEquals(0,surface[0].getTranslationX(),.001);assertEquals(0,surface[0].getTranslationY(),.001);});
        RhShared.write(new File(test.folder,"session-touch-zoom.json"),RhActions.obj("zoomBeforeReset",zoom,"stationaryMoveStable",true,"liveLabelUpdated",true,"resetByActualButton",true,"viewTransformRestored",true,"applicationMotionEventDispatch",true,"systemInputInjected",false,"physicalTouchTested",false));
    }
}
