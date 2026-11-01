package com.limelight.rhremote;

import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.R;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Production native Views rendered on the emulator without an Activity or remote session. */
@RunWith(AndroidJUnit4.class)
public final class RhFloatingLayoutDeviceTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final List<String> calls=new ArrayList<>();
    private FrameLayout root;
    private RhStreamPanel panel;
    private RhFloatingKeyboard keyboard;
    private File folder;
    private int backdropDowns;
    private JSONArray switchPixels;
    private JSONArray switchWidgetBounds;
    private JSONArray handleLabelPixels;

    private JSONObject switchInteractions(int width,int height)throws Exception{
        panel.setExpanded(true);panel.setTab("input");panel.updateScrollMode(false);layout(width,height);
        Switch toggle=root.findViewWithTag("stream.scroll.set");assertNotNull(toggle);toggle.requestRectangleOnScreen(new Rect(0,0,toggle.getWidth(),toggle.getHeight()),true);layout(width,height);
        assertTrue("Scroll switch cannot be revealed",fullyInsideParents(toggle));assertTrue(toggle.isClickable());assertTrue(toggle.isFocusable());calls.clear();
        // Unattached views cannot validate window focus or deferred touch clicks.
        // Both inputs are checked separately on the attached, owned Game after UI idle.
        panel.updateControls(true,false,1,true,"displays","Switching","");calls.clear();toggle.performClick();assertTrue("Busy switch dispatched an action",calls.isEmpty());
        panel.updateControls(true,false,1,false,"","","");panel.updateScrollMode(false);calls.clear();
        return RhActions.obj("labelTouchMeasured",false,"keyboardActivationMeasured",false,"busyCallbackRejected",true,"systemInputInjected",false);
    }

    private Context fixtureContext(Context original,boolean dark,float fontScale){
        Configuration config=new Configuration(original.getResources().getConfiguration());
        config.densityDpi=160;config.fontScale=fontScale;
        return new ContextThemeWrapper(original.createConfigurationContext(config),R.style.RhTheme){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                SharedPreferences saved=super.getSharedPreferences(name,mode);
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(method.getName().equals("edit"))throw new AssertionError("A layout fixture must not persist preferences");
                    if(name.equals("rhremote.global")&&method.getName().equals("getBoolean")&&args[0].equals("dark"))return dark;
                    try{return method.invoke(saved,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
            }
        };
    }
    private void layout(int width,int height){
        for(int i=0;i<4;i++){
            root.forceLayout();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
        }
    }
    private void action(String id,JSONObject args){
        calls.add(id+":"+args.toString());
        if(id.equals("stream.keyboard.floating.set")){
            keyboard.setEnabled(args.optBoolean("enabled"));panel.updateFloatingKeyboard(keyboard.enabled());panel.setExpanded(false);
        }else if(id.equals("stream.controls.toggle"))panel.setExpanded(!panel.isExpanded());
        keyboard.update(true,panel.isExpanded());
    }
    private Rect bounds(View view){Rect rect=new Rect();view.getDrawingRect(rect);root.offsetDescendantRectToMyCoords(view,rect);return rect;}
    private boolean visibleInFixture(View view){
        for(View current=view;current!=null;){if(current.getVisibility()!=View.VISIBLE)return false;if(current==root)return true;ViewParent parent=current.getParent();current=parent instanceof View?(View)parent:null;}return false;
    }
    private void header(View view,int width,int height){
        assertNotNull(view);assertTrue(visibleInFixture(view));Rect rect=bounds(view);
        assertTrue("Header control clipped: "+rect,rect.left>=0&&rect.top>=0&&rect.right<=width&&rect.bottom<=height);
        assertTrue("Touch target too small",rect.height()>=48);
        for(ViewParent parent=view.getParent();parent instanceof View;parent=parent.getParent())assertFalse("Header control requires scrolling",parent instanceof ScrollView);
    }
    private Button button(View view,String text){
        if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=button(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private SeekBar volume(View view){
        if(view instanceof SeekBar)return (SeekBar)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){SeekBar found=volume(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;
    }
    private boolean fullyInsideParents(View view){
        Rect area=bounds(view);for(ViewParent parent=view.getParent();parent instanceof View;parent=parent.getParent()){View current=(View)parent;if(!bounds(current).contains(area))return false;if(current==root)return true;}return false;
    }
    private int checkSwitchPixels(Bitmap image,String capture){
        int checked=0;
        for(String id:new String[]{"stream.keyboard.floating.set","stream.scroll.set"}){
            Switch toggle=root.findViewWithTag(id);if(!visibleInFixture(toggle)||!fullyInsideParents(toggle)||!toggle.isEnabled())continue;
            Rect widget=bounds(toggle),track=toggle.getTrackDrawable().getBounds(),thumb=toggle.getThumbDrawable().getBounds();
            int x=widget.left+(toggle.isChecked()?track.left+5:track.right-5),y=widget.top+track.centerY();
            int actualTrack=image.getPixel(x,y),actualThumb=image.getPixel(widget.left+thumb.centerX(),widget.top+thumb.centerY());
            int expected=toggle.getTrackTintList().getColorForState(toggle.getDrawableState(),0);
            assertEquals("Track asset changed the intended rendered color",expected,actualTrack);
            double contrast=androidx.core.graphics.ColorUtils.calculateContrast(actualThumb,actualTrack);assertTrue("Rendered switch thumb and track blend together",contrast>=3);
            switchPixels.put(RhActions.obj("capture",capture,"id",id,"checked",toggle.isChecked(),"widget",RhActions.obj("x",widget.left,"y",widget.top,"width",widget.width(),"height",widget.height()),"trackArgb",String.format(Locale.ROOT,"#%08x",actualTrack),"thumbArgb",String.format(Locale.ROOT,"#%08x",actualThumb),"contrast",contrast));checked++;
        }
        return checked;
    }
    private void capture(String name)throws Exception{
        File file=new File(folder,name+".png");assertFalse(file.exists());Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        for(String id:new String[]{"stream.keyboard.floating.set","stream.scroll.set"}){
            Switch toggle=root.findViewWithTag(id);if(toggle==null||!visibleInFixture(toggle))continue;Rect area=bounds(toggle);
            switchWidgetBounds.put(RhActions.obj("capture",name,"id",id,"widget",RhActions.obj("x",area.left,"y",area.top,"width",area.width(),"height",area.height()),"fullyInsideParents",fullyInsideParents(toggle)));
        }
        try{root.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}if(panel.isExpanded())assertTrue("No visible switch pixels were checked",checkSwitchPixels(image,name)>0);checkHandleLabelPixels(image,name);}finally{image.recycle();}
    }
    private void checkHandleLabelPixels(Bitmap image,String name){
        Button handle=button(root,panel.isExpanded()?"收起":"控制");assertNotNull(handle);Rect area=bounds(handle);int ink=0;
        for(int y=area.top+handle.getCompoundPaddingTop();y<area.bottom-handle.getCompoundPaddingBottom();y++)for(int x=area.left+handle.getCompoundPaddingLeft();x<area.right-handle.getCompoundPaddingRight();x++){int color=image.getPixel(x,y);if(Color.red(color)>245&&Color.green(color)>245&&Color.blue(color)>245)ink++;}
        handleLabelPixels.put(RhActions.obj("capture",name,"text",handle.getText().toString(),"inkPixels",ink,"scrollX",handle.getScrollX()));assertTrue("Control handle has no rendered text: "+name,ink>0);
    }
    private void caseOnMain(Context original,int width,int height,boolean dark,float scale,String name)throws Exception{
        Context context=fixtureContext(original,dark,scale);root=new FrameLayout(context);switchPixels=new JSONArray();switchWidgetBounds=new JSONArray();handleLabelPixels=new JSONArray();
        View backdrop=new View(context){@Override protected void onDraw(Canvas canvas){
            canvas.drawColor(dark?Color.rgb(25,32,48):Color.rgb(228,234,244));Paint p=new Paint();p.setColor(dark?Color.rgb(37,47,65):Color.rgb(214,224,238));
            for(int x=0;x<getWidth();x+=80)canvas.drawLine(x,0,x,getHeight(),p);for(int y=0;y<getHeight();y+=80)canvas.drawLine(0,y,getWidth(),y,p);
        }};
        backdrop.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)backdropDowns++;return true;});root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        keyboard=new RhFloatingKeyboard(context,root,this::action);panel=new RhStreamPanel(context,root,this::action);
        try{
            JSONObject first=RhActions.obj("id","fixture-1","name","显示器 1","available",true,"width",1920,"height",1080);
            JSONObject second=RhActions.obj("id","fixture-2","name","显示器 2","available",true,"width",1920,"height",1080);
            panel.setDeviceName("winserver");panel.update(false,false,false,100,RhActions.obj("displays",new JSONArray().put(first).put(second),"selected",first),false,"");
            panel.updateControls(true,false,1,false,"","","");panel.setExpanded(true);keyboard.update(true,true);
            if(scale>=2){layout(width,height);capture(name+"-initial");}
            JSONArray tabs=new JSONArray();
            for(String tab:new String[]{"input","displays","audio"}){
                panel.setTab(tab);layout(width,height);Switch toggle=root.findViewWithTag("stream.keyboard.floating.set");Button disconnect=button(root,"断开");
                header(toggle,width,height);header(disconnect,width,height);assertFalse("Header actions overlap",Rect.intersects(bounds(toggle),bounds(disconnect)));
                if(scale>=2)assertLabelsFit(root);
                JSONObject p=panel.layoutState().getJSONObject("panel");assertTrue("Control panel exceeds the viewport",p.getInt("x")>=0&&p.getInt("y")>=0&&p.getInt("x")+p.getInt("width")<=width&&p.getInt("y")+p.getInt("height")<=height);
                if(tab.equals("audio")){assertNotNull(volume(root));assertEquals("Volume model and slider disagree",100,volume(root).getProgress());}
                Rect a=bounds(toggle),b=bounds(disconnect);tabs.put(RhActions.obj("tab",tab,"toggle",RhActions.obj("x",a.left,"y",a.top,"width",a.width(),"height",a.height()),"disconnect",RhActions.obj("x",b.left,"y",b.top,"width",b.width(),"height",b.height())));
                capture(name+"-"+tab);
                if(scale>=2){
                    View last=tab.equals("input")?button(root,"统计：关"):tab.equals("displays")?button(root,"还原缩放"):volume(root);assertNotNull(last);
                    ViewParent ancestor=last.getParent();while(!(ancestor instanceof ScrollView)){assertTrue("Last action has no scroll container",ancestor instanceof View);ancestor=ancestor.getParent();}
                    ScrollView body=(ScrollView)ancestor;body.scrollTo(0,body.getChildAt(0).getHeight());RhShared.write(new File(folder,name+"-"+tab+"-scroll.json"),RhActions.obj("scrollY",body.getScrollY(),"contentHeight",body.getChildAt(0).getHeight(),"target",bounds(last).toShortString(),"viewport",bounds(body).toShortString(),"lastActionReachable",fullyInsideParents(last)));assertTrue("Last action is clipped after scrolling: "+tab,fullyInsideParents(last));capture(name+"-"+tab+"-bottom");body.scrollTo(0,0);
                    if(tab.equals("displays")){
                        View refresh=button(root,"刷新屏幕");assertNotNull(refresh);refresh.requestRectangleOnScreen(new Rect(0,0,refresh.getWidth(),refresh.getHeight()),true);
                        RhShared.write(new File(folder,name+"-displays-refresh-reveal.json"),RhActions.obj("scrollY",body.getScrollY(),"target",bounds(refresh).toShortString(),"viewport",bounds(body).toShortString(),"refreshFullyVisible",fullyInsideParents(refresh)));
                        assertTrue("Refresh action cannot be revealed",fullyInsideParents(refresh));capture(name+"-displays-refresh-revealed");body.scrollTo(0,0);
                    }
                }
            }
            calls.clear();Switch toggle=root.findViewWithTag("stream.keyboard.floating.set");toggle.performClick();layout(width,height);
            assertEquals(1,calls.size());assertTrue(calls.get(0).startsWith("stream.keyboard.floating.set:"));assertTrue(keyboard.enabled());assertFalse(panel.isExpanded());
            JSONObject state=keyboard.state();assertTrue(state.getBoolean("visible"));assertEquals(166,state.getInt("backgroundAlpha"));
            if(scale>=2)assertLabelsFit(root);
            List<Rect> occupied=new ArrayList<>();Map<String,Rect> positions=new HashMap<>();int visible=0;
            for(int i=0;i<state.getJSONArray("keys").length();i++){
                JSONObject key=state.getJSONArray("keys").getJSONObject(i);if(!key.getBoolean("visible"))continue;
                Rect r=new Rect(key.getInt("x"),key.getInt("y"),key.getInt("x")+key.getInt("width"),key.getInt("y")+key.getInt("height"));
                assertTrue("Floating key touches an edge: "+key.getString("id"),r.left>=16&&r.top>=16&&r.right<=width-16&&r.bottom<=height-16);assertTrue(r.height()>=48);
                for(Rect previous:occupied)assertFalse("Floating keys overlap",Rect.intersects(previous,r));occupied.add(r);positions.put(key.getString("id"),r);visible++;
            }
            assertEquals("All requested keys fit this viewport",10,visible);
            assertTrue(positions.get("tab").centerX()<width/3&&positions.get("tab").centerY()<height/2);
            assertTrue(positions.get("arrow-down").centerX()>width/2&&positions.get("arrow-down").centerY()>height/2);
            assertEquals(positions.get("arrow-up").centerX(),positions.get("arrow-down").centerX());assertEquals(positions.get("arrow-left").centerY(),positions.get("arrow-right").centerY());capture(name+"-floating");
            backdropDowns=0;long now=SystemClock.uptimeMillis();
            for(int kind:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(now,now,kind,width/2f,height/2f,0);try{assertTrue(root.dispatchTouchEvent(event));}finally{event.recycle();}}
            assertEquals("Blank areas must reach the remote surface",1,backdropDowns);
            String[] shortcuts={"tab","shift-tab","arrow-up","arrow-left","arrow-down","arrow-right","enter","backspace","delete","escape"};
            calls.clear();for(String key:shortcuts){Button target=root.findViewWithTag("floating-key:"+key);assertTrue(visibleInFixture(target));target.performClick();}
            assertEquals(10,calls.size());for(int i=0;i<shortcuts.length;i++){String call=calls.get(i);assertTrue(call.startsWith("stream.shortcut:"));assertEquals(shortcuts[i],new JSONObject(call.substring(call.indexOf(':')+1)).getString("shortcut"));}
            Button handle=button(root,"控制");assertNotNull(handle);handle.performClick();panel.setTab("input");panel.updateScrollMode(true);layout(width,height);assertTrue(panel.isExpanded());assertFalse(keyboard.state().getBoolean("visible"));capture(name+"-enabled");panel.updateScrollMode(false);
            toggle.performClick();layout(width,height);assertFalse(keyboard.enabled());assertFalse(keyboard.state().getBoolean("visible"));
            JSONObject interactions=switchInteractions(width,height);
            RhShared.write(new File(folder,name+".json"),RhActions.obj("width",width,"height",height,"dark",dark,"fontScale",scale,"tabs",tabs,"keyboard",state,"switchPixelChecks",switchPixels,"switchWidgetBounds",switchWidgetBounds,"handleLabelPixels",handleLabelPixels,"switchInteractions",interactions,"blankDowns",backdropDowns,"shortcutCallbacks",10,"fixtureOnly",true,"systemInputInjected",false));
        }finally{panel.dispose();keyboard.dispose();root.removeAllViews();}
    }
    private String fingerprint(File file)throws Exception{
        if(!file.exists())return "absent";byte[] hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));StringBuilder text=new StringBuilder();for(byte b:hash)text.append(String.format("%02x",b&255));return text.toString();
    }
    private void assertLabelsFit(View view){
        if(view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof Button||view instanceof TextView&&((TextView)view).getEllipsize()==null){
            TextView label=(TextView)view;android.text.Layout text=label.getLayout();
            if(text!=null){
                String title=label.getText().toString();
                assertTrue(title+" clipped vertically: "+view.getHeight(),text.getHeight()+label.getCompoundPaddingTop()+label.getCompoundPaddingBottom()<=view.getHeight()+2);
                int available=view.getWidth()-label.getCompoundPaddingLeft()-label.getCompoundPaddingRight();
                for(int line=0;line<text.getLineCount();line++){assertTrue(title+" clipped horizontally",text.getLineWidth(line)<=available+1);if(view instanceof Button)assertEquals(title+" action label was ellipsized",0,text.getEllipsisCount(line));}
            }
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)assertLabelsFit(((ViewGroup)view).getChildAt(i));
    }
    @Test public void maximumFontSizeKeepsTheControlActionsReadable()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("floatingLargeFontRun","");org.junit.Assume.assumeFalse("Explicit owned run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        File shared=new File(context.getFilesDir(),"rhremote-shared.json"),preferences=new File(context.getDataDir(),"shared_prefs/rhremote.global.xml");String beforeShared=fingerprint(shared),beforePreferences=fingerprint(preferences);int cases=0;
        try{
            for(boolean dark:new boolean[]{false,true})for(int[] viewport:new int[][]{{360,800},{640,360},{840,560}}){String name=(dark?"dark":"light")+"-"+viewport[0]+"x"+viewport[1]+"-font200";Throwable[] error={null};instrument.runOnMainSync(()->{try{caseOnMain(context,viewport[0],viewport[1],dark,2,name);}catch(Throwable value){error[0]=value;}});if(error[0]!=null)throw new AssertionError(name,error[0]);cases++;}
        }finally{assertEquals("Shared device configuration changed",beforeShared,fingerprint(shared));assertEquals("Global preferences changed",beforePreferences,fingerprint(preferences));}
        RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",true,"cases",cases,"fontScale",2,"rendering","native View.draw without attached Activity","hostOperations",false,"webdavOperations",false,"preferencesWritten",false,"systemInputInjected",false));
    }
    @Test public void controlsAndKeysFitNativeEmulatorViewsWithoutHostOperations()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("floatingLayoutRun","");org.junit.Assume.assumeFalse("Explicit owned run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Context context=instrument.getTargetContext();folder=new File(context.getExternalFilesDir("ui-evidence"),run);assertFalse(folder.exists());assertTrue(folder.mkdirs());
        File shared=new File(context.getFilesDir(),"rhremote-shared.json"),preferences=new File(context.getDataDir(),"shared_prefs/rhremote.global.xml");String beforeShared=fingerprint(shared),beforePreferences=fingerprint(preferences);int cases=0;
        try{
            for(boolean dark:new boolean[]{false,true})for(int[] viewport:new int[][]{{840,560},{360,800},{640,360}}){
                String name=(dark?"dark":"light")+"-"+viewport[0]+"x"+viewport[1];Throwable[] error={null};instrument.runOnMainSync(()->{try{caseOnMain(context,viewport[0],viewport[1],dark,1,name);}catch(Throwable value){error[0]=value;}});if(error[0]!=null)throw new AssertionError(name,error[0]);cases++;
            }
            for(boolean dark:new boolean[]{false,true}){String name=(dark?"dark":"light")+"-360x800-font130";Throwable[] error={null};instrument.runOnMainSync(()->{try{caseOnMain(context,360,800,dark,1.3f,name);}catch(Throwable value){error[0]=value;}});if(error[0]!=null)throw new AssertionError(name,error[0]);cases++;}
        }finally{assertEquals("Shared device configuration changed",beforeShared,fingerprint(shared));assertEquals("Global preferences changed",beforePreferences,fingerprint(preferences));}
        RhShared.write(new File(folder,"result.json"),RhActions.obj("passed",true,"cases",cases,"rendering","native View.draw without attached Activity","hostOperations",false,"webdavOperations",false,"preferencesWritten",false,"systemInputInjected",false));
    }
}
