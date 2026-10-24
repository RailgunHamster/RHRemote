/* Exercises the actual maintained CML FEC queue and Reed-Solomon implementation.
 * Only logging/control/depacketizer boundaries are stubs. No sockets or UI. */
#include "Limelight-internal.h"
#include "RhFrameClock.h"
#include "rs.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int AppVersionQuad[4] = {7, 1, 500, 0};
STREAM_CONFIGURATION StreamConfig;
CONNECTION_LISTENER_CALLBACKS ListenerCallbacks;
static int submitted, recovered, clockCalls, protocolClockCalls;
static uint64_t expectedTime;
static unsigned short expectedSequence;

static void check(int value, const char* message) {
    if (!value) { fprintf(stderr, "%s\n", message); exit(1); }
}
static void logMessage(const char* format, ...) { (void)format; }
uint64_t PltGetMillis(void) { ++protocolClockCalls; return 100000; }
static uint64_t recorderClock(void) { ++clockCalls; return 1000123; }
void connectionSawFrame(uint32_t frame) { (void)frame; }
void connectionSendFrameFecStatus(PSS_FRAME_FEC_STATUS status) {
    if (BE16(status->receivedDataPackets) < BE16(status->totalDataPackets)) ++recovered;
}
void notifyFrameLost(unsigned int frame, bool speculative) { (void)frame; (void)speculative; }
void queueRtpPacket(PRTPV_QUEUE_ENTRY entry) {
    check(entry->rhReceiveTimeUs == expectedTime, "FEC/reorder lost the first accepted packet timestamp");
    check(entry->receiveTimeMs == 100000, "Protocol milliseconds changed");
    check(entry->packet->sequenceNumber == expectedSequence++, "Reassembled packet order changed");
    const unsigned char* payload = (const unsigned char*)entry->packet + MAX_RTP_HEADER_SIZE + sizeof(NV_VIDEO_PACKET);
    for (unsigned int i = 0; i < StreamConfig.packetSize - sizeof(NV_VIDEO_PACKET); ++i)
        check(payload[i] == 0x30 + (entry->packet->sequenceNumber % 100), "FEC restored different payload bytes");
    ++submitted;
    free(entry->packet);
}

static unsigned char* packet(int index, int data, int fec) {
    const int size = StreamConfig.packetSize + MAX_RTP_HEADER_SIZE;
    unsigned char* bytes = calloc(1, size + sizeof(RTPV_QUEUE_ENTRY));
    check(bytes != NULL, "Allocation failed");
    PRTP_PACKET rtp = (PRTP_PACKET)bytes;
    rtp->header = FLAG_EXTENSION; rtp->sequenceNumber = (uint16_t)(100 + index); rtp->timestamp = 9000;
    PNV_VIDEO_PACKET nv = (PNV_VIDEO_PACKET)(bytes + MAX_RTP_HEADER_SIZE);
    nv->streamPacketIndex = (uint32_t)index << 8; nv->frameIndex = 1;
    nv->flags = FLAG_CONTAINS_PIC_DATA | (index == 0 ? FLAG_SOF : 0) | (index == data - 1 ? FLAG_EOF : 0);
    nv->multiFecFlags = 0x10; nv->fecInfo = (uint32_t)((data << 22) | (index << 12) | (fec << 4));
    memset(nv + 1, 0x30 + index, StreamConfig.packetSize - sizeof(*nv));
    return bytes;
}
static void submit(RTP_VIDEO_QUEUE* queue, unsigned char* bytes, uint64_t received) {
    const int size = StreamConfig.packetSize + MAX_RTP_HEADER_SIZE;
    PRTPV_QUEUE_ENTRY entry = (PRTPV_QUEUE_ENTRY)(bytes + size);
    entry->rhReceiveTimeUs = received;
    int result = RtpvAddPacket(queue, (PRTP_PACKET)bytes, size, entry);
    if (result != RTPF_RET_QUEUED) free(bytes);
    check(result == RTPF_RET_QUEUED, "Fixture packet unexpectedly rejected");
}
static void orderedOrReordered(int reordered, int enabled) {
    RTP_VIDEO_QUEUE queue;
    RtpvInitializeQueue(&queue);
    submitted = 0; expectedSequence = 100; expectedTime = enabled ? 1000123 : 0;
    const int order[3] = {reordered ? 2 : 0, reordered ? 0 : 1, reordered ? 1 : 2};
    for (int i = 0; i < 3; ++i) submit(&queue, packet(order[i], 3, 0), enabled ? 1000123 + i * 137 : 0);
    check(submitted == 3, "Data frame not delivered");
    RtpvCleanupQueue(&queue);
}
static void parityFirstRecovery(void) {
    RTP_VIDEO_QUEUE queue;
    RtpvInitializeQueue(&queue);
    unsigned char* shards[3] = {packet(0, 2, 50), packet(1, 2, 50), packet(2, 2, 50)};
    reed_solomon* rs = reed_solomon_new(2, 1);
    check(rs != NULL, "RS allocation failed");
    check(reed_solomon_encode(rs, shards, 3, StreamConfig.packetSize + MAX_RTP_HEADER_SIZE) == 0, "RS encoding failed");
    reed_solomon_release(rs);
    PRTP_PACKET parity = (PRTP_PACKET)shards[2];
    parity->header = FLAG_EXTENSION; parity->sequenceNumber = 102; parity->timestamp = 9000;
    PNV_VIDEO_PACKET nv = (PNV_VIDEO_PACKET)(shards[2] + MAX_RTP_HEADER_SIZE);
    nv->frameIndex = 1; nv->multiFecBlocks = 0; nv->multiFecFlags = 0x10;
    nv->fecInfo = (2U << 22) | (2U << 12) | (50U << 4);
    free(shards[1]); /* Truly missing data shard, reconstructed by the real queue. */
    submitted = recovered = 0; expectedSequence = 100; expectedTime = 2000456;
    submit(&queue, shards[2], expectedTime);
    submit(&queue, shards[0], expectedTime + 211);
    check(submitted == 2 && recovered == 1, "Actual parity recovery was not exercised");
    RtpvCleanupQueue(&queue);
}
static void consecutiveFrames(void) {
    RTP_VIDEO_QUEUE queue;
    RtpvInitializeQueue(&queue);
    submitted = 0; expectedSequence = 100; expectedTime = 3000789;
    submit(&queue, packet(0, 1, 0), expectedTime);
    unsigned char* next = packet(0, 1, 0);
    ((PRTP_PACKET)next)->sequenceNumber = 200;
    ((PNV_VIDEO_PACKET)(next + MAX_RTP_HEADER_SIZE))->frameIndex = 2;
    expectedSequence = 200; expectedTime = 4000123;
    submit(&queue, next, expectedTime);
    check(submitted == 2, "Next frame inherited stale state or failed to deliver");
    RtpvCleanupQueue(&queue);
}
int main(void) {
    ListenerCallbacks.logMessage = logMessage;
    StreamConfig.packetSize = 128;
    check(LiRhFrameTimeUs() == 0, "Disabled clock invented a timestamp");
    LiRhSetFrameClock(recorderClock);
    check(LiRhFrameTimeUs() == 1000123 && clockCalls == 1, "Callback clock was not used directly");
    LiRhSetFrameClock(NULL);
    check(LiRhFrameTimeUs() == 0 && clockCalls == 1, "Disabled clock still called the recorder");
    orderedOrReordered(0, 1); orderedOrReordered(1, 1); orderedOrReordered(1, 0);
    parityFirstRecovery();
    consecutiveFrames();
    check(protocolClockCalls == 6, "Protocol clock behavior changed");
    puts("PASS: direct/disabled clock, ordered/reordered packets, missing timestamps, parity-first real FEC recovery, frame advancement");
    return 0;
}
