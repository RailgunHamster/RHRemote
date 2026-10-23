#include "client/windows/overlay/app/gui/rhdisplaylaunch.h"
#include <cstdlib>
#include <QCoreApplication>

static void require(bool condition){if(!condition)std::abort();}
int main(int argc,char** argv){
    QCoreApplication app(argc,argv);
    const QString source="\\\\.\\DISPLAY25";
    require(RhDisplayLaunch::query(source)=="&rhrdisplay=%5C%5C.%5CDISPLAY25");
    for(const auto& bad:QStringList{"","bad","\\\\.\\DISPLAY0","\\\\.\\DISPLAY01","\\\\.\\display1","\\\\.\\DISPLAY1&mode=1",QString(32,'1'),source+QChar(0)}){
        require(!RhDisplayLaunch::valid(bad));require(RhDisplayLaunch::query(bad).isEmpty());
    }
    using Reply=RhDisplayLaunch::Reply;
    require(RhDisplayLaunch::acknowledge(source,source)==Reply::Selected);
    require(RhDisplayLaunch::acknowledge(source,{})==Reply::Legacy);
    require(RhDisplayLaunch::acknowledge({},source)==Reply::Invalid);
    require(RhDisplayLaunch::acknowledge(source,"\\\\.\\DISPLAY1")==Reply::Invalid);
    require(RhDisplayLaunch::acknowledge("bad","bad")==Reply::Invalid);
    const auto plain=RhDisplayLaunch::preparation("auto",{},1920,1080);
    require(plain["mode"]=="auto"&&plain["width"]==1920&&plain["height"]==1080&&!plain.contains("displayId"));
    for(bool virtualDisplay:{false,true}){
        QJsonObject hint{{"selected",QJsonObject{{"id","identity"},{"virtual",virtualDisplay},{"gdi","stale"},{"captureIndex",12}}}};
        const auto fresh=RhDisplayLaunch::preparation("auto",hint,2520,1680);
        require(fresh["mode"]==(virtualDisplay?"virtual":"physical"));
        require(!fresh.contains("gdi")&&!fresh.contains("captureIndex"));
        require(virtualDisplay?!fresh.contains("displayId"):fresh["displayId"]=="identity");
    }
    require(RhDisplayLaunch::preparation("auto",{{"selected",QJsonObject{{"id",QString(513,'x')}}}},1920,1080)==plain);
}
