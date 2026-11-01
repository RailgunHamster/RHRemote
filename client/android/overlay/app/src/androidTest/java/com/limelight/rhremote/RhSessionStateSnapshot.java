package com.limelight.rhremote;

import android.app.Activity;
import android.content.*;
import androidx.test.runner.lifecycle.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Restores the dedicated emulator after an opt-in real session, including original backup files. */
final class RhSessionStateSnapshot implements AutoCloseable {
    private final RhSessionFlowDeviceTest test;
    private final Context context;
    private final File shared,backups,scratch;
    private final byte[] bytes;
    private final boolean hadBackups;
    private final Map<String,Map<String,?>> preferences=new LinkedHashMap<>();
    RhSessionStateSnapshot(RhSessionFlowDeviceTest test,String run)throws Exception{
        this.test=test;context=test.instrument.getTargetContext();shared=new File(context.getFilesDir(),"rhremote-shared.json");bytes=Files.readAllBytes(shared.toPath());backups=new File(context.getFilesDir(),"sync-backups");hadBackups=backups.exists();scratch=new File(context.getFilesDir(),"ui-test-backups/session-"+run);assertFalse(scratch.exists());assertTrue(scratch.mkdirs());if(hadBackups)Files.move(backups.toPath(),new File(scratch,"original-backups").toPath());
        for(String name:new String[]{"rhremote.global","rhremote.devices","rhremote.statistics"})preferences.put(name,new HashMap<>(context.getSharedPreferences(name,0).getAll()));
        assertTrue(context.getSharedPreferences("rhremote.global",0).edit().putLong("lastUpdateCheck",System.currentTimeMillis()).commit());
    }
    public void close()throws Exception{
        assertFalse("Do not restore configuration while a stream is active",RhActions.hasStream());List<Activity> owned=new ArrayList<>();test.instrument.runOnMainSync(()->{for(Stage stage:Stage.values())if(stage!=Stage.DESTROYED)for(Activity value:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))if(value.getClass().getName().equals("com.limelight.PcView")||value instanceof com.limelight.Game){if(!owned.contains(value))owned.add(value);}for(Activity value:owned)if(!value.isFinishing())value.finish();});
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);for(Activity value:owned)while(!value.isDestroyed()){assertTrue("Owned Activity did not retire",System.nanoTime()<until);Thread.sleep(20);}
        if(backups.exists())Files.move(backups.toPath(),new File(scratch,"test-backups").toPath());if(hadBackups)Files.move(new File(scratch,"original-backups").toPath(),backups.toPath());android.util.AtomicFile file=new android.util.AtomicFile(shared);FileOutputStream out=file.startWrite();try{out.write(bytes);file.finishWrite(out);}catch(Exception error){file.failWrite(out);throw error;}
        for(Map.Entry<String,Map<String,?>> saved:preferences.entrySet()){SharedPreferences p=context.getSharedPreferences(saved.getKey(),0);SharedPreferences.Editor edit=p.edit().clear();for(Map.Entry<String,?> entry:saved.getValue().entrySet()){String key=entry.getKey();Object v=entry.getValue();if(v instanceof Boolean)edit.putBoolean(key,(Boolean)v);else if(v instanceof String)edit.putString(key,(String)v);else if(v instanceof Long)edit.putLong(key,(Long)v);else if(v instanceof Integer)edit.putInt(key,(Integer)v);else if(v instanceof Float)edit.putFloat(key,(Float)v);else if(v instanceof Set){Set<String> copy=new HashSet<>();for(Object item:(Set<?>)v)copy.add((String)item);edit.putStringSet(key,copy);}else throw new AssertionError("Unsupported preference type");}assertTrue(edit.commit());assertEquals(saved.getValue(),p.getAll());}
        assertArrayEquals(bytes,Files.readAllBytes(shared.toPath()));RhShared.write(new File(test.folder,"session-restored.json"),RhActions.obj("sharedBytesRestored",true,"preferencesRestored",true,"originalBackupsRestored",true,"ownedActivitiesRetired",true));
    }
}
