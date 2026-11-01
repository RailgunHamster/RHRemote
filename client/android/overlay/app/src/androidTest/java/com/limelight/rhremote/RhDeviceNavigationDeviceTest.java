package com.limelight.rhremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real PcView controls, profile storage and configuration changes on the dedicated emulator. */
@RunWith(AndroidJUnit4.class)
public final class RhDeviceNavigationDeviceTest {
    private final Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    private RhHome home;
    private File folder;
    private final JSONArray steps = new JSONArray();
    private JSONArray devices;
    private int orientationChanges;
    private boolean fontAudit;
    private final JSONArray textClipping = new JSONArray();

    private void inspectText(View view, String step) throws Exception {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Rect visible = new Rect();
            if (text.length() > 0 && text.getLayout() != null && text.getGlobalVisibleRect(visible)
                    && visible.width() == text.getWidth() && visible.height() == text.getHeight()) {
                int room = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                if (text.getLayout().getHeight() > room) textClipping.put(RhActions.obj("step", step,
                        "text", text.getText().toString(), "height", text.getHeight(), "contentHeight", room,
                        "textHeight", text.getLayout().getHeight(), "fontPixels", text.getTextSize()));
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            inspectText(((ViewGroup) view).getChildAt(i), step);
    }

    private Object field(Object owner, String name) throws Exception {
        Field value = owner.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value.get(owner);
    }

    private JSONObject state() {
        JSONObject[] value = {null};
        instrument.runOnMainSync(() -> {
            try { value[0] = home.state(); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        return value[0];
    }

    private void action(String id, JSONObject args) {
        instrument.runOnMainSync(() -> {
            JSONObject result = RhActions.dispatch(id, args);
            assertTrue(result.toString(), result.optBoolean("ok"));
        });
        instrument.waitForIdleSync();
    }

    private View find(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (label.equals(view.getContentDescription()) || view instanceof Button &&
                label.contentEquals(((Button) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View control(String label) {
        View result = find(activity.findViewById(android.R.id.content), label);
        assertNotNull(label, result);
        return result;
    }

    private void click(String label) {
        instrument.runOnMainSync(() -> {
            View view = control(label);
            assertTrue(label, view.isEnabled());
            assertTrue(label, view.performClick());
        });
        instrument.waitForIdleSync();
    }

    private void editVolume(String value) throws Exception {
        JSONArray catalog = RhSettings.catalog(activity);
        String label = null;
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.getJSONObject(i);
            if (item.getString("key").equals("volume")) label = item.getString("label");
        }
        assertNotNull(label);
        final String description = label;
        instrument.runOnMainSync(() -> ((EditText) control(description)).setText(value));
        instrument.waitForIdleSync();
        assertEquals(value, state().getJSONObject("settingsInputs").getString("volume"));
    }

    private int index(String uuid) throws Exception {
        for (int i = 0; i < devices.length(); i++) {
            if (devices.getJSONObject(i).getString("uuid").equals(uuid)) return i;
        }
        throw new AssertionError("Missing device " + uuid);
    }

    private String name(String uuid) throws Exception {
        JSONObject device = devices.getJSONObject(index(uuid));
        return device.optString("displayName").isEmpty() ? device.getString("name") : device.getString("displayName");
    }

    private void choose(String uuid, boolean wide) throws Exception {
        if (wide) click("选择设备 " + name(uuid));
        else {
            int destination = index(uuid);
            instrument.runOnMainSync(() -> ((Spinner) control("切换远程设备")).setSelection(destination));
            instrument.waitForIdleSync();
        }
    }

    private void decision(int choice) throws Exception {
        assertFalse(state().isNull("navigationPrompt"));
        AlertDialog dialog = (AlertDialog) field(home, "navigationDialog");
        instrument.runOnMainSync(() -> {
            Button button = dialog.getButton(choice);
            assertNotNull(button);
            assertTrue(button.performClick());
        });
        instrument.waitForIdleSync();
    }

    private void orient(boolean wide) throws Exception {
        RhHome original = home;
        boolean[] before = {false};
        instrument.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            before[0] = decor.getWidth() > decor.getHeight();
        });
        instrument.runOnMainSync(() -> activity.setRequestedOrientation(wide ?
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean[] ready = {false};
        while (!ready[0]) {
            instrument.waitForIdleSync();
            instrument.runOnMainSync(() -> {
                View decor = activity.getWindow().getDecorView();
                ready[0] = (decor.getWidth() > decor.getHeight()) == wide && decor.getWidth() > 0;
            });
            assertTrue("Orientation did not settle", System.nanoTime() < deadline);
            if (!ready[0]) Thread.sleep(25);
        }
        assertSame("Orientation should rebind the existing draft owner", original, field(activity, "rhHome"));
        if (before[0] != wide) orientationChanges++;
    }

    private void assertProfile(String uuid, boolean wide) throws Exception {
        JSONObject current = state();
        assertEquals("settings", current.getString("page"));
        assertEquals(uuid, current.getString("selected"));
        assertEquals("audio", current.getString("profileTab"));
        if (wide) assertEquals("settings-split", current.getJSONObject("homeLayout").getJSONObject("browser").getString("mode"));
        else instrument.runOnMainSync(() -> assertEquals(indexUnchecked(uuid),
                ((Spinner) control("切换远程设备")).getSelectedItemPosition()));
        instrument.runOnMainSync(() -> {
            View save = control("保存");
            Rect visible = new Rect();
            assertTrue(save.getGlobalVisibleRect(visible));
            assertEquals(save.getHeight(), visible.height());
            assertEquals(save.getWidth(), visible.width());
            for (android.view.ViewParent parent = save.getParent(); parent instanceof View; parent = parent.getParent())
                assertFalse("Save must stay outside the scrolling body", parent instanceof ScrollView);
        });
        assertFalse(RhActions.hasStream());
    }

    private int indexUnchecked(String uuid) {
        try { return index(uuid); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private void capture(String name) throws Exception {
        Bitmap[] frame = {null};
        instrument.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            decor.jumpDrawablesToCurrentState();
            if (fontAudit) try { inspectText(decor, name); }
            catch (Exception error) { throw new RuntimeException(error); }
            frame[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(frame[0]));
        });
        File destination = new File(folder, name + ".png");
        assertFalse(destination.exists());
        try (FileOutputStream output = new FileOutputStream(destination)) {
            assertTrue(frame[0].compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { frame[0].recycle(); }
        JSONObject current = state();
        steps.put(RhActions.obj("step", name, "page", current.optString("page"),
                "selected", current.optString("selected"), "category", current.optString("profileTab"),
                "dirty", current.optBoolean("settingsDirty"), "file", destination.getName()));
    }

    private View title(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && !(view instanceof Button) &&
                text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = title(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void verifyPageReturns(String uuid, boolean wide, String theme) throws Exception {
        action("devices.filter", RhActions.obj("query", name(uuid)));
        for (String[] page : new String[][]{{"device.info.open", "设备资料"},
                {"device.add.open", "添加电脑"}, {"app.settings.open", "设置"}}) {
            action(page[0], page[0].equals("device.info.open") ? RhActions.obj("uuid", uuid) : RhActions.obj());
            instrument.runOnMainSync(() -> {
                View back = control("返回设备");
                View heading = title(activity.findViewById(android.R.id.content), page[1]);
                assertNotNull(page[1], heading);
                int[] backPosition = new int[2], headingPosition = new int[2];
                back.getLocationInWindow(backPosition);
                heading.getLocationInWindow(headingPosition);
                assertTrue("Back belongs before the page title", backPosition[0] + back.getWidth() <= headingPosition[0]);
                Rect visible = new Rect();
                assertTrue(back.getGlobalVisibleRect(visible));
                assertEquals(back.getWidth(), visible.width());
                assertEquals(back.getHeight(), visible.height());
            });
            capture("back-" + page[0].replace('.', '-') + "-" + (wide ? "wide-" : "portrait-") + theme);
            if (page[0].equals("app.settings.open")) {
                for (String tab : new String[]{"sync", "appearance", "about"}) {
                    action("app.settings.tab", RhActions.obj("tab", tab));
                    capture("global-" + tab + "-" + (wide ? "wide-" : "portrait-") + theme);
                }
                action("app.settings.tab", RhActions.obj("tab", "updates"));
            }
            click("返回设备");
            assertEquals("devices", state().getString("page"));
            assertEquals(uuid, state().getString("selected"));
            assertEquals(name(uuid), state().getString("deviceQuery"));
        }
        action("devices.filter", RhActions.obj("query", ""));
    }

    @Test public void nativeDeviceSwitchingAndRotationPreserveDraftOwnership() throws Exception {
        assertTrue("Dedicated emulator only", android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish"));
        String run = InstrumentationRegistry.getArguments().getString("navigationRun", "");
        assertTrue("Fresh explicit navigationRun required", run.matches("[a-zA-Z0-9_-]{1,64}"));
        fontAudit = InstrumentationRegistry.getArguments().getString("navigationFontAudit", "false").equals("true");
        Context context = instrument.getTargetContext();
        if (fontAudit) assertEquals(2f, context.getResources().getConfiguration().fontScale, 0.001f);
        File shared = new File(context.getFilesDir(), "rhremote-shared.json");
        byte[] originalBytes = Files.readAllBytes(shared.toPath());
        JSONObject original = RhShared.read(context);
        boolean originalTheme = RhShared.dark(context);
        SharedPreferences global = context.getSharedPreferences("rhremote.global", 0);
        boolean hadCheck = global.contains("lastUpdateCheck");
        long previousCheck = global.getLong("lastUpdateCheck", 0);
        folder = new File(context.getExternalFilesDir("ui-evidence"), "navigation-" + run);
        assertFalse(folder.exists());
        assertTrue(folder.mkdirs());
        global.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).commit();
        try {
            for (boolean dark : new boolean[]{false, true}) {
                RhShared.theme(context, dark);
                // Establish a coherent persisted theme fixture before comparing profiles.
                JSONObject themed = RhShared.read(context);
                themed.put("dark", dark);
                RhShared.write(shared, themed);
                activity = instrument.startActivitySync(new Intent().setClassName(context, "com.limelight.PcView")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                instrument.waitForIdleSync();
                home = (RhHome) field(activity, "rhHome");
                JSONObject current = state();
                assertFalse("Automatic sync must be disabled in this isolated fixture", current.getJSONObject("sync").optBoolean("automatic"));
                devices = current.getJSONArray("devices");
                assertTrue("Two saved devices required", devices.length() >= 2);
                String first = devices.getJSONObject(0).getString("uuid"), second = devices.getJSONObject(1).getString("uuid");
                String theme = dark ? "dark" : "light";
                int firstVolume = RhProfile.load(context, first).volume;
                int secondVolume = RhProfile.load(context, second).volume;
                int changedFirst = firstVolume == 42 ? 41 : 42, changedSecond = secondVolume == 75 ? 74 : 75;
                String baseline = RhShared.canonical(RhShared.read(context));
                orient(true);
                action("device.settings.open", RhActions.obj("uuid", first));
                click("音频");
                assertProfile(first, true);
                editVolume(Integer.toString(changedFirst));
                choose(second, true);
                decision(AlertDialog.BUTTON_NEUTRAL);
                assertEquals(Integer.toString(changedFirst), state().getJSONObject("settingsInputs").getString("volume"));
                assertEquals(baseline, RhShared.canonical(RhShared.read(context)));
                capture("wide-stay-" + theme);

                orient(false);
                assertProfile(first, false);
                assertEquals(Integer.toString(changedFirst), state().getJSONObject("settingsInputs").getString("volume"));
                capture("portrait-draft-" + theme);
                for (String tab : new String[]{"video", "input", "network", "advanced"}) {
                    action("settings.tab", RhActions.obj("tab", tab));
                    assertEquals(Integer.toString(changedFirst), state().getJSONObject("settingsInputs").getString("volume"));
                    assertEquals(baseline, RhShared.canonical(RhShared.read(context)));
                    instrument.runOnMainSync(() -> {
                        View save = control("保存");Rect visible = new Rect();
                        assertTrue(save.getGlobalVisibleRect(visible));
                        assertEquals(save.getHeight(), visible.height());
                        assertEquals(save.getWidth(), visible.width());
                    });
                    capture("portrait-" + tab + "-draft-" + theme);
                }
                action("settings.tab", RhActions.obj("tab", "audio"));
                choose(second, false);
                assertProfile(first, false);
                decision(AlertDialog.BUTTON_NEGATIVE);
                assertProfile(second, false);
                assertFalse(state().getBoolean("settingsDirty"));
                assertEquals(baseline, RhShared.canonical(RhShared.read(context)));
                editVolume(Integer.toString(changedSecond));
                choose(first, false);
                decision(AlertDialog.BUTTON_POSITIVE);
                assertProfile(first, false);
                assertEquals(changedSecond, RhProfile.load(context, second).volume);
                assertEquals(firstVolume, RhProfile.load(context, first).volume);
                capture("portrait-saved-other-device-" + theme);

                String saved = RhShared.canonical(RhShared.read(context));
                editVolume("");
                choose(second, false);
                decision(AlertDialog.BUTTON_POSITIVE);
                assertProfile(first, false);
                assertEquals("", state().getJSONObject("settingsInputs").getString("volume"));
                assertEquals("volume", state().getString("settingsErrorField"));
                assertEquals(saved, RhShared.canonical(RhShared.read(context)));
                orient(true);
                assertProfile(first, true);
                assertEquals("", state().getJSONObject("settingsInputs").getString("volume"));
                capture("wide-invalid-draft-" + theme);
                click("概览");
                decision(AlertDialog.BUTTON_NEGATIVE);
                assertEquals("devices", state().getString("page"));
                assertEquals(first, state().getString("selected"));
                assertEquals(saved, RhShared.canonical(RhShared.read(context)));
                assertFalse(RhActions.hasStream());
                verifyPageReturns(first, true, theme);
                orient(false);
                verifyPageReturns(first, false, theme);
                assertEquals(saved, RhShared.canonical(RhShared.read(context)));
                instrument.runOnMainSync(() -> activity.finish());
                instrument.waitForIdleSync();
            }
            RhShared.write(new File(folder, "result.json"), RhActions.obj("version", com.limelight.BuildConfig.VERSION_NAME,
                    "actualPcView", true, "actualViewControls", true, "realOrientationChanges", orientationChanges,
                    "fontScale", context.getResources().getConfiguration().fontScale,
                    "fontAudit", fontAudit, "textClipping", textClipping,
                    "streamStarted", false, "syncStarted", false, "updateStarted", false,
                    "systemInputInjected", false, "osScreenshots", false, "steps", steps));
            if (fontAudit) assertEquals("Native form text is clipped; inspect result.json", 0, textClipping.length());
        } finally {
            if (activity != null) instrument.runOnMainSync(() -> {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                activity.finish();
            });
            instrument.waitForIdleSync();
            long finishDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (activity != null && !activity.isDestroyed()) {
                assertTrue("Retire the owned Activity before restoring files", System.nanoTime() < finishDeadline);
                Thread.sleep(20);
            }
            RhShared.apply(context, original);
            try (FileOutputStream output = new FileOutputStream(shared)) { output.write(originalBytes); }
            RhShared.theme(context, originalTheme);
            SharedPreferences.Editor restore = global.edit();
            if (hadCheck) restore.putLong("lastUpdateCheck", previousCheck); else restore.remove("lastUpdateCheck");
            restore.commit();
            assertArrayEquals(originalBytes, Files.readAllBytes(shared.toPath()));
        }
    }
}
