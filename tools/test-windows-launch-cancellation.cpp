#include "client/windows/overlay/app/gui/rhlaunchattempt.h"
#include <QCoreApplication>
#include <QSet>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <future>
#include <stdexcept>
#include <thread>
#include <vector>

static void require(bool value){if(!value)std::abort();}
int main(int argc,char** argv){
    QCoreApplication app(argc,argv);
    using Attempt=RhLaunchAttempt;using Result=Attempt::Result;
    std::atomic<int> calls{0};
    {Attempt legacy(false,[&](const QString&){++calls;return Result::Cancelled;});require(legacy.query().isEmpty());legacy.cancel();require(legacy.done());require(legacy.finish()==Result::Unsupported);}
    require(calls==0);
    QSet<QString> tokens;
    for(int i=0;i<32;++i){
        Attempt attempt(true,[&](const QString& token){require(token.size()==64);++calls;return Result::Cancelled;});
        const auto query=attempt.query();require(query.startsWith("&rhremoteLaunchToken="));const auto token=query.mid(query.indexOf('=')+1);
        require(QRegularExpression("^[0-9a-f]{64}$").match(token).hasMatch());require(!tokens.contains(token));tokens.insert(token);
        require(!attempt.done());attempt.cancel();require(attempt.finish()==Result::Cancelled);require(attempt.done());
    }
    require(calls==32);
    std::promise<void> remoteEntered,allowAcknowledgement;
    auto acknowledge=allowAcknowledgement.get_future().share();
    std::atomic_bool originalHttpReturned{false};std::atomic_int cancellationCalls{0};
    Attempt unknownResponse(true,[&](const QString&){++cancellationCalls;require(!originalHttpReturned);remoteEntered.set_value();acknowledge.wait();return Result::Cancelled;});
    std::vector<std::thread> cancels;
    for(int i=0;i<8;++i)cancels.emplace_back([&]{unknownResponse.cancel();});
    for(auto& thread:cancels)thread.join();remoteEntered.get_future().wait();
    require(!unknownResponse.done());
    auto first=std::async(std::launch::async,[&]{return unknownResponse.finish();});
    auto second=std::async(std::launch::async,[&]{return unknownResponse.finish();});
    require(first.wait_for(std::chrono::milliseconds(0))==std::future_status::timeout);
    allowAcknowledgement.set_value();require(first.get()==Result::Cancelled);require(second.get()==Result::Cancelled);require(cancellationCalls==1);
    originalHttpReturned=true;
    for(const auto& state:QStringList{"cancelled","finished","expired"}){
        const auto value=Attempt::reply(state);require(value!=Result::Failed);require(Attempt::confirmed(value));
        Attempt attempt(true,[value](const QString&){return value;});require(attempt.finish()==value);
    }
    require(Attempt::reply("handed_off")==Result::Claimed);require(!Attempt::confirmed(Result::Claimed));
    std::vector<QString> claimedKeys;
    {Attempt claimed(true,[&](const QString& token){claimedKeys.push_back(token);return claimedKeys.size()==1?Result::Claimed:Result::Expired;});require(claimed.finish()==Result::Expired);require(claimed.done());require(claimedKeys.size()==2);require(claimedKeys[0]==claimedKeys[1]);}
    std::atomic_int activeCleanupCalls{0};
    {Attempt active(true,[&](const QString&){++activeCleanupCalls;return Result::Failed;});active.handedOff();require(active.done());require(active.finish()==Result::HandedOff);}require(activeCleanupCalls==0);
    std::promise<void> claimEntered,releaseClaim;auto claimReleased=releaseClaim.get_future().share();
    {Attempt racing(true,[&](const QString&){claimEntered.set_value();claimReleased.wait();return Result::Claimed;});racing.cancel();claimEntered.get_future().wait();racing.handedOff();releaseClaim.set_value();require(racing.finish()==Result::HandedOff);}
    {const auto began=std::chrono::steady_clock::now();std::atomic_int polls{0};Attempt unfinished(true,[&](const QString&){++polls;return Result::Claimed;});require(unfinished.finish()==Result::Claimed);require(!Attempt::confirmed(unfinished.finish()));require(polls>=2&&polls<=16);require(std::chrono::steady_clock::now()-began<std::chrono::seconds(19));}
    require(Attempt::reply("")==Result::Failed);require(Attempt::reply("OK")==Result::Failed);require(!Attempt::confirmed(Result::Failed));
    {Attempt failure(true,[](const QString&)->Result{throw std::runtime_error("fixture");});require(failure.finish()==Result::Failed);require(failure.done());}
    std::atomic_bool destructorDrained{false};
    {Attempt destructed(true,[&](const QString&){destructorDrained=true;return Result::Finished;});}
    require(destructorDrained);
    const auto token=*tokens.cbegin();
    for(const auto& message:QStringList{"https://host/resume?rhremoteLaunchToken="+token+"&mode=1080", "Error transferring https://host/cancel?RHREMOTELAUNCHTOKEN="+token}){
        const auto safe=Attempt::redact(message);require(!safe.contains(token));require(safe.contains("[redacted]"));
    }
    require(Attempt::redact("ordinary diagnostic")=="ordinary diagnostic");
    std::puts("Native launch ownership, unknown-response cancellation, idempotence, drain, old-host and redaction contracts passed");
}
