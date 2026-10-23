#include "client/windows/overlay/app/gui/rhparentlifetime.h"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <string>

static void require(bool value){if(!value)std::abort();}
static void write(const std::filesystem::path& file,const std::string& text){std::ofstream stream(file);stream<<text;require(stream.good());}
int wmain(int argc,wchar_t** argv){
    if(argc<3)return 1;
    const std::wstring mode=argv[1];
    if(mode==L"hold"){Sleep(INFINITE);return 1;}
    if(mode==L"alive"){
        std::atomic<int> callbacks{0};
        RhParentLifetime watch(std::stoul(argv[2]),[&]{++callbacks;});
        std::this_thread::sleep_for(std::chrono::milliseconds(50));require(callbacks==0);
        watch.stop();watch.stop();require(callbacks==0);
        for(const auto invalid:{DWORD(0),GetCurrentProcessId(),DWORD(0xffffffff)}){
            bool rejected=false;try{RhParentLifetime bad(invalid,[]{});}catch(const std::exception&){rejected=true;}require(rejected);
        }
        wchar_t self[MAX_PATH];require(GetModuleFileNameW(nullptr,self,MAX_PATH)>0);
        std::wstring command=L"\""+std::wstring(self)+L"\" hold unused";
        STARTUPINFOW startup{};startup.cb=sizeof(startup);PROCESS_INFORMATION younger{};
        require(CreateProcessW(self,command.data(),nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,nullptr,&startup,&younger));
        bool rejected=false;try{RhParentLifetime bad(younger.dwProcessId,[]{});}catch(const std::exception&){rejected=true;}
        require(TerminateProcess(younger.hProcess,0));WaitForSingleObject(younger.hProcess,3000);CloseHandle(younger.hThread);CloseHandle(younger.hProcess);
        require(rejected);
        return 0;
    }
    if(mode==L"parent"){
        const std::filesystem::path root=argv[2];
        wchar_t self[MAX_PATH];require(GetModuleFileNameW(nullptr,self,MAX_PATH)>0);
        std::wstring command=L"\""+std::wstring(self)+L"\" watch "+std::to_wstring(GetCurrentProcessId())+L" \""+root.wstring()+L"\"";
        STARTUPINFOW startup{};startup.cb=sizeof(startup);PROCESS_INFORMATION process{};
        require(CreateProcessW(self,command.data(),nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,nullptr,&startup,&process));
        CloseHandle(process.hThread);CloseHandle(process.hProcess);
        Sleep(INFINITE);
        return 1;
    }
    if(mode==L"watch"&&argc==4){
        const std::filesystem::path root=argv[3];std::atomic<bool> notified{false};
        RhParentLifetime watch(std::stoul(argv[2]),[&]{write(root/L"observed.txt","owner-exited");notified=true;});
        write(root/L"watcher.pid",std::to_string(GetCurrentProcessId()));
        const auto until=std::chrono::steady_clock::now()+std::chrono::seconds(8);
        while(!notified){if(std::chrono::steady_clock::now()>until)return 8;std::this_thread::sleep_for(std::chrono::milliseconds(5));}
        watch.stop();return 0;
    }
    return 2;
}
