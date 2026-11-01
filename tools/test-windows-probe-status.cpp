#include "client/windows/overlay/app/gui/rhprobe.h"
#include <QCoreApplication>
#include <QFile>
#include <QTemporaryDir>
#include <cstdio>

int main(int argc,char** argv){
    QCoreApplication app(argc,argv);QTemporaryDir directory;
    if(!directory.isValid())return 1;
    const auto path=directory.filePath("report.json");
    qputenv("RHREMOTE_PROBE_OPERATION","1");qputenv("RHREMOTE_PROBE_ID",QByteArray(64,'a'));
    qputenv("RHREMOTE_TARGET_UUID","expected");qputenv("RHREMOTE_CONNECT_ADDRESS","192.0.2.1");qputenv("RHREMOTE_PROBE_REPORT",path.toUtf8());
    auto read=[&](){QFile file(path);if(!file.open(QIODevice::ReadOnly))return QJsonObject();return QJsonDocument::fromJson(file.readAll()).object();};
    auto check=[&](const char* outcome){const auto value=read();return value["outcome"].toString()==outcome&&value["probeId"].toString()==QString(64,'a')&&value["targetUuid"].toString()=="expected";};
    {RhProbe::ServerInfo info("192.0.2.1",false);info.http();info.complete("expected",true);if(!check("unavailable"))return 2;}
    RhProbe::noCertificateSelected("expected",true);if(!check("unavailable"))return 13;
    RhProbe::noCertificateSelected("other",false);if(!check("unavailable"))return 14;
    RhProbe::noCertificateSelected("expected",false);if(!check("unpaired")||!read()["identityResolved"].toBool())return 15;
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.complete("expected",true);if(!check("paired"))return 3;}
    // The report exists before a later optional metadata transaction finishes.
    if(!check("paired"))return 4;
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.complete("expected",false);if(!check("unpaired"))return 5;}
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.rejected();info.http();info.complete("expected",true);if(!check("auth_rejected"))return 6;}
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.failedTls(true);info.http();info.complete("expected",false);if(!check("certificate_mismatch"))return 16;}
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.failedTls(false);info.http();info.complete("expected",false);if(!check("tls_failure"))return 17;}
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.failedTls(true);}
    if(!check("certificate_mismatch"))return 18;
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.failedTls(true);info.http();info.complete("other",false);if(!check("identity_mismatch"))return 19;}
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.complete("other",true);if(!check("identity_mismatch"))return 7;}
    {RhProbe::ServerInfo outer("192.0.2.1",true);outer.http();outer.delegated();{RhProbe::ServerInfo inner("192.0.2.1",true);inner.https();inner.complete("expected",true);}}
    if(!check("paired"))return 8;
    {RhProbe::ServerInfo info("192.0.2.2",true);info.http();info.complete("other",false);}
    if(!check("paired"))return 9;
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();}
    if(!check("unavailable"))return 10;
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.pinnedHttpsAuthRejected();}
    if(!check("auth_rejected")||!read()["actualUuid"].toString().isEmpty()||!read()["pinnedHttpsAuthRejected"].toBool())return 20;
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.complete("",true);}
    if(!check("invalid_response"))return 11;
    QFile::remove(path);qunsetenv("RHREMOTE_PROBE_OPERATION");
    {RhProbe::ServerInfo info("192.0.2.1",true);info.https();info.complete("expected",true);}
    if(QFile::exists(path))return 12;
    std::puts("Native probe source, rejection, identity, delegation and publication checks passed");return 0;
}
