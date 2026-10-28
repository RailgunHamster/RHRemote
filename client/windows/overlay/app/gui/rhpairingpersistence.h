#pragma once
#include <QSettings>
#include <QSslCertificate>

// Call only after the native host manager and its delayed writer have stopped.
// Successful protocol pairing alone does not mean a subsequent process can use it.
namespace RhPairingPersistence {
inline QString confirm(QSettings& settings, const QString& uuid, const QSslCertificate& expected)
{
    if (uuid.isEmpty() || expected.isNull())
        return QStringLiteral("配对完成，但未取得主机证书，请重新配对");

    settings.sync();
    if (settings.status() != QSettings::NoError)
        return QStringLiteral("无法保存配对信息，请检查配置目录是否可写后重新配对");

    bool found = false;
    const int count = settings.beginReadArray(QStringLiteral("hosts"));
    for (int i = 0; i < count; ++i) {
        settings.setArrayIndex(i);
        if (settings.value(QStringLiteral("uuid")).toString().compare(uuid, Qt::CaseInsensitive) == 0) {
            found = QSslCertificate(settings.value(QStringLiteral("srvcert")).toByteArray()) == expected;
            break;
        }
    }
    settings.endArray();
    return found ? QString() : QStringLiteral("配对信息未完整保存，请重新配对");
}
}
