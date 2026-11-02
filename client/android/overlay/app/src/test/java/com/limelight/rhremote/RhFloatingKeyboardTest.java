package com.limelight.rhremote;

import android.app.Activity;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.limelight.R;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,qualifiers="mdpi")
public final class RhFloatingKeyboardTest {
    Activity activity;FrameLayout root;RhFloatingKeyboard keyboard;final List<JSONObject> actions=new ArrayList<>();int width,height;
    void setup(int w,int h){RuntimeEnvironment.setQualifiers("w"+w+"dp-h"+h+"dp-mdpi");width=w;height=h;activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.RhTheme);activity.getSharedPreferences("rhremote.global",0).edit().remove("hideKeyboardButton").remove("keyboardLayout").commit();root=new FrameLayout(activity);activity.setContentView(root);create();}
    void create(){keyboard=new RhFloatingKeyboard(activity,root,(id,args)->actions.add(RhActions.obj("action",id,"args",args)));keyboard.setEnabled(true);keyboard.update(true,false);layout();}
    void layout(){for(int pass=0;pass<4;pass++){root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}}
    Button key(String id){Button key=root.findViewWithTag("floating-key:"+id);assertNotNull(id,key);return key;}
    Rect bounds(View key){Rect r=new Rect();key.getDrawingRect(r);root.offsetDescendantRectToMyCoords(key,r);return r;}
    @After public void cleanup(){if(keyboard!=null)keyboard.dispose();}
    @Test public void all108KeysExistAndCanBeReachedOnWideAndNarrowScreens()throws Exception{
        for(int[] size:new int[][]{{1080,720},{360,800},{640,360},{320,240}}){setup(size[0],size[1]);JSONArray catalog=RhKeyboard.catalog(activity);assertEquals(108,catalog.length());Set<String> ids=new HashSet<>();
            for(int i=0;i<catalog.length();i++){String id=catalog.getJSONObject(i).getString("id");assertTrue(ids.add(id));Button key=key(id);keyboard.reveal(id);layout();Rect r=new Rect();assertTrue(id+" cannot be reached "+Arrays.toString(size),key.getLocalVisibleRect(r));assertEquals("A key is clipped vertically "+id,key.getHeight(),r.height());assertEquals("A key is clipped horizontally "+id,key.getWidth(),r.width());assertTrue(key.getWidth()>=32);assertTrue(key.getHeight()>=36);actions.clear();key.performClick();assertEquals(id,1,actions.size());assertEquals("stream.keyboard.press",actions.get(0).getString("action"));assertEquals(id,actions.get(0).getJSONObject("args").getString("key"));}
            keyboard.dispose();
        }
    }
    @Test public void standardKeyboardGroupsHaveNoOverlappingKeys()throws Exception{
        setup(1080,720);JSONArray catalog=RhKeyboard.catalog(activity);List<Rect> occupied=new ArrayList<>();for(int i=0;i<catalog.length();i++){Rect r=new Rect();key(catalog.getJSONObject(i).getString("id")).getHitRect(r);for(Rect prior:occupied)assertFalse(Rect.intersects(r,prior));occupied.add(r);}
        assertTrue(key("tab").getLeft()<key("q").getLeft());assertTrue(key("num-0").getLeft()>key("arrow-right").getLeft());assertEquals(key("arrow-up").getLeft(),key("arrow-down").getLeft());assertEquals(key("arrow-left").getTop(),key("arrow-right").getTop());assertTrue(key("num-enter").getHeight()>key("num-1").getHeight());
    }
    @Test public void narrowScreenBankButtonsUseTheRevealActionAndReachTheNumberPad()throws Exception{
        setup(360,800);View right=root.findViewWithTag("floating-keyboard-bank:num-5");assertEquals(View.VISIBLE,right.getVisibility());right.performClick();assertEquals("stream.keyboard.reveal",actions.get(0).getString("action"));keyboard.reveal(actions.get(0).getJSONObject("args").getString("key"));layout();assertTrue(key("num-5").getLocalVisibleRect(new Rect()));
        root.findViewWithTag("floating-keyboard-bank:escape").performClick();keyboard.reveal(actions.get(1).getJSONObject("args").getString("key"));layout();assertTrue(key("escape").getLocalVisibleRect(new Rect()));
    }
    @Test public void toggleKeepsItsPositionWhenKeyboardOpensAndClosesAndThePreferencePersists(){
        setup(840,560);View toggle=root.findViewWithTag("floating-keyboard-toggle");Rect open=bounds(toggle);keyboard.setEnabled(false);layout();assertEquals(open,bounds(toggle));assertEquals(View.VISIBLE,toggle.getVisibility());assertEquals(View.GONE,root.findViewWithTag("floating-keyboard-viewport").getVisibility());toggle.performClick();assertEquals(1,actions.size());assertTrue(actions.get(0).optJSONObject("args").optBoolean("enabled"));
        keyboard.setButtonVisible(false);assertEquals(View.GONE,toggle.getVisibility());assertFalse(keyboard.enabled());keyboard.setEnabled(true);assertEquals(View.VISIBLE,root.findViewWithTag("floating-keyboard-viewport").getVisibility());assertEquals(View.GONE,toggle.getVisibility());keyboard.dispose();create();assertFalse(keyboard.buttonVisible());
    }
    @Test public void hiddenOffscreenDetachedAndBusyKeysNeverSendInput(){
        setup(360,800);Button offscreen=key("num-enter");offscreen.performClick();assertTrue(actions.isEmpty());keyboard.reveal("num-enter");layout();offscreen.performClick();assertEquals(1,actions.size());keyboard.update(false,false);offscreen.performClick();assertEquals(1,actions.size());keyboard.update(true,true);offscreen.performClick();assertEquals(1,actions.size());keyboard.update(true,false);View layer=root.findViewWithTag("floating-keyboard");root.removeView(layer);offscreen.performClick();assertEquals(1,actions.size());
    }
    @Test public void modifierSelectionHighlightsBothPhysicalKeysWithoutSendingRemoteInput(){
        setup(840,560);keyboard.modifiers(Arrays.asList("ctrl","shift"));assertTrue(key("ctrl-left").isSelected());assertTrue(key("ctrl-right").isSelected());assertTrue(key("shift-left").isSelected());assertFalse(key("alt-left").isSelected());keyboard.modifiers(Collections.emptyList());assertFalse(key("ctrl-left").isSelected());assertTrue(actions.isEmpty());
    }
    @Test public void simplifiedKeyboardRetainsNavigationPositionsAndCanSwitchBackToAll108Keys()throws Exception{
        for(int[] size:new int[][]{{840,560},{360,800},{640,360},{320,240}}){setup(size[0],size[1]);Rect quick=bounds(root.findViewWithTag("floating-keyboard-toggle"));keyboard.setLayout("simple");layout();assertEquals(quick,bounds(root.findViewWithTag("floating-keyboard-toggle")));assertEquals(View.GONE,root.findViewWithTag("floating-keyboard-viewport").getVisibility());assertTrue(key("tab").getLeft()<width/3);assertEquals(key("arrow-up").getLeft(),key("arrow-down").getLeft());assertTrue(key("arrow-down").getLeft()>width/2);
            List<Rect> occupied=new ArrayList<>();for(String id:new String[]{"escape","tab","shift-tab","backspace","delete","enter","meta-left","ctrl-left","alt-left","shift-left","arrow-up","arrow-left","arrow-down","arrow-right"}){View key=key(id);if(key.getVisibility()!=View.VISIBLE)continue;Rect r=bounds(key);assertTrue(id+" outside "+r,r.left>=0&&r.top>=0&&r.right<=width&&r.bottom<=height);for(Rect previous:occupied)assertFalse(id+" overlaps "+r,Rect.intersects(r,previous));occupied.add(r);}
            actions.clear();key("meta-left").performClick();assertTrue(actions.get(0).getJSONObject("args").getBoolean("tap"));root.findViewWithTag("floating-keyboard-layout:full").performClick();assertEquals("stream.keyboard.layout.set",actions.get(1).getString("action"));assertEquals("full",actions.get(1).getJSONObject("args").getString("layout"));keyboard.setLayout("full");layout();keyboard.reveal("num-5");layout();assertTrue(key("num-5").getLocalVisibleRect(new Rect()));keyboard.dispose();
        }
    }
    @Test public void simplifiedLayoutChoiceIsLocalAndSurvivesSessionRecreation(){setup(840,560);keyboard.setLayout("simple");keyboard.dispose();create();assertEquals("simple",keyboard.layoutName());}
    @Test public void imeInsetsKeepTheToggleAvailableAndDoNotReplaceTheParentListener(){
        setup(640,360);keyboard.setEnabled(false);int[] calls={0};androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{calls[0]++;return insets;});
        androidx.core.view.WindowInsetsCompat insets=new androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(12,20,14,24)).setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.of(0,0,0,210)).build();androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,insets);layout();
        Rect r=bounds(root.findViewWithTag("floating-keyboard-toggle"));assertTrue("top "+r,r.top>=20);assertTrue("bottom "+r,r.bottom<=150);assertTrue("right "+r,r.right<=626);assertEquals(1,calls[0]);keyboard.update(true,false,true);assertEquals(View.VISIBLE,root.findViewWithTag("floating-keyboard-toggle").getVisibility());assertEquals(View.GONE,root.findViewWithTag("floating-keyboard-viewport").getVisibility());keyboard.dispose();androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,insets);assertEquals(2,calls[0]);
    }
    @Test public void spaceOutsideTheKeyboardAndTogglePassesToTheRemoteSurface(){
        setup(840,560);keyboard.setEnabled(false);View layer=root.findViewWithTag("floating-keyboard");MotionEvent event=MotionEvent.obtain(1,1,MotionEvent.ACTION_DOWN,420,150,0);try{assertFalse(layer.dispatchTouchEvent(event));}finally{event.recycle();}
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void opaqueOutlinedLetteringAndTranslucentKeysStayLegibleWithoutABackingSheet()throws Exception{
        setup(1080,720);java.io.File directory=new java.io.File("build/reports/rhremote-keyboard-contrast");assertTrue(directory.isDirectory()||directory.mkdirs());
        for(String mode:new String[]{"full","simple"}){keyboard.setLayout(mode);layout();Bitmap overlay=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(new Canvas(overlay));
            assertEquals(0,Color.alpha(overlay.getPixel(width/2,100)));Rect tab=bounds(key("tab"));assertEquals(80,Color.alpha(overlay.getPixel(tab.left+8,tab.top+5)));
            int white=0,outline=0;for(int y=tab.top+4;y<tab.bottom-4;y++)for(int x=tab.left+3;x<tab.right-3;x++){int p=overlay.getPixel(x,y);if(Color.alpha(p)>=250&&Color.red(p)>=240&&Color.green(p)>=240&&Color.blue(p)>=240)white++;if(Color.alpha(p)>=250&&Color.red(p)<40&&Color.green(p)<45&&Color.blue(p)<55)outline++;}
            assertTrue("Opaque letters missing",white>3);assertTrue("Dark glyph outline missing",outline>3);assertEquals(Color.WHITE,key("tab").getCurrentTextColor());
            if(mode.equals("full")){View table=root.findViewWithTag("floating-key:tab");ViewGroup parent=(ViewGroup)table.getParent();Rect area=bounds(parent);assertEquals("The gap must expose the remote frame",0,Color.alpha(overlay.getPixel(area.left+2,area.top+2)));}
            for(int background=0;background<3;background++){Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(background==0?Color.WHITE:0xff111722);Paint paint=new Paint();if(background==2)for(int y=0;y<height;y+=29)for(int x=0;x<width;x+=37){paint.setColor((x/37+y/29)%2==0?0xffcde1f4:0xff3c5676);canvas.drawRect(x,y,x+37,y+29,paint);}canvas.drawBitmap(overlay,0,0,null);try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory,"keyboard-"+mode+"-"+background+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}image.recycle();}
            overlay.recycle();
        }assertEquals(80,keyboard.state().getInt("backgroundAlpha"));assertFalse(keyboard.state().getBoolean("backgroundSampling"));
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void completeKeyboardRendersWithTranslucentSurfacesInBothOrientations()throws Exception{
        java.io.File directory=new java.io.File("build/reports/rhremote-full-keyboard");assertTrue(directory.isDirectory()||directory.mkdirs());
        for(int[] size:new int[][]{{1080,720},{360,800}}){setup(size[0],size[1]);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));assertEquals(0,Color.alpha(bitmap.getPixel(20,100)));Rect tab=bounds(key("tab"));int alpha=Color.alpha(bitmap.getPixel(tab.left+8,tab.top+8));assertTrue("Key must be translucent: "+alpha,alpha>64&&alpha<255);try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory,"keyboard-"+width+"x"+height+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();keyboard.dispose();}
    }
}
