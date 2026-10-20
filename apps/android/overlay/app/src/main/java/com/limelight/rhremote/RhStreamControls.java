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

/** Native controls route through the same dispatcher as the authenticated local HTTP API. */
public final class RhStreamControls implements RhActions.Target {
    private final Game game;private final RhTouchController touch;private final PreferenceConfiguration preferences;
    private final HorizontalScrollView scroller;private final Button mode,pan,audio;private final RhProfile profile;
    private boolean keyboard,disposed,connected;
    private static int dp(Game g,int v){return Math.round(g.getResources().getDisplayMetrics().density*v);}
    private static GradientDrawable background(String color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(Color.parseColor(color));d.setCornerRadius(radius);return d;}
    public static RhStreamControls install(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile){return new RhStreamControls(game,touch,preferences,profile);}
    private RhStreamControls(Game game,RhTouchController touch,PreferenceConfiguration preferences,RhProfile profile){
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
        pan=add(toolbar,"移动画面","stream.pan.toggle");add(toolbar,"还原缩放","stream.zoom.reset");audio=add(toolbar,profile.audioEnabled?"声音：开":"声音：关","stream.audio.toggle");add(toolbar,"音量 −","stream.audio.volume");add(toolbar,"音量 ＋","stream.audio.volume");add(toolbar,"统计","stream.stats.toggle");add(toolbar,"断开","stream.disconnect");RhActions.initialize(game,null);RhActions.attachStream(this);
    }
    public void dispose(){disposed=true;touch.cancel();RhActions.detachStream(this);}
    public void markConnected(){connected=true;}
    private void click(String id){RhActions.click(id,RhActions.obj());}
    private Button add(LinearLayout row,String title,String action){Button b=new Button(game);b.setText(title);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(Color.parseColor("#34415b"));b.setBackground(background("#f8faff",dp(game,12)));b.setPadding(dp(game,12),0,dp(game,12),0);b.setOnClickListener(v->{if(action.equals("stream.audio.volume"))RhActions.click(action,RhActions.obj("volume",Math.max(0,Math.min(100,profile.volume+(title.contains("＋")?10:-10)))));else click(action);});row.addView(b,new LinearLayout.LayoutParams(-2,dp(game,46)));return b;}
    public JSONObject state(){TextView overlay=game.findViewById(R.id.performanceOverlay);return RhActions.obj("connected",connected,"audioEnabled",profile.audioEnabled,"volume",profile.volume,"audioPackets",game.rhAudioRenderer==null?0:game.rhAudioRenderer.packets(),"controls",scroller.getVisibility()==View.VISIBLE,"trackpad",touch.isTrackpad(),"pan",touch.isPanMode(),"keyboard",keyboard,"showStats",preferences.enablePerfOverlay,"statistics",overlay==null?"":overlay.getText().toString(),"surface",touch.surfaceSize());}
    public JSONObject perform(String id,JSONObject args)throws Exception{
        if(disposed||game.isFinishing())throw new IllegalStateException("no active stream");
        if(!connected && id.equals("input.gesture"))throw new IllegalStateException("stream is not connected");
        switch(id){
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
    private void applyAudio(){if(game.rhAudioRenderer!=null)game.rhAudioRenderer.setPlayback(profile.audioEnabled,profile.volume);audio.setText(profile.audioEnabled?"声音：开":"声音：关");}
}
