package com.limelight.rhremote;

import java.io.File;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Real IME visibility in the owned Game window; does not type into the host. */
final class RhSessionImeProbe {
    private RhSessionImeProbe() {}
    private static JSONObject describe(RhSessionFlowDeviceTest test){
        JSONObject[] result={null};test.instrument.runOnMainSync(()->{
            android.view.View focus=test.game.getCurrentFocus(),surface=test.game.findViewById(com.limelight.R.id.surfaceView);
            android.view.inputmethod.InputMethodManager imm=(android.view.inputmethod.InputMethodManager)test.game.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            android.content.res.Configuration config=test.game.getResources().getConfiguration();
            result[0]=RhActions.obj("focusClass",focus==null?"":focus.getClass().getName(),"streamHasFocus",surface.hasFocus(),"streamTextEditor",surface.onCheckIsTextEditor(),"windowFocus",test.game.hasWindowFocus(),"immActive",imm.isActive(),"streamImmActive",imm.isActive(surface),"acceptingText",imm.isAcceptingText(),"keyboardConfiguration",config.keyboard,"hardKeyboardHidden",config.hardKeyboardHidden,"showImeWithHardware",android.provider.Settings.Secure.getInt(test.game.getContentResolver(),"show_ime_with_hard_keyboard",-1));
        });return result[0];
    }
    static void run(RhSessionFlowDeviceTest test)throws Exception {
        assertTrue("Dedicated emulator required",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        assertFalse("Begin with the keyboard closed",test.stream().getBoolean("keyboard"));
        JSONObject before=test.stream();
        try{
            RhStatistics.event("ui_ime_test_requested",-1,RhActions.obj());test.click("键盘");
            try{test.waitFor(()->test.stream().optBoolean("keyboard")&&!test.stream().optBoolean("keyboardPending"),6,"Actual IME visibility after keyboard button");}
            catch(AssertionError missing){
                RhShared.write(new File(test.folder,"session-ime-button-failure.json"),describe(test));
                test.instrument.runOnMainSync(()->{android.view.View surface=test.game.findViewById(com.limelight.R.id.surfaceView);surface.requestFocusFromTouch();androidx.core.view.WindowCompat.getInsetsController(test.game.getWindow(),surface).show(androidx.core.view.WindowInsetsCompat.Type.ime());});
                boolean referenceVisible=false;try{test.waitFor(()->test.stream().optBoolean("keyboard"),5,"Diagnostic explicit IME request");referenceVisible=true;}catch(AssertionError ignored){}
                RhShared.write(new File(test.folder,"session-ime-reference.json"),RhActions.obj("referenceVisible",referenceVisible,"diagnostics",describe(test),"productionButtonPassed",false));throw missing;
            }
            assertFalse(test.stream().getBoolean("controls"));assertTrue(test.stream().getBoolean("connected"));
            JSONObject open=test.stream();RhShared.write(new File(test.folder,"session-ime-open.json"),open);test.capture("session-ime-open");
            RhStatistics.event("ui_ime_test_visible",-1,RhActions.obj());
            test.instrument.runOnMainSync(test.game::onBackPressed);
            test.waitFor(()->!test.stream().optBoolean("keyboard")&&!test.stream().optBoolean("keyboardPending"),6,"IME hidden by local Back");
            assertTrue(test.stream().getBoolean("connected"));assertFalse(test.stream().getBoolean("controls"));assertFalse(test.stream().getBoolean("exitPrompt"));
            test.click("控制");assertTrue(test.stream().getBoolean("controls"));
            assertEquals(before.getJSONObject("controlsLayout").getJSONObject("handle").toString(),test.stream().getJSONObject("controlsLayout").getJSONObject("handle").toString());
            RhShared.write(new File(test.folder,"session-ime-before-second-open.json"),test.stream());
            test.click("键盘");RhShared.write(new File(test.folder,"session-ime-second-request.json"),test.stream());test.waitFor(()->test.stream().optBoolean("keyboard")&&!test.stream().optBoolean("keyboardPending"),6,"Second keyboard open");
            test.instrument.runOnMainSync(()->((com.limelight.Game)test.game).toggleKeyboard());
            test.waitFor(()->!test.stream().optBoolean("keyboard")&&!test.stream().optBoolean("keyboardPending"),6,"Game gesture uses the same keyboard hide operation");
            test.click("控制");test.click("键盘");test.instrument.runOnMainSync(test.game::onBackPressed);
            test.waitFor(()->!test.stream().optBoolean("keyboard")&&!test.stream().optBoolean("keyboardPending"),6,"Back cancels the pending keyboard request");
            Thread.sleep(600);assertFalse("Late keyboard request reopened after Back",test.stream().getBoolean("keyboard"));assertTrue(test.stream().getBoolean("connected"));assertFalse(test.stream().getBoolean("exitPrompt"));
            test.click("控制");
            RhShared.write(new File(test.folder,"session-ime-flow.json"),RhActions.obj("actualKeyboardVisibility",true,"panelCollapsed",true,"appBackHidKeyboard",true,"gameGestureHidKeyboard",true,"pendingBackCancelled",true,"connectionPreserved",true,"handleUnchanged",true,"physicalKeyboardTested",false,"hostTextInputTested",false));
        }finally{
            RhShared.write(new File(test.folder,"session-ime-final-state.json"),test.stream());
            if(RhActions.hasStream()&&(test.stream().optBoolean("keyboard")||test.stream().optBoolean("keyboardPending")))test.instrument.runOnMainSync(test.game::onBackPressed);
        }
    }
}
