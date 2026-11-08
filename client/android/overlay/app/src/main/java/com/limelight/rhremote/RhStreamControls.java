package com.limelight.rhremote;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import com.limelight.Game;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import org.json.*;

/** Session controls, visible feedback and system Back use the same action dispatcher. */
public final class RhStreamControls implements RhActions.Target {
    private final Game game;private final RhTouchController touch;private final PreferenceConfiguration preferences;
    private final RhStreamPanel panel;private final RhProfile profile;private final RhDisplay display;
    private boolean disposed,connected,firstFrame,shortcutBusy,displayBusy;
    private final RhSoftKeyboard keyboard;
    private final RhFloatingKeyboard floating;
    private final java.util.LinkedHashSet<String> keyboardModifiers=new java.util.LinkedHashSet<>();
    private final RhSessionWarning warning;
    private JSONObject lastShortcut=RhActions.obj();private String displayError="";
    private String feedbackAction="",feedbackGroup="",feedbackMessage="",feedbackError="",menu="";
    private AlertDialog menuDialog,exitDialog;
    private ArrayAdapter<String> pinnedAdapter;
    private JSONArray pinnedCatalog;
    public static RhStreamControls install(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile,RhDisplay display){RhStreamControls controls=new RhStreamControls(game,touch,preferences,profile,display);return controls.disposed?null:controls;}
    private RhStreamControls(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile,RhDisplay display){
        this.game=game;this.touch=touch;this.preferences=preferences;this.profile=profile;this.display=display;
        restoreLocalPreferences();
        keyboard=new RhSoftKeyboard(game);floating=new RhFloatingKeyboard(game,(android.view.ViewGroup)game.findViewById(android.R.id.content),this::viewAction);
        panel=new RhStreamPanel(game,game.findViewById(android.R.id.content),this::viewAction);panel.setDeviceName(game.getIntent().getStringExtra(Game.EXTRA_PC_NAME));
        View notification=game.findViewById(R.id.notificationOverlay);warning=notification instanceof RhSessionWarning?(RhSessionWarning)notification:null;
        if(warning!=null){warning.bind(panel.controlHandle());warning.setChanged(this::updatePanel);}
        touch.setTransformListener(this::transformed);
        updatePanel();RhActions.initialize(game,null);if(!RhActions.attachStream(this,game))dispose();
    }
    private void restoreLocalPreferences(){
        SharedPreferences prefs=game.getSharedPreferences("rhremote.global",0);
        if(prefs.contains("session.trackpad")){
            boolean tp=prefs.getBoolean("session.trackpad",profile.trackpad);
            profile.trackpad=tp;preferences.touchscreenTrackpad=tp;touch.setTrackpad(tp);
        }
        if(prefs.getBoolean("session.scrollMode",false))touch.setScrollMode(true);
        if(prefs.getBoolean("session.clickScrollMode",false))touch.setClickScrollMode(true);
        if(prefs.contains("session.showStats")){
            preferences.enablePerfOverlay=prefs.getBoolean("session.showStats",preferences.enablePerfOverlay);
            View overlay=game.findViewById(R.id.performanceOverlay);
            if(overlay!=null)overlay.setVisibility(preferences.enablePerfOverlay?View.VISIBLE:View.GONE);
        }
        if(prefs.contains("session.audioEnabled"))profile.audioEnabled=prefs.getBoolean("session.audioEnabled",profile.audioEnabled);
        if(prefs.contains("session.volume")){
            int v=prefs.getInt("session.volume",profile.volume);
            if(v>=0&&v<=100)profile.volume=v;
        }
        applyAudio();
    }
    private void savePointerPreferences(){
        game.getSharedPreferences("rhremote.global",0).edit()
            .putBoolean("session.trackpad",touch.isTrackpad())
            .putBoolean("session.scrollMode",touch.isScrollMode())
            .putBoolean("session.clickScrollMode",touch.isClickScrollMode())
            .apply();
    }
    public void dispose(){if(disposed)return;disposed=true;keyboard.dispose();floating.dispose();if(warning!=null)warning.dispose();closeMenu();closeExit();touch.setTransformListener(null);touch.cancel();panel.dispose();RhActions.detachStream(this);}
    private void viewAction(String id,JSONObject args){if(!disposed&&!game.isFinishing()&&RhActions.ownsStream(this))RhActions.click(id,args);}
    public void markFirstFrame(){if(disposed)return;firstFrame=true;game.runOnUiThread(this::updatePanel);}
    public void markConnected(){if(disposed)return;connected=true;try{if(display!=null)display.applyInitial();}catch(Exception failure){displayError=problem(failure,"屏幕切换失败");noteFailure("stream.display.select",displayError);}updatePanel();}
    private boolean busy(){return displayBusy||shortcutBusy||keyboard.pending();}
    private static boolean controlMutation(String id){return java.util.Arrays.asList("stream.displays.refresh","stream.display.select","stream.shortcut","stream.shortcut.pin.set","stream.overlay.move","stream.overlay.reset","stream.window.move","stream.mode.toggle","stream.keyboard.toggle","stream.keyboard.floating.set","stream.keyboard.press","stream.keyboard.button.set","stream.keyboard.layout.set","stream.scroll.set","stream.click-scroll.set","stream.pan.toggle","stream.zoom.reset","stream.stats.toggle","stream.audio.toggle","stream.audio.volume").contains(id);}
    private static String group(String id){return (id.startsWith("stream.display.")||id.startsWith("stream.displays."))||id.equals("stream.window.move")||id.equals("stream.pan.toggle")||id.equals("stream.zoom.reset")?"displays":id.startsWith("stream.audio.")?"audio":"input";}
    private static String problem(Exception failure,String fallback){return failure.getMessage()==null?fallback:failure.getMessage();}
    private static String failed(String id){switch(id){case "stream.displays.refresh":return "屏幕列表读取失败";case "stream.display.select":return "屏幕切换失败";case "stream.window.move":return "窗口移动失败";case "stream.shortcut":return "快捷键发送失败";case "stream.audio.toggle":case "stream.audio.volume":return "声音设置失败";case "stream.keyboard.toggle":return "键盘操作失败";case "stream.stats.toggle":return "统计设置失败";default:return "操作失败";}}
    private void feedback(String id,String message,String error){feedbackAction=id;feedbackGroup=group(id);feedbackMessage=error.isEmpty()?message:failed(id);feedbackError=error;}
    private boolean keyboardVisible(){return keyboard.visible();}
    private void updatePanel(){
        if(disposed)return;
        if(warning!=null){
            if(menu.equals("warning")&&menuDialog!=null){if(!warning.active())closeMenu();else menuDialog.setMessage(warning.message());}
            warning.cover(!firstFrame?"first-frame":panel.isExpanded()?"controls":floating.enabled()?"floating-keyboard":keyboard.pending()||keyboardVisible()?"keyboard":menuDialog!=null||exitDialog!=null?"menu":"");
            panel.updateNetworkWarning(warning.message(),warning.summary());
        }
        panel.updateFloatingKeyboard(floating.enabled());panel.updateKeyboardButton(floating.buttonVisible());panel.updateKeyboardLayout(floating.layoutName().equals("simple"));floating.modifiers(keyboardModifiers);floating.update(connected&&!busy(),!firstFrame||panel.isExpanded()||menuDialog!=null||exitDialog!=null,keyboard.pending()||keyboardVisible());panel.update(touch.isTrackpad(),touch.isPanMode(),profile.audioEnabled,profile.volume,display==null?RhActions.obj():display.state(),displayBusy,displayError,feedbackAction);String message=feedbackMessage;String scope=feedbackGroup;if(!busy()&&feedbackError.isEmpty()&&(!connected||!firstFrame)){scope=panel.tab();message=connected?"等待首帧…":"正在建立串流…";}panel.updateControls(connected,preferences.enablePerfOverlay,touch.scale(),busy(),scope,message,feedbackError);panel.updateScrollMode(touch.isScrollMode());panel.updateClickScrollMode(touch.isClickScrollMode());if(menuDialog!=null&&menuDialog.getListView()!=null)menuDialog.getListView().setEnabled(!busy());
    }
    public JSONObject state(){TextView overlay=game.findViewById(R.id.performanceOverlay);return RhActions.obj("connected",connected,"firstFrame",firstFrame,"phase",!connected?"starting":firstFrame?"connected":"first-frame","audioEnabled",profile.audioEnabled,"volume",profile.volume,"audioPackets",game.rhAudioRenderer==null?0:game.rhAudioRenderer.packets(),"controls",panel.isExpanded(),"controlsTab",panel.tab(),"controlsLayout",panel.layoutState(),"connectionWarning",warning==null?RhActions.obj():warning.state(),"controlAction",RhActions.obj("action",feedbackAction,"group",feedbackGroup,"busy",busy(),"message",feedbackMessage,"error",feedbackError),"streamMenu",menu,"exitPrompt",exitDialog!=null,"trackpad",touch.isTrackpad(),"pan",touch.isPanMode(),"scrollMode",touch.isScrollMode(),"clickScrollMode",touch.isClickScrollMode(),"floatingKeyboard",floating.enabled(),"keyboardModifiers",new JSONArray(keyboardModifiers),"keyboardButtonVisible",floating.buttonVisible(),"keyboardLayout",floating.layoutName(),"pinnedShortcuts",new JSONArray(floating.pinnedShortcuts()),"buttonPositions",floating.positionsJson(),"floatingKeyboardLayout",floating.state(),"keyboard",keyboardVisible(),"keyboardPending",keyboard.pending(),"keyboardTargetVisible",keyboard.target(),"keyboardError",keyboard.error(),"zoom",touch.viewTransform(),"showStats",preferences.enablePerfOverlay,"statistics",overlay==null?"":overlay.getText().toString(),"surface",touch.surfaceSize(),"lastShortcut",lastShortcut,"shortcutBusy",shortcutBusy,"display",display==null?RhActions.obj():display.state(),"displayBusy",displayBusy,"displayError",displayError);}
    public void noteFailure(String id,String error){if(disposed||busy()||id.equals(feedbackAction)&&error.equals(feedbackError))return;feedback(id,"",error);updatePanel();}
    public boolean dispatchKeyEvent(KeyEvent event){return !disposed&&panel.dispatchKeyEvent(event,this::goBack);}
    private void transformed(double scale){panel.updateZoom((float)scale);if(feedbackAction.equals("stream.zoom.reset")&&feedbackError.isEmpty()&&Math.abs(scale-1)>.001){feedbackAction="input.gesture";feedbackGroup="displays";feedbackMessage="";if(panel.isExpanded()&&panel.tab().equals("displays"))panel.updateFeedback("displays","","");}}
    public boolean goBack(){
        if(disposed)return false;
        if(exitDialog!=null){viewAction("stream.exit.resolve",RhActions.obj("choice","stay"));return true;}
        if(menuDialog!=null){viewAction("stream.menu.close",RhActions.obj());return true;}
        if(keyboard.pending()||keyboardVisible()){requestKeyboard(false);return true;}
        if(panel.isExpanded()){viewAction("stream.controls.toggle",RhActions.obj());return true;}
        viewAction(connected?"stream.exit.request":"stream.disconnect",RhActions.obj());return true;
    }
    public JSONObject perform(String id,JSONObject args)throws Exception{
        try{return performAction(id,args);}catch(Exception failure){noteFailure(id,problem(failure,"会话操作失败"));throw failure;}
    }
    private JSONObject performAction(String id,JSONObject args)throws Exception{
        if(disposed||game.isFinishing())throw new IllegalStateException("no active stream");
        if(exitDialog!=null&&!java.util.Arrays.asList("stream.exit.resolve","stream.disconnect","stream.menu.close").contains(id))throw new IllegalStateException("请先选择是否断开连接");
        if(!connected&&(controlMutation(id)||id.equals("input.gesture")))throw new IllegalStateException("stream is not connected");
        // Reversing our own pending IME request remains available to the Game gesture.
        boolean keyboardReversal=id.equals("stream.keyboard.toggle")&&keyboard.pending()&&!displayBusy&&!shortcutBusy;
        if(busy()&&controlMutation(id)&&!keyboardReversal)throw new IllegalStateException("上一项会话操作尚未完成");
        switch(id){
            case "stream.displays.open":panel.setExpanded(true);panel.setTab("displays");loadMissingDisplays();break;
            case "stream.displays.refresh":displayAction(null);break;
            case "stream.display.select":String displayId=args.getString("displayId");if(displayId.isEmpty()||displayId.length()>512)throw new IllegalArgumentException("displayId required");displayAction(displayId);break;
            case "stream.shortcuts.open":openMenu("shortcuts");break;
            case "stream.menu.open":openMenu(args.getString("menu"));break;
            case "stream.menu.close":closeMenu();break;
            case "stream.shortcut":keyboardModifiers.clear();sendShortcut(args.getString("shortcut"),"stream.shortcut");if(menu.equals("shortcuts"))closeMenu();break;
            case "stream.shortcut.pin.set":
                String pinId=args.getString("shortcut");
                Boolean pinnedArg=args.has("pinned")?args.getBoolean("pinned"):null;
                if(args.has("pinned")&&!(args.get("pinned") instanceof Boolean))throw new IllegalArgumentException("pinned must be boolean");
                boolean pinnedNow=floating.setShortcutPinned(pinId,pinnedArg);
                refreshPinnedShortcutsDialog();
                feedback(id,pinnedNow?"已添加外置快捷键":"已移除外置快捷键","");
                break;
            case "stream.overlay.move":
                floating.moveButton(args.getString("id"),(float)args.getDouble("x"),(float)args.getDouble("y"));
                break;
            case "stream.overlay.reset":
                floating.resetPositions();
                feedback(id,"已重置按钮位置","");
                break;
            case "stream.window.move":String direction=args.getString("direction");if(!direction.equals("left")&&!direction.equals("right"))throw new IllegalArgumentException("direction must be left or right");sendShortcut(direction.equals("left")?"win-shift-left":"win-shift-right","stream.window.move");break;
            case "stream.controls.toggle":closeMenu();keyboardModifiers.clear();panel.setExpanded(!panel.isExpanded());break;
            case "stream.controls.tab":closeMenu();panel.setTab(args.getString("tab"));if(panel.tab().equals("displays"))loadMissingDisplays();break;
            case "stream.mode.toggle":touch.setTrackpad(!touch.isTrackpad());profile.trackpad=touch.isTrackpad();preferences.touchscreenTrackpad=profile.trackpad;savePointerPreferences();feedback(id,touch.isTrackpad()?"触控板模式":"直接点击模式","");break;
            case "stream.keyboard.toggle":floating.setEnabled(false);keyboardModifiers.clear();boolean show=keyboard.pending()?!keyboard.target():!keyboardVisible();panel.setExpanded(false);requestKeyboard(show);break;
            case "stream.keyboard.floating.set":if(!connected)throw new IllegalStateException("stream is not connected");if(!(args.get("enabled") instanceof Boolean))throw new IllegalArgumentException("enabled must be boolean");boolean floatingEnabled=args.getBoolean("enabled");keyboardModifiers.clear();if(floatingEnabled&&keyboardVisible())requestKeyboard(false);floating.setEnabled(floatingEnabled);panel.updateFloatingKeyboard(floating.enabled());panel.setExpanded(false);break;
            case "stream.keyboard.button.set":if(!(args.get("enabled") instanceof Boolean))throw new IllegalArgumentException("enabled must be boolean");floating.setButtonVisible(args.getBoolean("enabled"));break;
            case "stream.keyboard.layout.set":floating.setLayout(args.getString("layout"));keyboardModifiers.clear();break;
            case "stream.keyboard.catalog":return new JSONObject().put("keys",RhKeyboard.catalog(game));
            case "stream.keyboard.reveal":floating.reveal(args.getString("key"));break;
            case "stream.keyboard.press":
                JSONObject key=RhKeyboard.find(game,args.getString("key"));String modifier=key.optString("modifier");boolean tap=args.optBoolean("tap",false);if(args.has("tap")&&!(args.get("tap") instanceof Boolean))throw new IllegalArgumentException("tap must be boolean");
                if(!modifier.isEmpty()&&!tap){if(!keyboardModifiers.remove(modifier))keyboardModifiers.add(modifier);}
                else{JSONObject command=RhKeyboard.command(key,tap?java.util.Collections.emptyList():keyboardModifiers);keyboardModifiers.clear();if(command.optBoolean("secureAttention"))sendShortcut("ctrl-alt-delete",id);else{RhShortcuts.send(command,RhShortcuts::sendNative);feedback(id,"已发送按键","");}}break;
            case "stream.scroll.set":touch.setScrollMode(args.getBoolean("enabled"));savePointerPreferences();feedback(id,touch.isScrollMode()?"滚动模式已开启":"滚动模式已关闭","");break;
            case "stream.click-scroll.set":touch.setClickScrollMode(args.getBoolean("enabled"));savePointerPreferences();feedback(id,touch.isClickScrollMode()?"点击与滚动已开启":"点击与滚动已关闭","");break;
            case "stream.pan.toggle":touch.togglePan();savePointerPreferences();feedback(id,touch.isPanMode()?"移动画面已开启":"移动画面已关闭","");break;
            case "stream.zoom.reset":touch.resetZoom();feedback(id,"缩放已还原","");break;
            case "stream.stats.toggle":preferences.enablePerfOverlay=!preferences.enablePerfOverlay;game.findViewById(R.id.performanceOverlay).setVisibility(preferences.enablePerfOverlay?View.VISIBLE:View.GONE);game.getSharedPreferences("rhremote.global",0).edit().putBoolean("session.showStats",preferences.enablePerfOverlay).apply();feedback(id,preferences.enablePerfOverlay?"统计已开启":"统计已关闭","");break;
            case "stream.audio.toggle":profile.audioEnabled=!profile.audioEnabled;applyAudio();game.getSharedPreferences("rhremote.global",0).edit().putBoolean("session.audioEnabled",profile.audioEnabled).apply();feedback(id,profile.audioEnabled?"声音已开启":"声音已关闭","");break;
            case "stream.audio.volume":int volume=args.getInt("volume");if(volume<0||volume>100)throw new IllegalArgumentException("volume must be 0..100");profile.volume=volume;applyAudio();game.getSharedPreferences("rhremote.global",0).edit().putInt("session.volume",profile.volume).apply();feedback(id,"音量 "+volume+"%","");break;
            case "stream.exit.request":requestExit();break;
            case "stream.exit.resolve":if(exitDialog==null)throw new IllegalStateException("没有等待处理的断开请求");String choice=args.getString("choice");if(!choice.equals("stay")&&!choice.equals("disconnect"))throw new IllegalArgumentException("unknown exit choice");closeExit();if(choice.equals("disconnect"))return performAction("stream.disconnect",RhActions.obj());break;
            case "stream.disconnect":RhActions.connectionStopping(game.getIntent().getStringExtra(Game.EXTRA_PC_UUID));game.rhCancelConnection();dispose();game.finish();break;
            case "stream.test.interrupt":if(!connected)throw new IllegalStateException("stream is not connected");game.rhTestInterrupt();break;
            case "input.gesture":touch.inject(args);break;
            default:throw new IllegalArgumentException("unsupported stream action");
        }
        updatePanel();return RhActions.obj("accepted",true);
    }
    private void requestExit(){if(exitDialog!=null)return;exitDialog=new AlertDialog.Builder(game,RhUiStyle.dialogTheme(game)).setTitle("断开连接？").setPositiveButton("断开",(dialog,which)->dialogAction(dialog,"stream.exit.resolve",RhActions.obj("choice","disconnect"))).setNegativeButton("继续连接",(dialog,which)->dialogAction(dialog,"stream.exit.resolve",RhActions.obj("choice","stay"))).create();exitDialog.setOnCancelListener(dialog->dialogAction(dialog,"stream.exit.resolve",RhActions.obj("choice","stay")));localEscape(exitDialog,"stream.exit.resolve",RhActions.obj("choice","stay"));exitDialog.show();RhUiStyle.dialog(exitDialog,true);}
    private void dialogAction(DialogInterface owner,String action,JSONObject arguments){if(disposed||(owner!=menuDialog&&owner!=exitDialog))return;viewAction(action,arguments);}
    /** Consume both halves locally; older Android dialogs do not handle Escape consistently. */
    private void localEscape(AlertDialog dialog,String action,JSONObject arguments){dialog.setOnKeyListener((ignored,key,event)->{if(key!=KeyEvent.KEYCODE_ESCAPE)return false;if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())dialogAction(dialog,action,arguments);return true;});}
    private void closeExit(){if(exitDialog!=null){exitDialog.dismiss();exitDialog=null;}}
    private void closeMenu(){if(menuDialog!=null){menuDialog.dismiss();menuDialog=null;}menu="";pinnedAdapter=null;pinnedCatalog=null;}
    private void refreshPinnedShortcutsDialog(){
        if(!menu.equals("pinned-shortcuts")||pinnedAdapter==null||pinnedCatalog==null)return;
        pinnedAdapter.clear();
        for(int i=0;i<pinnedCatalog.length();i++){
            JSONObject item=pinnedCatalog.optJSONObject(i);if(item==null)continue;
            String id=item.optString("id"),label=item.optString("label",id);
            pinnedAdapter.add(floating.isShortcutPinned(id)?label+"  ✓":label);
        }
        pinnedAdapter.notifyDataSetChanged();
    }
    private void openMenu(String kind)throws Exception{
        if(!java.util.Arrays.asList("shortcuts","pinned-shortcuts","displays","feedback","warning").contains(kind))throw new IllegalArgumentException("unknown stream menu");
        if(kind.equals("warning")&&(warning==null||!warning.active()))throw new IllegalStateException("当前没有连接提示");
        if(kind.equals("feedback")&&feedbackError.isEmpty())throw new IllegalStateException("没有会话操作错误");closeMenu();menu=kind;
        if(kind.equals("displays")){panel.setExpanded(true);panel.setTab("displays");loadMissingDisplays();return;}
        String title=kind.equals("warning")?"连接状态":kind.equals("feedback")?"操作失败":kind.equals("pinned-shortcuts")?"外置快捷键（点击勾选）":"远程快捷键";
        AlertDialog.Builder builder=new AlertDialog.Builder(game,RhUiStyle.dialogTheme(game)).setTitle(title).setNegativeButton("关闭",(dialog,which)->dialogAction(dialog,"stream.menu.close",RhActions.obj()));
        final JSONArray list=(kind.equals("shortcuts")||kind.equals("pinned-shortcuts"))?RhShortcuts.catalog(game):null;
        if(kind.equals("warning"))builder.setMessage(warning.message());
        else if(kind.equals("feedback"))builder.setMessage(feedbackError);
        else if(kind.equals("pinned-shortcuts")){
            pinnedCatalog=list;
            java.util.List<String> labels=new java.util.ArrayList<>();
            for(int i=0;i<list.length();i++){
                JSONObject item=list.getJSONObject(i);String id=item.getString("id"),label=item.getString("label");
                labels.add(floating.isShortcutPinned(id)?label+"  ✓":label);
            }
            pinnedAdapter=new ArrayAdapter<>(builder.getContext(),android.R.layout.simple_list_item_1,labels);
            builder.setAdapter(pinnedAdapter,null);
        }else{
            String[] labels=new String[list.length()];for(int i=0;i<labels.length;i++)labels[i]=list.getJSONObject(i).getString("label");
            builder.setAdapter(new ArrayAdapter<>(builder.getContext(),android.R.layout.simple_list_item_1,labels),null);
        }
        menuDialog=builder.create();final AlertDialog owner=menuDialog;owner.setOnCancelListener(dialog->dialogAction(dialog,"stream.menu.close",RhActions.obj()));localEscape(owner,"stream.menu.close",RhActions.obj());owner.show();RhUiStyle.dialog(owner,false);
        if(list!=null){
            final String rowAction=kind.equals("pinned-shortcuts")?"stream.shortcut.pin.set":"stream.shortcut";
            owner.getListView().setOnItemClickListener((parent,view,index,row)->dialogAction(owner,rowAction,RhActions.obj("shortcut",list.optJSONObject(index).optString("id"))));
        }
        updatePanel();
    }
    private void loadMissingDisplays(){JSONObject data=display==null?RhActions.obj():display.state();JSONArray monitors=data.optJSONArray("displays");if(!displayBusy&&(monitors==null||monitors.length()==0||!data.optString("error").isEmpty()))displayAction(null);}
    private void displayAction(String id){
        if(display==null)throw new IllegalStateException("屏幕组件不可用");displayBusy=true;String action=id==null?"stream.displays.refresh":"stream.display.select";feedback(action,id==null?"正在读取屏幕…":"正在切换屏幕…","");updatePanel();
        new Thread(()->{String error="";JSONObject prepared=null;try{if(id==null)display.refresh();else prepared=display.prepareSelection(id);}catch(Exception failure){error=problem(failure,"屏幕切换失败");}final String result=error;final JSONObject candidate=prepared;game.runOnUiThread(()->{if(disposed)return;displayBusy=false;displayError=result;if(result.isEmpty()&&id!=null)try{display.commitSelection(candidate,connected);}catch(Exception failure){displayError=problem(failure,"屏幕切换失败");}feedback(action,displayError.isEmpty()?(id==null?"屏幕列表已刷新":"已发送屏幕切换"):"",displayError);updatePanel();});},"RHRemote display").start();
    }
    private void sendShortcut(String id,String action)throws Exception{
        JSONObject shortcut=RhShortcuts.find(game,id);
        if(!shortcut.optBoolean("secureAttention")){try{RhShortcuts.send(shortcut,RhShortcuts::sendNative);}catch(Exception failure){lastShortcut=RhActions.obj("shortcut",id,"accepted",false,"error",problem(failure,"快捷键发送失败"));throw failure;}lastShortcut=RhActions.obj("shortcut",id,"accepted",true);feedback(action,"已发送快捷键","");return;}
        shortcutBusy=true;lastShortcut=RhActions.obj("shortcut",id,"pending",true);feedback(action,"正在发送快捷键…","");updatePanel();
        new Thread(()->{JSONObject result;try{RhHost.request(game,game.getIntent().getStringExtra(Game.EXTRA_HOST),game.getIntent().getByteArrayExtra(Game.EXTRA_SERVER_CERT),"/v1/input/secure-attention",RhActions.obj("action","ctrl-alt-delete"));result=RhActions.obj("shortcut",id,"accepted",true);}catch(Exception failure){result=RhActions.obj("shortcut",id,"accepted",false,"error",problem(failure,"主机辅助服务不可用"));}final JSONObject completed=result;game.runOnUiThread(()->{if(disposed)return;shortcutBusy=false;lastShortcut=completed;feedback(action,completed.optBoolean("accepted")?"已发送快捷键":"",completed.optString("error"));updatePanel();});},"RHRemote secure attention").start();
    }
    private void applyAudio(){if(game.rhAudioRenderer!=null)game.rhAudioRenderer.setPlayback(profile.audioEnabled,profile.volume);}
    private void requestKeyboard(boolean show){
        feedback("stream.keyboard.toggle",show?"正在打开键盘…":"正在关闭键盘…","");
        keyboard.request(show,(visible,error)->{if(disposed)return;if(feedbackAction.equals("stream.keyboard.toggle"))feedback("stream.keyboard.toggle",visible?"键盘已打开":"键盘已关闭",error);if(!error.isEmpty()&&!panel.isExpanded()&&menuDialog==null&&exitDialog==null){panel.setExpanded(true);panel.setTab("input");}updatePanel();});updatePanel();
    }
    public void toggleKeyboardFromGame(){if(disposed||game.isFinishing()||!RhActions.ownsStream(this))return;try{perform("stream.keyboard.toggle",RhActions.obj());}catch(Exception failure){noteFailure("stream.keyboard.toggle",problem(failure,"键盘操作失败"));}}
}
