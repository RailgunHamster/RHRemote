package com.limelight.rhremote;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Browsing at the bottom must not hide navigation or consume the body on short screens. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi")
public final class RhDeviceHeaderTest {
    @After public void restoreFont(){android.content.res.Resources r=RuntimeEnvironment.getApplication().getResources();Configuration c=new Configuration(r.getConfiguration());c.fontScale=1;r.updateConfiguration(c,r.getDisplayMetrics());}
    private Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private void force(View v){v.forceLayout();if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)force(((ViewGroup)v).getChildAt(i));}
    private void layout(View v,int w,int h){for(int i=0;i<4;i++){force(v);v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}}
    private Rect bounds(View root,View child){Rect r=new Rect(child.getLeft(),child.getTop(),child.getRight(),child.getBottom());if(child.getParent()!=root)((ViewGroup)root).offsetDescendantRectToMyCoords((View)child.getParent(),r);return r;}
    private List<ComputerDetails> devices(){List<ComputerDetails> list=new ArrayList<>();for(int i=0;i<2;i++){ComputerDetails d=new ComputerDetails();d.uuid="pinned-"+i;d.name="设备 "+i;d.manualAddress=new ComputerDetails.AddressTuple("192.0.2."+(180+i),47989);list.add(d);}return list;}
    private Activity activity(float font){Activity a=Robolectric.buildActivity(Activity.class).setup().get();a.setTheme(R.style.RhTheme);Configuration c=new Configuration(a.getResources().getConfiguration());c.fontScale=font;a.getResources().updateConfiguration(c,a.getResources().getDisplayMetrics());return a;}
    private void inside(View root,View child){Rect r=bounds(root,child);assertTrue(r.toString(),r.top>=0&&r.bottom<=root.getHeight()&&r.left>=0&&r.right<=root.getWidth());}

    @Test public void titleAndSettingsStayAvailableAtBottomIncludingShortLandscape()throws Exception{
        for(float font:new float[]{1,2})for(int[] size:new int[][]{{360,640},{640,250},{1008,560}}){
            Activity a=activity(font);List<String> events=new ArrayList<>();RhDeviceBrowser b=new RhDeviceBrowser(a,(id,args)->events.add(id+":"+args.optString("uuid")));
            b.update(devices(),"pinned-0","");b.showDetail(true);layout(b,size[0],size[1]);
            ScrollView detail=(ScrollView)field(b,"detail");View chrome=(View)field(b,"detailChrome"),title=(View)field(b,"title");Button settings=(Button)field(b,"settings");
            Rect titleBefore=bounds(b,title),settingsBefore=bounds(b,settings);detail.scrollTo(0,detail.getChildAt(0).getHeight());
            assertTrue("The fixture must scroll",detail.getScrollY()>0);
            assertEquals("Selected device title scrolled away",titleBefore,bounds(b,title));assertEquals("Settings scrolled away",settingsBefore,bounds(b,settings));inside(b,settings);inside(b,title);
            assertTrue("No usable body at "+size[0]+"x"+size[1]+" font "+font+": "+detail.getHeight(),detail.getHeight()>=96);
            assertTrue("Body and navigation overlap",bounds(b,detail).top>=bounds(b,chrome).bottom);
            assertTrue(settings.performClick());assertEquals(Collections.singletonList("device.settings.open:pinned-0"),events);a.finish();
        }
    }

    @Test public void narrowListAndSettingsSidebarHideTheWholeDetailPane()throws Exception{
        Activity a=activity(1);List<String> events=new ArrayList<>();RhDeviceBrowser b=new RhDeviceBrowser(a,(id,args)->events.add(id));b.update(devices(),"pinned-0","");layout(b,360,640);
        View settings=(View)field(b,"settings");assertFalse(RhViewOrigin.visibleWithin(b,settings));b.showDetail(true);layout(b,360,640);assertTrue(RhViewOrigin.visibleWithin(b,settings));
        Button back=(Button)field(b,"back");assertTrue(back.performClick());assertEquals(Collections.singletonList("devices.open"),events);
        b.showDetail(false);layout(b,360,640);assertFalse(RhViewOrigin.visibleWithin(b,settings));b.setListOnly(true);layout(b,1008,640);assertFalse(RhViewOrigin.visibleWithin(b,settings));
        b.setListOnly(false);layout(b,1008,640);assertTrue(RhViewOrigin.visibleWithin(b,settings));a.finish();
    }

    @Test public void refreshRetainsBodyPositionButNewSelectionStartsAtTop()throws Exception{
        Activity a=activity(1);RhDeviceBrowser b=new RhDeviceBrowser(a,(id,args)->{});b.update(devices(),"pinned-0","");layout(b,1008,400);ScrollView detail=(ScrollView)field(b,"detail");detail.scrollTo(0,10000);int bottom=detail.getScrollY();assertTrue(bottom>0);
        b.update(devices(),"pinned-0","");layout(b,1008,400);assertEquals(bottom,detail.getScrollY());
        ((View)field(b,"settings")).requestFocus();b.update(devices(),"pinned-1","");layout(b,1008,400);assertEquals(0,detail.getScrollY());assertEquals("设备 1",((TextView)field(b,"title")).getText().toString());
        b.update(Collections.emptyList(),"","");layout(b,1008,400);assertEquals(View.GONE,((View)field(b,"detailChrome")).getVisibility());assertEquals(View.VISIBLE,((View)field(b,"detailEmpty")).getVisibility());a.finish();
    }
}
