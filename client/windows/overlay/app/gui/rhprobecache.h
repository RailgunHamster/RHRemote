#pragma once
#include <memory>
#include <utility>

// One connection and one test window own this probe. Dimensions, codec, decoder
// policy and window identity are part of Key; no hardware result survives them.
template<class Key,class Decoder>
class RhProbeCache {
    Key key{};
    std::unique_ptr<Decoder> decoder;
public:
    Decoder* get(const Key& requested) const {return key==requested?decoder.get():nullptr;}
    void put(Key requested,std::unique_ptr<Decoder> value){key=std::move(requested);decoder=std::move(value);}
    std::unique_ptr<Decoder> take(const Key& requested){return key==requested?std::move(decoder):nullptr;}
    void clear(){decoder.reset();}
};
