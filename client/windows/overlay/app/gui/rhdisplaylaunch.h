#pragma once
#include <QJsonObject>
#include <QString>
#include <QUrl>

// The acknowledgement means that the requested source will be resolved before
// capture creation. It is not a physical presentation or successful-capture ACK.
namespace RhDisplayLaunch {
enum class Reply { Legacy, Selected, Invalid };
inline bool valid(const QString& gdi) {
    const QString prefix=QStringLiteral("\\\\.\\DISPLAY");
    if(!gdi.startsWith(prefix)||gdi.size()<=prefix.size()||gdi.size()>31||gdi[prefix.size()]=='0')return false;
    for(qsizetype i=prefix.size();i<gdi.size();++i)if(gdi[i]<'0'||gdi[i]>'9')return false;
    return true;
}
inline QString query(const QString& gdi) {
    return valid(gdi)?QStringLiteral("&rhrdisplay=")+QString::fromLatin1(QUrl::toPercentEncoding(gdi)):QString();
}
inline Reply acknowledge(const QString& requested,const QString& ack) {
    if(ack.isEmpty())return Reply::Legacy;
    return valid(requested)&&requested==ack?Reply::Selected:Reply::Invalid;
}
inline QJsonObject preparation(const QString& mode,const QJsonObject& cached,int width,int height) {
    QJsonObject body{{"mode",mode},{"width",width},{"height",height}};
    const auto selected=cached["selected"].toObject();
    // A resume hint retains identity only. Never reuse its GDI name or index:
    // session changes, hotplug and unlock can all invalidate the old enumeration.
    const auto id=selected["id"].toString();
    if(!id.isEmpty()&&id.size()<=512){
        const bool virtualDisplay=selected["virtual"].toBool();
        body["mode"]=virtualDisplay?"virtual":"physical";
        if(!virtualDisplay)body["displayId"]=id;
    }
    return body;
}
}
