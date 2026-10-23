#include "client/windows/overlay/app/gui/rhstatistics.h"
#include <QCoreApplication>
#include <QTemporaryDir>
#include <cstdio>
#include <cstdlib>

static void require(bool value){if(!value)std::abort();}
int main(int argc,char** argv){
    QCoreApplication app(argc,argv);
    require(RhHostFrameTrace::query(false,"valid").isEmpty());
    for(const auto& tag:QStringList{"",".","..","../file","a/b","a\\b","x&appid=2","a b",QString::fromUtf8("名字"),"a\nb",QString(97,'a')})require(RhHostFrameTrace::query(true,tag).isEmpty());
    require(RhHostFrameTrace::query(true,"run-A_1.v2")=="&rhremoteFrameTrace=1&rhremoteTraceClient=run-A_1.v2");
    require(RhHostFrameTrace::acknowledge(false,"1")=="disabled");require(RhHostFrameTrace::acknowledge(true,"1")=="accepted");
    require(RhHostFrameTrace::acknowledge(true,"0")=="not_accepted");require(RhHostFrameTrace::acknowledge(true,{})=="unsupported");
    require(RhHostFrameTrace::acknowledge(true,"anything")=="invalid_ack");
    QTemporaryDir temporary;require(temporary.isValid());
    const bool tracing=argc>1&&QString::fromLocal8Bit(argv[1])=="trace";
    if(tracing){
        require(QDir(temporary.path()).mkdir("client-fixture"));
        const auto file=temporary.filePath("client-fixture/native.jsonl");qputenv("RHREMOTE_STATISTICS_FILE",file.toUtf8());
        require(RhStatistics::launchQuery()=="&rhremoteFrameTrace=1&rhremoteTraceClient=client-fixture");
        RhStatistics::event("unit-stage");RhStatistics::finishNow();QFile trace(file);require(trace.open(QIODevice::ReadOnly));
        require(trace.readAll().contains("trace_end"));trace.close();
    }else{
        qunsetenv("RHREMOTE_STATISTICS_FILE");require(RhStatistics::launchQuery().isEmpty());require(QDir(temporary.path()).isEmpty());
    }
    std::puts(tracing?"Active recording sends an opaque host trace label":"Normal connection creates no trace file");
}
