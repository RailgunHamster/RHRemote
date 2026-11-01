package com.limelight.rhremote;

import android.os.Looper;
import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.EditText;
import com.limelight.nvstream.http.ComputerDetails;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Completion follows the current editor, including real TextView Enter down/up dispatch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w1200dp-h800dp-mdpi",shadows=RhAdmissionTest.Controls.class)
public final class RhEditorSubmitTest {
    private final RhFormFlowTest fixture=new RhFormFlowTest();
    @Before public void setup(){fixture.setup();fixture.home.resumed();}
    @After public void cleanup(){fixture.dispose();}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private InputConnection connection(EditText editor){fixture.layout(1200,800);assertTrue(editor.requestFocusFromTouch());InputConnection result=editor.onCreateInputConnection(new EditorInfo());assertNotNull(result);return result;}
    private EditText add()throws Exception{fixture.home.perform("device.add.open",RhActions.obj());EditText editor=fixture.edit("设备地址");editor.setText("invalid address !");connection(editor);return editor;}
    private EditText info(String uuid,String name)throws Exception{fixture.home.perform("device.info.open",RhActions.obj("uuid",uuid));EditText editor=fixture.edit("设备名称");editor.setText(name);connection(editor);return editor;}
    private String saved()throws Exception{return RhShared.canonical(RhShared.read(fixture.activity));}
    private String addError()throws Exception{return fixture.home.state().getJSONObject("add").getString("error");}
    private void enter(EditText editor,int action,int flags,int repeat){long time=1000;editor.dispatchKeyEvent(new KeyEvent(time,time+repeat,action,KeyEvent.KEYCODE_ENTER,repeat,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,flags,InputDevice.SOURCE_KEYBOARD));idle();}
    @Test public void aRetiredAddConnectionCannotSubmitTheNewAddForm()throws Exception{
        InputConnection retired=connection(add());fixture.home.perform("device.add.cancel",RhActions.obj());EditText current=add();String before=saved();retired.performEditorAction(EditorInfo.IME_ACTION_DONE);idle();assertEquals("An old editor submitted the replacement form","",addError());assertEquals(before,saved());connection(current).performEditorAction(EditorInfo.IME_ACTION_DONE);idle();assertFalse(addError().isEmpty());
    }
    @Test public void aRetiredNameConnectionCannotSaveAnotherDevicesDraft()throws Exception{
        InputConnection retired=connection(info("host","Discarded"));fixture.home.perform("device.info.cancel",RhActions.obj());ComputerDetails other=new ComputerDetails();other.uuid="editor-other119";other.name="Other";other.manualAddress=new ComputerDetails.AddressTuple("192.0.2.119",47989);fixture.home.update(other);EditText current=info(other.uuid,"当前草稿");String before=saved();retired.performEditorAction(EditorInfo.IME_ACTION_DONE);idle();assertEquals("device-info",fixture.home.state().getString("page"));assertEquals(before,saved());assertEquals("当前草稿",fixture.home.state().getJSONObject("infoDraft").getString("displayName"));connection(current).performEditorAction(EditorInfo.IME_ACTION_DONE);idle();assertEquals("当前草稿",RhShared.alias(fixture.activity,other.uuid));
    }
    @Test public void onlyTheConfiguredSoftDoneActionSubmits()throws Exception{
        EditText editor=add();for(int action:new int[]{EditorInfo.IME_ACTION_NEXT,EditorInfo.IME_ACTION_PREVIOUS,EditorInfo.IME_ACTION_SEARCH,EditorInfo.IME_ACTION_GO,EditorInfo.IME_ACTION_SEND,EditorInfo.IME_NULL}){connection(editor).performEditorAction(action);idle();assertEquals("Unrelated editor action submitted: "+action,"",addError());}connection(editor).performEditorAction(EditorInfo.IME_ACTION_DONE);idle();assertFalse(addError().isEmpty());
    }
    @Test public void disabledEditorsCannotSubmitEvenThroughTheirInputConnection()throws Exception{
        EditText editor=add();InputConnection current=connection(editor);editor.setEnabled(false);current.performEditorAction(EditorInfo.IME_ACTION_DONE);idle();assertEquals("",addError());fixture.home.perform("device.add.cancel",RhActions.obj());EditText alias=info("host","Disabled draft");InputConnection name=connection(alias);String before=saved();alias.setEnabled(false);name.performEditorAction(EditorInfo.IME_ACTION_DONE);idle();assertEquals(before,saved());assertEquals("device-info",fixture.home.state().getString("page"));
    }
    @Test public void currentSoftDoneSavesUnicodeWithoutChangingSelectionOrSearch()throws Exception{
        fixture.home.perform("devices.filter",RhActions.obj("query","win"));EditText editor=info("host","研发😀 设备");InputConnection current=connection(editor);current.performEditorAction(EditorInfo.IME_ACTION_DONE);idle();assertEquals("研发😀 设备",RhShared.alias(fixture.activity,"host"));assertEquals("devices",fixture.home.state().getString("page"));assertEquals("host",fixture.home.state().getString("selected"));assertEquals("win",fixture.home.state().getString("deviceQuery"));
    }
    @Test public void enterSubmitsOnlyOnTheMatchingRelease()throws Exception{
        EditText editor=add();enter(editor,KeyEvent.ACTION_DOWN,0,0);assertEquals("Enter down must not submit","",addError());enter(editor,KeyEvent.ACTION_DOWN,0,1);assertEquals("",addError());enter(editor,KeyEvent.ACTION_UP,0,0);assertFalse(addError().isEmpty());editor.setText("another invalid address !");assertEquals("",addError());enter(editor,KeyEvent.ACTION_UP,0,0);assertEquals("An orphan release resubmitted","",addError());
    }
    @Test public void canceledEnterKeepsTheNameDraftUnsaved()throws Exception{
        EditText editor=info("host","Unsaved Enter draft");String before=saved();enter(editor,KeyEvent.ACTION_UP,0,0);assertEquals(before,saved());enter(editor,KeyEvent.ACTION_DOWN,0,0);assertEquals("device-info",fixture.home.state().getString("page"));enter(editor,KeyEvent.ACTION_UP,KeyEvent.FLAG_CANCELED,0);assertEquals(before,saved());assertEquals("device-info",fixture.home.state().getString("page"));
    }
    @Test public void pausingClearsAnEnterPressBeforeReturningToTheSameEditor()throws Exception{
        EditText editor=info("host","Pause draft");String before=saved();enter(editor,KeyEvent.ACTION_DOWN,0,0);fixture.home.paused();fixture.home.resumed();enter(editor,KeyEvent.ACTION_UP,0,0);assertEquals("device-info",fixture.home.state().getString("page"));assertEquals(before,saved());enter(editor,KeyEvent.ACTION_DOWN,0,0);enter(editor,KeyEvent.ACTION_UP,0,0);assertEquals("Pause draft",RhShared.alias(fixture.activity,"host"));
    }
}
