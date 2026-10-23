package com.limelight.rhremote;

import android.content.Context;
import com.limelight.nvstream.jni.MoonBridge;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Local test telemetry only: timestamps/IDs/lengths, never pixels, sound or input contents. */
public final class RhStatistics {
    private static volatile Session current;private static Context context;private static String latest="";
    private static volatile long requestedUs;
    public static long originUs(){return requestedUs;}
    public static void initialize(Context c){context=c.getApplicationContext();}
    public static boolean enabled(){return context!=null&&context.getSharedPreferences("rhremote.statistics",0).getBoolean("enabled",false);}
    public static String launchQuery(){Session session=current;return RhHostFrameTrace.query(session!=null&&!session.stopping,new File(latest).getName());}
    public static JSONObject state(){Session s=current;return RhActions.obj("enabled",enabled(),"latest",latest,"recording",s!=null,"lostEvents",s==null?0:s.lost.get(),"format","rhremote.statistics/v1");}
    public static JSONObject configure(boolean enabled){if(current!=null)throw new IllegalStateException("disconnect before changing test mode");context.getSharedPreferences("rhremote.statistics",0).edit().putBoolean("enabled",enabled).apply();return state();}
    public static synchronized void begin(RhProfile profile)throws IOException{beginAt(profile,System.currentTimeMillis()*1000);}
    public static synchronized void beginAt(RhProfile profile,long originUs)throws IOException{
        long now=System.currentTimeMillis()*1000;requestedUs=originUs>0&&originUs<=now?originUs:now;
        if(!enabled())return;if(current!=null)throw new IOException("statistics session already active");
        File root=new File(context.getFilesDir(),"statistics");root.mkdirs();
        File[] old=root.listFiles();if(old!=null)for(File f:old)if(new File(f,"analysis-complete.json").isFile()&&!new File(f,"keep").exists())removeOwned(root,f);
        File dir=new File(root,System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,12));if(!dir.mkdirs())throw new IOException("statistics directory unavailable");
        write(new File(dir,"manifest.json"),RhActions.obj("schema","rhremote.statistics/v1","platform","android","profile",profile.json(),"clock","monotonic_anchored_utc_us","files",new org.json.JSONArray().put("native.jsonl"),"containsPixels",false,"containsAudio",false,"networkTimestampsPrecisionUs",1).toString());
        Session s=new Session(new File(dir,"native.jsonl"));latest=dir.getAbsolutePath();current=s;event("connect_action",-1,new JSONObject(),requestedUs,0);
    }
    private static void removeOwned(File root,File f)throws IOException{
        String base=root.getCanonicalPath()+File.separator;if(!f.getCanonicalPath().startsWith(base))throw new IOException("statistics cleanup escaped root");
        if(f.isDirectory()){File[] files=f.listFiles();if(files!=null)for(File child:files)removeOwned(root,child);}if(!f.delete())throw new IOException("statistics cleanup failed");
    }
    private static void write(File path,String value)throws IOException{try(FileOutputStream out=new FileOutputStream(path)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private static final class Session{
        final long nano=System.nanoTime(),epoch=System.currentTimeMillis()*1000;final AtomicLong lost=new AtomicLong();
        final ArrayBlockingQueue<JSONObject> queue=new ArrayBlockingQueue<>(65536);volatile boolean stopping;final Thread writer;
        final ConcurrentHashMap<Long,Long> pts=new ConcurrentHashMap<>();final ConcurrentHashMap<Integer,Long> buffers=new ConcurrentHashMap<>();
        Session(File file)throws IOException{
            final FileOutputStream stream=new FileOutputStream(file);writer=new Thread(()->{
                boolean capped=false;long written=0;try(BufferedWriter out=new BufferedWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8),65536)){
                    while(!stopping||!queue.isEmpty()){JSONObject row=queue.poll(250,TimeUnit.MILLISECONDS);if(row==null){out.flush();continue;}String line=row.toString();if(written+line.length()>512L*1024*1024){lost.incrementAndGet();capped=true;continue;}out.write(line);out.newLine();written+=line.length()+1;if(queue.isEmpty())out.flush();}
                    out.write(RhActions.obj("event","trace_end","ts_us",now(),"lost_events",lost.get(),"size_limit_reached",capped).toString());out.newLine();
                }catch(Exception e){lost.incrementAndGet();} },"RHRemote statistics writer");writer.start();
        }
        long now(){return epoch+(System.nanoTime()-nano)/1000;}
    }
    public static void event(String name){event(name,-1,new JSONObject(),0,0);}
    public static void event(String name,long frame,JSONObject fields){event(name,frame,fields,0,0);}
    public static void event(String name,long frame,JSONObject fields,long at,long duration){Session s=current;if(s==null||s.stopping)return;JSONObject row=RhActions.obj("event",name,"frame",frame,"thread",Thread.currentThread().getName(),"ts_us",at==0?s.now():at,"fields",fields);if(duration>0)try{row.put("dur_us",duration);}catch(Exception ignored){}if(!s.queue.offer(row))s.lost.incrementAndGet();}
    public static long now(){Session s=current;return s==null?0:s.now();}
    public static void received(int frame,int bytes,char hostLatency,long receive,long enqueue){Session s=current;if(s==null)return;long now=s.now(),common=MoonBridge.rhGetMicroseconds();event("packet_first",frame,new JSONObject(),now-(common-receive),0);event("frame_assembled",frame,RhActions.obj("bytes",bytes,"host_processing_us",(int)hostLatency*100),now-(common-enqueue),0);event("decode_submit",frame,new JSONObject());}
    public static void submitted(long pts,int frame){Session s=current;if(s==null)return;if(s.pts.size()>8192){s.pts.clear();s.lost.incrementAndGet();}s.pts.put(pts,(long)frame);}
    public static void submitReturned(int frame,boolean ok){event("decode_submit_return",frame,RhActions.obj("code",ok?0:-1));}
    public static void decoded(int buffer,long pts){Session s=current;if(s==null)return;Long frame=s.pts.get(pts);if(frame==null){event("unmatched_output",-1,RhActions.obj("pts",pts));return;}s.buffers.put(buffer,frame);event("decode_output",frame,new JSONObject());}
    public static void release(int buffer,boolean render){Session s=current;if(s==null)return;Long frame=s.buffers.remove(buffer);if(frame!=null){event(render?"render_submit":"frame_drop",frame,RhActions.obj("reason",render?"surface_submit":"decoder_backlog"));if(!render)s.pts.values().remove(frame);}}
    public static void rendered(long pts,long nano){Session s=current;if(s==null)return;Long frame=s.pts.remove(pts);if(frame!=null){long reported=s.epoch+(nano-s.nano)/1000;event("surface_render_callback",frame,RhActions.obj("reported_render_us",reported));event("surface_rendered",frame,RhActions.obj("semantics","framework_reported_timestamp_may_precede_submission"),reported,0);}}
    public static synchronized void finish(){Session s=current;if(s==null)return;event("session_end");s.stopping=true;current=null;/* Writer drains asynchronously; exporter waits for trace_end. */}
    public static final class Scope implements AutoCloseable{final String name;final long start;public Scope(String name){this.name=name;start=now();}public void close(){if(start!=0)event(name,-1,new JSONObject(),start,now()-start);}}
}
