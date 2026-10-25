"""Apply RHRemote session lifecycle changes after statistics/tunnel adaptation."""
import argparse
from pathlib import Path

def change(root, relative, replacements):
    path=root / "app/src/main/java" / relative
    text=path.read_text(encoding="utf-8")
    for before, after in replacements:
        if text.count(before)!=1:
            raise RuntimeError(f"Recovery anchor missing or ambiguous in {relative}: {before[:80]}")
        text=text.replace(before,after,1)
    path.write_text(text,encoding="utf-8",newline="\n")

def patch(root):
    change(root,"com/limelight/Game.java",[
        ("    private boolean connecting = false;", """    private boolean connecting = false;
    private com.limelight.rhremote.RhRecovery rhRecovery;
    private final java.util.concurrent.CountDownLatch rhStopped=new java.util.concurrent.CountDownLatch(1);
    private volatile boolean rhCleanupComplete,rhNativeStopped;
    public void rhCancelConnection(){if(rhRecovery!=null)rhRecovery.background();}
    private void rhStopForRetry(java.util.function.Consumer<Boolean> completed){
        runOnUiThread(()->{
            displayedFailureDialog=true;stopConnection();
            new Thread(()->{
                boolean done=false;
                try{done=rhStopped.await(12,java.util.concurrent.TimeUnit.SECONDS)&&rhCleanupComplete;}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
                final boolean cleaned=done;runOnUiThread(()->completed.accept(cleaned));
                if(!done){
                    try{
                        rhStopped.await();
                        if(!rhNativeStopped)return;
                        while(!conn.rhAwaitStopped(1000)){}
                        runOnUiThread(()->completed.accept(true));
                    }catch(InterruptedException e){Thread.currentThread().interrupt();}
                }
            },"RHRemote await cleanup").start();
        });
    }"""),
        ("        rhProfile.applyNetwork(getIntent().getStringExtra(EXTRA_HOST),prefConfig);", """        rhProfile=com.limelight.rhremote.RhRecovery.profile(this,rhProfile);
        rhProfile.applyNetwork(getIntent().getStringExtra(EXTRA_HOST),prefConfig);
        rhRecovery=new com.limelight.rhremote.RhRecovery(this,rhProfile,this::rhStopForRetry);"""),
        ("        StreamConfiguration config = new StreamConfiguration.Builder()", "        decoderRenderer.rhFrameReady=rhRecovery::frame;\n        StreamConfiguration config = new StreamConfiguration.Builder()"),
        ("            attemptedConnection = true;", "            attemptedConnection = true;\n            connecting = true;"),
        ("        super.onStop();", "        super.onStop();\n        if(rhRecovery!=null)rhRecovery.background();"),
        ("                    conn.stop();", """                    try{conn.stop();rhNativeStopped=true;rhCleanupComplete=conn.rhAwaitStopped(10000);}
                    finally{rhStopped.countDown();}"""),
        ("""            }.start();
        }
    }

    @Override
    public void stageFailed""", """            }.start();
        }
        else if(!attemptedConnection){rhNativeStopped=true;rhCleanupComplete=true;rhStopped.countDown();}
    }

    @Override
    public void stageFailed"""),
        ("    public void stageFailed(final String stage, final int portFlags, final int errorCode) {", """    public void stageFailed(final String stage, final int portFlags, final int errorCode) {
        if(rhRecovery!=null){rhRecovery.handleStageFailure(stage,portFlags,errorCode);return;}"""),
        ("    public void connectionTerminated(final int errorCode) {", """    public void connectionTerminated(final int errorCode) {
        if(rhRecovery!=null&&rhRecovery.terminated(errorCode))return;"""),
    ])
    change(root,"com/limelight/binding/video/MediaCodecDecoderRenderer.java",[
        ("public class MediaCodecDecoderRenderer extends VideoDecoderRenderer implements Choreographer.FrameCallback {", "public class MediaCodecDecoderRenderer extends VideoDecoderRenderer implements Choreographer.FrameCallback {\n    public volatile Runnable rhFrameReady;\n    private void rhReportFrame(){Runnable ready=rhFrameReady;if(ready!=null)ready.run();}"),
        ("videoDecoder.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos);", "videoDecoder.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos);rhReportFrame();"),
        ("videoDecoder.releaseOutputBuffer(lastIndex, 0);", "videoDecoder.releaseOutputBuffer(lastIndex, 0);rhReportFrame();"),
        ("videoDecoder.releaseOutputBuffer(lastIndex, System.nanoTime());", "videoDecoder.releaseOutputBuffer(lastIndex, System.nanoTime());rhReportFrame();"),
    ])
    change(root,"com/limelight/nvstream/NvConnection.java",[
        ("    private String uniqueId;", """    private String uniqueId;
    private volatile boolean rhStopped,rhOwnsBridge;
    private boolean rhPermitOwned;
    private volatile NvHTTP rhHttp;
    private volatile Thread rhStarter;
    public boolean rhAwaitStopped(long timeoutMs){
        Thread starter=rhStarter;if(starter==null||starter==Thread.currentThread())return true;
        try{starter.join(timeoutMs);return !starter.isAlive();}
        catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }"""),
        ("""        // Interrupt any pending connection. This is thread-safe.
        MoonBridge.interruptConnection();""", """        rhStopped=true;
        NvHTTP http=rhHttp;if(http!=null)http.cancelRhRequests();
        Thread starter=rhStarter;if(starter!=null&&starter!=Thread.currentThread())starter.interrupt();
        if(rhOwnsBridge)MoonBridge.interruptConnection();"""),
        ("""            MoonBridge.stopConnection();
            MoonBridge.cleanupBridge();""", """            if(rhOwnsBridge){MoonBridge.stopConnection();MoonBridge.cleanupBridge();rhOwnsBridge=false;}
            if(rhPermitOwned){rhPermitOwned=false;connectionAllowed.release();}"""),
        ("""        // Now a pending connection can be processed
        connectionAllowed.release();""", "        // The permit is released only by the instance that acquired it."),
        ("        NvHTTP h = new NvHTTP(context.serverAddress, context.httpsPort, uniqueId, context.serverCert, cryptoProvider);", """        NvHTTP h = new NvHTTP(context.serverAddress, context.httpsPort, uniqueId, context.serverCert, cryptoProvider);
        rhHttp=h;if(rhStopped){h.cancelRhRequests();throw new IOException("Connection cancelled");}"""),
        ("        new Thread(new Runnable() {", "        rhStarter=new Thread(new Runnable() {"),
        ("""                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();""", """                } catch (XmlPullParserException | IOException e) {
                    if(rhStopped)return;
                    e.printStackTrace();"""),
        ("""                } catch (HostHttpResponseException e) {
                    e.printStackTrace();""", """                } catch (HostHttpResponseException e) {
                    if(rhStopped)return;
                    e.printStackTrace();"""),
        ("context.connListener.stageFailed(appName, MoonBridge.ML_PORT_FLAG_TCP_47984 | MoonBridge.ML_PORT_FLAG_TCP_47989, 0);", "context.connListener.stageFailed(appName, com.limelight.rhremote.RhRecoveryPolicy.networkError(e)?MoonBridge.ML_PORT_FLAG_TCP_47984 | MoonBridge.ML_PORT_FLAG_TCP_47989:0, 0);"),
        ("""                } catch (InterruptedException e) {
                    context.connListener.displayMessage""", """                } catch (InterruptedException e) {
                    if(rhStopped)return;
                    context.connListener.displayMessage"""),
        ("""                synchronized (MoonBridge.class) {
                    MoonBridge.setupBridge""", """                synchronized (MoonBridge.class) {
                    if(rhStopped){connectionAllowed.release();return;}
                    rhPermitOwned=true;rhOwnsBridge=true;
                    MoonBridge.setupBridge"""),
        ("""                        connectionAllowed.release();
                        return;""", """                        rhOwnsBridge=false;MoonBridge.cleanupBridge();
                        if(rhPermitOwned){rhPermitOwned=false;connectionAllowed.release();}
                        return;"""),
        ("        }).start();", "        },\"RHRemote connection\");rhStarter.start();"),
    ])

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("source",type=Path)
    patch(parser.parse_args().source)
