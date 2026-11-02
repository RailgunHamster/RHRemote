package com.limelight.rhremote;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w1200dp-h1400dp-mdpi",shadows=RhAdmissionTest.Controls.class)
public final class RhLayoutReorderTest {
    Activity activity;RhHome home;View root;
    void setup()throws Exception{
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);RhShared.theme(activity,false);
        home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No connections in layout tests");}public void add(String s){}public void advanced(){}public void pair(ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}});
        for(String id:new String[]{"a","b","c"}){ComputerDetails d=new ComputerDetails();d.uuid=id;d.name=id.toUpperCase(Locale.ROOT);d.manualAddress=new ComputerDetails.AddressTuple(id+".local",47989);d.state=ComputerDetails.State.ONLINE;home.update(d);}
        root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);layout(1200,800);
    }
    void force(View v){v.forceLayout();if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)force(((ViewGroup)v).getChildAt(i));}
    void layout(int w,int h){View decor=activity.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));decor.layout(0,0,w,h);for(int i=0;i<4;i++){force(root);root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);}}
    View described(View v,String label){if(v.getVisibility()!=View.VISIBLE)return null;if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=described(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    int[] at(View v){int[] p=new int[2];v.getLocationOnScreen(p);return p;}
    void drag(View grip,View target){assertNotNull(grip);assertNotNull(target);assertTrue("Grip attached",grip.isAttachedToWindow());Rect visible=new Rect();if(!target.getGlobalVisibleRect(visible)){String chain="";for(View v=target;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null)chain+=v.getClass().getSimpleName()+":"+v.getWidth()+"x"+v.getHeight()+"@"+java.util.Arrays.toString(at(v))+" visible="+v.getVisibility()+"; ";fail("Target clipped: "+chain);}int[] a=at(grip),b=at(target);float sx=a[0]+grip.getWidth()/2f,sy=a[1]+grip.getHeight()/2f,tx=b[0]+target.getWidth()/2f,ty=b[1]+target.getHeight()*.8f;long down=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(down,down+16,action,action==MotionEvent.ACTION_DOWN?sx:tx,action==MotionEvent.ACTION_DOWN?sy:ty,0);e.offsetLocation(-a[0],-a[1]);assertEquals("Raw gesture Y",action==MotionEvent.ACTION_DOWN?sy:ty,e.getRawY(),.1);grip.dispatchTouchEvent(e);e.recycle();}
    }
    @Test public void deviceDragUsesPersistentSyncedOrderAndKeepsTheOpenDraft()throws Exception{
        setup();home.perform("device.select",RhActions.obj("uuid","b"));home.perform("device.settings.open",RhActions.obj("uuid","b"));home.perform("settings.input",RhActions.obj("key","fps","value","23"));layout(1200,1400);
        String before=home.state().getJSONObject("draft").toString();drag(described(root,"拖动设备 A 排序"),described(root,"选择设备 C"));layout(1200,800);
        JSONArray saved=RhShared.read(activity).getJSONArray("devices");assertEquals("No drag commit: "+home.state().optString("status"),3,saved.length());assertEquals("b",saved.getJSONObject(0).getString("uuid"));assertEquals("c",saved.getJSONObject(1).getString("uuid"));assertEquals("a",saved.getJSONObject(2).getString("uuid"));
        assertEquals("settings",home.state().getString("page"));assertEquals("b",home.state().getString("selected"));assertEquals(before,home.state().getJSONObject("draft").toString());assertEquals("23",home.state().getJSONObject("settingsInputs").getString("fps"));assertEquals(30,RhProfile.load(activity,"b").fps);home.dispose();
    }
    @Test public void routeGripUsesTheSameDraftActionAndTheResultCanBeCancelled()throws Exception{
        setup();home.perform("device.settings.open",RhActions.obj("uuid","b"));home.perform("settings.tab",RhActions.obj("tab","network"));layout(1200,1000);
        LinearLayout routes=root.findViewWithTag("route-order");assertNotNull(routes);ScrollView scroll=ReflectionHelpers.getField(home,"scroll");Rect r=new Rect();routes.getDrawingRect(r);scroll.offsetDescendantRectToMyCoords(routes,r);scroll.scrollTo(0,r.top);layout(1200,1000);
        JSONArray original=home.state().getJSONObject("draft").getJSONArray("routeOrder");String source=original.getString(0),target=original.getString(original.length()-1);View first=routes.getChildAt(0),last=routes.getChildAt(original.length()-1);drag(((ViewGroup)first).getChildAt(0),last);
        JSONArray order=home.state().getJSONObject("draft").getJSONArray("routeOrder");assertEquals(source,order.getString(order.length()-1));assertEquals(original.toString(),new JSONArray(RhProfile.load(activity,"b").routeOrder).toString());home.perform("settings.cancel",RhActions.obj());assertEquals(original.toString(),new JSONArray(RhProfile.load(activity,"b").routeOrder).toString());home.dispose();
    }
    @Test public void deviceListRemainsBesideFormsAndGlobalTabsStaySeparatedWhileScrolling()throws Exception{
        setup();for(String page:new String[]{"settings","global-settings","add"}){if(page.equals("settings"))home.perform("device.settings.open",RhActions.obj("uuid","b"));else if(page.equals("add"))home.perform("device.add.open",RhActions.obj());else home.perform("app.settings.open",RhActions.obj());layout(1200,800);View row=described(root,"选择设备 A");assertNotNull(page,row);assertTrue(at(row)[0]<273);assertEquals(View.GONE,((View)ReflectionHelpers.getField(home,"sidebarScroll")).getVisibility());}
        home.perform("app.settings.tab",RhActions.obj("tab","updates"));home.perform("updates.history.toggle",RhActions.obj());layout(1200,800);View gap=root.findViewWithTag("settings-separation");ScrollView body=ReflectionHelpers.getField(home,"scroll");View header=ReflectionHelpers.getField(home,"formHeader");assertEquals(View.VISIBLE,gap.getVisibility());assertTrue(gap.getHeight()>=13);int y=at(gap)[1];body.scrollTo(0,1000);layout(1200,800);assertEquals(y,at(gap)[1]);assertTrue(at(body)[1]>=at(header)[1]+header.getHeight()+13);assertNull(described(root,"深色模式"));home.dispose();
    }
}
