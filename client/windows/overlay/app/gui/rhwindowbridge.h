#pragma once
#include <SDL.h>
#include <SDL_vulkan.h>

// Keep only the SDL loader reference between the hidden probe window and the
// live window. Each window, surface and renderer still has its own lifetime.
class RhWindowBridge {
    bool retained=false;
public:
    RhWindowBridge()=default;
    RhWindowBridge(const RhWindowBridge&)=delete;
    RhWindowBridge& operator=(const RhWindowBridge&)=delete;
    ~RhWindowBridge(){release();}
    void retain(SDL_Window* window){
        if(!retained&&window&&(SDL_GetWindowFlags(window)&SDL_WINDOW_VULKAN))
            retained=SDL_Vulkan_LoadLibrary(nullptr)==0;
    }
    void release(){if(retained){SDL_Vulkan_UnloadLibrary();retained=false;}}
};
