package com.limelight.rhremote;

import android.app.Activity;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi")
public final class RhFloatingKeyboardTest {
    private FrameLayout root;private RhFloatingKeyboard keyboard;private final List<String> actions=new ArrayList<>();
    private void setup(int width,int height){Activity activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);root=new FrameLayout(activity);activity.setContentView(root);keyboard=new RhFloatingKeyboard(activity,root,(id,args)->{assertEquals("stream.shortcut",id);actions.add(args.optString("shortcut"));});keyboard.setEnabled(true);keyboard.update(true,false);for(int pass=0;pass<3;pass++){root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    private Button key(String id){return root.findViewWithTag("floating-key:"+id);}
    private Rect bounds(String id){Rect r=new Rect();key(id).getHitRect(r);return r;}
    @Test public void keysRespectKeyboardPositionsAndStaySeparatedInLandscapeAndPortrait(){
        for(int[] size:new int[][]{{840,560},{360,800},{640,360},{320,240}}){setup(size[0],size[1]);List<Rect> occupied=new ArrayList<>();for(String id:new String[]{"escape","tab","shift-tab","backspace","delete","enter","arrow-up","arrow-left","arrow-down","arrow-right"}){if(key(id).getVisibility()!=View.VISIBLE)continue;Rect r=bounds(id);assertTrue(id,r.left>=0&&r.top>=0&&r.right<=size[0]&&r.bottom<=size[1]);assertTrue(r.height()>=48);for(Rect other:occupied)assertFalse(id+" overlaps a key",Rect.intersects(other,r));occupied.add(r);}assertTrue(bounds("tab").centerX()<size[0]/3);assertTrue(bounds("tab").centerY()<size[1]/2);assertEquals(bounds("arrow-up").centerX(),bounds("arrow-down").centerX());assertEquals(bounds("arrow-left").centerY(),bounds("arrow-right").centerY());assertTrue(bounds("arrow-down").centerX()>size[0]/2);assertTrue(bounds("arrow-up").bottom<bounds("arrow-down").top);keyboard.dispose();}
    }
    @Test public void blankAreasPassTouchesAndOnlyReadyVisibleKeysDispatch(){setup(840,560);View layer=root.findViewWithTag("floating-keyboard");MotionEvent down=MotionEvent.obtain(1,1,MotionEvent.ACTION_DOWN,420,280,0);try{assertFalse("Blank overlay must not consume remote touches",layer.dispatchTouchEvent(down));}finally{down.recycle();}for(String id:new String[]{"tab","shift-tab","arrow-up","arrow-left","arrow-down","arrow-right","enter","backspace","delete","escape"})key(id).performClick();assertEquals(10,actions.size());Button old=key("tab");keyboard.update(false,false);old.performClick();assertEquals(10,actions.size());keyboard.update(true,true);old.performClick();assertEquals(10,actions.size());keyboard.dispose();old.performClick();assertEquals(10,actions.size());}
    @Test public void switchOffRemovesTheOverlayWithoutChangingRemoteInputState(){setup(840,560);keyboard.setEnabled(false);assertEquals(View.GONE,root.findViewWithTag("floating-keyboard").getVisibility());assertFalse(keyboard.enabled());keyboard.setEnabled(true);assertEquals(View.VISIBLE,root.findViewWithTag("floating-keyboard").getVisibility());keyboard.dispose();assertNull(root.findViewWithTag("floating-keyboard"));}
    @Test public void keysHiddenByResizeOrDetachedFromTheirOwnerCannotDispatchQueuedClicks(){
        setup(840,560);Button backspace=key("backspace"),tab=key("tab");
        backspace.performClick();assertEquals(java.util.Collections.singletonList("backspace"),actions);actions.clear();
        for(int pass=0;pass<3;pass++){root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));root.layout(0,0,320,240);}
        assertEquals(View.GONE,backspace.getVisibility());backspace.performClick();assertTrue("Resized hidden key sent a shortcut",actions.isEmpty());
        View layer=root.findViewWithTag("floating-keyboard");root.removeView(layer);tab.performClick();assertTrue("Detached key sent a shortcut",actions.isEmpty());
        root.addView(layer,new FrameLayout.LayoutParams(-1,-1));tab.performClick();assertEquals(java.util.Collections.singletonList("tab"),actions);
    }
    @Test @Config(sdk=35,qualifiers="mdpi") public void insetDispatchMovesKeysWithoutAParentResizeAndPreservesTheRootListener(){
        setup(840,560);int[] rootCalls={0};androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{rootCalls[0]++;return insets;});
        androidx.core.view.WindowInsetsCompat shown=new androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(20,24,30,32)).build();
        androidx.core.view.WindowInsetsCompat returned=androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,shown);
        assertEquals(1,rootCalls[0]);assertEquals(24,returned.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top);assertEquals(840,root.getWidth());assertEquals(560,root.getHeight());
        FrameLayout.LayoutParams escape=(FrameLayout.LayoutParams)key("escape").getLayoutParams(),down=(FrameLayout.LayoutParams)key("arrow-down").getLayoutParams();assertEquals(36,escape.leftMargin);assertEquals(40,escape.topMargin);assertEquals(560-32-16-48,down.topMargin);
        androidx.core.view.WindowInsetsCompat hidden=new androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.NONE).build();
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,hidden);assertEquals(2,rootCalls[0]);assertEquals(16,((FrameLayout.LayoutParams)key("escape").getLayoutParams()).topMargin);assertTrue(actions.isEmpty());keyboard.dispose();
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,shown);assertEquals("Disposing the layer must leave the root listener installed",3,rootCalls[0]);
    }
}
