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
#elif defined(__linux__)
#include <atomic>
#include <cerrno>
#include <functional>
#include <poll.h>
#include <stdexcept>
#include <sys/eventfd.h>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>
#include <utility>

// pidfds retain process identity across PID reuse. The native engine must be a
// direct child of its Rust shell; diagnostic wrappers must use exec, not fork.
class RhParentLifetime {
    struct Descriptor {
        int value=-1;
        ~Descriptor(){if(value>=0)::close(value);}
    } parent,shutdown;
    std::thread worker;
    std::atomic<bool> stopping{false};
public:
    RhParentLifetime(unsigned pid,std::function<void()> ownerExited){
        if(pid<=1||pid!=static_cast<unsigned>(::getppid())||!ownerExited)throw std::invalid_argument("Invalid owner process");
        parent.value=static_cast<int>(::syscall(SYS_pidfd_open,pid,0));
        if(parent.value<0||pid!=static_cast<unsigned>(::getppid()))throw std::runtime_error("Owner process unavailable");
        shutdown.value=::eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
        if(shutdown.value<0)throw std::runtime_error("Cannot create owner watcher shutdown event");
        worker=std::thread([this,notify=std::move(ownerExited)]{
            pollfd descriptors[]={{parent.value,POLLIN,0},{shutdown.value,POLLIN,0}};
            int result;do{result=::poll(descriptors,2,-1);}while(result<0&&errno==EINTR);
            if(!stopping.load()&&(result<0||(descriptors[0].revents&(POLLIN|POLLERR|POLLHUP|POLLNVAL)))){try{notify();}catch(...){}}
        });
    }
    RhParentLifetime(const RhParentLifetime&)=delete;
    RhParentLifetime& operator=(const RhParentLifetime&)=delete;
    void stop(){if(!stopping.exchange(true)&&shutdown.value>=0){const unsigned long long value=1;const auto result=::write(shutdown.value,&value,sizeof(value));(void)result;}if(worker.joinable())worker.join();}
    ~RhParentLifetime(){stop();}
};
#endif
