"""Pinned Sunshine diagnostic build; no configuration, identity or protocol changes."""
import argparse
from pathlib import Path
import subprocess

REVISION = "cb72dffa3233c5815cd5ba88f09f049dd679ba75"

def replace_once(text, before, after):
    if text.count(before) != 1:
        raise RuntimeError(f"Unexpected upstream anchor: {before[:90]}")
    return text.replace(before, after, 1)

TRACE = r'''#pragma once
#include "logging.h"
#include <atomic>
#include <chrono>
namespace rhremote {
inline std::atomic<unsigned long long> serial{0};
inline long long tick() {return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();}
class scope {
 const char* name; unsigned long long id; long long start; bool enabled;
public:
 explicit scope(const char* n, bool enable=true):name(n),id(++serial),start(tick()),enabled(enable) {if(enabled)BOOST_LOG(info)<<"RHREMOTE_PHASE begin id="<<id<<" name="<<name<<" tick_us="<<start;}
 ~scope(){if(enabled)BOOST_LOG(info)<<"RHREMOTE_PHASE end id="<<id<<" name="<<name<<" tick_us="<<tick()<<" elapsed_us="<<(tick()-start);}
};
}
'''

def patch_video(s, optimized):
    s='#include "rhremote_trace.h"\n'+s
    for signature, name in [
        ('  int probe_encoders() {','encoder_probe'),
        ('  bool validate_encoder(encoder_t &encoder, bool expect_failure) {','encoder_validate'),
        ('  void reset_display(std::shared_ptr<platf::display_t> &disp, const platf::mem_type_e &type, const std::string &display_name, const config_t &config) {','display_reset'),
        ('  std::unique_ptr<encode_session_t> make_encode_session(platf::display_t *disp, const encoder_t &encoder, const config_t &config, int width, int height, std::unique_ptr<platf::encode_device_t> encode_device) {','encoder_create'),
    ]:
        s=replace_once(s,signature,signature+'\n    rhremote::scope rh_scope("'+name+'");')
    signature='  int encode(int64_t frame_nr, encode_session_t &session, safe::mail_raw_t::queue_t<packet_t> &packets, void *channel_data, std::optional<std::chrono::steady_clock::time_point> frame_timestamp) {'
    s=replace_once(s,signature,signature+'\n    rhremote::scope rh_first_encode("first_encode", frame_nr == 1);')
    if optimized:
        # Failed/disabled codec and format probes must not create a DDA device first.
        block='''        reset_display(disp, encoder.platform_formats->dev_type, output_name, config);
        if (!disp) {
          return;
        }
        if (!flag_map[encoder_t::PASSED]) {
          return;
        }'''
        if s.count(block)!=3: raise RuntimeError('Expected three HDR/chroma probe helpers')
        s=s.replace(block,'''        if (!flag_map[encoder_t::PASSED]) { return; }
        reset_display(disp, encoder.platform_formats->dev_type, output_name, config);
        if (!disp) { return; }''')
        # Explicit software is a deliberate fallback; its capabilities remain valid
        # while the DXGI factory is current. Automatic fallback still re-probes.
        old='''    if (chosen_encoder && !(chosen_encoder->flags & ALWAYS_REPROBE) && !platf::needs_encoder_reenumeration()) {'''
        new='''    const bool topology_changed = platf::needs_encoder_reenumeration();
    const bool explicit_software = chosen_encoder && chosen_encoder->name == "software" && config::video.encoder == "software";
    if (chosen_encoder && (!(chosen_encoder->flags & ALWAYS_REPROBE) || explicit_software) && !topology_changed) {'''
        s=replace_once(s,old,new)
    return s

def prepare(root, optimized):
    actual=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
    if actual!=REVISION: raise RuntimeError('Sunshine revision differs from audited server revision')
    def original(file):return subprocess.check_output(['git','-C',str(root),'show','HEAD:'+file]).decode('utf-8')
    video=patch_video(original('src/video.cpp'),optimized)
    base=original('src/platform/windows/display_base.cpp')
    base='#include "src/rhremote_trace.h"\n'+base
    for signature,name in [
        ('  int duplication_t::init(display_base_t *display, const ::video::config_t &config) {','dda_duplicate_output'),
        ('  int display_base_t::init(const ::video::config_t &config, const std::string &display_name) {','dda_device_init'),
    ]:base=replace_once(base,signature,signature+'\n    rhremote::scope rh_scope("'+name+'");')
    if optimized:base=replace_once(base,'    std::once_flag windows_cpp_once_flag;','    static std::once_flag windows_cpp_once_flag;')
    for file,text in [('src/video.cpp',video),('src/platform/windows/display_base.cpp',base),('src/rhremote_trace.h',TRACE)]:
        (root/file).write_text(text,encoding='utf-8',newline='\n')
    print('Prepared Sunshine '+('optimized' if optimized else 'profile')+' at '+REVISION)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('--optimized',action='store_true');a=p.parse_args();prepare(a.source,a.optimized)
