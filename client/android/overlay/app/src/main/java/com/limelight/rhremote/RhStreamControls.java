package com.limelight.rhremote;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.limelight.Game;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.nvstream.jni.MoonBridge;
import org.json.*;

/** Session controls, visible feedback and system Back use the same action dispatcher. */
public final class RhStreamControls implements RhActions.Target {
    private final Game game;private final RhTouchController touch;private final PreferenceConfiguration preferences;
    private final RhStreamPanel panel;private final RhProfile profile;private final RhDisplay display;
    private boolean keyboard,disposed,connected,firstFrame,shortcutBusy,displayBusy;
    private JSONObject lastShortcut=RhActions.obj();private String displayError="";
    private String feedbackAction="",feedbackGroup="",feedbackMessage="",feedbackError="",menu="";
    private AlertDialog menuDialog,exitDialog;
    public static RhStreamControls install(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile,RhDisplay display){RhStreamControls controls=new RhStreamControls(game,touch,preferences,profile,display);return controls.disposed?null:controls;}
    private RhStreamControls(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile,RhDisplay display){
        this.game=game;this.touch=touch;this.preferences=preferences;this.profile=profile;this.display=display;
        panel=new RhStreamPanel(game,game.findViewById(android.R.id.content),RhActions::click);panel.setDeviceName(game.getIntent().getStringExtra(Game.EXTRA_PC_NAME));
        updatePanel();RhActions.initialize(game,null);if(!RhActions.attachStream(this,game))dispose();
    }
    public void dispose(){if(disposed)return;disposed=true;closeMenu();closeExit();touch.cancel();panel.dispose();RhActions.detachStream(this);}
    public void markFirstFrame(){if(disposed)return;firstFrame=true;game.runOnUiThread(this::updatePanel);}
    public void markConnected(){if(disposed)return;connected=true;try{if(display!=null)display.applyInitial();}catch(Exception failure){displayError=problem(failure,"屏幕切换失败");noteFailure("stream.display.select",displayError);}updatePanel();}
    private boolean busy(){return displayBusy||shortcutBusy;}
    private static String group(String id){return (id.startsWith("stream.display.")||id.startsWith("stream.displays."))||id.equals("stream.window.move")?"displays":id.startsWith("stream.audio.")?"audio":"input";}
    private static String problem(Exception failure,String fallback){return failure.getMessage()==null?fallback:failure.getMessage();}
    private static String failed(String id){switch(id){case "stream.displays.refresh":return "屏幕列表读取失败";case "stream.display.select":return "屏幕切换失败";case "stream.window.move":return "窗口移动失败";case "stream.shortcut":return "快捷键发送失败";case "stream.audio.toggle":case "stream.audio.volume":return "声音设置失败";case "stream.stats.toggle":return "统计设置失败";default:return "操作失败";}}
    private void feedback(String id,String message,String error){feedbackAction=id;feedbackGroup=group(id);feedbackMessage=error.isEmpty()?message:failed(id);feedbackError=error;}
    private boolean keyboardVisible(){WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(game.getWindow().getDecorView());return insets==null?keyboard:insets.isVisible(WindowInsetsCompat.Type.ime());}
    private void updatePanel(){if(disposed)return;panel.update(touch.isTrackpad(),touch.isPanMode(),profile.audioEnabled,profile.volume,display==null?RhActions.obj():display.state(),displayBusy,displayError);String message=feedbackMessage;String scope=feedbackGroup;if(!busy()&&feedbackError.isEmpty()&&(!connected||!firstFrame)){scope=panel.tab();message=connected?"等待首帧…":"正在建立串流…";}panel.updateControls(connected,preferences.enablePerfOverlay,touch.scale(),busy(),scope,message,feedbackError);if(menuDialog!=null&&menuDialog.getListView()!=null)menuDialog.getListView().setEnabled(!busy());}
    public JSONObject state(){TextView overlay=game.findViewById(R.id.performanceOverlay);return RhActions.obj("connected",connected,"firstFrame",firstFrame,"phase",!connected?"starting":firstFrame?"connected":"first-frame","audioEnabled",profile.audioEnabled,"volume",profile.volume,"audioPackets",game.rhAudioRenderer==null?0:game.rhAudioRenderer.packets(),"controls",panel.isExpanded(),"controlsTab",panel.tab(),"controlsLayout",panel.layoutState(),"controlAction",RhActions.obj("action",feedbackAction,"group",feedbackGroup,"busy",busy(),"message",feedbackMessage,"error",feedbackError),"streamMenu",menu,"exitPrompt",exitDialog!=null,"trackpad",touch.isTrackpad(),"pan",touch.isPanMode(),"keyboard",keyboardVisible(),"zoom",touch.viewTransform(),"showStats",preferences.enablePerfOverlay,"statistics",overlay==null?"":overlay.getText().toString(),"surface",touch.surfaceSize(),"lastShortcut",lastShortcut,"shortcutBusy",shortcutBusy,"display",display==null?RhActions.obj():display.state(),"displayBusy",displayBusy,"displayError",displayError);}
    public void noteFailure(String id,String error){if(disposed||busy()||id.equals(feedbackAction)&&error.equals(feedbackError))return;feedback(id,"",error);updatePanel();}
    public boolean goBack(){
        if(disposed)return false;
        if(exitDialog!=null){RhActions.click("stream.exit.resolve",RhActions.obj("choice","stay"));return true;}
        if(menuDialog!=null){RhActions.click("stream.menu.close",RhActions.obj());return true;}
        if(keyboardVisible()){((InputMethodManager)game.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(game.getWindow().getDecorView().getWindowToken(),0);keyboard=false;return true;}
        if(panel.isExpanded()){RhActions.click("stream.controls.toggle",RhActions.obj());return true;}
        RhActions.click(connected?"stream.exit.request":"stream.disconnect",RhActions.obj());return true;
    }
    public JSONObject perform(String id,JSONObject args)throws Exception{
        try{return performAction(id,args);}catch(Exception failure){noteFailure(id,problem(failure,"会话操作失败"));throw failure;}
    }
    private JSONObject performAction(String id,JSONObject args)throws Exception{
        if(disposed||game.isFinishing())throw new IllegalStateException("no active stream");
        if(exitDialog!=null&&!java.util.Arrays.asList("stream.exit.resolve","stream.disconnect","stream.menu.close").contains(id))throw new IllegalStateException("请先选择是否断开连接");
        if(!connected&&(id.equals("input.gesture")||id.equals("stream.shortcut")||id.equals("stream.window.move")||id.equals("stream.display.select")))throw new IllegalStateException("stream is not connected");
        if(busy()&&java.util.Arrays.asList("stream.displays.refresh","stream.display.select","stream.shortcut","stream.window.move","stream.audio.toggle","stream.audio.volume","stream.stats.toggle").contains(id))throw new IllegalStateException("上一项会话操作尚未完成");
        switch(id){
            case "stream.displays.open":panel.setExpanded(true);panel.setTab("displays");loadMissingDisplays();break;
            case "stream.displays.refresh":displayAction(null);break;
            case "stream.display.select":String displayId=args.getString("displayId");if(displayId.isEmpty()||displayId.length()>512)throw new IllegalArgumentException("displayId required");displayAction(displayId);break;
            case "stream.shortcuts.open":openMenu("shortcuts");break;
            case "stream.menu.open":openMenu(args.getString("menu"));break;
            case "stream.menu.close":closeMenu();break;
            case "stream.shortcut":sendShortcut(args.getString("shortcut"),"stream.shortcut");if(menu.equals("shortcuts"))closeMenu();break;
            case "stream.window.move":String direction=args.getString("direction");if(!direction.equals("left")&&!direction.equals("right"))throw new IllegalArgumentException("direction must be left or right");sendShortcut(direction.equals("left")?"win-shift-left":"win-shift-right","stream.window.move");break;
            case "stream.controls.toggle":closeMenu();panel.setExpanded(!panel.isExpanded());break;
            case "stream.controls.tab":closeMenu();panel.setTab(args.getString("tab"));if(panel.tab().equals("displays"))loadMissingDisplays();break;
            case "stream.mode.toggle":touch.setTrackpad(!touch.isTrackpad());feedback(id,touch.isTrackpad()?"触控板模式":"直接点击模式","");break;
            case "stream.keyboard.toggle":panel.setExpanded(false);game.toggleKeyboard();keyboard=!keyboard;break;
            case "stream.pan.toggle":touch.togglePan();feedback(id,touch.isPanMode()?"移动画面已开启":"移动画面已关闭","");break;
            case "stream.zoom.reset":touch.resetZoom();feedback(id,"缩放已还原","");break;
            case "stream.stats.toggle":preferences.enablePerfOverlay=!preferences.enablePerfOverlay;game.findViewById(R.id.performanceOverlay).setVisibility(preferences.enablePerfOverlay?View.VISIBLE:View.GONE);feedback(id,preferences.enablePerfOverlay?"统计已开启":"统计已关闭","");break;
            case "stream.audio.toggle":profile.audioEnabled=!profile.audioEnabled;applyAudio();feedback(id,profile.audioEnabled?"声音已开启":"声音已关闭","");break;
            case "stream.audio.volume":int volume=args.getInt("volume");if(volume<0||volume>100)throw new IllegalArgumentException("volume must be 0..100");profile.volume=volume;applyAudio();feedback(id,"音量 "+volume+"%","");break;
            case "stream.exit.request":requestExit();break;
            case "stream.exit.resolve":if(exitDialog==null)throw new IllegalStateException("没有等待处理的断开请求");String choice=args.getString("choice");if(!choice.equals("stay")&&!choice.equals("disconnect"))throw new IllegalArgumentException("unknown exit choice");closeExit();if(choice.equals("disconnect"))return performAction("stream.disconnect",RhActions.obj());break;
            case "stream.disconnect":RhActions.connectionStopping(game.getIntent().getStringExtra(Game.EXTRA_PC_UUID));game.rhCancelConnection();dispose();game.finish();break;
            case "stream.test.interrupt":if(!connected)throw new IllegalStateException("stream is not connected");game.rhTestInterrupt();break;
            case "input.gesture":touch.inject(args);break;
            default:throw new IllegalArgumentException("unsupported stream action");
        }
        updatePanel();return RhActions.obj("accepted",true);
    }
    private void requestExit(){if(exitDialog!=null)return;exitDialog=new AlertDialog.Builder(game).setTitle("断开连接？").setPositiveButton("断开",(dialog,which)->RhActions.click("stream.exit.resolve",RhActions.obj("choice","disconnect"))).setNegativeButton("继续连接",(dialog,which)->RhActions.click("stream.exit.resolve",RhActions.obj("choice","stay"))).create();exitDialog.setOnCancelListener(dialog->RhActions.click("stream.exit.resolve",RhActions.obj("choice","stay")));exitDialog.show();}
    private void closeExit(){if(exitDialog!=null){exitDialog.dismiss();exitDialog=null;}}
    private void closeMenu(){if(menuDialog!=null){menuDialog.dismiss();menuDialog=null;}menu="";}
    private void openMenu(String kind)throws Exception{
        if(!java.util.Arrays.asList("shortcuts","displays","feedback").contains(kind))throw new IllegalArgumentException("unknown stream menu");
        if(kind.equals("feedback")&&feedbackError.isEmpty())throw new IllegalStateException("没有会话操作错误");closeMenu();menu=kind;
        if(kind.equals("displays")){panel.setExpanded(true);panel.setTab("displays");loadMissingDisplays();return;}
        AlertDialog.Builder builder=new AlertDialog.Builder(game).setTitle(kind.equals("feedback")?"操作失败":"远程快捷键").setNegativeButton("关闭",(dialog,which)->RhActions.click("stream.menu.close",RhActions.obj()));
        final JSONArray list=kind.equals("shortcuts")?RhShortcuts.catalog(game):null;
        if(kind.equals("feedback"))builder.setMessage(feedbackError);else{String[] labels=new String[list.length()];for(int i=0;i<labels.length;i++)labels[i]=list.getJSONObject(i).getString("label");builder.setAdapter(new android.widget.ArrayAdapter<>(game,android.R.layout.simple_list_item_1,labels),null);}
        menuDialog=builder.create();menuDialog.setOnCancelListener(dialog->RhActions.click("stream.menu.close",RhActions.obj()));menuDialog.show();if(list!=null)menuDialog.getListView().setOnItemClickListener((parent,view,index,row)->RhActions.click("stream.shortcut",RhActions.obj("shortcut",list.optJSONObject(index).optString("id"))));updatePanel();
    }
    private void loadMissingDisplays(){JSONObject data=display==null?RhActions.obj():display.state();JSONArray monitors=data.optJSONArray("displays");if(!displayBusy&&(monitors==null||monitors.length()==0||!data.optString("error").isEmpty()))displayAction(null);}
    private void displayAction(String id){
        if(display==null)throw new IllegalStateException("屏幕组件不可用");displayBusy=true;String action=id==null?"stream.displays.refresh":"stream.display.select";feedback(action,id==null?"正在读取屏幕…":"正在切换屏幕…","");updatePanel();
        new Thread(()->{String error="";try{if(id==null)display.refresh();else display.select(id,false);}catch(Exception failure){error=problem(failure,"屏幕切换失败");}final String result=error;game.runOnUiThread(()->{if(disposed)return;displayBusy=false;displayError=result;if(result.isEmpty()&&id!=null&&connected)try{display.applyCurrent();}catch(Exception failure){displayError=problem(failure,"屏幕切换失败");}feedback(action,displayError.isEmpty()?(id==null?"屏幕列表已刷新":"已发送屏幕切换"):"",displayError);updatePanel();});},"RHRemote display").start();
    }
    private void sendShortcut(String id,String action)throws Exception{
        JSONObject shortcut=RhShortcuts.find(game,id);
        if(!shortcut.optBoolean("secureAttention")){RhShortcuts.send(shortcut,(key,event,modifiers)->MoonBridge.sendKeyboardInput(key,event,modifiers,(byte)0));lastShortcut=RhActions.obj("shortcut",id,"accepted",true);feedback(action,"已发送快捷键","");return;}
        shortcutBusy=true;lastShortcut=RhActions.obj("shortcut",id,"pending",true);feedback(action,"正在发送快捷键…","");updatePanel();
        new Thread(()->{JSONObject result;try{RhHost.request(game,game.getIntent().getStringExtra(Game.EXTRA_HOST),game.getIntent().getByteArrayExtra(Game.EXTRA_SERVER_CERT),"/v1/input/secure-attention",RhActions.obj("action","ctrl-alt-delete"));result=RhActions.obj("shortcut",id,"accepted",true);}catch(Exception failure){result=RhActions.obj("shortcut",id,"accepted",false,"error",problem(failure,"主机辅助服务不可用"));}final JSONObject completed=result;game.runOnUiThread(()->{if(disposed)return;shortcutBusy=false;lastShortcut=completed;feedback(action,completed.optBoolean("accepted")?"已发送快捷键":"",completed.optString("error"));updatePanel();});},"RHRemote secure attention").start();
    }
    private void applyAudio(){if(game.rhAudioRenderer!=null)game.rhAudioRenderer.setPlayback(profile.audioEnabled,profile.volume);}
}
