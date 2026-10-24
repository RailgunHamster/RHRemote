"""Record Windows receive/assembly timestamps at their actual CML producer sites.

Only generated native sources are patched. Protocol timers and pacing stay intact.
The opt-in callback uses exactly the recorder's clock; disabled or missing times
remain zero. No clock-domain backprojection occurs on the decode thread.
"""
import argparse
from pathlib import Path


def change(path, replacements):
    text = path.read_text(encoding="utf-8")
    for before, after in replacements:
        if after in text:
            continue
        if text.count(before) != 1:
            raise RuntimeError(f"Frame clock anchor missing or ambiguous: {path}: {before[:80]}")
        text = text.replace(before, after, 1)
    path.write_text(text, encoding="utf-8", newline="\n")


def windows(root):
    repo = Path(__file__).resolve().parent.parent
    common = root / "moonlight-common-c/moonlight-common-c/src"
    overlay = repo / "client/windows/overlay/moonlight-common-c"
    for name in ("RhFrameClock.h", "RhFrameClock.c"):
        (common / name).write_bytes((overlay / name).read_bytes())
    change(root / "moonlight-common-c/moonlight-common-c.pro", [
        ("    $$COMMON_C_DIR/src/Platform.c \\", "    $$COMMON_C_DIR/src/RhFrameClock.c \\\n    $$COMMON_C_DIR/src/Platform.c \\")])
    change(common / "Limelight.h", [
        ("#ifdef __cplusplus\n}\n#endif", '#include "RhFrameClock.h"\n\n#ifdef __cplusplus\n}\n#endif'),
        ("} DECODE_UNIT, *PDECODE_UNIT;", """    // Optional direct recorder-domain times. Zero means disabled or unavailable.
    // Appended locally; all native clients and this static library are rebuilt together.
    uint64_t rhReceiveTimeUs;
    uint64_t rhEnqueueTimeUs;
} DECODE_UNIT, *PDECODE_UNIT;""")])
    change(common / "RtpVideoQueue.h", [
        ("    uint64_t receiveTimeMs;", "    uint64_t receiveTimeMs;\n    uint64_t rhReceiveTimeUs;"),
        ("    uint64_t bufferFirstRecvTimeMs;", "    uint64_t bufferFirstRecvTimeMs;\n    uint64_t rhBufferFirstRecvTimeUs;")])
    change(common / "VideoStream.c", [
        ("                            useSelect);\n        if (err < 0)", "                            useSelect);\n        const uint64_t rhReceiveTimeUs = LiRhFrameTimeUs();\n        if (err < 0)"),
        ("        queueStatus = RtpvAddPacket(&rtpQueue, packet, err, (PRTPV_QUEUE_ENTRY)&buffer[decryptedSize]);", "        ((PRTPV_QUEUE_ENTRY)&buffer[decryptedSize])->rhReceiveTimeUs = rhReceiveTimeUs;\n        queueStatus = RtpvAddPacket(&rtpQueue, packet, err, (PRTPV_QUEUE_ENTRY)&buffer[decryptedSize]);")])
    change(common / "RtpVideoQueue.c", [
        ("                entry->receiveTimeMs = queue->bufferFirstRecvTimeMs;", "                entry->receiveTimeMs = queue->bufferFirstRecvTimeMs;\n                entry->rhReceiveTimeUs = queue->rhBufferFirstRecvTimeUs;"),
        ("        queue->bufferFirstRecvTimeMs = PltGetMillis();", "        queue->bufferFirstRecvTimeMs = PltGetMillis();\n        queue->rhBufferFirstRecvTimeUs = packetEntry->rhReceiveTimeUs;")])
    change(common / "VideoDepacketizer.c", [
        ("static uint64_t firstPacketReceiveTime;", "static uint64_t firstPacketReceiveTime;\nstatic uint64_t rhFirstPacketReceiveTimeUs;"),
        ("    firstPacketReceiveTime = 0;", "    firstPacketReceiveTime = 0;\n    rhFirstPacketReceiveTimeUs = 0;"),
        ("            qdu->decodeUnit.enqueueTimeMs = LiGetMillis();", "            qdu->decodeUnit.enqueueTimeMs = LiGetMillis();\n            qdu->decodeUnit.rhReceiveTimeUs = rhFirstPacketReceiveTimeUs;\n            qdu->decodeUnit.rhEnqueueTimeUs = LiRhFrameTimeUs();"),
        ("                       uint64_t receiveTimeMs, unsigned int presentationTimeMs,", "                       uint64_t receiveTimeMs, uint64_t rhReceiveTimeUs, unsigned int presentationTimeMs,"),
        ("        firstPacketReceiveTime = receiveTimeMs;", "        firstPacketReceiveTime = receiveTimeMs;\n        rhFirstPacketReceiveTimeUs = rhReceiveTimeUs;"),
        ("                      queueEntry.receiveTimeMs,\n                      queueEntry.presentationTimeMs,", "                      queueEntry.receiveTimeMs,\n                      queueEntry.rhReceiveTimeUs,\n                      queueEntry.presentationTimeMs,")])
    change(root / "app/streaming/session.cpp", [
        ('    RhStatistics::event("connection_begin");\n    int err = LiStartConnection(', '''    // Installed before CML creates its connection threads; no live setter races.
    LiRhSetFrameClock(RhStatistics::active() ? +[]() -> uint64_t {
        return RhStatistics::active() ? uint64_t(RhStatistics::now()) : 0;
    } : nullptr);
    RhStatistics::event("frame_clock_contract",-1,{{"contract","rh-cml-frame-clock-v1"},{"clock_domain","rhstatistics"},{"unit","us"},{"receive_site","udp_recv_return"},{"assembly_site","decode_unit_ready"}});
    RhStatistics::event("connection_begin");
    int err = LiStartConnection(''')])
    change(root / "app/streaming/video/ffmpeg.cpp", [
        ('''        const qint64 now=RhStatistics::now();const quint64 common=LiGetMillis();
        RhStatistics::event("packet_first",du->frameNumber,{},now-qint64(common-du->receiveTimeMs)*1000);
        RhStatistics::event("frame_assembled",du->frameNumber,{{"bytes",du->fullLength},{"host_processing_us",du->frameHostProcessingLatency*100},{"keyframe",du->frameType==FRAME_TYPE_IDR}},now-qint64(common-du->enqueueTimeMs)*1000);''', '''        const auto contract=QJsonObject{{"contract","rh-cml-frame-clock-v1"},{"clock_domain","rhstatistics"},{"unit","us"}};
        if(du->rhReceiveTimeUs && du->rhEnqueueTimeUs && du->rhReceiveTimeUs<=du->rhEnqueueTimeUs){
            RhStatistics::event("packet_first",du->frameNumber,contract,qint64(du->rhReceiveTimeUs));
            auto assembled=contract;assembled["bytes"]=du->fullLength;assembled["host_processing_us"]=du->frameHostProcessingLatency*100;assembled["keyframe"]=du->frameType==FRAME_TYPE_IDR;
            RhStatistics::event("frame_assembled",du->frameNumber,assembled,qint64(du->rhEnqueueTimeUs));
        }else{
            RhStatistics::event("frame_clock_missing",du->frameNumber);
        }''')])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    windows(parser.parse_args().source)
