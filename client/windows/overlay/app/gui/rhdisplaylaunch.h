#pragma once
#include <QJsonObject>
#include <QString>
#include <QUrl>
#include "rhwindowsource.h"

// The acknowledgement means that the requested source will be resolved before
// capture creation. It is not a physical presentation or successful-capture ACK.
namespace RhDisplayLaunch {
enum class Reply { Legacy, Selected, Invalid };
inline bool x11(const QString& source) {
    const QString prefix=QStringLiteral("rhrx11:");
    if(!source.startsWith(prefix)||source.size()<=prefix.size()||source.size()>prefix.size()+128)return false;
    for(qsizetype i=prefix.size();i<source.size();++i){const auto c=source[i];if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'||c=='.'||c==':'))return false;}
    return true;
}
inline bool valid(const QString& gdi) {
    if(x11(gdi))return true;
    if(RhWindowSource::valid(gdi))return true;
    const QString prefix=QStringLiteral("\\\\.\\DISPLAY");
    if(!gdi.startsWith(prefix)||gdi.size()<=prefix.size()||gdi.size()>31||gdi[prefix.size()]=='0')return false;
    for(qsizetype i=prefix.size();i<gdi.size();++i)if(gdi[i]<'0'||gdi[i]>'9')return false;
    return true;
}
inline QString query(const QString& gdi) {
    return valid(gdi)?QStringLiteral("&rhrdisplay=")+QString::fromLatin1(QUrl::toPercentEncoding(gdi)):QString();
}
inline Reply acknowledge(const QString& requested,const QString& ack) {
    if(ack.isEmpty())return RhWindowSource::valid(requested)||x11(requested)?Reply::Invalid:Reply::Legacy;
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
