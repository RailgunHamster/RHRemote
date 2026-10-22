#pragma once
#include "backend/identitymanager.h"
#include <QSslSocket>
#include <QNetworkProxy>
#include <QJsonDocument>
#include <QJsonObject>
#include <QElapsedTimer>
#include <QUdpSocket>

namespace RhHost {
inline QJsonObject request(const QString& host,const QSslCertificate& pinned,const QString& path,const QJsonObject& body=QJsonObject()) {
    if(host.isEmpty() || pinned.isNull()) return {{"ok",false},{"error","缺少主机配对信息"}};
    QSslSocket socket;socket.setProxy(QNetworkProxy::NoProxy);socket.setSslConfiguration(IdentityManager::get()->getSslConfig());
    QObject::connect(&socket,&QSslSocket::sslErrors,&socket,[&](const QList<QSslError>& errors){if(socket.peerCertificate()==pinned)socket.ignoreSslErrors(errors);});
    socket.connectToHostEncrypted(host,47995);
    if(!socket.waitForEncrypted(2500))return {{"ok",false},{"error","主机辅助服务不可用，请安装或启动 RHRemoteHost"}};
    if(socket.peerCertificate()!=pinned){socket.abort();return {{"ok",false},{"error","主机证书与配对记录不一致"}};}
    const auto payload=body.isEmpty()?QByteArray():QJsonDocument(body).toJson(QJsonDocument::Compact);
    socket.write((payload.isEmpty()?"GET ":"POST ")+path.toUtf8()+" HTTP/1.1\r\nHost: RHRemoteHost\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+QByteArray::number(payload.size())+"\r\n\r\n"+payload);
    QByteArray data;QElapsedTimer timer;timer.start();
    while(timer.elapsed()<15000){if(!socket.bytesAvailable()&&!socket.waitForReadyRead(qMin(1000,int(15000-timer.elapsed())))){if(socket.state()==QAbstractSocket::UnconnectedState)break;continue;}data+=socket.readAll();if(data.size()>131072)return {{"ok",false},{"error","主机响应过大"}};}
    const int split=data.indexOf("\r\n\r\n");if(split<0)return {{"ok",false},{"error","主机响应不完整"}};
    QJsonParseError error;const auto reply=QJsonDocument::fromJson(data.mid(split+4),&error);
    if(error.error!=QJsonParseError::NoError||!reply.isObject())return {{"ok",false},{"error","主机响应格式错误"}};
    return reply.object();
}
inline QJsonObject diagnose(const QString& host,const QSslCertificate& pinned) {
    auto info=request(host,pinned,"/v1/network");
    if(!info["ok"].toBool())return info;
    auto result=info["result"].toObject();
    const auto ticket=request(host,pinned,"/v1/network/probe");
    if(!ticket["ok"].toBool()){result["mediaUdp"]="unavailable";result["probeError"]=ticket["error"];}
    else {
        const auto payload=ticket["result"].toObject();
        const auto token=payload["token"].toString().toLatin1();
        if(payload["udpPort"].toInt()!=47995||token.size()!=40||!token.startsWith("RHRNET1:"))return {{"ok",false},{"error","诊断响应无效"}};
        QUdpSocket udp;udp.setProxy(QNetworkProxy::NoProxy);udp.connectToHost(host,47995);
        if(udp.state()!=QAbstractSocket::ConnectedState&&!udp.waitForConnected(1200))result["mediaUdp"]="unreachable";
        else {
            udp.write(token);udp.waitForBytesWritten(200);
            if(udp.hasPendingDatagrams()||udp.waitForReadyRead(1200)){
                QByteArray reply;reply.resize(int(udp.pendingDatagramSize()));udp.readDatagram(reply.data(),reply.size());
                result["mediaUdp"]=reply==token?"reachable":"invalid_reply";
            }else result["mediaUdp"]="timeout";
        }
    }
    return {{"ok",true},{"result",result}};
}
}
