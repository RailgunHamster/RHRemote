"""Idempotent instrumentation applied after RHRemote overlays, never to upstream backups."""
import argparse
from pathlib import Path

def edit(root, relative, changes):
    path = root / relative
    text = path.read_text(encoding="utf-8")
    backup=root/".rhremote-statistics-originals"/relative
    if "// RHREMOTE_STATISTICS_PATCH" in text:
        if not backup.exists():return
        text=backup.read_text(encoding="utf-8")
    else:
        backup.parent.mkdir(parents=True,exist_ok=True)
        backup.write_text(text,encoding="utf-8",newline="\n")
    for old, new in changes:
        if old not in text:
            raise RuntimeError(f"statistics patch anchor missing: {relative}: {old[:90]}")
        text = text.replace(old, new)
    path.write_text("// RHREMOTE_STATISTICS_PATCH\n" + text, encoding="utf-8", newline="\n")

def windows(root):
    include = '#include "gui/rhstatistics.h"\n#include "gui/rhcontrol.h"\n'
    edit(root, "app/main.cpp", [("int main(int argc, char *argv[])", "int main(int argc, char *argv[])"), ("    QQuickStyle::setStyle(\"Material\");", '    RhStatistics::event("native_ui_ready");\n    QQuickStyle::setStyle("Material");')])
    # Include before any function definitions (the main signature can vary by compiler).
    for relative in ["app/main.cpp", "app/streaming/session.cpp", "app/backend/nvhttp.cpp", "app/streaming/video/ffmpeg.cpp", "app/streaming/video/ffmpeg-renderers/pacer/pacer.cpp"]:
        p=root/relative
        s=p.read_text(encoding="utf-8")
        if include not in s:p.write_text(include+s,encoding="utf-8",newline="\n")
    edit(root, "app/backend/nvhttp.cpp", [
        ("    int riKeyId;", '    RhStatistics::Scope traceLaunch("host_launch_request");\n    int riKeyId;'),
        ("    QNetworkReply* reply = openConnection(baseUrl, command, arguments, timeoutMs, logLevel);", '    RhStatistics::Scope traceHttp("http_"+command);\n    QNetworkReply* reply = openConnection(baseUrl, command, arguments, timeoutMs, logLevel);')])
    edit(root, "app/streaming/session.cpp", [
        ("bool Session::initialize()\n{", 'bool Session::initialize()\n{\n    RhStatistics::Scope traceInitialize("client_session_initialize");'),
        ("bool Session::validateLaunch(SDL_Window* testWindow)\n{",'bool Session::validateLaunch(SDL_Window* testWindow)\n{\n    RhStatistics::Scope traceValidate("client_validate_launch");'),
        ("void Session::emitLaunchWarning(QString text)\n{", 'void Session::emitLaunchWarning(QString text)\n{\n    RhStatistics::Scope traceWarning("client_warning_display_wait");'),
        ("bool Session::startConnectionAsync()\n{",'bool Session::startConnectionAsync()\n{\n    RhStatistics::event("host_launch_prepare");'),
        ("    Session::clStageStarting,\n    nullptr,", '    Session::clStageStarting,\n    [](int stage){RhStatistics::event("connection_stage_end",-1,{{"stage",stage},{"name",QString::fromLocal8Bit(LiGetStageName(stage))}});},'),
        ("    emit s_ActiveSession->stageStarting", '    RhStatistics::event("connection_stage_begin",-1,{{"stage",stage},{"name",QString::fromLocal8Bit(LiGetStageName(stage))}});\n    emit s_ActiveSession->stageStarting'),
        ("void Session::clStageFailed(int stage, int errorCode)\n{", 'void Session::clStageFailed(int stage, int errorCode)\n{\n    RhStatistics::event("connection_stage_failed",-1,{{"stage",stage},{"code",errorCode}});'),
        ("    int err = LiStartConnection(", '    RhStatistics::event("connection_begin");\n    int err = LiStartConnection('),
        ("    RhRuntime::stream(true);", '    RhStatistics::event("connection_ready");\n    RhRuntime::stream(true);'),
        ("        RhRuntime::stream(false);", '        RhStatistics::event("session_end");\n        RhRuntime::stream(false);')])
    edit(root, "app/streaming/video/ffmpeg.cpp", [
        ("    PLENTRY entry = du->bufferList;", '''    if(RhStatistics::active()){
        const qint64 now=RhStatistics::now();const quint64 common=LiGetMillis();
        RhStatistics::event("packet_first",du->frameNumber,{},now-qint64(common-du->receiveTimeMs)*1000);
        RhStatistics::event("frame_assembled",du->frameNumber,{{"bytes",du->fullLength},{"host_processing_us",du->frameHostProcessingLatency*100},{"keyframe",du->frameType==FRAME_TYPE_IDR}},now-qint64(common-du->enqueueTimeMs)*1000);
        RhStatistics::event("decode_submit",du->frameNumber);
    }
    PLENTRY entry = du->bufferList;'''),
        ("    m_ActiveWndVideoStats.totalReassemblyTime += du->enqueueTimeMs - du->receiveTimeMs;\n\n    err = avcodec_send_packet(m_VideoDecoderCtx, m_Pkt);", '    m_ActiveWndVideoStats.totalReassemblyTime += du->enqueueTimeMs - du->receiveTimeMs;\n\n    err = avcodec_send_packet(m_VideoDecoderCtx, m_Pkt);\n    RhStatistics::event("decode_submit_return",du->frameNumber,{{"code",err}});'),
        ("                        frame->pts = du.presentationTimeMs;", '''                        frame->pts = du.presentationTimeMs;
                        if(RhStatistics::active()){
                            frame->opaque=reinterpret_cast<void*>(quintptr(du.frameNumber));
                            RhStatistics::event("decode_output",du.frameNumber);
                        }''')])
    edit(root, "app/streaming/video/ffmpeg-renderers/pacer/pacer.cpp", [
        ("    m_VsyncRenderer->renderFrame(frame);", '''    const qint64 traceFrame=qint64(quintptr(frame->opaque));
    RhStatistics::event("render_submit",traceFrame);
    m_VsyncRenderer->renderFrame(frame);
    RhStatistics::event("render_return",traceFrame);
    RhRuntime::get().firstFrame=true;'''),
        ("            AVFrame* oldFrame = m_PacingQueue.dequeue();", '            AVFrame* oldFrame = m_PacingQueue.dequeue();\n            RhStatistics::event("frame_drop",qint64(quintptr(oldFrame->opaque)),{{"reason","cadence_backlog"}});'),
        ("\n        m_VideoStats->pacerDroppedFrames++;", '\n        RhStatistics::event("frame_drop",qint64(quintptr(frame->opaque)),{{"reason","pacer_backlog"}});\n        m_VideoStats->pacerDroppedFrames++;')])

    # Existing instrumentation backups can predate runtime state tracking.
    pacer=root/"app/streaming/video/ffmpeg-renderers/pacer/pacer.cpp"
    text=pacer.read_text(encoding="utf-8")
    if '#include "gui/rhcontrol.h"' not in text:text='#include "gui/rhcontrol.h"\n'+text
    if 'RhRuntime::get().firstFrame=true;' not in text:
        anchor='RhStatistics::event("render_return",traceFrame);'
        if anchor not in text:raise RuntimeError("Native first-frame runtime tracking anchor missing")
        text=text.replace(anchor,anchor+'\n    RhRuntime::get().firstFrame=true;')
    pacer.write_text(text,encoding="utf-8",newline="\n")

def android(root):
    prefix="app/src/main/"
    edit(root,prefix+"jni/moonlight-core/simplejni.c",[("#include <Limelight.h>","#include <Limelight.h>\n#include <jni.h>\nJNIEXPORT jlong JNICALL Java_com_limelight_nvstream_jni_MoonBridge_rhGetMicroseconds(JNIEnv *env,jclass clazz){return (jlong)LiGetMicroseconds();}")])
    edit(root,prefix+"java/com/limelight/nvstream/jni/MoonBridge.java",[("    public static native void init();","    public static native long rhGetMicroseconds();\n    public static native void init();")])
    stat="com.limelight.rhremote.RhStatistics"
    edit(root,prefix+"java/com/limelight/nvstream/http/NvHTTP.java",[(
        "private String openHttpConnectionToString(OkHttpClient client, HttpUrl baseUrl, String path, String query) throws IOException {\n        try {",
        f'private String openHttpConnectionToString(OkHttpClient client, HttpUrl baseUrl, String path, String query) throws IOException {{\n        try ({stat}.Scope rhTrace = new {stat}.Scope("http_"+path)) {{')])
    edit(root,prefix+"java/com/limelight/Game.java",[
        ("    public void stageStarting(final String stage) {", f'    public void stageStarting(final String stage) {{\n        {stat}.event("connection_stage_begin",-1,com.limelight.rhremote.RhActions.obj("name",stage));'),
        ("    public void stageComplete(String stage) {", f'    public void stageComplete(String stage) {{\n        {stat}.event("connection_stage_end",-1,com.limelight.rhremote.RhActions.obj("name",stage));'),
        ("    public void connectionStarted() {",f'    public void connectionStarted() {{\n        {stat}.event("connection_ready");'),
        ("    public void stageFailed(final String stage, final int portFlags, final int errorCode) {",f'    public void stageFailed(final String stage, final int portFlags, final int errorCode) {{\n        {stat}.event("connection_stage_failed",-1,com.limelight.rhremote.RhActions.obj("name",stage,"code",errorCode));\n        {stat}.finish();')])
    p=prefix+"java/com/limelight/binding/video/MediaCodecDecoderRenderer.java"
    edits=[
        ("        if (stopping) {\n            // Don't bother if we're stopping",f'        if(decodeUnitType==MoonBridge.BUFFER_TYPE_PICDATA){stat}.received(frameNumber,decodeUnitLength,frameHostProcessingLatency,receiveTimeUs,enqueueTimeUs);\n        if (stopping) {{\n            // Don\'t bother if we\'re stopping'),
        ("        if (!queueNextInputBuffer(timestampUs, codecFlags)) {",f'        {stat}.submitted(timestampUs,frameNumber);\n        boolean rhQueued=queueNextInputBuffer(timestampUs, codecFlags);\n        {stat}.submitReturned(frameNumber,rhQueued);\n        if (!rhQueued) {{'),
        ("                            long presentationTimeUs = info.presentationTimeUs;", f'                            {stat}.decoded(outIndex,info.presentationTimeUs);\n                            long presentationTimeUs = info.presentationTimeUs;'),
        ("                                    numFramesOut++;",f'                                    {stat}.decoded(outIndex,info.presentationTimeUs);\n                                    numFramesOut++;'),
        ("                    long delta = (renderTimeNanos / 1000000L)",f'                    {stat}.rendered(presentationTimeUs,renderTimeNanos);\n                    long delta = (renderTimeNanos / 1000000L)'),
        ("if (USE_FRAME_RENDER_TIME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)",f'if ((USE_FRAME_RENDER_TIME || {stat}.enabled()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)'),
        ("videoDecoder.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos);",f'{stat}.release(nextOutputBuffer,true);\n                    videoDecoder.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos);'),
        ("videoDecoder.releaseOutputBuffer(nextOutputBuffer, false);",f'{stat}.release(nextOutputBuffer,false);\n                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, false);'),
        ("videoDecoder.releaseOutputBuffer(lastIndex, false);",f'{stat}.release(lastIndex,false);\n                                    videoDecoder.releaseOutputBuffer(lastIndex, false);'),
        ("videoDecoder.releaseOutputBuffer(lastIndex, 0);",f'{stat}.release(lastIndex,true);\n                                    videoDecoder.releaseOutputBuffer(lastIndex, 0);'),
        ("videoDecoder.releaseOutputBuffer(lastIndex, System.nanoTime());",f'{stat}.release(lastIndex,true);\n                                    videoDecoder.releaseOutputBuffer(lastIndex, System.nanoTime());'),
        ("videoDecoder.releaseOutputBuffer(outputBufferQueue.take(), false);",f'int rhDropped=outputBufferQueue.take();{stat}.release(rhDropped,false);videoDecoder.releaseOutputBuffer(rhDropped, false);'),
        ("    public void cleanup() {\n        videoDecoder.release();",f'    public void cleanup() {{\n        videoDecoder.release();\n        {stat}.finish();')]
    edit(root,p,edits)

if __name__ == "__main__":
    p=argparse.ArgumentParser();p.add_argument("platform",choices=["windows","android"]);p.add_argument("source",type=Path)
    a=p.parse_args();globals()[a.platform](a.source)
