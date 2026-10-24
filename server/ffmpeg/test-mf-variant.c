/* Test the actual patched FFmpeg encoder function against the CodecAPI ABI.
 * Only the destination ICodecAPI is a fake; the encoder source and MF attributes
 * are real. No video, GPU, audio, sockets or production configuration are used. */
#include "mfenc.c"

#include <stdio.h>

static unsigned calls;
static VARTYPE observed_type;
static VARIANT_BOOL observed_value;

static HRESULT STDMETHODCALLTYPE set_value(ICodecAPI *self, const GUID *property, VARIANT *value) {
  (void) self;
  if (IsEqualGUID(property, &ff_CODECAPI_AVLowLatencyMode)) {
    ++calls;
    observed_type = value->vt;
    observed_value = value->boolVal;
  }
  return S_OK;
}

static ICodecAPIVtbl methods = {.SetValue = set_value};

int main(void) {
  HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
  if (FAILED(hr)) {
    return 1;
  }
  hr = MFStartup(MF_VERSION, MFSTARTUP_LITE);
  if (FAILED(hr)) {
    CoUninitialize();
    return 2;
  }
  IMFMediaType *type = NULL;
  hr = MFCreateMediaType(&type);
  if (FAILED(hr)) {
    MFShutdown();
    CoUninitialize();
    return 2;
  }
  ICodecAPI api = {.lpVtbl = &methods};
  MFContext mf = {0};
  mf.codec_api = &api;
  AVCodecContext context = {0};
  context.priv_data = &mf;
  context.codec_id = AV_CODEC_ID_H264;
  context.profile = AV_PROFILE_H264_HIGH;
  context.width = 1920;
  context.height = 1080;
  context.framerate = (AVRational) {30, 1};
  context.time_base = (AVRational) {1, 30};
  context.bit_rate = 5000000;
  context.flags = AV_CODEC_FLAG_LOW_DELAY;
  mf.opt_enc_rc = mf.opt_enc_quality = mf.opt_enc_scenario = -1;
  int result = mf_encv_output_adjust(&context, type);
  int valid = result == 0 && calls == 1 && observed_type == VT_BOOL && observed_value == VARIANT_TRUE;
  context.flags = 0;
  calls = 0;
  result = mf_encv_output_adjust(&context, type);
  valid = valid && result == 0 && calls == 0;
  IMFMediaType_Release(type);
  MFShutdown();
  CoUninitialize();
  if (!valid) {
    fprintf(stderr, "Actual MF encoder emitted the wrong low-latency variant or touched a non-low-delay context\n");
    return 3;
  }
  puts("PASS: actual mf_encv_output_adjust uses canonical VARIANT_BOOL only for low-delay encoding");
  return 0;
}
