#pragma once
#ifdef _WIN32
#include <Windows.h>
#include <atomic>
#include <functional>
#include <stdexcept>
#include <thread>
#include <utility>

// Retain the original process handle. A recycled PID cannot extend a stream.
class RhParentLifetime {
    struct Handle {
        HANDLE value=nullptr;
        ~Handle(){if(value)CloseHandle(value);}
        Handle()=default;
        Handle(const Handle&)=delete;
        Handle& operator=(const Handle&)=delete;
    } parent,shutdown;
    std::thread worker;
    std::atomic<bool> stopping{false};
    static unsigned long long creation(HANDLE process){
        FILETIME created{},exited{},kernel{},user{};
        if(!GetProcessTimes(process,&created,&exited,&kernel,&user))throw std::runtime_error("Cannot verify owner process time");
        return (static_cast<unsigned long long>(created.dwHighDateTime)<<32)|created.dwLowDateTime;
    }
public:
    RhParentLifetime(DWORD pid,std::function<void()> ownerExited){
        if(!pid||pid==GetCurrentProcessId()||!ownerExited)throw std::invalid_argument("Invalid owner process");
        parent.value=OpenProcess(SYNCHRONIZE|PROCESS_QUERY_LIMITED_INFORMATION,FALSE,pid);
        if(!parent.value)throw std::runtime_error("Owner process unavailable");
        // A process created after this native engine cannot be its original owner.
        if(creation(parent.value)>creation(GetCurrentProcess()))throw std::runtime_error("Owner process identity changed");
        shutdown.value=CreateEventW(nullptr,TRUE,FALSE,nullptr);
        if(!shutdown.value)throw std::runtime_error("Cannot create owner watcher shutdown event");
        worker=std::thread([this,notify=std::move(ownerExited)]{
            HANDLE handles[]={parent.value,shutdown.value};
            const auto result=WaitForMultipleObjects(2,handles,FALSE,INFINITE);
            if(result!=WAIT_OBJECT_0+1&&!stopping.load()){
                try{notify();}catch(...){}
            }
        });
    }
    RhParentLifetime(const RhParentLifetime&)=delete;
    RhParentLifetime& operator=(const RhParentLifetime&)=delete;
    void stop(){stopping=true;if(shutdown.value)SetEvent(shutdown.value);if(worker.joinable())worker.join();}
    ~RhParentLifetime(){stop();}
};
#endif
