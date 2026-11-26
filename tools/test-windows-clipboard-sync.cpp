#include "client/windows/overlay/app/gui/rhclipboardsync.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QFile>
#include <QSslCertificate>
#include <QSslKey>
#include <QSslSocket>
#include <QTcpServer>
#include <QUrl>
#include <QUrlQuery>
#include <atomic>
#include <cstdio>
#include <limits>
#include <mutex>
#include <vector>

namespace {

static bool check(bool cond, const char* msg) {
    if (!cond) {
        std::fprintf(stderr, "FAIL: %s\n", msg);
        return false;
    }
    return true;
}

static bool testUint64Parsing() {
    const quint64 samples[] = {
        0ULL,
        1ULL,
        (quint64(1) << 52) - 1ULL,
        (quint64(1) << 53) + 1ULL,
        9223372036854775807ULL,   // INT64_MAX
        9223372036854775808ULL,   // INT64_MAX + 1 (Qt QJsonDocument loses precision here!)
        12345678901234567891ULL,  // 0xAB54A98CEB1F0AD3
        18446744073709551615ULL,  // UINT64_MAX
    };
    for (quint64 expected : samples) {
        const QByteArray numJson = QByteArray("{\"ok\":true,\"events\":[{\"epoch\":999,\"cursor\":888}],\"msg\":\"escaped \\\"epoch\\\": 1\",\"epoch2\":7,\"epoch\": ") +
                                   QByteArray::number(expected) + ",\"cursor\":42}";
        const auto parsedNum = RhClipboardWire::extractTopLevelUint64(numJson, "epoch");
        if (!check(parsedNum.has_value() && *parsedNum == expected, "numeric 64-bit epoch mismatch")) return false;
        const auto parsedCursor = RhClipboardWire::extractTopLevelUint64(numJson, "cursor");
        if (!check(parsedCursor.has_value() && *parsedCursor == 42ULL, "cursor mismatch")) return false;

        const QByteArray strJson = QByteArray("{\"epoch\":\"") + QByteArray::number(expected) + "\",\"ok\":true}";
        const auto parsedStr = RhClipboardWire::extractTopLevelUint64(strJson, "epoch");
        if (!check(parsedStr.has_value() && *parsedStr == expected, "string 64-bit epoch mismatch")) return false;
    }

    // Malformed / out-of-range inputs must be rejected.
    const char* bad[] = {
        "{\"epoch\":-1}",
        "{\"epoch\":1.5}",
        "{\"epoch\":1e18}",
        "{\"epoch\":18446744073709551616}",
        "{\"epoch\":\"18446744073709551616\"}",
        "{\"epoch\":\"abc\"}",
        "{\"epoch\":null}",
        "{\"nested\":{\"epoch\":123}}",
        "[]",
    };
    for (const char* raw : bad) {
        if (!check(!RhClipboardWire::extractTopLevelUint64(QByteArray(raw), "epoch").has_value(), raw)) return false;
    }

    // parseUint64Value checks
    auto checkVal = [](const char* json, std::optional<quint64> expected) {
        const auto obj = QJsonDocument::fromJson(QByteArray(json)).object();
        return RhClipboardWire::parseUint64Value(obj.value("v")) == expected;
    };
    if (!check(checkVal("{\"v\":0}", 0ULL), "parseUint64Value 0")) return false;
    if (!check(checkVal("{\"v\":9007199254740993}", 9007199254740993ULL), "parseUint64Value 2^53+1")) return false;
    if (!check(checkVal("{\"v\":9223372036854775807}", 9223372036854775807ULL), "parseUint64Value INT64_MAX")) return false;
    if (!check(checkVal("{\"v\":\"18446744073709551615\"}", 18446744073709551615ULL), "parseUint64Value UINT64_MAX string")) return false;
    if (!check(checkVal("{\"v\":-1}", std::nullopt), "parseUint64Value -1")) return false;
    if (!check(checkVal("{\"v\":1.5}", std::nullopt), "parseUint64Value 1.5")) return false;
    if (!check(checkVal("{\"v\":\"abc\"}", std::nullopt), "parseUint64Value abc")) return false;
    if (!check(checkVal("{\"v\":true}", std::nullopt), "parseUint64Value bool")) return false;

    if (!check(RhClipboardWire::pollIdleMs > RhClipboardWire::pollWaitMs, "pollIdleMs must exceed pollWaitMs")) return false;
    if (!check(RhClipboardWire::maxWorkers >= 4 && RhClipboardWire::maxWorkers <= 16, "maxWorkers must be bounded")) return false;
    return true;
}

class SslMockServer final : public QTcpServer {
public:
    QSslCertificate cert;
    QSslKey key;
    quint64 serverEpoch = 12345678901234567891ULL;
    bool stallReplayMode = false;
    std::atomic<bool> stopping{false};

    std::mutex mu;
    std::vector<QString> pollQueries;
    std::vector<QString> renderQueries;
    std::atomic<int> pollCount{0};
    std::atomic<int> renderPostCount{0};
    std::atomic<bool> sawExactEpochOnWaitPoll{false};
    std::vector<std::unique_ptr<QThread>> connThreads;

    ~SslMockServer() override { stopAll(); }

    void stopAll() {
        stopping = true;
        close();
        std::vector<std::unique_ptr<QThread>> list;
        {
            std::lock_guard<std::mutex> lock(mu);
            list.swap(connThreads);
        }
        for (auto& t : list) {
            if (t) t->wait(5000);
        }
    }

protected:
    void incomingConnection(qintptr handle) override {
        auto worker = std::unique_ptr<QThread>(QThread::create([this, handle] { handleClient(handle); }));
        worker->start();
        std::lock_guard<std::mutex> lock(mu);
        connThreads.push_back(std::move(worker));
    }

private:
    void handleClient(qintptr handle) {
        QSslSocket socket;
        if (!socket.setSocketDescriptor(handle)) return;
        socket.setLocalCertificate(cert);
        socket.setPrivateKey(key);
        socket.startServerEncryption();
        if (!socket.waitForEncrypted(3000)) return;

        QByteArray req;
        int headerEnd = -1;
        QElapsedTimer timer;
        timer.start();
        while ((headerEnd = req.indexOf("\r\n\r\n")) < 0 && timer.elapsed() < 3000 && !stopping.load()) {
            if (!socket.bytesAvailable() && !socket.waitForReadyRead(100)) {
                if (socket.state() == QAbstractSocket::UnconnectedState) return;
                continue;
            }
            req += socket.readAll();
        }
        if (headerEnd < 0) return;

        const auto lines = req.left(headerEnd).split('\n');
        const auto first = lines.value(0).trimmed().split(' ');
        const QByteArray method = first.value(0);
        const QByteArray target = first.value(1);
        const QUrl url("https://127.0.0.1" + QString::fromUtf8(target));
        const QUrlQuery query(url);

        QByteArray body = "{\"ok\":true}";
        if (url.path() == "/rhremote/clipboard/events") {
            const int n = ++pollCount;
            {
                std::lock_guard<std::mutex> lock(mu);
                pollQueries.push_back(url.query());
            }
            const QString epochStr = query.queryItemValue("epoch");
            const QString cursorStr = query.queryItemValue("cursor");
            const int waitMs = query.queryItemValue("wait").toInt();
            const QString expectedEpoch = QString::number(serverEpoch);

            if (stallReplayMode) {
                // Misbehaving server: ignores wait and always replays the exact same render event immediately!
                body = "{\"ok\":true,\"epoch\":" + QByteArray::number(serverEpoch) +
                       ",\"cursor\":1,\"events\":[{\"type\":\"render\",\"request\":88,\"format\":2}]}";
            } else if (epochStr != expectedEpoch) {
                // First poll (epoch=0) or mismatched epoch: return 1 untransferable render event (format=2 never opens clipboard).
                body = "{\"ok\":true,\"epoch\":" + QByteArray::number(serverEpoch) +
                       ",\"cursor\":1,\"events\":[{\"type\":\"render\",\"request\":77,\"format\":2}]}";
            } else {
                // Exact 64-bit epoch matched! Verify cursor advanced to 1 and hold long-poll cleanly.
                if (waitMs > 0 && cursorStr == "1") {
                    sawExactEpochOnWaitPoll = true;
                }
                QElapsedTimer hold;
                hold.start();
                while (!stopping.load() && hold.elapsed() < qMin(waitMs, 600)) {
                    QThread::msleep(20);
                }
                body = "{\"ok\":true,\"epoch\":" + QByteArray::number(serverEpoch) + ",\"cursor\":1,\"events\":[]}";
            }
            (void)n;
        } else if (url.path() == "/rhremote/clipboard/render") {
            ++renderPostCount;
            {
                std::lock_guard<std::mutex> lock(mu);
                renderQueries.push_back(url.query());
            }
            body = "{\"ok\":true}";
        } else if (url.path() == "/rhremote/clipboard/formats") {
            body = "{\"ok\":true}";
        }

        const QByteArray resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " +
                                QByteArray::number(body.size()) + "\r\n\r\n" + body;
        socket.write(resp);
        socket.waitForBytesWritten(1000);
        socket.disconnectFromHost();
        socket.waitForDisconnected(500);
    }
};

static bool testLiveTlsEpochAndGuard(const QString& certPath, const QString& keyPath) {
    QFile cf(certPath), kf(keyPath);
    if (!check(cf.open(QIODevice::ReadOnly) && kf.open(QIODevice::ReadOnly), "open test cert/key")) return false;
    const QSslCertificate cert(cf.readAll(), QSsl::Pem);
    const QSslKey key(kf.readAll(), QSsl::Rsa, QSsl::Pem);
    if (!check(!cert.isNull() && !key.isNull(), "load test cert/key")) return false;

    // Case A: Exact 64-bit epoch (> INT64_MAX) round-trips without precision loss and holds long-poll!
    {
        SslMockServer server;
        server.cert = cert;
        server.key = key;
        server.serverEpoch = 12345678901234567891ULL;
        if (!check(server.listen(QHostAddress::LocalHost, 0), "listen Case A")) return false;

        auto& sync = RhClipboardSync::get();
        sync.start("127.0.0.1", cert, server.serverPort());

        QElapsedTimer waitTimer;
        waitTimer.start();
        while (waitTimer.elapsed() < 4000) {
            QCoreApplication::processEvents(QEventLoop::AllEvents, 20);
            if (server.sawExactEpochOnWaitPoll.load() && server.renderPostCount.load() >= 1 && waitTimer.elapsed() > 350) {
                break;
            }
            QThread::msleep(20);
        }

        const bool sawExact = server.sawExactEpochOnWaitPoll.load();
        const int polls = server.pollCount.load();
        const int renders = server.renderPostCount.load();

        server.stopping = true;
        sync.stop();
        server.stopAll();

        std::printf("Case A (64-bit epoch > INT64_MAX): exact=%d polls=%d renders=%d\n", int(sawExact), polls, renders);
        if (!check(sawExact, "client must echo exact 64-bit epoch (> INT64_MAX) and cursor=1 on second poll")) return false;
        if (!check(renders == 1, "single render event must trigger exactly 1 render POST")) return false;
        if (!check(polls <= 4, "long-poll must not spin when epoch matches")) return false;
    }

    // Case B: Misbehaving server ignores wait=25000 and replays the same render event immediately!
    {
        SslMockServer server;
        server.cert = cert;
        server.key = key;
        server.serverEpoch = 12345678901234567891ULL;
        server.stallReplayMode = true;
        if (!check(server.listen(QHostAddress::LocalHost, 0), "listen Case B")) return false;

        auto& sync = RhClipboardSync::get();
        sync.start("127.0.0.1", cert, server.serverPort());

        QElapsedTimer waitTimer;
        waitTimer.start();
        while (waitTimer.elapsed() < 550) {
            QCoreApplication::processEvents(QEventLoop::AllEvents, 20);
            QThread::msleep(20);
        }

        const int polls = server.pollCount.load();
        const int renders = server.renderPostCount.load();

        server.stopping = true;
        sync.stop();
        server.stopAll();

        std::printf("Case B (forced replay storm): polls_in_550ms=%d renders=%d\n", polls, renders);
        if (!check(renders == 1, "replayed render request ID must be deduplicated to exactly 1 upload")) return false;
        if (!check(polls >= 2 && polls <= 12, "rapid-spin guard must throttle immediate stalled poll replies")) return false;
    }

    return true;
}

}  // namespace

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (!testUint64Parsing()) return 1;
    if (argc < 3) {
        std::fprintf(stderr, "Usage: test-clipboard-sync <cert.pem> <key.pem>\n");
        return 2;
    }
    if (!testLiveTlsEpochAndGuard(QString::fromLocal8Bit(argv[1]), QString::fromLocal8Bit(argv[2]))) return 1;
    std::puts("Native clipboard sync regressions passed");
    return 0;
}
