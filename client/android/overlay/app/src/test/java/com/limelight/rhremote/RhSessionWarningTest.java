package com.limelight.rhremote;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import com.limelight.Game;
import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real upstream status callback and the production session controller, without transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,shadows={RhAdmissionTest.Controls.class,RhSessionMenuTest.Keyboard.class})
public final class RhSessionWarningTest {
    private final RhSessionMenuTest fixture=new RhSessionMenuTest();
    private PreferenceConfiguration preferences;
    private RhSessionWarning warning;
    private void field(String name,Object value)throws Exception{Field field=Game.class.getDeclaredField(name);field.setAccessible(true);field.set(fixture.game,value);}
    private void create()throws Exception{fixture.create(false,true);warning=fixture.game.findViewById(R.id.notificationOverlay);preferences=new PreferenceConfiguration();preferences.bitrate=6000;field("prefConfig",preferences);field("notificationOverlayView",warning);}
    private void status(int value){fixture.game.connectionStatusUpdate(value);Shadows.shadowOf(Looper.getMainLooper()).idle();fixture.layout();}
    private TextView detailText(AlertDialog dialog){return dialog.findViewById(android.R.id.message);}
    @After public void cleanup(){fixture.cleanup();}
    @Test public void upstreamStatusAndWarningPreferenceRetainTheirMeaning()throws Exception{
        create();assertFalse(warning.active());status(MoonBridge.CONN_STATUS_POOR);assertTrue(warning.active());assertEquals("连接较慢，可降低码率",warning.message());assertEquals(View.VISIBLE,warning.getVisibility());assertFalse(warning.isClickable());assertFalse(warning.isFocusable());
        preferences.bitrate=5000;status(MoonBridge.CONN_STATUS_POOR);assertEquals("连接质量不佳",warning.message());
        status(MoonBridge.CONN_STATUS_OKAY);assertFalse(warning.active());assertEquals(View.GONE,warning.getVisibility());
        preferences.disableWarnings=true;status(MoonBridge.CONN_STATUS_POOR);assertFalse(warning.active());
        assertTrue(((RhSessionMenuTest.KeyProbeGame)fixture.game).remoteKeys.isEmpty());
    }
    @Test public void bannerYieldsToControlsFloatingKeysAndMenusAndRespectsUpstreamHide()throws Exception{
        create();status(MoonBridge.CONN_STATUS_POOR);fixture.toggle();assertEquals("controls",fixture.controls.state().getJSONObject("connectionWarning").getString("suppressed"));assertTrue(warning.active());assertEquals(View.GONE,warning.getVisibility());fixture.toggle();assertEquals(View.VISIBLE,warning.getVisibility());
        fixture.controls.perform("stream.keyboard.floating.set",RhActions.obj("enabled",true));assertEquals("floating-keyboard",warning.state().getString("suppressed"));fixture.controls.perform("stream.keyboard.floating.set",RhActions.obj("enabled",false));assertEquals(View.VISIBLE,warning.getVisibility());
        fixture.controls.perform("stream.shortcuts.open",RhActions.obj());assertEquals(View.GONE,warning.getVisibility());fixture.controls.perform("stream.menu.close",RhActions.obj());assertEquals(View.VISIBLE,warning.getVisibility());
        warning.setVisibility(View.GONE);fixture.controls.perform("stream.controls.toggle",RhActions.obj());fixture.controls.perform("stream.controls.toggle",RhActions.obj());assertFalse(warning.active());assertEquals(View.GONE,warning.getVisibility());
        fixture.controls.dispose();warning.setVisibility(View.VISIBLE);warning.setText("late status");assertFalse(warning.active());assertEquals(View.GONE,warning.getVisibility());
    }
    @Test public void warningDetailsDoNotReuseAnErrorFromAnotherTabAndResolveOnRecovery()throws Exception{
        create();status(MoonBridge.CONN_STATUS_POOR);fixture.controls.noteFailure("stream.shortcut","old input failure");fixture.toggle();fixture.controls.perform("stream.controls.tab",RhActions.obj("tab","audio"));fixture.layout();
        fixture.button("详情").performClick();assertEquals("warning",fixture.controls.state().getString("streamMenu"));assertEquals(warning.message(),detailText(fixture.dialog("menuDialog")).getText().toString());
        status(MoonBridge.CONN_STATUS_OKAY);assertEquals("",fixture.controls.state().getString("streamMenu"));assertNull(fixture.dialog("menuDialog"));
        fixture.controls.perform("stream.controls.tab",RhActions.obj("tab","input"));fixture.layout();fixture.button("详情").performClick();assertEquals("feedback",fixture.controls.state().getString("streamMenu"));assertEquals("old input failure",detailText(fixture.dialog("menuDialog")).getText().toString());
    }
}
