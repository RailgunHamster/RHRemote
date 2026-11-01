// Seed only a fresh, explicitly supplied protocol-test INI with generated test keys.
#include <QCoreApplication>
#include <QFile>
#include <QFileInfo>
#include <QDir>
#include <QSettings>
#include <QSslCertificate>
#include <QSslKey>
#include <QJsonDocument>
#include <QJsonObject>
#include <cstdio>
#include <stdexcept>
static QByteArray read(const QString& path){QFile f(path);if(!f.open(QIODevice::ReadOnly)||f.size()>16384)throw std::runtime_error("fixture input unavailable");return f.readAll();}
int main(int argc,char** argv){
    QCoreApplication app(argc,argv);
    try{
        const auto a=app.arguments();if(a.size()!=6)throw std::runtime_error("INI, UUID, client certificate, client key and pin required");
        if(!QFileInfo(a[1]).isAbsolute()||QFile::exists(a[1])||!QFileInfo(a[1]).dir().exists())throw std::runtime_error("fresh absolute fixture INI required");
        const auto cert=read(a[3]),key=read(a[4]);if(QSslCertificate(cert).isNull()||QSslKey(key,QSsl::Rsa).isNull())throw std::runtime_error("invalid generated fixture identity");
        QByteArray pin;if(a[5]!="-"){pin=read(a[5]);if(QSslCertificate(pin).isNull())throw std::runtime_error("invalid generated fixture pin");}
        QSettings settings(a[1],QSettings::IniFormat);settings.setValue("uniqueid","1511511511511511");settings.setValue("certificate",cert);settings.setValue("key",key);settings.setValue("fixtureOnly",true);
        settings.beginWriteArray("hosts",1);settings.setArrayIndex(0);settings.setValue("uuid",a[2]);settings.setValue("hostname","Native protocol fixture");settings.setValue("manualaddress","127.77.0.2");settings.setValue("manualport",47989);settings.setValue("localaddress","127.77.0.2");settings.setValue("localport",47989);if(!pin.isEmpty())settings.setValue("srvcert",pin);settings.endArray();settings.sync();
        if(settings.status()!=QSettings::NoError)throw std::runtime_error("fixture INI was not committed");
        std::puts("{\"prepared\":true,\"generatedIdentityOnly\":true}");return 0;
    }catch(const std::exception& e){std::fprintf(stderr,"%s\n",e.what());return 1;}
}
