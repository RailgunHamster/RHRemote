package com.limelight.rhremote;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** A settings page needs a usable viewport and reachable save/navigation controls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public final class RhSettingsViewportTest {
    Activity activity;RhHome home;View root;
    Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    void setup(float font)throws Exception{activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);Configuration c=new Configuration(activity.getResources().getConfiguration());c.fontScale=font;activity.getResources().updateConfiguration(c,activity.getResources().getDisplayMetrics());home=new RhHome(activity,new RhHome.Actions(){public void connect(ComputerDetails d){throw new AssertionError("No remote sessions");}public void add(String a){}public void advanced(){}public void pair(ComputerDetails d,String p){throw new AssertionError("No pairing");}public void cancelPair(){}public void refresh(){}});ComputerDetails d=new ComputerDetails();d.uuid="settings-space";d.name="winserver";d.manualAddress=new ComputerDetails.AddressTuple("192.0.2.183",47989);home.update(d);root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);}
    void force(View v){v.forceLayout();if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)force(((ViewGroup)v).getChildAt(i));}
    void layout(int width,int height){for(int i=0;i<5;i++){force(root);root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    void inside(View v){Rect r=new Rect(v.getLeft(),v.getTop(),v.getRight(),v.getBottom());if(v.getParent()!=root)((ViewGroup)root).offsetDescendantRectToMyCoords((View)v.getParent(),r);assertTrue("Clipped control: "+r,r.top>=0&&r.bottom<=root.getHeight()-root.getPaddingBottom()&&r.left>=root.getPaddingLeft()&&r.right<=root.getWidth()-root.getPaddingRight());}
    @After public void finish(){if(home!=null)home.dispose();if(activity!=null)activity.finish();android.content.res.Resources r=RuntimeEnvironment.getApplication().getResources();Configuration c=new Configuration(r.getConfiguration());c.fontScale=1;r.updateConfiguration(c,r.getDisplayMetrics());}

    @Test public void mediumWidthUsesOneRailWhileCompactWidthKeepsBottomNavigation()throws Exception{
        setup(1);for(int width:new int[]{599,600,839,840,1180,360}){layout(width,640);boolean rail=width>=600;assertEquals("Rail at "+width,rail?View.VISIBLE:View.GONE,((View)field(home,"sidebar")).getVisibility());assertEquals(rail?View.GONE:View.VISIBLE,((View)field(home,"compactHeader")).getVisibility());assertEquals(rail?View.GONE:View.VISIBLE,((View)field(home,"bar")).getVisibility());}
    }
    @Test public void shortLandscapeLeavesRoomForFieldsAndFixedSaveControls()throws Exception{
        setup(2);home.perform("device.settings.open",RhActions.obj("uuid","settings-space"));
        for(String tab:new String[]{"video","audio","input","network","advanced"}){home.perform("settings.tab",RhActions.obj("tab",tab));layout(640,360);ScrollView body=(ScrollView)field(home,"scroll");assertTrue(tab+" has no usable body: "+body.getHeight(),body.getHeight()>=96);inside((View)field(home,"formSave"));}
        home.perform("settings.cancel",RhActions.obj());for(String tab:new String[]{"updates","sync","appearance","about"}){home.perform("app.settings.tab",RhActions.obj("tab",tab));layout(640,360);assertTrue(tab+" has no usable body",((ScrollView)field(home,"scroll")).getHeight()>=96);}
    }
    @Test @Config(sdk=35) public void resizingAndImeInsetsPreserveTheSameDraftAndInputOwner()throws Exception{
        setup(1);home.perform("device.settings.open",RhActions.obj("uuid","settings-space"));home.perform("settings.tab",RhActions.obj("tab","audio"));home.perform("settings.input",RhActions.obj("key","volume","value","51"));String draft=home.state().getJSONObject("draft").toString();
        for(int width:new int[]{640,360,800,599,600,1008}){layout(width,640);assertEquals("settings",home.state().getString("page"));assertEquals("audio",home.state().getString("profileTab"));assertEquals(draft,home.state().getJSONObject("draft").toString());assertEquals("51",home.state().getJSONObject("settingsInputs").getString("volume"));}
        View rail=(View)field(home,"sidebar");ViewCompat.dispatchApplyWindowInsets(root,new WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(0,24,0,24)).setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,240)).setVisible(WindowInsetsCompat.Type.ime(),true).build());layout(1008,640);assertSame(rail,field(home,"sidebar"));assertEquals(View.VISIBLE,rail.getVisibility());assertEquals(draft,home.state().getJSONObject("draft").toString());
    }
}
