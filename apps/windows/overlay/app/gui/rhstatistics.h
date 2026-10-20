#pragma once
#include <QDateTime>
#include <QElapsedTimer>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QThread>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

// Opt-in, metadata-only trace. Disk I/O never runs on decoder/render threads.
class RhStatistics {
    std::atomic<bool> enabled{false};bool stopping=false;
    std::atomic<quint64> lost{0};
    std::mutex mutex;std::condition_variable wake;std::deque<QByteArray> queue;
    std::thread writer;QElapsedTimer clock;qint64 origin;
    RhStatistics():origin(QDateTime::currentMSecsSinceEpoch()*1000){
        clock.start();const auto path=qEnvironmentVariable("RHREMOTE_STATISTICS_FILE");
        enabled=!path.isEmpty();if(!enabled)return;
        writer=std::thread([this,path]{
            QFile file(path);if(!file.open(QIODevice::WriteOnly|QIODevice::NewOnly)){enabled=false;return;}
            qint64 written=0;bool capped=false;
            while(true){std::deque<QByteArray> batch;{
                std::unique_lock<std::mutex> lock(mutex);
                wake.wait_for(lock,std::chrono::milliseconds(250),[this]{return stopping||queue.size()>=512;});
                batch.swap(queue);if(batch.empty()&&stopping)break;
            }
            for(const auto& row:batch){if(written+row.size()>512LL*1024*1024){capped=true;++lost;continue;}if(file.write(row)!=row.size()){++lost;continue;}written+=row.size();}
            file.flush();}
            auto footer=QJsonDocument(QJsonObject{{"event","trace_end"},{"ts_us",double(now())},{"lost_events",double(lost.load())},{"size_limit_reached",capped}}).toJson(QJsonDocument::Compact)+'\n';file.write(footer);file.flush();
        });
    }
public:
    static RhStatistics& get(){static RhStatistics s;return s;}
    ~RhStatistics(){if(writer.joinable()){{std::lock_guard<std::mutex> lock(mutex);stopping=true;}wake.notify_one();writer.join();}}
    static bool active(){return get().enabled;}
    static qint64 now(){auto& s=get();return s.origin+s.clock.nsecsElapsed()/1000;}
    static void event(const QString& name,qint64 frame=-1,QJsonObject fields={},qint64 at=0,qint64 duration=0){
        auto& s=get();if(!s.enabled)return;
        QJsonObject row{{"event",name},{"ts_us",double(at?at:now())},{"thread",QString::number(quintptr(QThread::currentThreadId()))},{"frame",double(frame)},{"fields",fields}};
        if(duration)row["dur_us"]=double(duration);
        auto bytes=QJsonDocument(row).toJson(QJsonDocument::Compact)+'\n';
        {std::lock_guard<std::mutex> lock(s.mutex);if(s.stopping)return;if(s.queue.size()>=65536){++s.lost;return;}s.queue.push_back(std::move(bytes));}s.wake.notify_one();
    }
    class Scope {
        QString name;qint64 start,frame;
    public: Scope(QString n,qint64 f=-1):name(std::move(n)),start(active()?now():0),frame(f){}
        ~Scope(){if(start)event(name,frame,{},start,now()-start);}
    };
};
