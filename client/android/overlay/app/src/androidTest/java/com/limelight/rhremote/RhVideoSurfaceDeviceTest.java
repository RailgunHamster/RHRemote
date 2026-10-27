package com.limelight.rhremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Generated grey frames only. Separates encoded content, decoder output and this app's SurfaceView. */
@RunWith(AndroidJUnit4.class)
public class RhVideoSurfaceDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    Activity activity;FrameLayout root;File folder;
    static final int WIDTH=320,HEIGHT=180;
    static class Frame {byte[] data;long pts;int flags;Frame(byte[] bytes,long time,int bits){data=bytes;pts=time;flags=bits;}}
    static class Clip {MediaFormat format;List<Frame> frames=new ArrayList<>();}

    SurfaceView surface()throws Exception {
        SurfaceView[] view={null};CountDownLatch ready=new CountDownLatch(1);
        instrument.runOnMainSync(()->{root.removeAllViews();view[0]=new SurfaceView(activity);view[0].getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder holder){}public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){if(width>0&&height>0)ready.countDown();}public void surfaceDestroyed(SurfaceHolder holder){}});root.addView(view[0],new FrameLayout.LayoutParams(-1,-1));});
        assertTrue("Own SurfaceView was not created",ready.await(5,TimeUnit.SECONDS));return view[0];
    }
    Clip encode()throws Exception {
        MediaCodec encoder=MediaCodec.createByCodecName("c2.android.avc.encoder");
        try{MediaFormat format=MediaFormat.createVideoFormat("video/avc",WIDTH,HEIGHT);format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);format.setInteger(MediaFormat.KEY_BIT_RATE,300000);format.setInteger(MediaFormat.KEY_FRAME_RATE,30);format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();
            byte[] pixels=new byte[WIDTH*HEIGHT*3/2];Arrays.fill(pixels,0,WIDTH*HEIGHT,(byte)200);Arrays.fill(pixels,WIDTH*HEIGHT,pixels.length,(byte)128);
            Clip clip=new Clip();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int input=0;boolean done=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!done&&System.nanoTime()<deadline){if(input<3){int index=encoder.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=encoder.getInputBuffer(index);buffer.clear();buffer.put(pixels);encoder.queueInputBuffer(index,0,pixels.length,input*33333L,input==2?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);input++;}}
                int output=encoder.dequeueOutputBuffer(info,10000);if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)clip.format=encoder.getOutputFormat();else if(output>=0){if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){ByteBuffer bytes=encoder.getOutputBuffer(output).duplicate();bytes.position(info.offset);bytes.limit(info.offset+info.size);byte[] data=new byte[info.size];bytes.get(data);clip.frames.add(new Frame(data,info.presentationTimeUs,info.flags&~MediaCodec.BUFFER_FLAG_END_OF_STREAM));}done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;encoder.releaseOutputBuffer(output,false);}}
            assertTrue("Generated encoder did not finish",done);assertNotNull(clip.format);assertEquals(3,clip.frames.size());return clip;
        }finally{try{encoder.stop();}finally{encoder.release();}}
    }
    JSONObject decode(Clip clip,Surface target,AtomicInteger rendered,MediaCodec[] keep)throws Exception {
        MediaCodec decoder=MediaCodec.createByCodecName("c2.android.avc.decoder");keep[0]=decoder;if(target!=null)decoder.setOnFrameRenderedListener((codec,pts,nano)->rendered.incrementAndGet(),new Handler(Looper.getMainLooper()));decoder.configure(clip.format,target,null,0);decoder.start();
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int input=0,outputs=0;boolean inputEnded=false,done=false;long lumaTotal=0,lumaSamples=0,deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!done&&System.nanoTime()<deadline){if(!inputEnded){int index=decoder.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=decoder.getInputBuffer(index);buffer.clear();if(input<clip.frames.size()){Frame frame=clip.frames.get(input++);buffer.put(frame.data);decoder.queueInputBuffer(index,0,frame.data.length,frame.pts,frame.flags);}else{decoder.queueInputBuffer(index,0,0,100000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true;}}}
            int index=decoder.dequeueOutputBuffer(info,10000);if(index>=0){boolean hasFrame=info.size>0||(target!=null&&(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)==0);if(hasFrame){outputs++;if(target==null){try(Image image=decoder.getOutputImage(index)){assertNotNull("Decoded Y plane unavailable",image);Image.Plane y=image.getPlanes()[0];Rect crop=image.getCropRect();ByteBuffer values=y.getBuffer();for(int row=crop.top;row<crop.bottom;row+=8)for(int col=crop.left;col<crop.right;col+=8){lumaTotal+=values.get(row*y.getRowStride()+col*y.getPixelStride())&255;lumaSamples++;}}}}done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;decoder.releaseOutputBuffer(index,target!=null&&hasFrame);}}
        assertTrue("Generated decoder did not finish",done);return RhActions.obj("outputFrames",outputs,"lumaSamples",lumaSamples,"meanY",lumaSamples==0?0:(double)lumaTotal/lumaSamples);
    }
    JSONObject copy(SurfaceView surface,String name)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(surface.getWidth(),surface.getHeight(),Bitmap.Config.ARGB_8888);int[] status={-1};int nonDark=0,samples=0,attempts=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        try{do{CountDownLatch copied=new CountDownLatch(1);instrument.runOnMainSync(()->PixelCopy.request(surface,bitmap,result->{status[0]=result;copied.countDown();},new Handler(Looper.getMainLooper())));assertTrue(copied.await(2,TimeUnit.SECONDS));nonDark=samples=0;attempts++;if(status[0]==PixelCopy.SUCCESS)for(int y=0;y<bitmap.getHeight();y+=8)for(int x=0;x<bitmap.getWidth();x+=8){int pixel=bitmap.getPixel(x,y);samples++;if(Color.red(pixel)>24||Color.green(pixel)>24||Color.blue(pixel)>24)nonDark++;}if(nonDark>samples/100&&samples>0)break;Thread.sleep(50);}while(System.nanoTime()<deadline);
            if(status[0]==PixelCopy.SUCCESS)try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}return RhActions.obj("result",status[0],"samples",samples,"nonDark",nonDark,"attempts",attempts,"visibleContent",samples>0&&nonDark>samples/100);
        }finally{bitmap.recycle();}
    }
    void release(MediaCodec[] codec){if(codec[0]!=null)try{codec[0].stop();}finally{codec[0].release();codec[0]=null;}}
    @Test public void generatedContentSeparatesDecoderPixelsFromSurfacePixels()throws Exception {
        Context context=instrument.getTargetContext();assertTrue("Dedicated headless emulator required",Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));assertFalse(RhActions.hasStream());String preferences=RhShared.canonical(RhShared.read(context));String run=InstrumentationRegistry.getArguments().getString("surfaceRun","");assertTrue(run.matches("[a-zA-Z0-9_-]{1,80}"));folder=new File(context.getExternalFilesDir("ui-evidence"),"video-surface-"+run);assertFalse(folder.exists());assertTrue(folder.mkdirs());JSONObject report=RhActions.obj("generatedContentOnly",true,"systemInputInjected",false,"osScreenshots",false,"encoder","c2.android.avc.encoder","decoder","c2.android.avc.decoder");MediaCodec[] codec={null};
        try{activity=instrument.startActivitySync(new Intent().setClassName(context,"com.limelight.PcView").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrument.runOnMainSync(()->{root=new FrameLayout(activity);activity.setContentView(root);});SurfaceView canvasSurface=surface();Canvas canvas=canvasSurface.getHolder().lockCanvas();try{canvas.drawColor(Color.LTGRAY);}finally{canvasSurface.getHolder().unlockCanvasAndPost(canvas);}JSONObject canvasPixels=copy(canvasSurface,"canvas-generated");report.put("canvasPixels",canvasPixels);RhShared.write(new File(folder,"video-surface-probe.json"),report);assertTrue("Own Canvas surface is black",canvasPixels.optBoolean("visibleContent"));
            Clip clip=encode();report.put("encodedFrames",clip.frames.size());JSONObject buffer=decode(clip,null,new AtomicInteger(),codec);report.put("bufferDecoder",buffer);release(codec);RhShared.write(new File(folder,"video-surface-probe.json"),report);assertTrue("Generated decoded content is dark",buffer.getDouble("meanY")>150);
            SurfaceView video=surface();AtomicInteger rendered=new AtomicInteger();JSONObject output=decode(clip,video.getHolder().getSurface(),rendered,codec);JSONObject pixels=copy(video,"video-generated");report.put("surfaceDecoder",output).put("surfaceCallbacks",rendered.get()).put("videoPixels",pixels);RhShared.write(new File(folder,"video-surface-probe.json"),report);assertTrue("Generated video surface is black",pixels.optBoolean("visibleContent"));assertTrue(rendered.get()>0);
        }finally{try{release(codec);}finally{if(activity!=null)instrument.runOnMainSync(activity::finish);assertEquals(preferences,RhShared.canonical(RhShared.read(context)));report.put("preferencesUnchanged",true);RhShared.write(new File(folder,"video-surface-probe.json"),report);}}
    }
}
