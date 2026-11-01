package com.limelight.rhremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;
import com.limelight.Game;
import com.limelight.nvstream.jni.MoonBridge;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in emulator-only startup fault. This class is never part of the client APK. */
public final class RhStartupGateInstrumentation extends AndroidJUnitRunner {
    private static final AtomicBoolean injected=new AtomicBoolean();
    private static boolean enabled;
    private static String expectedUuid;
    @Override public void onCreate(Bundle arguments){
        enabled="true".equals(arguments.getString("startupGate","false"));
        if(enabled&&!(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish")))throw new IllegalStateException("Startup fault requires the dedicated emulator");
        expectedUuid=arguments.getString("sessionUuid","C3448560-C539-6209-F595-88C1E8D2769B");injected.set(false);super.onCreate(arguments);
    }
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        if(enabled&&name.equals(Game.class.getName())&&expectedUuid.equals(intent.getStringExtra(Game.EXTRA_PC_UUID)))return new StartupGateGame();
        return super.newActivity(loader,name,intent);
    }
    public static final class StartupGateGame extends Game {
        private boolean gated;
        @Override public void stageStarting(String stage){
            super.stageStarting(stage);
            if(stage.equals("control stream initialization")&&injected.compareAndSet(false,true)){
                gated=true;
                RhStatistics.event("test_startup_claim_interrupt",-1,RhActions.obj("synthetic",true,"stage",stage,"actualNetworkInterrupted",false,"remoteInputSent",false));
                // RTSP has completed and the host has allocated this request,
                // while no control/UDP connection has been established yet.
                MoonBridge.interruptConnection();
            }
        }
        @Override public void stageFailed(String stage,int portFlags,int code){
            // Normalize only our deliberate native interruption into the same
            // network-recovery branch; authorization failures remain fatal.
            if(gated&&code!=401&&code!=403)super.stageFailed("RTSP handshake",portFlags,-1);
            else super.stageFailed(stage,portFlags,code);
        }
    }
}
