#include "../client/windows/overlay/app/gui/rhrecoverybusy.h"
#include <cassert>
#include <initializer_list>

int main(){
    for(int status:{200,400,401,403,404,409,500,502}){
        assert(!RhRecoveryBusy::retry(status,0,false));
        for(int attempt=1;attempt<=3;++attempt){
            assert(RhRecoveryBusy::retry(status,attempt,false)==(status==409));
            assert(!RhRecoveryBusy::retry(status,attempt,true));
        }
        assert(!RhRecoveryBusy::retry(status,4,false));
    }
}
