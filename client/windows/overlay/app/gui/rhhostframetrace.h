#pragma once
#include <QString>

// The server receives an opaque run label, never a local statistics path.
class RhHostFrameTrace {
public:
    static bool validTag(const QString& tag){
        if(tag.isEmpty()||tag.size()>96||tag=="."||tag=="..")return false;
        for(const auto ch:tag){const auto c=ch.unicode();if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'||c=='.'))return false;}
        return true;
    }
    static QString query(bool recording,const QString& tag){
        return recording&&validTag(tag)?QStringLiteral("&rhremoteFrameTrace=1&rhremoteTraceClient=")+tag:QString();
    }
    static QString acknowledge(bool requested,const QString& value){
        if(!requested)return QStringLiteral("disabled");
        if(value=="1")return QStringLiteral("accepted");
        if(value=="0")return QStringLiteral("not_accepted");
        if(value.isEmpty())return QStringLiteral("unsupported");
        return QStringLiteral("invalid_ack");
    }
};
