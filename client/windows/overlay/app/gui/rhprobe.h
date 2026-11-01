#pragma once
#include <QElapsedTimer>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

// Observes the existing serverinfo transaction; it never initiates a request or
// changes certificate validation, retries, or the legacy HTTP fallback.
namespace RhProbe {
class ServerInfo {
public:
    ServerInfo(const QString& address, bool hasCertificate)
        : m_HasCertificate(hasCertificate) {
        if(qEnvironmentVariable("RHREMOTE_PROBE_OPERATION")!="1")return;
        m_Id=qEnvironmentVariable("RHREMOTE_PROBE_ID");m_Target=qEnvironmentVariable("RHREMOTE_TARGET_UUID");m_Path=qEnvironmentVariable("RHREMOTE_PROBE_REPORT");
        m_Active=
            !m_Target.isEmpty() && !m_Path.isEmpty() && m_Id.size()==64 &&
            address.compare(qEnvironmentVariable("RHREMOTE_CONNECT_ADDRESS"),Qt::CaseInsensitive)==0;
        for(const auto c:m_Id) if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F'))) m_Active=false;
        if(m_Active)m_Clock.start();
    }
    ~ServerInfo(){if(m_Active&&!m_Completed)write(!m_TlsFailure.isEmpty()?m_TlsFailure:m_PinnedHttpsAuthRejected?"auth_rejected":"unavailable",QString());}
    void https(){m_Source="https";}
    void http(){m_Source="http";}
    void rejected(){m_Rejected=true;}
    // Only an actual HTTP 401 after the saved HTTPS pin was verified qualifies.
    // The error body need not disclose a UUID; never invent one from the target.
    void pinnedHttpsAuthRejected(){m_PinnedHttpsAuthRejected=true;}
    void failedTls(bool certificateChanged){m_TlsFailure=certificateChanged?"certificate_mismatch":"tls_failure";}
    void delegated(){m_Active=false;}
    void complete(const QString& uuid,bool paired){
        m_Completed=true;
        if(!m_Active)return;
        QString outcome;
        if(!uuid.isEmpty()&&uuid.compare(m_Target,Qt::CaseInsensitive)!=0)outcome="identity_mismatch";
        else if(!m_TlsFailure.isEmpty())outcome=m_TlsFailure;
        else if(uuid.isEmpty())outcome="invalid_response";
        else if(m_Rejected)outcome="auth_rejected";
        // Initial add-host discovery may recover a saved certificate only after
        // this HTTP response. CLI selection finalizes absence after that lookup.
        else if(!m_HasCertificate)outcome="unavailable";
        else if(m_Source=="https")outcome=paired?"paired":"unpaired";
        else outcome="unavailable";
        write(outcome,uuid);
    }
private:
    void write(const QString& outcome,const QString& actual){
        QJsonObject value{{"schemaVersion",1},{"probeId",m_Id},{"targetUuid",m_Target},
            {"actualUuid",actual},{"source",m_Source},{"hasCertificate",m_HasCertificate},
            {"identityResolved",m_HasCertificate},{"outcome",outcome},{"elapsedUs",qint64(m_Clock.nsecsElapsed()/1000)}};
        if(m_PinnedHttpsAuthRejected)value["pinnedHttpsAuthRejected"]=true;
        QSaveFile output(m_Path);
        if(output.open(QIODevice::WriteOnly)){
            const auto bytes=QJsonDocument(value).toJson(QJsonDocument::Compact);
            if(output.write(bytes)==bytes.size())output.commit();
        }
    }
    QString m_Id,m_Target,m_Path,m_Source="none",m_TlsFailure;
    bool m_HasCertificate=false,m_Active=false,m_Completed=false,m_Rejected=false,m_PinnedHttpsAuthRejected=false;
    QElapsedTimer m_Clock;
};
inline void noCertificateSelected(const QString& uuid,bool hasCertificate){
    if(hasCertificate||qEnvironmentVariable("RHREMOTE_PROBE_OPERATION")!="1")return;
    const auto id=qEnvironmentVariable("RHREMOTE_PROBE_ID"),target=qEnvironmentVariable("RHREMOTE_TARGET_UUID"),path=qEnvironmentVariable("RHREMOTE_PROBE_REPORT");
    if(id.size()!=64||uuid.compare(target,Qt::CaseInsensitive)!=0||path.isEmpty())return;
    QFile input(path);if(!input.open(QIODevice::ReadOnly)||input.size()>8192)return;
    auto value=QJsonDocument::fromJson(input.readAll()).object();input.close();
    if(value["probeId"].toString()!=id||value["targetUuid"].toString().compare(target,Qt::CaseInsensitive)!=0||value["actualUuid"].toString().compare(uuid,Qt::CaseInsensitive)!=0||value["hasCertificate"].toBool()||value["source"].toString()!="http"||value["outcome"].toString()!="unavailable")return;
    value["identityResolved"]=true;value["outcome"]="unpaired";
    QSaveFile output(path);if(output.open(QIODevice::WriteOnly)){const auto bytes=QJsonDocument(value).toJson(QJsonDocument::Compact);if(output.write(bytes)==bytes.size())output.commit();}
}
}
