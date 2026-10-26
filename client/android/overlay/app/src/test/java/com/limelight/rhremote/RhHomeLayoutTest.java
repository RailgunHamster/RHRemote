package com.limelight.rhremote;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=RhAdmissionTest.Controls.class)
public final class RhHomeLayoutTest {
    private Activity activity;
    private RhHome home;
    private View root;
    private final RhHome.Actions callbacks=new RhHome.Actions(){
        public void connect(ComputerDetails d){throw new AssertionError("Browsing must not start a connection");}
        public void add(String address){}public void advanced(){}public void pair(ComputerDetails d,String pin){}public void cancelPair(){}public void refresh(){}
    };
    private void setup() throws Exception {
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);
        RhShared.theme(activity,false);home=new RhHome(activity,callbacks);
        for(int i=0;i<3;i++){ComputerDetails d=new ComputerDetails();d.uuid="host-"+i;d.name=i==0?"winserver":i==1?"RailgunHamster":"Claw";d.manualAddress=new ComputerDetails.AddressTuple("192.168.0."+(100+i),47989);d.state=ComputerDetails.State.ONLINE;d.pairState=i==2?PairingManager.PairState.NOT_PAIRED:PairingManager.PairState.PAIRED;home.update(d);}
        root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
    }
    private void forceLayout(View v){v.forceLayout();if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)forceLayout(((ViewGroup)v).getChildAt(i));}
    private void layout(int width,int height){for(int i=0;i<4;i++){forceLayout(root);root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    private List<Button> buttons(View view){List<Button> result=new ArrayList<>();if(view.getVisibility()!=View.VISIBLE)return result;if(view instanceof Button)result.add((Button)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)result.addAll(buttons(((ViewGroup)view).getChildAt(i)));return result;}
    private void assertButtonsInsideParents(){for(Button b:buttons(root)){View parent=(View)b.getParent();String label=b.getText().toString();assertTrue(label+" too short: "+b.getHeight(),b.getHeight()>=48);assertTrue(label+" crosses left padding",b.getLeft()>=parent.getPaddingLeft());assertTrue(label+" crosses right padding "+b.getRight()+"/"+parent.getWidth(),b.getRight()<=parent.getWidth()-parent.getPaddingRight());assertTrue(label+" crosses parent bottom",b.getBottom()<=parent.getHeight()-parent.getPaddingBottom());}}

    @Test public void landscapePortraitAndNarrowWindowsKeepTheirButtonsInsideTheLayout()throws Exception {
        setup();
        for(int[] size:new int[][]{{1200,800},{1008,672},{800,1200},{360,800},{640,360}}){
            home.perform("devices.open",RhActions.obj());layout(size[0],size[1]);assertButtonsInsideParents();
            home.perform("device.select",RhActions.obj("uuid","host-0"));layout(size[0],size[1]);assertButtonsInsideParents();
            String mode=home.state().getJSONObject("homeLayout").getJSONObject("browser").getString("mode");assertEquals(size[0]>=700?"split":"detail",mode);
            for(String tab:new String[]{"updates","sync","appearance","about"}){home.perform("app.settings.tab",RhActions.obj("tab",tab));layout(size[0],size[1]);assertButtonsInsideParents();}
            home.perform("device.settings.open",RhActions.obj("uuid","host-0"));
            for(String tab:new String[]{"video","audio","input","network","advanced"}){home.perform("settings.tab",RhActions.obj("tab",tab));layout(size[0],size[1]);assertButtonsInsideParents();}
        }
        home.dispose();
    }
    @Test public void selectingSearchingAndReturningDoNotRewriteConnectionProfiles()throws Exception {
        setup();layout(360,800);String before=RhShared.canonical(RhShared.read(activity));
        home.perform("device.select",RhActions.obj("uuid","host-2"));layout(360,800);assertEquals("host-2",home.state().getString("selected"));assertEquals("detail",home.state().getJSONObject("homeLayout").getJSONObject("browser").getString("mode"));
        assertTrue(home.goBack());layout(360,800);assertEquals("list",home.state().getJSONObject("homeLayout").getJSONObject("browser").getString("mode"));
        home.perform("devices.filter",RhActions.obj("query","CLAW"));layout(360,800);assertEquals("CLAW",home.state().getString("deviceQuery"));
        try{home.perform("device.select",RhActions.obj("uuid","missing"));fail("Unknown selection accepted");}catch(IllegalArgumentException expected){}
        assertEquals("host-2",home.state().getString("selected"));
        assertEquals(before,RhShared.canonical(RhShared.read(activity)));home.dispose();
    }
    @Test @Config(sdk=35) public void safeInsetsStayOutsideAllNavigationAndContent()throws Exception {
        setup();ViewCompat.dispatchApplyWindowInsets(root,new WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(12,28,16,24)).build());layout(1008,672);
        assertEquals(12,root.getPaddingLeft());assertEquals(28,root.getPaddingTop());assertEquals(16,root.getPaddingRight());assertEquals(24,root.getPaddingBottom());assertButtonsInsideParents();home.dispose();
    }
    @Test public void actionGroupsWrapWithoutOverlapAtSmallWidths(){
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        for(int width:new int[]{288,360,640}){RhActionRow row=new RhActionRow(activity);for(String label:new String[]{"保存设置","立即同步","移除设置","冲突项保留本机","冲突项保留远端"}){Button b=new Button(activity);b.setText(label);b.setMinHeight(48);row.addView(b,new ViewGroup.LayoutParams(-2,48));}
            row.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));row.layout(0,0,width,row.getMeasuredHeight());
            for(int i=0;i<row.getChildCount();i++){View child=row.getChildAt(i);assertTrue(child.getLeft()>=0&&child.getRight()<=width);assertTrue(child.getBottom()<=row.getHeight());Rect a=new Rect(child.getLeft(),child.getTop(),child.getRight(),child.getBottom());for(int j=0;j<i;j++){View other=row.getChildAt(j);assertFalse(Rect.intersects(a,new Rect(other.getLeft(),other.getTop(),other.getRight(),other.getBottom())));}}
        }
    }
}
