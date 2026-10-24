/* Exercise the actual generated encoder and its original retry loops, with SDK
 * boundary fixtures. No GPU, capture, input pixels, audio, files or sockets. */
#include <windows.h>
#define MFXVideoENCODE_EncodeFrameAsync rhremote_test_encode_async
#define MFXVideoCORE_SyncOperation rhremote_test_sync_operation
#define av_usleep rhremote_test_sleep
#include "qsvenc.c"
#include <stdio.h>

static unsigned clock_calls, records, encode_calls, sync_calls, sleep_calls;
static uint32_t apis[32];
static int32_t statuses[32];
static int failed, sdk_failure;
static void *expected_opaque;
static mfxSession expected_session = (mfxSession)(uintptr_t)0x1234;
static mfxSyncPoint expected_sync = (mfxSyncPoint)(uintptr_t)0x5678;
static mfxEncodeCtrl *expected_control;
static mfxFrameSurface1 *expected_surface;
static mfxU32 expected_wait = 1000;
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"QSV call regression line %d\n",__LINE__); failed=1; } } while(0)

mfxStatus MFX_CDECL rhremote_test_encode_async(mfxSession session, mfxEncodeCtrl *control,
        mfxFrameSurface1 *surface, mfxBitstream *bits, mfxSyncPoint *sync) {
    CHECK(session==expected_session && control==expected_control && surface==expected_surface);
    CHECK(bits && bits->Data && bits->MaxLength==128 && sync);
    ++encode_calls;
    if(sdk_failure)return MFX_ERR_DEVICE_FAILED;
    if(encode_calls==1)return MFX_WRN_DEVICE_BUSY;
    if(encode_calls==2)return MFX_WRN_IN_EXECUTION;
    bits->Data[0]=0x6b;bits->DataLength=1;bits->FrameType=MFX_FRAMETYPE_P;
    bits->TimeStamp=0;bits->DecodeTimeStamp=0;*sync=expected_sync;
    return MFX_ERR_NONE;
}
mfxStatus MFX_CDECL rhremote_test_sync_operation(mfxSession session, mfxSyncPoint sync,mfxU32 wait) {
    CHECK(session==expected_session && sync==expected_sync && wait==expected_wait);
    return ++sync_calls==1 ? MFX_WRN_IN_EXECUTION : MFX_ERR_NONE;
}
int rhremote_test_sleep(unsigned usec) {
    CHECK(usec==500 || usec==7);++sleep_calls;
    return usec==7 ? -117 : 0;
}
static int64_t tick(void *opaque) {
    CHECK(opaque==expected_opaque);return 100+ ++clock_calls;
}
static void record(void *opaque,uint32_t api,int64_t begin,int64_t end,int32_t result) {
    CHECK(opaque==expected_opaque && end==begin+1 && records<32);
    if(records<32){apis[records]=api;statuses[records]=result;}
    ++records;
}
static DWORD WINAPI isolated_thread(void *unused) {
    (void)unused;
    rhremote_qsv_sink old=rhremote_qsv_set_sink((rhremote_qsv_sink){0});
    CHECK(old.opaque==NULL && old.tick==NULL && old.record==NULL);
    return 0;
}
static int run_actual_encoder(int enabled,int failure) {
    AVCodecContext avctx={0};QSVEncContext q={0};AVPacket packet={0};int got=0;
    int context;expected_opaque=&context;
    rhremote_qsv_sink old=rhremote_qsv_set_sink(enabled ? (rhremote_qsv_sink){&context,tick,record} : (rhremote_qsv_sink){0});
    clock_calls=records=encode_calls=sync_calls=sleep_calls=0;sdk_failure=failure;
    expected_control=NULL;expected_surface=NULL;expected_wait=1000;
    avctx.codec_id=AV_CODEC_ID_VP9;avctx.time_base=(AVRational){1,30};
    q.session=expected_session;q.packet_size=128;q.async_depth=1;
    q.async_fifo=av_fifo_alloc2(2,sizeof(QSVPacket),0);
    CHECK(q.async_fifo!=NULL);
    int result=ff_qsv_encode(&avctx,&q,&packet,NULL,&got);
    if(failure){
        CHECK(result<0 && got==0 && encode_calls==1 && sync_calls==0 && sleep_calls==0);
        CHECK(records==(enabled ? 1u : 0u));
        if(enabled)CHECK(apis[0]==RHREMOTE_QSV_ENCODE_ASYNC && statuses[0]==MFX_ERR_DEVICE_FAILED);
    }else{
        CHECK(result==0 && got==1 && packet.size==1 && packet.data[0]==0x6b);
        CHECK(encode_calls==3 && sleep_calls==1 && sync_calls==2);
        CHECK(records==(enabled ? 6u : 0u));
        if(enabled){
            const uint32_t expected[]={11,13,11,11,12,12};
            const int32_t results[]={MFX_WRN_DEVICE_BUSY,0,MFX_WRN_IN_EXECUTION,0,MFX_WRN_IN_EXECUTION,0};
            for(unsigned i=0;i<6;i++)CHECK(apis[i]==expected[i] && statuses[i]==results[i]);
        }
    }
    CHECK(clock_calls==records*2);
    av_packet_unref(&packet);av_fifo_freep2(&q.async_fifo);
    rhremote_qsv_set_sink(old);
    return result;
}
int main(void) {
    CHECK(run_actual_encoder(0,0)==run_actual_encoder(1,0));
    CHECK(run_actual_encoder(0,1)==run_actual_encoder(1,1));
    int outer,inner;expected_opaque=&outer;
    rhremote_qsv_sink initial=rhremote_qsv_set_sink((rhremote_qsv_sink){&outer,tick,record});
    rhremote_qsv_sink previous=rhremote_qsv_set_sink((rhremote_qsv_sink){&inner,tick,record});
    CHECK(previous.opaque==&outer);expected_opaque=&inner;
    unsigned before=records;CHECK(rhremote_qsv_busy_sleep(7)==-117 && records==before+1);
    rhremote_qsv_set_sink(previous);expected_opaque=&outer;
    HANDLE thread=CreateThread(NULL,0,isolated_thread,NULL,0,NULL);
    CHECK(thread && WaitForSingleObject(thread,10000)==WAIT_OBJECT_0);if(thread)CloseHandle(thread);
    before=records;CHECK(rhremote_qsv_busy_sleep(7)==-117 && records==before+1);
    mfxEncodeCtrl control={0};mfxFrameSurface1 surface={0};mfxBitstream bits={0};mfxU8 data[128]={0};mfxSyncPoint sync=NULL;
    expected_control=&control;expected_surface=&surface;bits.Data=data;bits.MaxLength=128;sdk_failure=0;encode_calls=2;
    before=records;CHECK(rhremote_qsv_encode_async(expected_session,&control,&surface,&bits,&sync)==0);
    CHECK(records==before+1 && bits.DataLength==1 && data[0]==0x6b && sync==expected_sync);
    expected_wait=17;sync_calls=1;before=records;
    CHECK(rhremote_qsv_sync_operation(expected_session,sync,17)==0 && records==before+1);
    previous=rhremote_qsv_set_sink((rhremote_qsv_sink){&outer,tick,NULL});CHECK(previous.opaque==&outer);
    unsigned clocks=clock_calls;before=records;CHECK(rhremote_qsv_busy_sleep(7)==-117 && records==before && clocks==clock_calls);
    rhremote_qsv_set_sink(initial);
    if(failed)return 1;
    puts("PASS: actual QSV encoder preserves retries, sleep, sync timeout, packet, failures and thread-local opt-in spans");
    return 0;
}
