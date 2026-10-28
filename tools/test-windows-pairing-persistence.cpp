#include "client/windows/overlay/app/gui/rhpairingpersistence.h"
#include <QCoreApplication>
#include <QFile>
#include <QProcess>
#include <QTemporaryDir>
#include <QTextStream>
#include <windows.h>
#include <stdexcept>

static void require(bool value, const char* message)
{
    if (!value) throw std::runtime_error(message);
}

static QSslCertificate certificate(const QString& path)
{
    QFile file(path);
    require(file.open(QIODevice::ReadOnly), "certificate fixture unreadable");
    QSslCertificate cert(file.readAll());
    require(!cert.isNull(), "certificate fixture invalid");
    return cert;
}

static void host(QSettings& settings, const QString& uuid, const QSslCertificate& cert)
{
    settings.beginWriteArray("hosts", 1);
    settings.setArrayIndex(0);
    settings.setValue("uuid", uuid);
    settings.setValue("srvcert", cert.toPem());
    settings.endArray();
}

// A different process cannot observe QSettings' shared in-process cache.
static void diskCheck(const QString& path, const QString& certPath, bool expected)
{
    QProcess process;
    process.start(QCoreApplication::applicationFilePath(),
        {"--disk-check", path, certPath, expected ? "yes" : "no"});
    require(process.waitForFinished(10000), "fresh settings reader timed out");
    require(process.exitStatus() == QProcess::NormalExit && process.exitCode() == 0,
        "fresh process did not observe the expected certificate on disk");
}

int main(int argc, char** argv)
{
    QCoreApplication app(argc, argv);
    try {
        const QStringList args = app.arguments();
        if (args.value(1) == "--disk-check") {
            QSettings settings(args.at(2), QSettings::IniFormat);
            const bool matches = RhPairingPersistence::confirm(settings, "owned-host", certificate(args.at(3))).isEmpty();
            return matches == (args.at(4) == "yes") ? 0 : 1;
        }
        require(args.size() == 3, "two public certificate fixtures required");
        const QSslCertificate paired = certificate(args.at(1));
        const QSslCertificate different = certificate(args.at(2));
        require(paired != different, "fixtures must be distinct");
        QTemporaryDir directory;
        require(directory.isValid(), "test directory unavailable");
        const QString path = directory.filePath("pairing.ini");
        {
            QSettings settings(path, QSettings::IniFormat);
            require(!RhPairingPersistence::confirm(settings, "owned-host", paired).isEmpty(), "missing pin accepted");
            require(!RhPairingPersistence::confirm(settings, "", paired).isEmpty(), "missing identity accepted");
            require(!RhPairingPersistence::confirm(settings, "owned-host", QSslCertificate()).isEmpty(), "empty certificate accepted");
            settings.setValue("unrelated-profile", 37);
            host(settings, "owned-host", paired);
            require(RhPairingPersistence::confirm(settings, "OWNED-HOST", paired).isEmpty(), "pending valid pin did not commit");
            require(!RhPairingPersistence::confirm(settings, "another-host", paired).isEmpty(), "another UUID accepted");
            require(!RhPairingPersistence::confirm(settings, "owned-host", different).isEmpty(), "another certificate accepted");
        }
        diskCheck(path, args.at(1), true);
        diskCheck(path, args.at(2), false);
        {
            // Match the failure of QSaveFile's final rename, without changing ACLs.
            HANDLE locked = CreateFileW(reinterpret_cast<LPCWSTR>(path.utf16()), GENERIC_READ,
                FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
            require(locked != INVALID_HANDLE_VALUE, "could not hold test destination");
            bool rejected = false;
            {
                QSettings settings(path, QSettings::IniFormat);
                host(settings, "owned-host", different);
                rejected = !RhPairingPersistence::confirm(settings, "owned-host", different).isEmpty();
            }
            CloseHandle(locked);
            require(rejected, "atomic replacement failure reported success");
            diskCheck(path, args.at(1), true);
            diskCheck(path, args.at(2), false);
            // New settings instance can explicitly retry after the obstruction ends.
            QSettings retry(path, QSettings::IniFormat);
            require(RhPairingPersistence::confirm(retry, "owned-host", different).isEmpty(), "retry failed");
            require(retry.value("unrelated-profile").toInt() == 37, "unrelated setting changed");
        }
        diskCheck(path, args.at(2), true);
        {
            QFile blocker(directory.filePath("blocked-parent"));
            require(blocker.open(QIODevice::WriteOnly), "could not create obstruction");
            blocker.close();
            QSettings settings(blocker.fileName() + "/pairing.ini", QSettings::IniFormat);
            host(settings, "owned-host", paired);
            require(!RhPairingPersistence::confirm(settings, "owned-host", paired).isEmpty(), "unwritable directory reported success");
        }
        QTextStream(stdout) << "Native pairing pin commit, fresh-process read, atomic replacement failure and retry passed\n";
        return 0;
    } catch (const std::exception& error) {
        QTextStream(stderr) << error.what() << '\n';
        return 1;
    }
}
