package com.limelight.rhremote;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.widget.Button;
import java.io.File;
import java.lang.reflect.Field;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Observe the actual first-frame recovery dialog without cancelling or injecting input. */
final class RhStartupProgressProbe {
    static void sample(RhSessionFlowDeviceTest test)throws Exception{
        File output=new File(test.folder,"session-startup-recovery-ui.json");if(output.exists())return;
        AlertDialog[] dialog={null};JSONObject[] result={null};
        test.instrument.runOnMainSync(()->{
            RhRecovery owner=RhActions.recovery();if(owner==null)return;
            try{
                Field field=RhRecovery.class.getDeclaredField("feedbackDialog");field.setAccessible(true);AlertDialog current=(AlertDialog)field.get(owner);
                if(current==null||!current.isShowing())return;Button cancel=current.getButton(DialogInterface.BUTTON_NEGATIVE);Rect area=new Rect();
                if(cancel==null||cancel.getWidth()==0||cancel.getHeight()==0||!cancel.getGlobalVisibleRect(area))return;
                assertTrue("Cancel must remain available during first-frame cleanup",cancel.isEnabled());assertEquals(cancel.getWidth(),area.width());assertEquals(cancel.getHeight(),area.height());
                dialog[0]=current;result[0]=RhActions.obj("observed",true,"cancelVisible",true,"cancelEnabled",true,"cancelBounds",area.toShortString(),"state",owner.state(),"systemInputInjected",false,"remoteInputSent",false);
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}
        });
        if(result[0]!=null){test.captureRecovery(dialog[0],"session-startup-recovery-ui");RhShared.write(output,result[0]);}
    }
}
