package com.limelight.rhremote;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in heartbeat and Java frame sampling in the owned test process; no UI input. */
final class RhUiResponsivenessProbe implements AutoCloseable {
    private final File folder;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicLong lastBeat=new AtomicLong(SystemClock.uptimeMillis());
    private final AtomicBoolean pending=new AtomicBoolean();
    private final JSONArray stalls=new JSONArray(),actions=new JSONArray();
    private final long started=SystemClock.uptimeMillis();
    private final Runnable beat=()->{lastBeat.set(SystemClock.uptimeMillis());pending.set(false);};
    private final Thread sampler;
    private final BufferedWriter journal;
    private final Object journalLock=new Object();
    private volatile String journalFailure="";
    private final boolean captureStacks;
    private volatile boolean active=true;

    RhUiResponsivenessProbe(File folder)throws IOException{
        this.folder=folder;
        captureStacks=!"false".equals(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("sessionUiProbeStacks","true"));
        File file=new File(folder,"session-ui-responsiveness.jsonl");if(file.exists())throw new IOException("UI journal already exists");
        journal=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8));
        append(RhActions.obj("event","begin","pid",android.os.Process.myPid(),"wallTimeMs",System.currentTimeMillis(),"uptimeMs",started,"stackSampling",captureStacks,"argumentsRecorded",false));
        sampler=new Thread(()->{
            while(active&&SystemClock.uptimeMillis()-started<240000){
                long now=SystemClock.uptimeMillis(),delay=now-lastBeat.get();
                append(RhActions.obj("event","tick","elapsedMs",now-started,"heartbeatDelayMs",delay));
                if(captureStacks&&delay>750&&stalls.length()<960){
                    append(RhActions.obj("event","stack-requested","elapsedMs",now-started,"heartbeatDelayMs",delay));
                    Thread target=Looper.getMainLooper().getThread();JSONArray frames=new JSONArray();
                    StackTraceElement[] stack=target.getStackTrace();for(int i=0;i<Math.min(stack.length,30);i++)frames.put(stack[i].toString());
                    JSONObject sample=RhActions.obj("elapsedMs",now-started,"heartbeatDelayMs",delay,"captureDurationMs",SystemClock.uptimeMillis()-now,"threadState",target.getState().name(),"frames",frames);stalls.put(sample);append(RhActions.obj("event","stall","sample",sample));
                }
                if(pending.compareAndSet(false,true))main.post(beat);
                try{Thread.sleep(250);}catch(InterruptedException done){break;}
            }
        },"RHRemote owned UI sampler");
        sampler.start();
    }

    private void append(JSONObject value){synchronized(journalLock){try{journal.write(value.toString());journal.newLine();journal.flush();}catch(IOException failure){journalFailure=failure.toString();}}}
    synchronized void action(String id,long elapsedMillis){if(actions.length()<500){JSONObject sample=RhActions.obj("action",id,"durationMs",elapsedMillis,"atMs",SystemClock.uptimeMillis()-started);actions.put(sample);append(RhActions.obj("event","action","sample",sample));}}

    @Override public void close()throws Exception{
        active=false;sampler.interrupt();sampler.join(2000);main.removeCallbacks(beat);
        boolean stopped=!sampler.isAlive();append(RhActions.obj("event","end","elapsedMs",SystemClock.uptimeMillis()-started,"samplerStopped",stopped));synchronized(journalLock){journal.close();}
        RhShared.write(new File(folder,"session-ui-responsiveness.json"),RhActions.obj("durationMs",SystemClock.uptimeMillis()-started,"sampleIntervalMs",250,"stallThresholdMs",750,"stalls",stalls,"actions",actions,"systemInputInjected",false,"argumentsRecorded",false,"javaFramesOnly",true,"stackSampling",captureStacks,"journalFailure",journalFailure,"samplerStopped",stopped));
        if(!stopped)throw new AssertionError("Owned UI sampler did not stop");if(!journalFailure.isEmpty())throw new IOException(journalFailure);
    }
}
