#include "../client/windows/overlay/app/gui/rhparentlifetime.h"
#include <cassert>
#include <chrono>
#include <csignal>
#include <sys/wait.h>

int main() {
    std::atomic<unsigned> calls{0};
    {
        RhParentLifetime owner(static_cast<unsigned>(getppid()),[&]{++calls;});
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        owner.stop();owner.stop();assert(calls==0);
    }
    for(unsigned pid:{0u,1u,static_cast<unsigned>(getpid())}) {
        bool rejected=false;try{RhParentLifetime invalid(pid,[]{});}catch(const std::exception&){rejected=true;}
        assert(rejected);
    }
    int result[2],ready[2];assert(pipe(result)==0&&pipe(ready)==0);
    const auto owner=fork();assert(owner>=0);
    if(owner==0){
        close(result[0]);close(ready[0]);
        const auto watcher=fork();assert(watcher>=0);
        if(watcher==0){
            std::atomic<bool> exited{false};
            RhParentLifetime watch(static_cast<unsigned>(getppid()),[&]{char value='Y';const auto n=write(result[1],&value,1);(void)n;exited=true;});
            char value='R';assert(write(ready[1],&value,1)==1);
            const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(3);
            while(!exited&&std::chrono::steady_clock::now()<deadline)std::this_thread::sleep_for(std::chrono::milliseconds(5));
            _exit(exited?0:2);
        }
        for(;;)pause();
    }
    close(result[1]);close(ready[1]);char value=0;
    pollfd prepared{ready[0],POLLIN,0};assert(poll(&prepared,1,3000)==1&&read(ready[0],&value,1)==1&&value=='R');
    assert(kill(owner,SIGTERM)==0);int status;assert(waitpid(owner,&status,0)==owner);
    pollfd observed{result[0],POLLIN,0};assert(poll(&observed,1,3000)==1&&read(result[0],&value,1)==1&&value=='Y');
    close(result[0]);close(ready[0]);
    return 0;
}
