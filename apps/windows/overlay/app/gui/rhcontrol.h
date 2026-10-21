#pragma once
#include <QObject>
#include <QCoreApplication>
#include <QJsonObject>
#include <QJsonDocument>
#include <QJsonArray>
#include <QMutex>
#include <QMutexLocker>
#include <QTcpServer>
#include <QTcpSocket>
#include <QScopedPointer>
#include <QHostAddress>
#include <QStringList>
#include <QThread>
#include <QDateTime>
#include <QPointer>
#include <QSaveFile>
#include <atomic>
#include <Limelight.h>
#include <SDL_events.h>
#include <QFile>
#include "rhhost.h"
#include "rhkeys.h"

// Shared with the decoder; never reads live renderer buffers from the HTTP thread.
struct RhRuntime {
    QMutex mutex;
    QJsonObject home,profile,lastCommand;
    QString stats;
    qint64 statsAt=0;
    bool streaming=false;
    QString phase,error;
    QString hostAddress;QSslCertificate hostCert;
    QJsonArray displays;QJsonObject selectedDisplay;QString displayError;int initialDisplay=0;
    std::atomic<bool> firstFrame{false};
    std::atomic<quintptr> streamWindow{0};
    std::atomic<bool> localDisconnect{false},retryEligible{false},transportReleased{false};
    std::atomic<int> terminationCode{0};
    std::atomic<bool> audioEnabled{true};std::atomic<int> volume{100};std::atomic<quint64> audioPackets{0};
    RhRuntime(){audioEnabled=qEnvironmentVariable("RHREMOTE_AUDIO_ENABLED")!="0";bool ok=false;int v=qEnvironmentVariableIntValue("RHREMOTE_AUDIO_VOLUME",&ok);if(ok)volume=qBound(0,v,100);}
    static RhRuntime& get(){static RhRuntime value;return value;}
    static void status(const QString& phase,const QString& error=QString()){
        auto& r=get();QMutexLocker lock(&r.mutex);r.phase=phase;r.error=error;
        if(phase=="transport_closed")r.transportReleased=true;
        const auto path=qEnvironmentVariable("RHREMOTE_SESSION_STATUS");
        if(!path.isEmpty()){QSaveFile file(path);if(file.open(QIODevice::WriteOnly)){file.write(QJsonDocument(QJsonObject{{"phase",phase},{"error",error},{"streaming",r.streaming},{"firstFrame",r.firstFrame.load()},{"retryEligible",r.retryEligible.load()},{"terminationCode",r.terminationCode.load()},{"transportReleased",r.transportReleased.load()}}).toJson(QJsonDocument::Compact));file.commit();}}
    }
    static void terminated(int code){auto& r=get();r.terminationCode=code;r.retryEligible=r.firstFrame.load()&&!r.localDisconnect.load()&&code!=ML_ERROR_PROTECTED_CONTENT;status("interrupted",QString("串流中断 (%1)").arg(code));}
    static void stream(bool value){auto& r=get();{QMutexLocker lock(&r.mutex);r.streaming=value;if(value){r.stats.clear();r.statsAt=0;}}if(value)status("connected");else{QString error;{QMutexLocker lock(&r.mutex);error=r.error;}status(error.isEmpty()?"disconnected":"error",error);}}
    static void performance(const QString& text){auto& r=get();QMutexLocker lock(&r.mutex);r.stats=text;r.statsAt=QDateTime::currentMSecsSinceEpoch();}
};

class RhControl : public QObject {
    Q_OBJECT
    class Server final : public QThread {
    public:
        RhControl* owner;quint16 port;QByteArray token;
        Server(RhControl* o,quint16 p,QByteArray t):owner(o),port(p),token(t){}
        void handle(QTcpSocket* owned){
            QScopedPointer<QTcpSocket> socket(owned);QElapsedTimer clock;clock.start();
                QByteArray data;int split=-1;bool valid=true;
                while((split=data.indexOf("\r\n\r\n"))<0){
                    if(clock.elapsed()>5000||(!socket->bytesAvailable()&&!socket->waitForReadyRead(1000))){valid=false;break;}
                    data+=socket->readAll();if(data.size()>16384){valid=false;break;}
                }
                if(!valid)return;
                const auto lines=data.left(split).split('\n');const auto first=lines.value(0).trimmed().split(' ');
                QByteArray auth;int length=0;bool origin=false;
                for(const auto& raw:lines){auto line=raw.trimmed();int colon=line.indexOf(':');if(colon<0)continue;auto key=line.left(colon).toLower();auto value=line.mid(colon+1).trimmed();if(key=="authorization")auth=value;if(key=="origin")origin=true;if(key=="content-length"){bool ok=false;length=value.toInt(&ok);if(!ok)valid=false;}if(key=="transfer-encoding")valid=false;}
                int status=200;QJsonObject result;
                if(auth!="Bearer "+token||origin){status=403;result={{"ok",false},{"error","unauthorized"}};}
                else if(!valid||length<0||length>8192||first.size()!=3){status=400;result={{"ok",false},{"error","invalid request"}};}
                else {
                    QByteArray body=data.mid(split+4);
                    while(body.size()<length){if(clock.elapsed()>5000||!socket->waitForReadyRead(1000)){valid=false;break;}body+=socket->readAll();if(body.size()>8192){valid=false;break;}}
                    QJsonParseError error;auto document=length?QJsonDocument::fromJson(body.left(length),&error):QJsonDocument(QJsonObject());
                    if(!valid||(length&&(error.error!=QJsonParseError::NoError||!document.isObject()))){status=400;result={{"ok",false},{"error","invalid JSON body"}};}
                    else result=owner->route(first[0],first[1],document.object(),status);
                }
                auto payload=QJsonDocument(result).toJson(QJsonDocument::Compact);
                socket->write("HTTP/1.1 "+QByteArray::number(status)+" Result\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+QByteArray::number(payload.size())+"\r\n\r\n"+payload);
                socket->waitForBytesWritten(1000);socket->disconnectFromHost();
        }
        void run() override {
            QTcpServer listener;QList<QThread*> workers;
            if(!listener.listen(QHostAddress::LocalHost,port)){qWarning("RHRemote control port unavailable");return;}
            while(!isInterruptionRequested()){
                for(int i=workers.size()-1;i>=0;i--)if(workers[i]->isFinished()){auto thread=workers.takeAt(i);thread->wait();delete thread;}
                if(!listener.waitForNewConnection(100))continue;
                auto socket=listener.nextPendingConnection();if(!socket)continue;
                if(workers.size()>=8){delete socket;continue;}
                socket->setParent(nullptr);auto worker=QThread::create([this,socket]{handle(socket);});socket->moveToThread(worker);workers.append(worker);worker->start();
            }
            listener.close();for(auto worker:workers){worker->wait();delete worker;}
        }
    };
    Server* server=nullptr;std::atomic<quint64> nextId{0};
    QJsonObject route(const QByteArray& method,const QByteArray& path,QJsonObject body,int& status){
        auto& r=RhRuntime::get();
        if(method=="GET"&&(path=="/v1/state"||path=="/v1/stats")){
            QMutexLocker lock(&r.mutex);auto result=path=="/v1/state"?r.home:QJsonObject();
            result["ok"]=true;result["streaming"]=r.streaming;result["activeProfile"]=r.profile;
            result["audioEnabled"]=r.audioEnabled.load();result["volume"]=r.volume.load();result["audioPackets"]=double(r.audioPackets.load());result["phase"]=r.phase;result["error"]=r.error;result["statistics"]=r.stats;result["statisticsAtMs"]=r.statsAt;result["lastCommand"]=r.lastCommand;result["firstFrame"]=r.firstFrame.load();result["displays"]=r.displays;result["selectedDisplay"]=r.selectedDisplay;result["displayError"]=r.displayError;result["streamWindow"]=QString::number(r.streamWindow.load());result["retryEligible"]=r.retryEligible.load();result["terminationCode"]=r.terminationCode.load();result["transportReleased"]=r.transportReleased.load();return result;
        }
        if(method!="POST"){status=404;return {{"ok",false},{"error","unknown endpoint"}};}
        bool streaming;QJsonObject profile;{QMutexLocker lock(&r.mutex);streaming=r.streaming;profile=r.profile;}
        if(path=="/v1/disconnect"){
            r.localDisconnect=true;r.retryEligible=false;
            if(!streaming){status=409;return {{"ok",false},{"error","no active stream"}};}
            SDL_Event event{};event.type=SDL_QUIT;
            if(SDL_PushEvent(&event)<0){status=503;return {{"ok",false},{"error","disconnect queue failed"}};}
            return {{"ok",true},{"accepted",true}};
        }
        if(path=="/v1/test-interrupt"){
            if(!streaming||!qEnvironmentVariableIsSet("RHREMOTE_STATISTICS_FILE")){status=409;return {{"ok",false},{"error","requires an active test-mode stream"}};}
            SDL_Event event{};event.type=SDL_USEREVENT;event.user.code=0x52480002;
            if(SDL_PushEvent(&event)<0){status=503;return {{"ok",false},{"error","command queue failed"}};}
            return {{"ok",true},{"accepted",true},{"synthetic",true}};
        }
        if(path=="/v1/stats-toggle"){
            if(!streaming){status=409;return {{"ok",false},{"error","no active stream"}};}
            SDL_Event event{};event.type=SDL_USEREVENT;event.user.code=0x52480001;
            if(SDL_PushEvent(&event)<0){status=503;return {{"ok",false},{"error","command queue failed"}};}
            return {{"ok",true},{"accepted",true}};
        }
        if(path=="/v1/audio"){
            if(!streaming){status=409;return {{"ok",false},{"error","no active stream"}};}
            bool enabled=r.audioEnabled.load();int volume=r.volume.load();
            if(body.contains("enabled")){if(!body["enabled"].isBool()){status=400;return {{"ok",false},{"error","enabled must be boolean"}};}enabled=body["enabled"].toBool();}
            if(body.contains("volume")){volume=body["volume"].toInt(-1);if(volume<0||volume>100){status=400;return {{"ok",false},{"error","volume must be 0..100"}};}}
            r.volume=volume;r.audioEnabled=enabled;
            return {{"ok",true},{"enabled",r.audioEnabled.load()},{"volume",r.volume.load()}};
        }
        if(path=="/v1/shortcut"){
            if(!streaming){status=409;return {{"ok",false},{"error","no active stream"}};}
            QFile file(":/res/rhremote-shortcuts.json");file.open(QIODevice::ReadOnly);const auto shortcuts=QJsonDocument::fromJson(file.readAll()).array();QJsonObject shortcut;
            for(const auto& entry:shortcuts)if(entry.toObject()["id"]==body["shortcut"]&&body["shortcut"].isString()){shortcut=entry.toObject();break;}
            if(shortcut.isEmpty()){status=400;return {{"ok",false},{"error","unknown remote shortcut"}};}
            if(shortcut["secureAttention"].toBool()){
                QString address;QSslCertificate cert;{QMutexLocker lock(&r.mutex);address=r.hostAddress;cert=r.hostCert;}
                auto result=RhHost::request(address,cert,"/v1/input/secure-attention",QJsonObject{{"action","ctrl-alt-delete"}});if(!result["ok"].toBool())status=409;return result;
            }
            auto result=RhKeys::send(shortcut["keys"].toArray());if(!result["ok"].toBool())status=409;return result;
        }
        if(path=="/v1/displays"||path=="/v1/display"){
            if(!streaming){status=409;return {{"ok",false},{"error","no active stream"}};}
            QString address;QSslCertificate cert;{QMutexLocker lock(&r.mutex);address=r.hostAddress;cert=r.hostCert;}
            QJsonObject request;QString endpoint="/v1/displays";
            if(path=="/v1/display") {const auto id=body["displayId"].toString();if(id.isEmpty()||id.size()>512){status=400;return {{"ok",false},{"error","displayId required"}};}endpoint="/v1/display/prepare";request={{"mode",id=="auto"?"auto":id=="virtual"?"virtual":"physical"},{"displayId",id=="auto"||id=="virtual"?"":id},{"width",profile["width"]},{"height",profile["height"]}};}
            auto result=RhHost::request(address,cert,endpoint,request);if(!result["ok"].toBool()){status=409;return result;}
            auto data=result["result"].toObject();{QMutexLocker lock(&r.mutex);r.displays=data["displays"].toArray();r.displayError.clear();}
            if(path=="/v1/display"){auto selected=data["selected"].toObject();auto input=RhKeys::display(selected["captureIndex"].toInt());if(!input["ok"].toBool()){status=409;return input;}{QMutexLocker lock(&r.mutex);r.selectedDisplay=selected;}data["accepted"]=true;}
            return {{"ok",true},{"result",data}};
        }
        if(path=="/v1/input"){
            if(!streaming){status=409;return {{"ok",false},{"error","no active stream"}};}
            const QString gesture=body.value("gesture").toString();
            if(gesture=="scroll"){int delta=body.value("delta").toInt();if(delta<-32767||delta>32767){status=400;return {{"ok",false},{"error","invalid delta"}};}int code=LiSendHighResScrollEvent(short(delta));if(code!=0)status=409;return {{"ok",code==0},{"sendResult",code},{"error",code==0?"":"input transport rejected scroll"}};}
            if(gesture!="tap"&&gesture!="secondary"&&gesture!="drag"){status=400;return {{"ok",false},{"error","unsupported gesture"}};}
            const int w=profile.value("width").toInt(),h=profile.value("height").toInt();
            int x=body.value("x").toInt(-1),y=body.value("y").toInt(-1),toX=body.value("toX").toInt(x),toY=body.value("toY").toInt(y);
            if(w<1||h<1||x<0||y<0||x>=w||y>=h||toX<0||toY<0||toX>=w||toY>=h){status=400;return {{"ok",false},{"error","coordinates outside the stream"}};}
            const int button=((gesture!="secondary")^profile.value("swapButtons").toBool())?BUTTON_LEFT:BUTTON_RIGHT;
            int position=LiSendMousePositionEvent(short(x),short(y),short(w),short(h));
            if(position!=0){status=409;return {{"ok",false},{"sendResult",position},{"error","input transport rejected position"}};}
            int press=LiSendMouseButtonEvent(BUTTON_ACTION_PRESS,button);
            if(press!=0){status=409;return {{"ok",false},{"sendResult",press},{"error","input transport rejected press"}};}
            if(gesture=="drag")for(int i=1;i<=12;i++){QThread::msleep(20);position=LiSendMousePositionEvent(short(x+(toX-x)*i/12),short(y+(toY-y)*i/12),short(w),short(h));if(position!=0)break;}
            else QThread::msleep(70);
            int release=LiSendMouseButtonEvent(BUTTON_ACTION_RELEASE,button);bool ok=position==0&&release==0;if(!ok)status=409;
            return {{"ok",ok},{"positionResult",position},{"pressResult",press},{"releaseResult",release},{"buttonReleased",release==0},{"error",ok?"":"input transport rejected event"}};
        }
        const QString op=QString::fromUtf8(path.mid(4));
        if(!QStringList{"connect","settings","add","pair","view"}.contains(op)){status=404;return {{"ok",false},{"error","unknown endpoint"}};}
        if(streaming){status=409;return {{"ok",false},{"error","disconnect before changing connection state"}};}
        QString id=QString::number(++nextId);body["op"]=op;
        QMetaObject::invokeMethod(this,[this,id,body]{emit command(id,body.toVariantMap());},Qt::QueuedConnection);
        status=202;return {{"ok",true},{"accepted",true},{"id",id}};
    }
public:
    explicit RhControl(QObject* parent=nullptr):QObject(parent){
        bool ok=false;int port=qEnvironmentVariableIntValue("RHREMOTE_CONTROL_PORT",&ok);auto token=qgetenv("RHREMOTE_CONTROL_TOKEN");
        if(ok&&port>1024&&port<65536&&token.size()>=32){server=new Server(this,quint16(port),token);server->start();}
        QObject::connect(QCoreApplication::instance(),&QCoreApplication::aboutToQuit,this,[this]{
            bool cleaned;{auto& r=RhRuntime::get();QMutexLocker lock(&r.mutex);cleaned=r.phase=="cleanup_complete";}
            if(cleaned){stopServer();RhRuntime::status("transport_closed");}
        });
    }
    void stopServer(){if(server){server->requestInterruption();server->wait();delete server;server=nullptr;}}
    ~RhControl(){stopServer();}
    Q_INVOKABLE void publish(const QVariantMap& value){auto& r=RhRuntime::get();QMutexLocker lock(&r.mutex);r.home=QJsonObject::fromVariantMap(value);}
    Q_INVOKABLE void complete(const QString& id,const QVariantMap& result){auto& r=RhRuntime::get();QMutexLocker lock(&r.mutex);r.lastCommand={{"id",id},{"result",QJsonObject::fromVariantMap(result)}};}
signals:
    void command(QString id,QVariantMap request);
};
