#pragma once
#include "rhhost.h"
#include "rhstatistics.h"
#include <future>

namespace RhPreparation {
// Keep preparation scoped to this connection. Each task owns immutable host and
// profile values so a failed initialization cannot dereference a deleted Session.
inline std::future<QJsonObject> start(const QString& host,const QSslCertificate& certificate,int width,int height) {
    const auto mode=qEnvironmentVariable("RHREMOTE_DISPLAY_MODE");
    if(mode.isEmpty())return {};
    const auto cached=QJsonDocument::fromJson(qgetenv("RHREMOTE_RESUME_SELECTION")).object();
    return std::async(std::launch::async,[host,certificate,width,height,mode,cached](){
        RhStatistics::Scope trace("host_display_prepare_worker");
        const int index=cached["selected"].toObject()["captureIndex"].toInt();
        if(index>=1&&index<=12&&cached["displays"].isArray())return QJsonObject{{"ok",true},{"result",cached}};
        return RhHost::request(host,certificate,"/v1/display/prepare",{{"mode",mode},{"width",width},{"height",height}});
    });
}
}
