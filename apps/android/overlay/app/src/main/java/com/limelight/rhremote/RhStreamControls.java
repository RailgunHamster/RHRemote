package com.limelight.rhremote;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.limelight.Game;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.R;
import org.json.JSONObject;
import org.json.JSONArray;
import android.app.AlertDialog;
import android.widget.Toast;
import com.limelight.nvstream.jni.MoonBridge;

/** Native controls route through the same dispatcher as the authenticated local HTTP API. */
public final class RhStreamControls implements RhActions.Target {
    private final Game game;private final RhTouchController touch;private final PreferenceConfiguration preferences;
    private final HorizontalScrollView scroller;private final Button mode,pan,audio;private final RhProfile profile;
    private boolean keyboard,disposed,connected;
    private boolean shortcutBusy;private JSONObject lastShortcut=RhActions.obj();
    private final RhDisplay display;private boolean displayBusy;private String displayError="";
    private static int dp(Game g,int v){return Math.round(g.getResources().getDisplayMetrics().density*v);}
    private static GradientDrawable background(String color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(Color.parseColor(color));d.setCornerRadius(radius);return d;}
    public static RhStreamControls install(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile,RhDisplay display){return new RhStreamControls(game,touch,preferences,profile,display);}
    private RhStreamControls(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile,RhDisplay display){
        this.display=display;
        this.profile=profile;
        this.game=game;this.touch=touch;this.preferences=preferences;
        FrameLayout container=game.findViewById(android.R.id.content);
        Button handle=new Button(game);handle.setText("控制");handle.setTextSize(13);handle.setAllCaps(false);handle.setTextColor(Color.WHITE);handle.setBackground(background("#5274e8",dp(game,22)));
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(game,76),dp(game,44),Gravity.TOP|Gravity.END);hp.setMargins(0,dp(game,16),dp(game,18),0);container.addView(handle,hp);
        LinearLayout toolbar=new LinearLayout(game);toolbar.setPadding(dp(game,8),dp(game,6),dp(game,8),dp(game,6));toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setBackground(background("#f8faff",dp(game,18)));
        scroller=new HorizontalScrollView(game);scroller.setHorizontalScrollBarEnabled(false);scroller.addView(toolbar);scroller.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-2,dp(game,60),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);tp.setMargins(dp(game,12),0,dp(game,12),dp(game,20));container.addView(scroller,tp);
        handle.setOnClickListener(v->click("stream.controls.toggle"));
        mode=add(toolbar,touch.isTrackpad()?"触控板":"直接点击","stream.mode.toggle");add(toolbar,"键盘","stream.keyboard.toggle");
        add(toolbar,"Ctrl+Alt+Delete","stream.shortcut");add(toolbar,"快捷键","stream.shortcuts.open");
        add(toolbar,"屏幕","stream.displays.open");
        add(toolbar,"窗口移到左屏","stream.window.move");add(toolbar,"窗口移到右屏","stream.window.move");
        pan=add(toolbar,"移动画面","stream.pan.toggle");add(toolbar,"还原缩放","stream.zoom.reset");audio=add(toolbar,profile.audioEnabled?"声音：开":"声音：关","stream.audio.toggle");add(toolbar,"音量 −","stream.audio.volume");add(toolbar,"音量 ＋","stream.audio.volume");add(toolbar,"统计","stream.stats.toggle");add(toolbar,"断开","stream.disconnect");RhActions.initialize(game,null);RhActions.attachStream(this);
    }
    public void dispose(){disposed=true;touch.cancel();RhActions.detachStream(this);}
    public void markConnected(){connected=true;try{if(display!=null)display.applyInitial();}catch(Exception e){displayError=e.getMessage()==null?"屏幕切换失败":e.getMessage();}}
    private void click(String id){RhActions.click(id,RhActions.obj());}
    private Button add(LinearLayout row,String title,String action){Button b=new Button(game);b.setText(title);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(Color.parseColor("#34415b"));b.setBackground(background("#f8faff",dp(game,12)));b.setPadding(dp(game,12),0,dp(game,12),0);b.setOnClickListener(v->{if(action.equals("stream.audio.volume"))RhActions.click(action,RhActions.obj("volume",Math.max(0,Math.min(100,profile.volume+(title.contains("＋")?10:-10)))));else if(action.equals("stream.shortcut"))RhActions.click(action,RhActions.obj("shortcut","ctrl-alt-delete"));else if(action.equals("stream.window.move"))RhActions.click(action,RhActions.obj("direction",title.contains("左")?"left":"right"));else click(action);});row.addView(b,new LinearLayout.LayoutParams(-2,dp(game,46)));return b;}
    public JSONObject state(){TextView overlay=game.findViewById(R.id.performanceOverlay);return RhActions.obj("connected",connected,"audioEnabled",profile.audioEnabled,"volume",profile.volume,"audioPackets",game.rhAudioRenderer==null?0:game.rhAudioRenderer.packets(),"controls",scroller.getVisibility()==View.VISIBLE,"trackpad",touch.isTrackpad(),"pan",touch.isPanMode(),"keyboard",keyboard,"showStats",preferences.enablePerfOverlay,"statistics",overlay==null?"":overlay.getText().toString(),"surface",touch.surfaceSize(),"lastShortcut",lastShortcut,"shortcutBusy",shortcutBusy,"display",display==null?RhActions.obj():display.state(),"displayBusy",displayBusy,"displayError",displayError);}
    public JSONObject perform(String id,JSONObject args)throws Exception{
        if(disposed||game.isFinishing())throw new IllegalStateException("no active stream");
        if(!connected && (id.equals("input.gesture")||id.equals("stream.shortcut")||id.equals("stream.window.move")))throw new IllegalStateException("stream is not connected");
        switch(id){
            case "stream.displays.open":openDisplays();break;
            case "stream.displays.refresh":displayAction(null);break;
            case "stream.display.select":if(!connected)throw new IllegalStateException("stream is not connected");String displayId=args.getString("displayId");if(displayId.isEmpty()||displayId.length()>512)throw new IllegalArgumentException("displayId required");displayAction(displayId);break;
            case "stream.shortcuts.open":JSONArray list=RhShortcuts.catalog(game);String[] labels=new String[list.length()];for(int i=0;i<labels.length;i++)labels[i]=list.getJSONObject(i).getString("label");new AlertDialog.Builder(game).setTitle("远程快捷键").setItems(labels,(dialog,index)->{try{RhActions.click("stream.shortcut",RhActions.obj("shortcut",list.getJSONObject(index).getString("id")));}catch(Exception e){Toast.makeText(game,"快捷键不可用",Toast.LENGTH_SHORT).show();}}).setNegativeButton("取消",null).show();break;
            case "stream.shortcut":sendShortcut(args.getString("shortcut"));break;
            case "stream.window.move":String direction=args.getString("direction");if(!direction.equals("left")&&!direction.equals("right"))throw new IllegalArgumentException("direction must be left or right");sendShortcut(direction.equals("left")?"win-shift-left":"win-shift-right");break;
            case "stream.controls.toggle":scroller.setVisibility(scroller.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);break;
            case "stream.mode.toggle":touch.setTrackpad(!touch.isTrackpad());mode.setText(touch.isTrackpad()?"触控板":"直接点击");break;
            case "stream.keyboard.toggle":game.toggleKeyboard();keyboard=!keyboard;break;
            case "stream.pan.toggle":pan.setText(touch.togglePan()?"移动中":"移动画面");break;
            case "stream.zoom.reset":touch.resetZoom();break;
            case "stream.stats.toggle":preferences.enablePerfOverlay=!preferences.enablePerfOverlay;game.findViewById(R.id.performanceOverlay).setVisibility(preferences.enablePerfOverlay?View.VISIBLE:View.GONE);break;
            case "stream.audio.toggle":profile.audioEnabled=!profile.audioEnabled;applyAudio();break;
            case "stream.audio.volume":int volume=args.getInt("volume");if(volume<0||volume>100)throw new IllegalArgumentException("volume must be 0..100");profile.volume=volume;applyAudio();break;
            case "stream.disconnect":dispose();game.finish();break;
            case "input.gesture":touch.inject(args);break;
            default:throw new IllegalArgumentException("unsupported stream action");
        }return RhActions.obj("accepted",true);
    }
    private void openDisplays()throws Exception {
        JSONObject state=display==null?RhActions.obj():display.state();JSONArray monitors=state.optJSONArray("displays");java.util.ArrayList<String> ids=new java.util.ArrayList<>(),labels=new java.util.ArrayList<>();ids.add("auto");labels.add("自动：优先现有屏幕");ids.add("virtual");labels.add("客户端专用虚拟屏");
        if(monitors!=null)for(int i=0;i<monitors.length();i++){JSONObject monitor=monitors.getJSONObject(i);if(!monitor.optBoolean("available"))continue;String name=monitor.optBoolean("virtual")?"虚拟屏":monitor.optString("name","屏幕");if(name.isEmpty())name="屏幕";ids.add(monitor.getString("id"));labels.add(name+" · "+monitor.optInt("width")+"×"+monitor.optInt("height"));}
        new AlertDialog.Builder(game).setTitle("远程屏幕").setItems(labels.toArray(new String[0]),(dialog,index)->RhActions.click("stream.display.select",RhActions.obj("displayId",ids.get(index)))).setNeutralButton("刷新",(dialog,which)->RhActions.click("stream.displays.refresh",RhActions.obj())).setNegativeButton("取消",null).show();
    }
    private void displayAction(String id){
        if(display==null)throw new IllegalStateException("屏幕组件不可用");if(displayBusy)throw new IllegalStateException("屏幕切换正在进行");displayBusy=true;
        new Thread(()->{String error="";try{if(id==null)display.refresh();else display.select(id,false);}catch(Exception e){error=e.getMessage()==null?"屏幕切换失败":e.getMessage();}final String result=error;game.runOnUiThread(()->{displayBusy=false;displayError=result;if(disposed||!connected)return;if(result.isEmpty()){try{if(id!=null)display.applyCurrent();else openDisplays();}catch(Exception e){displayError=e.getMessage()==null?"屏幕切换失败":e.getMessage();}}if(!displayError.isEmpty())Toast.makeText(game,displayError,Toast.LENGTH_LONG).show();});},"RHRemote display").start();
    }
    private void sendShortcut(String id)throws Exception {
        JSONObject shortcut=RhShortcuts.find(game,id);if(shortcutBusy)throw new IllegalStateException("快捷键正在发送");
        if(!shortcut.optBoolean("secureAttention")){RhShortcuts.send(shortcut,(key,action,modifiers)->MoonBridge.sendKeyboardInput(key,action,modifiers,(byte)0));lastShortcut=RhActions.obj("shortcut",id,"accepted",true);return;}
        shortcutBusy=true;lastShortcut=RhActions.obj("shortcut",id,"pending",true);
        new Thread(()->{JSONObject result;try{RhHost.request(game,game.getIntent().getStringExtra(Game.EXTRA_HOST),game.getIntent().getByteArrayExtra(Game.EXTRA_SERVER_CERT),"/v1/input/secure-attention",RhActions.obj("action","ctrl-alt-delete"));result=RhActions.obj("shortcut",id,"accepted",true);}catch(Exception e){result=RhActions.obj("shortcut",id,"accepted",false,"error",e.getMessage()==null?"主机辅助服务不可用":e.getMessage());}final JSONObject completed=result;game.runOnUiThread(()->{shortcutBusy=false;lastShortcut=completed;if(!disposed&&!completed.optBoolean("accepted"))Toast.makeText(game,completed.optString("error"),Toast.LENGTH_LONG).show();});},"RHRemote secure attention").start();
    }
    private void applyAudio(){if(game.rhAudioRenderer!=null)game.rhAudioRenderer.setPlayback(profile.audioEnabled,profile.volume);audio.setText(profile.audioEnabled?"声音：开":"声音：关");}
}
