#pragma once
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
