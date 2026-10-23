#pragma once
#include <QByteArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>
#include <stdexcept>

// Only public, attempt-scoped proof crosses the process boundary; the key stays native.
struct RhPairing {
    static QByteArray id() { return qgetenv("RHREMOTE_PAIR_ID"); }
    static QByteArray message(const QByteArray& salt) {
        return QByteArray("RHREMOTE-PAIR-CANCEL-1\n") + id() + "\n" + salt;
    }
    static void save(const QByteArray& salt, const QByteArray& signature) {
        const QString path=qEnvironmentVariable("RHREMOTE_PAIR_CANCEL_FILE");
        if(id().size()!=64 || path.isEmpty())throw std::runtime_error("Missing pairing cancellation context");
        const QByteArray data=QJsonDocument(QJsonObject{{"pairId",QString::fromLatin1(id())},
            {"salt",QString::fromLatin1(salt)},{"signature",QString::fromLatin1(signature)}}).toJson(QJsonDocument::Compact);
        QSaveFile file(path);
        if(!file.open(QIODevice::WriteOnly) || file.write(data)!=data.size() || !file.commit())
            throw std::runtime_error("Unable to save pairing cancellation proof");
    }
};
