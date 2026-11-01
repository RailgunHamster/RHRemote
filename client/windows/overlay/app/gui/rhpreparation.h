#pragma once
#include "rhhost.h"
#include "rhstatistics.h"
#include "rhdisplaylaunch.h"
#include <future>

namespace RhPreparation {
// Keep preparation scoped to this connection. Each task owns immutable host and
// profile values so a failed initialization cannot dereference a deleted Session.
inline std::future<QJsonObject> start(const QString& host,const QSslCertificate& certificate,int width,int height) {
    const auto mode=qEnvironmentVariable("RHREMOTE_DISPLAY_MODE");
    if(mode.isEmpty())return {};
    if(mode=="window"){
        const auto selected=QJsonDocument::fromJson(qgetenv("RHREMOTE_WINDOW_SELECTION")).object();
        return std::async(std::launch::deferred,[selected](){
            const auto source=selected["id"].toString();
            if(!RhWindowSource::valid(source))return RhWindowSource::failure("窗口选择无效，请重新选择");
            return QJsonObject{{"ok",true},{"result",QJsonObject{{"displays",QJsonArray{}},{"selected",QJsonObject{{"id",source},{"gdi",source},{"title",selected["title"]},{"kind","window"},{"captureIndex",0}}},{"initialCaptureIndex",0}}}};
        });
    }
    const auto cached=QJsonDocument::fromJson(qgetenv("RHREMOTE_RESUME_SELECTION")).object();
    return std::async(std::launch::async,[host,certificate,width,height,mode,cached](){
        RhStatistics::Scope trace("host_display_prepare_worker");
        return RhHost::request(host,certificate,"/v1/display/prepare",RhDisplayLaunch::preparation(mode,cached,width,height));
    });
}
}
