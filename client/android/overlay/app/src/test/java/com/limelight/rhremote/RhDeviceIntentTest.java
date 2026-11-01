package com.limelight.rhremote;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.*;
import android.widget.Button;
import com.limelight.R;
import com.limelight.nvstream.http.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Dispatches events only to this test's Views; never injects system or remote input. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,qualifiers="mdpi")
public class RhDeviceIntentTest {
    Activity activity;RhDeviceBrowser browser;ComputerDetails first,second;
    final List<JSONObject> sent=new ArrayList<>();
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        first=device("a");second=device("b");browser=new RhDeviceBrowser(activity,(id,args)->sent.add(RhActions.obj("action",id,"args",args)));
        activity.setContentView(browser);update(first.uuid);
    }
    ComputerDetails device(String uuid){ComputerDetails d=new ComputerDetails();d.uuid=uuid;d.name="PC "+uuid;d.state=ComputerDetails.State.ONLINE;d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.10",47989);return d;}
    void paired(ComputerDetails d){d.pairState=PairingManager.PairState.PAIRED;d.serverCert=com.limelight.binding.PlatformBinding.getCryptoProvider(activity).getClientCertificate();}
    void update(String selected){browser.update(Arrays.asList(first,second),selected,"");browser.showDetail(true);browser.measure(View.MeasureSpec.makeMeasureSpec(1008,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(672,View.MeasureSpec.EXACTLY));browser.layout(0,0,1008,672);}
    Button find(View view,String label){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
    void event(View button,int action,long down){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,button.getWidth()/2f,button.getHeight()/2f,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{assertTrue(button.dispatchTouchEvent(e));}finally{e.recycle();}}
    void gesture(View button,Runnable change){long down=SystemClock.uptimeMillis();event(button,MotionEvent.ACTION_DOWN,down);change.run();event(button,MotionEvent.ACTION_UP,down);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void expect(String action,String uuid)throws Exception{assertEquals(1,sent.size());assertEquals(action,sent.get(0).getString("action"));assertEquals(uuid,sent.get(0).getJSONObject("args").getString("uuid"));}
    @Test public void aHeldCheckCannotBecomeConnectAfterAPairingRefresh()throws Exception{
        Button held=find(browser,"检查连接");assertNotNull(held);gesture(held,()->{paired(first);update(first.uuid);});
        assertTrue("Holding Check unexpectedly dispatched "+sent,sent.isEmpty());find(browser,"连接").performClick();expect("device.connect",first.uuid);
    }
    @Test public void changingTheSelectedDeviceCannotRedirectAHeldConnect()throws Exception{
        paired(first);paired(second);update(first.uuid);Button held=find(browser,"连接");gesture(held,()->update(second.uuid));
        assertTrue("The old gesture targeted the new device: "+sent,sent.isEmpty());find(browser,"连接").performClick();expect("device.connect",second.uuid);
    }
    @Test public void returningToTheOriginalSelectionDoesNotReviveAnInvalidatedGesture(){
        paired(first);paired(second);update(first.uuid);Button held=find(browser,"连接");gesture(held,()->{update(second.uuid);update(first.uuid);});assertTrue("Selection changed during the gesture: "+sent,sent.isEmpty());
    }
    @Test public void anUnrelatedNameRefreshDoesNotDiscardTheSameIntent()throws Exception{
        paired(first);update(first.uuid);Button held=find(browser,"连接");gesture(held,()->{first.name="Renamed PC";update(first.uuid);});expect("device.connect",first.uuid);
    }
    @Test public void heldSettingsInfoAndOrderingControlsCannotSwitchTheirTarget()throws Exception{
        for(String label:new String[]{"连接设置","编辑资料","下移"}){
            sent.clear();update(first.uuid);Button held=find(browser,label);assertNotNull(label,held);gesture(held,()->update(second.uuid));assertTrue(label+" redirected: "+sent,sent.isEmpty());
        }
    }
    @Test public void hidingAndShowingTheSameParentDoesNotRestoreTheOldGesture()throws Exception{
        paired(first);update(first.uuid);Button held=find(browser,"连接");gesture(held,()->{browser.setVisibility(View.GONE);browser.setVisibility(View.VISIBLE);});assertTrue(sent.isEmpty());held.performClick();expect("device.connect",first.uuid);
    }
    @Test public void disablingAndReenablingTheSameButtonDoesNotRestoreTheOldGesture()throws Exception{
        paired(first);update(first.uuid);Button held=find(browser,"连接");gesture(held,()->{held.setEnabled(false);held.setEnabled(true);});assertTrue(sent.isEmpty());held.performClick();expect("device.connect",first.uuid);
    }
    @Test public void cancelAndAnOrphanReleaseCannotSubmitAgain()throws Exception{
        paired(first);update(first.uuid);Button held=find(browser,"连接");long down=SystemClock.uptimeMillis();event(held,MotionEvent.ACTION_DOWN,down);event(held,MotionEvent.ACTION_CANCEL,down);event(held,MotionEvent.ACTION_UP,down);Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(sent.isEmpty());held.performClick();expect("device.connect",first.uuid);
    }
    @Test public void aPostedClickCannotReadTheNewBindingWhenTheUiQueueDrains()throws Exception{
        paired(first);paired(second);update(first.uuid);Button held=find(browser,"连接");long down=SystemClock.uptimeMillis();event(held,MotionEvent.ACTION_DOWN,down);event(held,MotionEvent.ACTION_UP,down);update(second.uuid);Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("Posted click used a later binding: "+sent,sent.isEmpty());held.performClick();expect("device.connect",second.uuid);
    }
    @Test public void keyboardReleaseAndRepeatCannotRedirectTheHeldAction()throws Exception{
        paired(first);paired(second);update(first.uuid);Button held=find(browser,"连接");held.setFocusableInTouchMode(true);assertTrue(held.requestFocus());long time=SystemClock.uptimeMillis();
        held.dispatchKeyEvent(new KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0));update(second.uuid);held.dispatchKeyEvent(new KeyEvent(time,time+1,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,1));held.dispatchKeyEvent(new KeyEvent(time,time+2,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0));Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(sent.isEmpty());held.performClick();expect("device.connect",second.uuid);
    }
    @Test public void theSameKeyboardIntentStillActivatesOnce()throws Exception{
        paired(first);update(first.uuid);Button held=find(browser,"连接");held.setFocusableInTouchMode(true);assertTrue(held.requestFocus());long time=SystemClock.uptimeMillis();
        held.dispatchKeyEvent(new KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0));first.name="Updated label";update(first.uuid);held.dispatchKeyEvent(new KeyEvent(time,time+1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0));Shadows.shadowOf(Looper.getMainLooper()).idle();expect("device.connect",first.uuid);
    }
    @Test public void aDeviceRowCannotChangeFromSelectToSettingsDuringItsGesture()throws Exception{
        View row=browser.findViewWithTag("missing");java.lang.reflect.Field rows=RhDeviceBrowser.class.getDeclaredField("rows");rows.setAccessible(true);row=((Map<String,? extends View>)rows.get(browser)).get(first.uuid);assertNotNull(row);
        gesture(row,()->browser.setListOnly(true));assertTrue(sent.isEmpty());row.performClick();expect("device.settings.open",first.uuid);
    }
    @Test public void sourceAndReceiverJsonCannotChangeAnAlreadyBoundAction()throws Exception{
        RhBoundAction.Button button=new RhBoundAction.Button(activity,(view,id,args)->{sent.add(RhActions.obj("action",id,"args",RhActions.obj("uuid",args.optString("uuid"))));try{args.put("uuid","receiver mutation");}catch(Exception failure){throw new AssertionError(failure);}});
        JSONObject args=RhActions.obj("uuid","a");button.bind("device.connect",args);args.put("uuid","source mutation");button.performClick();button.performClick();assertEquals(2,sent.size());for(JSONObject result:sent)assertEquals("a",result.getJSONObject("args").getString("uuid"));
    }
}
