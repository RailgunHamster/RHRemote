#pragma once
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSet>
#include <QXmlStreamReader>

// Window IDs are opaque and tied to the paired TLS certificate, never native handles.
namespace RhWindowSource {
inline bool valid(const QString& value){
    if(!value.startsWith("rhrwindow:")||value.size()!=42)return false;
    for(auto c:value.mid(10))if((c<'0'||c>'9')&&(c<'a'||c>'f'))return false;
    return true;
}
inline QJsonObject failure(const QString& error){return {{"ok",false},{"error",error}};}
inline QJsonObject parse(const QString& input){
    if(input.size()>1024*1024)return failure("窗口列表过大");
    QXmlStreamReader xml(input);QJsonArray rows;QSet<QString> ids;bool supported=false,root=false;
    while(xml.readNextStartElement()){
        if(xml.name()!=QStringLiteral("root")||root)return failure("无效窗口列表");root=true;
        if(xml.attributes().value("status_code")!=QStringLiteral("200"))return failure("主机尚未支持窗口串流或配对已失效");
        while(xml.readNextStartElement()){
            if(xml.name()==QStringLiteral("supported")){supported=xml.readElementText()=="1";continue;}
            if(xml.name()!=QStringLiteral("Window")){xml.skipCurrentElement();continue;}
            if(rows.size()>=128)return failure("窗口列表过大");
            QJsonObject row;while(xml.readNextStartElement()){const auto key=xml.name().toString();const auto value=xml.readElementText();if(value.size()>2048)return failure("窗口信息过长");row[key]=value;}
            const auto id=row["id"].toString();const bool ready=row["state"].toString()=="ready";
            if(!id.isEmpty()&&(!valid(id)||ids.contains(id)))return failure("无效或重复的窗口标识");ids.insert(id);
            const int width=row["width"].toString().toInt(),height=row["height"].toString().toInt();
            if(ready&&(!valid(id)||width<1||height<1||width>16384||height>16384))return failure("无效窗口尺寸");
            row["width"]=width;row["height"]=height;row["available"]=ready;rows.append(row);
        }
    }
    if(xml.hasError()||!root)return failure("无效窗口列表");
    if(!supported)return failure("请先更新 RHRemote-Sunshine，主机尚未支持窗口串流");
    return {{"ok",true},{"result",QJsonObject{{"supported",true},{"secondaryWindows",false},{"windows",rows}}}};
}
}
