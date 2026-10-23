package com.limelight.rhremote;
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
    private final RhStreamPanel panel;private final RhProfile profile;
    private boolean keyboard,disposed,connected;
    private boolean shortcutBusy;private JSONObject lastShortcut=RhActions.obj();
    private final RhDisplay display;private boolean displayBusy;private String displayError="";
    public static RhStreamControls install(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile,RhDisplay display){return new RhStreamControls(game,touch,preferences,profile,display);}
    private RhStreamControls(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile,RhDisplay display){
        this.display=display;
        this.profile=profile;
        this.game=game;this.touch=touch;this.preferences=preferences;
        panel=new RhStreamPanel(game,game.findViewById(android.R.id.content),RhActions::click);
        updatePanel();RhActions.initialize(game,null);RhActions.attachStream(this);
    }
    public void dispose(){if(disposed)return;disposed=true;touch.cancel();panel.dispose();RhActions.detachStream(this);}
    public void markConnected(){connected=true;try{if(display!=null)display.applyInitial();}catch(Exception e){displayError=e.getMessage()==null?"屏幕切换失败":e.getMessage();}updatePanel();}
    private void click(String id){RhActions.click(id,RhActions.obj());}
    private void updatePanel(){if(!disposed)panel.update(touch.isTrackpad(),touch.isPanMode(),profile.audioEnabled,profile.volume,display==null?RhActions.obj():display.state(),displayBusy,displayError);}
    public JSONObject state(){TextView overlay=game.findViewById(R.id.performanceOverlay);return RhActions.obj("connected",connected,"audioEnabled",profile.audioEnabled,"volume",profile.volume,"audioPackets",game.rhAudioRenderer==null?0:game.rhAudioRenderer.packets(),"controls",panel.isExpanded(),"controlsTab",panel.tab(),"controlsLayout",panel.layoutState(),"trackpad",touch.isTrackpad(),"pan",touch.isPanMode(),"keyboard",keyboard,"showStats",preferences.enablePerfOverlay,"statistics",overlay==null?"":overlay.getText().toString(),"surface",touch.surfaceSize(),"lastShortcut",lastShortcut,"shortcutBusy",shortcutBusy,"display",display==null?RhActions.obj():display.state(),"displayBusy",displayBusy,"displayError",displayError);}
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
            case "stream.controls.toggle":panel.setExpanded(!panel.isExpanded());break;
            case "stream.controls.tab":panel.setTab(args.getString("tab"));if(panel.tab().equals("displays"))loadMissingDisplays();break;
            case "stream.mode.toggle":touch.setTrackpad(!touch.isTrackpad());break;
            case "stream.keyboard.toggle":panel.setExpanded(false);game.toggleKeyboard();keyboard=!keyboard;break;
            case "stream.pan.toggle":touch.togglePan();break;
            case "stream.zoom.reset":touch.resetZoom();break;
            case "stream.stats.toggle":preferences.enablePerfOverlay=!preferences.enablePerfOverlay;game.findViewById(R.id.performanceOverlay).setVisibility(preferences.enablePerfOverlay?View.VISIBLE:View.GONE);break;
            case "stream.audio.toggle":profile.audioEnabled=!profile.audioEnabled;applyAudio();break;
            case "stream.audio.volume":int volume=args.getInt("volume");if(volume<0||volume>100)throw new IllegalArgumentException("volume must be 0..100");profile.volume=volume;applyAudio();break;
            case "stream.disconnect":game.rhCancelConnection();dispose();game.finish();break;
            case "input.gesture":touch.inject(args);break;
            default:throw new IllegalArgumentException("unsupported stream action");
        }updatePanel();return RhActions.obj("accepted",true);
    }
    private void openDisplays()throws Exception {
        panel.setExpanded(true);panel.setTab("displays");loadMissingDisplays();
    }
    private void loadMissingDisplays(){JSONObject state=display==null?RhActions.obj():display.state();JSONArray monitors=state.optJSONArray("displays");if(!displayBusy&&(monitors==null||monitors.length()==0||!state.optString("error").isEmpty()))displayAction(null);}
    private void displayAction(String id){
        if(display==null)throw new IllegalStateException("屏幕组件不可用");if(displayBusy)throw new IllegalStateException("屏幕切换正在进行");displayBusy=true;updatePanel();
        new Thread(()->{String error="";try{if(id==null)display.refresh();else display.select(id,false);}catch(Exception e){error=e.getMessage()==null?"屏幕切换失败":e.getMessage();}final String result=error;game.runOnUiThread(()->{displayBusy=false;displayError=result;if(disposed)return;if(result.isEmpty()&&id!=null&&connected){try{display.applyCurrent();}catch(Exception e){displayError=e.getMessage()==null?"屏幕切换失败":e.getMessage();}}updatePanel();if(!displayError.isEmpty())Toast.makeText(game,displayError,Toast.LENGTH_LONG).show();});},"RHRemote display").start();
    }
    private void sendShortcut(String id)throws Exception {
        JSONObject shortcut=RhShortcuts.find(game,id);if(shortcutBusy)throw new IllegalStateException("快捷键正在发送");
        if(!shortcut.optBoolean("secureAttention")){RhShortcuts.send(shortcut,(key,action,modifiers)->MoonBridge.sendKeyboardInput(key,action,modifiers,(byte)0));lastShortcut=RhActions.obj("shortcut",id,"accepted",true);return;}
        shortcutBusy=true;lastShortcut=RhActions.obj("shortcut",id,"pending",true);
        new Thread(()->{JSONObject result;try{RhHost.request(game,game.getIntent().getStringExtra(Game.EXTRA_HOST),game.getIntent().getByteArrayExtra(Game.EXTRA_SERVER_CERT),"/v1/input/secure-attention",RhActions.obj("action","ctrl-alt-delete"));result=RhActions.obj("shortcut",id,"accepted",true);}catch(Exception e){result=RhActions.obj("shortcut",id,"accepted",false,"error",e.getMessage()==null?"主机辅助服务不可用":e.getMessage());}final JSONObject completed=result;game.runOnUiThread(()->{shortcutBusy=false;lastShortcut=completed;if(!disposed&&!completed.optBoolean("accepted"))Toast.makeText(game,completed.optString("error"),Toast.LENGTH_LONG).show();});},"RHRemote secure attention").start();
    }
    private void applyAudio(){if(game.rhAudioRenderer!=null)game.rhAudioRenderer.setPlayback(profile.audioEnabled,profile.volume);}
}
