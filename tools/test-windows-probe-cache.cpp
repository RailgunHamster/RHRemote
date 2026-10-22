#include "client/windows/overlay/app/gui/rhprobecache.h"
#include <cstdlib>
#include <tuple>

struct Probe {
    static inline int live=0;
    Probe(){++live;}
    ~Probe(){--live;}
};
int main(){
    auto check=[](bool value){if(!value)std::abort();};
    using Key=std::tuple<int,int,int>;
    RhProbeCache<Key,Probe> cache;
    Key h264{1,1920,1080},hevc{2,1920,1080},larger{1,3840,2160};
    check(!cache.get(h264));
    cache.put(h264,std::make_unique<Probe>());
    check(cache.get(h264)&&!cache.get(hevc)&&!cache.get(larger)&&Probe::live==1);
    check(!cache.take(hevc)&&Probe::live==1);
    auto selected=cache.take(h264);
    check(selected&&!cache.get(h264)&&!cache.take(h264)&&Probe::live==1);
    selected.reset();
    check(Probe::live==0);
    cache.put(hevc,std::make_unique<Probe>());
    cache.clear();
    check(!cache.get(hevc)&&Probe::live==0);
    cache.put(h264,std::make_unique<Probe>());
    cache.put(larger,std::make_unique<Probe>());
    check(Probe::live==1&&!cache.get(h264)&&cache.get(larger));
    cache.clear();
    check(Probe::live==0);
}
