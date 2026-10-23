#pragma once
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QString>
#include <QThread>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>

// One real HTTPS launch/resume, never persisted, synced, logged or reused for a retry.
// The network callback constructs its own NvHTTP on this dedicated Qt thread.
class RhLaunchAttempt final {
public:
    enum class Result { Unsupported, Cancelled, HandedOff, Finished, Expired, Failed };
    using Cancel = std::function<Result(const QString&)>;
    explicit RhLaunchAttempt(bool supported, Cancel remote):supported_(supported) {
        if(!supported_){done_=true;result_=Result::Unsupported;return;}
        for(int i=0;i<8;++i)credential_+=QStringLiteral("%1").arg(QRandomGenerator::system()->generate(),8,16,QLatin1Char('0'));
        worker_.reset(QThread::create([this,remote=std::move(remote)]{
            {std::unique_lock<std::mutex> lock(mutex_);ready_.wait(lock,[this]{return cancelled_;});}
            auto result=Result::Failed;
            try{if(remote)result=remote(credential_);}catch(...){result=Result::Failed;}
            {std::lock_guard<std::mutex> lock(mutex_);result_=result;done_=true;}
        }));
        worker_->setObjectName("Pending launch cleanup");worker_->start();
    }
    ~RhLaunchAttempt(){finish();}
    RhLaunchAttempt(const RhLaunchAttempt&)=delete;
    RhLaunchAttempt& operator=(const RhLaunchAttempt&)=delete;
    QString query()const{return supported_?QStringLiteral("&rhremoteLaunchToken=")+credential_:QString();}
    void cancel(){if(!supported_)return;{std::lock_guard<std::mutex> lock(mutex_);cancelled_=true;}ready_.notify_one();}
    // The callback has a bounded HTTP timeout. Await it before releasing the native transport.
    Result finish(){cancel();if(worker_)worker_->wait();std::lock_guard<std::mutex> lock(mutex_);return result_;}
    bool done()const{std::lock_guard<std::mutex> lock(mutex_);return done_;}
    static bool confirmed(Result value){return value!=Result::Failed;}
    static QString name(Result value){switch(value){case Result::Unsupported:return "unsupported";case Result::Cancelled:return "cancelled";case Result::HandedOff:return "handed_off";case Result::Finished:return "finished";case Result::Expired:return "expired";default:return "failed";}}
    static Result reply(const QString& state){if(state=="cancelled")return Result::Cancelled;if(state=="handed_off")return Result::HandedOff;if(state=="finished")return Result::Finished;if(state=="expired")return Result::Expired;return Result::Failed;}
    // Cover normal URL diagnostics as well as Qt error strings that include the URL.
    static QString redact(QString value){static const QRegularExpression pattern("(?i)(rhremoteLaunchToken=)[0-9a-f]{64}");value.replace(pattern,"\\1[redacted]");return value;}
private:
    bool supported_;
    QString credential_;
    mutable std::mutex mutex_;
    std::condition_variable ready_;
    bool cancelled_=false,done_=false;
    Result result_=Result::Failed;
    std::unique_ptr<QThread> worker_;
};
