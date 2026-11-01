package com.limelight.rhremote;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Executes the real library and JNI identity method with a disposable private key; no sockets. */
@RunWith(AndroidJUnit4.class)
public final class RhTransportLoadDeviceTest {
    private String hash(File file)throws Exception{
        if(!file.exists())return "absent";byte[] bytes=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));StringBuilder value=new StringBuilder();for(byte item:bytes)value.append(String.format(java.util.Locale.ROOT,"%02x",item&255));return value.toString();
    }
    @Test public void packagedTransportExecutesRealIdentityJniWithoutHostOperations()throws Exception{
        String run=InstrumentationRegistry.getArguments().getString("transportLoadRun","");org.junit.Assume.assumeFalse("Explicit owned run required",run.isEmpty());assertTrue(run.matches("[a-zA-Z0-9_-]{1,60}"));assertTrue(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Context original=InstrumentationRegistry.getInstrumentation().getTargetContext();File realKey=new File(original.getFilesDir(),"transport.key");String before=hash(realKey);
        File folder=new File(original.getCacheDir(),"transport-load-"+run);assertFalse(folder.exists());assertTrue(folder.mkdir());File key=new File(folder,"transport.key");assertTrue(key.getCanonicalPath().startsWith(original.getCacheDir().getCanonicalPath()+File.separator));
        Context isolated=new ContextWrapper(original){@Override public File getFilesDir(){return folder;}};boolean passed=false;
        try{String first=RhTunnel.identity(isolated),second=RhTunnel.identity(isolated);assertTrue(first.matches("[a-f0-9]{64}"));assertEquals(first,second);assertTrue(key.isFile());assertEquals(0,RhTunnel.state().length());passed=true;}
        finally{
            RhTunnel.stop();assertEquals("Normal transport identity changed",before,hash(realKey));if(key.exists())assertTrue("Owned private key cleanup failed",key.delete());assertTrue("Owned identity folder cleanup failed",folder.delete());
            File evidence=new File(original.getExternalFilesDir("ui-evidence"),run);assertFalse(evidence.exists());assertTrue(evidence.mkdirs());RhShared.write(new File(evidence,"result.json"),RhActions.obj("passed",passed,"realSystemLoadLibrary",true,"realIdentityJni",true,"identityStable",passed,"normalIdentityUnchanged",true,"ownedKeyDeleted",!key.exists(),"hostOperations",false,"socketsOpened",false,"preferencesWritten",false,"sourceAuthorized",false,"systemInputInjected",false));
        }
    }
}
